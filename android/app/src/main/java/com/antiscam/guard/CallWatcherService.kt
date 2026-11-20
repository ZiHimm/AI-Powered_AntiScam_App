package com.antiscam.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

/**
 * Background watcher. A video call is "on" when a supported call app is in front AND Android reports
 * an active voice/video call (AudioManager.MODE_IN_COMMUNICATION). While it's on, we take a screenshot
 * about once a second, find the caller's face, and compare it with the enrolled family faces.
 * Screenshots are only held in memory for the check and are never saved.
 */
class CallWatcherService : AccessibilityService() {

    companion object {
        val CALL_APPS = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "com.facebook.orca" to "Messenger",
            "org.telegram.messenger" to "Telegram",
        )
        private const val TICK_MS = 1000L

        /** The running service (if the user switched it on), for the in-app "Preview the warning" button. */
        @Volatile var running: CallWatcherService? = null; private set
        private const val VOTES = 3       // decide on the most common result of the last 3 frames…
        private const val AGREE = 2       // …once at least 2 of them agree (only clear matches count, see FaceStore.CONFIDENT_GAP)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var engine: FaceEngine
    private lateinit var overlay: WarningOverlay
    private lateinit var family: FamilyCheck

    private var activeApp: String? = null     // call app name while a call is being checked
    private var busy = false                  // a frame is being processed
    private val votes = ArrayDeque<String>()  // recent per-frame labels: a name, "?stranger", "?noface", "?blocked"
    private var lastDecision: String? = null
    private val overlaysShown = mutableSetOf<String>() // each warning appears at most once per call
    private var namesOnScreen: List<String> = emptyList()
    private var people: List<Person> = emptyList()
    private var lastShotErrorLog = 0L
    /** Family roles seen anywhere in each of the last few frames (even weak matches), to tell "joined" from "switched". */
    private val recentFamily = ArrayDeque<Set<String>>()
    private var joinLogged = mutableSetOf<String>()

    /** One screenshot's result: the main (biggest non-owner) face's label, plus every family role visible in it. */
    private class FrameResult(val label: String, val familyRoles: Set<String> = emptySet())

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        engine = FaceEngine.get(this)
        overlay = WarningOverlay(this)
        FamilyLink.init(this)
        family = FamilyCheck(overlay) { hangUpFromOverlay() }
        FamilyLink.listeners.add(family)
        MemberHub.init(this); FamilyLink.listeners.add(MemberHub)
        if (FamilyLink.enabled && !FamilyLink.connected) FamilyLink.start()   // FamilyService normally owns it
        running = this
        Notifier.ensureChannels(this)
        LiveLog.serviceConnected = true
        LiveLog.add("Call watcher on (${engine.modelInfo}). Waiting for a video call…")
        if (Build.VERSION.SDK_INT < 30) LiveLog.add("Android ${Build.VERSION.RELEASE}: screen scanning needs Android 11+.")
        val caps = serviceInfo?.capabilities ?: 0
        if (caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0)
            LiveLog.add("⚠️ Gestures not granted yet (needed for one-tap HANG UP): turn the call watcher off and on once.")
        main.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polling in tick() is enough */ }
    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.hide()
        if (::family.isInitialized) { family.end(quiet = true); FamilyLink.listeners.remove(family) }
        if (running === this) running = null
        LiveLog.serviceConnected = false
        LiveLog.add("Call watcher off.")
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (t: Throwable) { LiveLog.add("Error: ${t.message}") }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun step() {
        if (!FamilyLink.protect) { if (activeApp != null) endCall(); return } // "check my calls" is off on this phone
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        val inCall = audio.mode == AudioManager.MODE_IN_COMMUNICATION
        val app = if (inCall) CALL_APPS[pkg] else null

        if (app == null) {
            if (activeApp != null) endCall()
            return
        }
        if (activeApp == null) startCall(app)
        namesOnScreen = familyNamesIn(root)

        if (!busy && Build.VERSION.SDK_INT >= 30) {
            busy = true
            capture()
        }
    }

    private fun startCall(app: String) {
        activeApp = app
        people = FaceStore.load(this)
        votes.clear(); lastDecision = null; overlaysShown.clear(); recentFamily.clear(); joinLogged.clear()
        LiveLog.add("📞 $app call detected. Checking the caller's face…")
        LiveLog.callStarted(app)
        Notifier.status(this, "$app call: looking at the caller's face on this phone")
        if (people.none { !it.isOwner }) LiveLog.add("No family faces enrolled yet. Add some in the app.")
        family.begin(app)
    }

    private fun endCall() {
        LiveLog.add("Call ended (${activeApp}).")
        activeApp = null
        votes.clear(); lastDecision = null; overlaysShown.clear(); recentFamily.clear(); joinLogged.clear()
        Notifier.clearStatus(this)
        overlay.hide()
        family.end()
        LiveLog.callEnded()
    }

    /** Roots of the call app's windows (the call screen may not be the "active" window while our card is up). */
    private fun callRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { if (it.packageName?.toString() in CALL_APPS) roots += it } } } catch (_: Throwable) {}
        rootInActiveWindow?.let { r -> if (roots.none { it == r }) roots += r }
        return roots
    }

    private val endCallLabel = Regex("end call|end video call|end voice call|hang ?up|leave call|leave|end", RegexOption.IGNORE_CASE)

    private fun looksLikeEndCall(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName.orEmpty()
        if (id.contains("end_call", true) || id.contains("endcall", true) || id.contains("hangup", true)) return true
        return listOfNotNull(n.contentDescription, n.text).any { endCallLabel.matches(it.toString().trim()) }
    }

    /** Press the call app's own end-call button, found by its label or id. Returns false if we couldn't. */
    private fun tryHangUp(): Boolean {
        for (root in callRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var seen = 0
            while (queue.isNotEmpty() && seen < 800) {
                val n = queue.removeFirst(); seen++
                if (looksLikeEndCall(n)) {
                    var target: AccessibilityNodeInfo? = n
                    while (target != null && !target.isClickable) target = target.parent
                    if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /** For debugging HANG UP on new phones/app versions: what buttons can we see on the call screen? */
    private fun logCallButtons() {
        val found = mutableListOf<String>()
        for (root in callRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var seen = 0
            while (queue.isNotEmpty() && seen < 800 && found.size < 25) {
                val n = queue.removeFirst(); seen++
                if (n.isClickable) found += listOfNotNull(
                    n.viewIdResourceName?.substringAfter(":id/"), n.contentDescription?.toString(), n.text?.toString()
                ).joinToString("|").ifEmpty { n.className?.toString()?.substringAfterLast('.') ?: "?" }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        LiveLog.add("Call screen buttons (${callRoots().size} window(s)): ${found.joinToString(", ").ifEmpty { "none visible" }}")
    }

    private fun showOnce(key: String, level: WarningOverlay.Level, title: String, body: String, onHangUp: (() -> Unit)? = null) {
        if (!overlaysShown.add(key)) return
        val dismiss = WarningOverlay.Action("Dismiss") { overlay.hide() }
        overlay.show(level, title, body,
            if (onHangUp == null) listOf(dismiss)
            else listOf(WarningOverlay.Action("HANG UP", WarningOverlay.Style.BAD) { onHangUp() }, dismiss))
    }

    /** Shows the warning exactly as during a real call, so it can be seen without making one. */
    fun previewWarning(name: String) {
        showOnce("preview-${System.nanoTime()}", WarningOverlay.Level.DANGER, "This may NOT be $name",
            "The face doesn't look like $name. Don't send money or share codes. " +
                "Hang up and call $name back on their usual number. (Preview)") { overlay.hide() }
    }

    /** Runs the family check as if a call from [role] were on screen (no WhatsApp call needed). */
    fun simulateCall(role: String) {
        overlay.hide()
        LiveLog.callStarted("Test")
        family.begin("Test")
        family.verify(role, "test from the app, no real call")
    }

    /**
     * HANG UP: press the call app's own end-call button. During a video call WhatsApp hides its controls
     * after a few seconds, so if the button isn't there: move our card aside, tap the middle of the call
     * screen to bring the controls back, then look again.
     */
    private fun hangUpFromOverlay() {
        if (tryHangUp()) { LiveLog.add("Hung up the call for the user."); overlay.hide(); return }
        overlay.hide() // so the tap reaches the call screen, not our card
        revealControlsThenHangUp(attempt = 1)
    }

    /** Tap the call screen to bring back the hidden controls, then look for end-call for up to 2 s. Two tries. */
    private fun revealControlsThenHangUp(attempt: Int) {
        val dm = resources.displayMetrics
        val tap = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
            Path().apply { moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.45f) }, 0, 60)).build()
        var waited = 0L
        val poll = object : Runnable {
            override fun run() {
                if (tryHangUp()) { LiveLog.add("Hung up the call for the user (after showing the call controls, try $attempt)."); return }
                waited += 250
                if (waited < 2000) { main.postDelayed(this, 250); return }
                if (attempt == 1) { revealControlsThenHangUp(attempt = 2); return } // the first tap may have HIDDEN them
                logCallButtons()
                LiveLog.add("Couldn't find the end-call button; asked the user to tap it.")
                overlay.show(WarningOverlay.Level.DANGER, "Hang up now",
                    "Tap the red ✆ button at the bottom of the call screen. Then call them back on their usual number.",
                    listOf(WarningOverlay.Action("OK") { overlay.hide() }))
            }
        }
        val sent = dispatchGesture(tap, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { main.postDelayed(poll, 250) }
            override fun onCancelled(d: GestureDescription?) {
                LiveLog.add("Tap on the call screen was cancelled (try $attempt)."); main.postDelayed(poll, 250)
            }
        }, null)
        if (!sent) {
            LiveLog.add("Couldn't tap the call screen: gestures not allowed. Turn the call watcher off and on once.")
            main.postDelayed(poll, 100)
        }
    }

    @RequiresApi(30)
    private fun capture() {
        takeScreenshot(Display.DEFAULT_DISPLAY, engine.executor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val label = try {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    if (bmp == null) FrameResult("?noface") else classify(bmp).also { bmp.recycle() }
                } catch (t: Throwable) {
                    LiveLog.add("Frame error: ${t.message}"); FrameResult("?noface")
                }
                main.post { busy = false; onFrame(label) }
            }

            override fun onFailure(errorCode: Int) {
                main.post {
                    busy = false
                    // 3 = "too soon since the last screenshot": normal, just retry next tick. Others explain a stall.
                    if (errorCode != 3 && SystemClock.elapsedRealtime() - lastShotErrorLog > 10_000) {
                        lastShotErrorLog = SystemClock.elapsedRealtime()
                        LiveLog.add("Screenshot failed (code $errorCode: ${mapOf(1 to "internal error", 2 to "no access",
                            4 to "invalid display", 5 to "invalid window")[errorCode] ?: "unknown"}). Faces can't be checked right now.")
                    }
                }
            }
        })
    }

    /** Runs on the engine thread. Returns a person's name or a "?..." label. */
    private fun classify(bmp: Bitmap): FrameResult {
        if (looksBlack(bmp)) return FrameResult("?blocked")
        val threshold = FaceStore.threshold(this)
        var main: String? = null
        val present = mutableSetOf<String>()
        for (face in engine.findFaces(bmp)) {
            if (face.areaFraction < 0.01f) continue // too tiny to judge
            val ranked = FaceStore.ranked(people, face.embedding)
            val best = ranked.firstOrNull()
            val next = ranked.getOrNull(1)
            val matched = best != null && best.distance <= threshold
            if (matched && best!!.person!!.isOwner) continue // our own camera preview, look at the next face
            if (matched) FamilyLink.roleForName(best!!.person!!.name)?.let { present += it }
            if (main != null) continue // the biggest face decides; smaller ones only count as "present"
            // A clear match: well inside the limit AND clearly closer than the next person
            val confident = matched && best!!.distance <= threshold - FaceStore.CONFIDENT_GAP &&
                (next == null || next.distance - best.distance >= FaceStore.MARGIN)
            val nextTxt = next?.let { " (next: ${it.person!!.name} %.2f)".format(it.distance) } ?: ""
            LiveLog.add(when {
                confident -> "Face ≈ ${best!!.person!!.name} (distance %.2f)$nextTxt".format(best.distance)
                matched -> "Face maybe ${best!!.person!!.name} (distance %.2f), not sure$nextTxt".format(best.distance)
                else -> "Face not in family (closest ${best?.person?.name ?: "-"} %.2f)$nextTxt".format(best?.distance ?: Float.NaN)
            })
            main = when { confident -> best!!.person!!.name; matched -> "?unsure"; else -> "?stranger" }
        }
        return FrameResult(main ?: "?noface", present)
    }

    private fun onFrame(frame: FrameResult) {
        val app = activeApp ?: return
        val label = frame.label
        recentFamily.addLast(frame.familyRoles)
        while (recentFamily.size > VOTES) recentFamily.removeFirst()
        votes.addLast(label)
        while (votes.size > VOTES) votes.removeFirst()
        val (top, count) = votes.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return
        if (count < AGREE) return
        // "Not family" needs every recent frame to agree: in testing it flashed one second before the same call
        // matched Daughter, contradicting itself on screen.
        if (top == "?stranger" && count < VOTES) return

        val claimed = namesOnScreen.firstOrNull()
        val decision = "$top|$claimed"
        if (decision == lastDecision) return
        lastDecision = decision

        // Once the real family member has been asked, their answer decides and later frames are ignored
        // (the warning card itself can hide the face in screenshots), EXCEPT a different family member's face:
        // a scammer may start with an innocent face and then switch to a deepfake of Son. Check that person too.
        if (family.handling) {
            val role = FamilyLink.roleForName(top) ?: return
            val asked = family.targetRole
            if (!isFaceSwap(asked, role, recentFamily)) {
                // the person already asked is still on screen: someone JOINED the call, it wasn't a face swap
                if (joinLogged.add(role)) LiveLog.add("${FamilyLink.nameOf(role)} joined ${FamilyLink.nameOf(asked ?: "")} on the call. Not asking again.")
                return
            }
            family.faceChanged(role, "seen in $count of the last ${votes.size} frames; ${FamilyLink.nameOf(asked ?: "")} no longer on screen")
            return
        }

        when {
            top == "?blocked" -> {
                LiveLog.add("$app blocks screenshots on this screen. Can't see the caller.")
                Notifier.status(this, "$app hides the call video from other apps. Can't check this caller.")
            }
            top == "?noface" -> Notifier.status(this, "$app call: no clear face on screen yet")
            top == "?unsure" -> Notifier.status(this, "$app call: the face is a weak match, still looking")
            top == "?stranger" && claimed != null -> {
                LiveLog.add("🚨 Screen says \"$claimed\" but the face doesn't match.")
                FamilyLink.roleForName(claimed)?.let { family.nameButNotFace(it) }
                LiveLog.callVerdict("⚠️ says \"$claimed\" but the face doesn't match")
                showOnce(decision, WarningOverlay.Level.DANGER, "This may NOT be $claimed",
                    "The face doesn't look like $claimed. Don't send money or share codes. " +
                        "Hang up and call $claimed back on their usual number.") { hangUpFromOverlay() }
                Notifier.alert(this, "⚠️ This may NOT be $claimed",
                    "The screen says $claimed, but the face doesn't look like $claimed. " +
                        "Don't send money or share codes. Hang up and call $claimed back on their usual number.",
                    urgent = true)
            }
            top == "?stranger" -> {
                LiveLog.add("Caller is not one of your family faces.")
                LiveLog.callVerdict("not someone in your family")
                showOnce(decision, WarningOverlay.Level.INFO, "Not someone in your family",
                    "Be careful if this caller asks for money or codes.")
                Notifier.alert(this, "Caller isn't in your family list",
                    "This $app video caller doesn't match anyone in your family. Be careful with requests for money.",
                    urgent = false)
            }
            FamilyLink.connected && FamilyLink.roleForName(top) != null -> {
                // Looks like a family member: ask the REAL person on their own phone
                family.verify(FamilyLink.roleForName(top)!!, "seen in $count of the last ${votes.size} frames")
                Notifier.alert(this, "📞 Checking with $top…",
                    "This video call looks like $top. Asking $top on their own phone.", urgent = false)
            }
            else -> {
                val nameNote = if (claimed != null && !claimed.equals(top, true)) " The screen shows \"$claimed\"." else ""
                LiveLog.add("👀 Caller looks like $top.$nameNote Warning shown.")
                LiveLog.callVerdict("looks like $top (not confirmed)")
                showOnce(decision, WarningOverlay.Level.WARN, "Looks like $top, but check first",
                    "Faces can be faked.$nameNote If they ask for money, hang up and call $top back yourself.") { hangUpFromOverlay() }
                Notifier.alert(this, "📞 This video call looks like $top",
                    "A face that looks like $top can be faked.$nameNote If they ask for money or codes, " +
                        "hang up and call $top back on their usual number first.",
                    urgent = false)
            }
        }
    }

    /** Enrolled family names that appear as text on the call screen (e.g. the caller's contact name). */
    private fun familyNamesIn(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val names = people.filter { !it.isOwner }.map { it.name }
        if (names.isEmpty()) return emptyList()
        val texts = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 400) {
            val n = queue.removeFirst(); seen++
            n.text?.let { texts += it.toString() }
            n.contentDescription?.let { texts += it.toString() }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return names.filter { name ->
            val rx = Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
            texts.any { rx.containsMatchIn(it) }
        }
    }

    /** Apps that block screen capture (FLAG_SECURE) give an all-black screenshot. */
    private fun looksBlack(bmp: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bmp, 24, 48, false)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        if (small !== bmp) small.recycle()
        val bright = px.count { ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) > 30 }
        return bright < px.size / 50
    }
}

/**
 * After someone was asked, a different family member's face became the main face. Is it a face SWAP (ask again) or did
 * they JOIN (both on screen; don't)? Swap only if the person already asked hasn't been seen, even as a weak match, in any
 * recent frame. Pure function, unit-tested.
 */
internal fun isFaceSwap(asked: String?, newRole: String, recentFamily: Collection<Set<String>>): Boolean =
    asked != null && newRole != asked && recentFamily.none { asked in it }
