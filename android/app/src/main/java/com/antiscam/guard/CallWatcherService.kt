package com.antiscam.guard

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
        private const val VOTES = 3       // decide on the most common result of the last 3 frames…
        private const val AGREE = 2       // …once at least 2 of them agree
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var engine: FaceEngine

    private var activeApp: String? = null     // call app name while a call is being checked
    private var busy = false                  // a frame is being processed
    private val votes = ArrayDeque<String>()  // recent per-frame labels: a name, "?stranger", "?noface", "?blocked"
    private var lastDecision: String? = null
    private var namesOnScreen: List<String> = emptyList()
    private var people: List<Person> = emptyList()

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        engine = FaceEngine.get(this)
        Notifier.ensureChannels(this)
        LiveLog.serviceConnected = true
        LiveLog.add("Call watcher on (${engine.modelInfo}). Waiting for a video call…")
        if (Build.VERSION.SDK_INT < 30) LiveLog.add("Android ${Build.VERSION.RELEASE}: screen scanning needs Android 11+.")
        main.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polling in tick() is enough */ }
    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
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
        votes.clear(); lastDecision = null
        LiveLog.add("📞 $app call detected. Checking the caller's face…")
        Notifier.status(this, "$app call: looking at the caller's face on this phone")
        if (people.none { !it.isOwner }) LiveLog.add("No family faces enrolled yet. Add some in the app.")
    }

    private fun endCall() {
        LiveLog.add("Call ended (${activeApp}).")
        activeApp = null
        votes.clear(); lastDecision = null
        Notifier.clearStatus(this)
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
                    if (bmp == null) "?noface" else classify(bmp).also { bmp.recycle() }
                } catch (t: Throwable) {
                    LiveLog.add("Frame error: ${t.message}"); "?noface"
                }
                main.post { busy = false; onFrame(label) }
            }

            override fun onFailure(errorCode: Int) {
                main.post { busy = false } // usually "too soon since last screenshot"; just retry next tick
            }
        })
    }

    /** Runs on the engine thread. Returns a person's name or a "?..." label. */
    private fun classify(bmp: Bitmap): String {
        if (looksBlack(bmp)) return "?blocked"
        val threshold = FaceStore.threshold(this)
        for (face in engine.findFaces(bmp)) {
            if (face.areaFraction < 0.01f) continue // too tiny to judge
            val m = FaceStore.best(people, face.embedding)
            val matched = m.person != null && m.distance <= threshold
            if (matched && m.person!!.isOwner) continue // our own camera preview, look at the next face
            LiveLog.add(if (matched) "Face ≈ ${m.person!!.name} (distance %.2f)".format(m.distance)
                        else "Face not in family (closest ${m.person?.name ?: "-"} %.2f)".format(m.distance))
            return if (matched) m.person!!.name else "?stranger"
        }
        return "?noface"
    }

    private fun onFrame(label: String) {
        val app = activeApp ?: return
        votes.addLast(label)
        while (votes.size > VOTES) votes.removeFirst()
        val (top, count) = votes.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return
        if (count < AGREE) return

        val claimed = namesOnScreen.firstOrNull()
        val decision = "$top|$claimed"
        if (decision == lastDecision) return
        lastDecision = decision

        when {
            top == "?blocked" -> {
                LiveLog.add("$app blocks screenshots on this screen. Can't see the caller.")
                Notifier.status(this, "$app hides the call video from other apps. Can't check this caller.")
            }
            top == "?noface" -> Notifier.status(this, "$app call: no clear face on screen yet")
            top == "?stranger" && claimed != null -> {
                LiveLog.add("🚨 Screen says \"$claimed\" but the face doesn't match.")
                Notifier.alert(this, "⚠️ This may NOT be $claimed",
                    "The screen says $claimed, but the face doesn't look like $claimed. " +
                        "Don't send money or share codes. Hang up and call $claimed back on their usual number.",
                    urgent = true)
            }
            top == "?stranger" -> {
                LiveLog.add("Caller is not one of your family faces.")
                Notifier.alert(this, "Caller isn't in your family list",
                    "This $app video caller doesn't match anyone in your family. Be careful with requests for money.",
                    urgent = false)
            }
            else -> {
                val nameNote = if (claimed != null && !claimed.equals(top, true)) " The screen shows \"$claimed\"." else ""
                LiveLog.add("👀 Caller looks like $top.$nameNote Warning shown.")
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
