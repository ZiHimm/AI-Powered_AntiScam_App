package com.antiscam.guard

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import com.antiscam.guard.WarningOverlay.Action
import com.antiscam.guard.WarningOverlay.Level
import com.antiscam.guard.WarningOverlay.Style

/**
 * Mum's side of the family check, over the relay (same flow as mobile/www/app.js, shown over the call):
 *
 *   face ≈ Son → ping Son's phone "Are you calling Mum right now?" (single-use nonce, 15 s)
 *     ├─ Son: YES (signed)  → 🟢 "This is really Son"
 *     ├─ Son: NO  (signed)  → 🔴 SCAM, hang up            + urgent signed alert to guardians (Elder Mode)
 *     └─ no reply / offline → 🟠 ask the secret question   + urgent signed alert to guardians (Elder Mode)
 *
 * Guardians can answer from their phones: "Call Mum now" / signed "Tell Mum to hang up".
 * Runs on the main thread.
 */
class FamilyCheck(
    private val overlay: WarningOverlay,
    private val hangUp: () -> Unit,
) : FamilyLink.Listener {

    private class Call(val id: String, val app: String) {
        var status = "scanning"          // scanning | verifying | A | B | C | secret-ok | secret-fail | warned
        var target: String? = null
        var nonce: String? = null
        var expiredNonce: String? = null  // the request that timed out: a late signed "No" still counts
        var expires = 0L
        val notified = linkedSetOf<String>()
    }

    private val main = Handler(Looper.getMainLooper())
    private var call: Call? = null

    /** True once a family check has been started for the current call (so local-only warnings step aside). */
    val handling get() = call?.status?.let { it != "scanning" } == true

    fun begin(app: String) {
        end(quiet = true)
        call = Call(FamilyLink.randHex(4), app)
    }

    fun end(quiet: Boolean = false) {
        val c = call ?: return
        main.removeCallbacksAndMessages(null)
        if (c.status == "verifying") c.nonce?.let { n -> c.target?.let { FamilyLink.sendTo(it, JSONObject().put("kind", "cancel-ping").put("nonce", n)) } }
        call = null
        if (!quiet) FamilyLink.log("info", "📴 Call ended (${c.app}).")
    }

    // ---------------------------------------------------------------- step 1: ask the real person
    fun verify(role: String, how: String) {
        val c = call ?: return
        if (c.status != "scanning") return
        val who = FamilyLink.nameOf(role)
        c.target = role
        FamilyLink.log("face", "${c.app} call: face matches $who ($how). A deepfake would match too, so now we ask the real $who.")

        if (!FamilyLink.isOnline(role)) {
            FamilyLink.log("warn", "✗ $who's phone isn't connected. Can't ask $who.")
            noReply(c, offline = true)
            return
        }
        val nonce = FamilyLink.randHex(16)
        val now = System.currentTimeMillis()
        c.nonce = nonce
        c.expires = now + FamilyLink.replySeconds * 1000L
        val req = JSONObject()
            .put("type", "verify-request").put("callId", c.id).put("to", role).put("nonce", nonce)
            .put("prompt", "Are you calling Mum right now?").put("ts", now).put("expires", c.expires)
            .put("elder", FamilyLink.elderMode)
        FamilyLink.sendTo(role, JSONObject().put("kind", "ping").put("req", req))
        c.status = "verifying"
        FamilyLink.log("push", "Mum → $who's phone: \"Are you calling Mum right now?\" nonce ${nonce.take(8)}…, ${FamilyLink.replySeconds}s. No video, audio or location.")

        overlay.show(Level.WARN, "Checking with $who…", countdownText(c, who),
            listOf(Action("Hang up", Style.BAD) { hangUp() }), autoHideMs = 0)
        tickCountdown(c, who)
        main.postDelayed({ if (call === c && c.status == "verifying") onReply(role, nonce, "timeout") }, FamilyLink.replySeconds * 1000L)

        alertGuardians(c, "info", "Mum is getting a video call from someone who looks like $who",
            "Checking with $who's phone now…", about = role)
    }

    private fun countdownText(c: Call, who: String): String {
        val left = ((c.expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return "Asking $who on their own phone. Please wait. Don't send anything yet. (${left}s)"
    }

    private fun tickCountdown(c: Call, who: String) {
        main.postDelayed({
            if (call === c && c.status == "verifying") { overlay.updateBody(countdownText(c, who)); tickCountdown(c, who) }
        }, 1000)
    }

    // ---------------------------------------------------------------- step 2: the answer
    override fun onFamilyMessage(from: String, body: JSONObject) {
        when (body.optString("kind")) {
            "reply" -> {
                val env = body.optJSONObject("env") ?: return
                val p = try { JSONObject(env.getString("body")) } catch (_: Throwable) { return }
                val sender = p.optString("from")
                if (sender !in FamilyLink.MEMBERS.keys || !FamilyLink.verifyFrom(env, sender)) {
                    FamilyLink.log("attack", "✗ Rejected a reply claiming to be from ${FamilyLink.nameOf(sender)}: bad signature")
                    return
                }
                FamilyLink.log("sec", "✓ Reply signature from ${FamilyLink.nameOf(sender)}'s phone verified")
                onReply(sender, p.optString("nonce"), p.optString("answer"))
            }
            "guardian-warn" -> {
                val env = body.optJSONObject("env") ?: return
                val p = try { JSONObject(env.getString("body")) } catch (_: Throwable) { return }
                val gid = p.optString("from")
                if (gid !in FamilyLink.MEMBERS.keys || !FamilyLink.verifyFrom(env, gid)) {
                    FamilyLink.log("attack", "✗ Rejected a \"hang up\" message claiming to be from ${FamilyLink.nameOf(gid)}: bad signature")
                    return
                }
                val c = call ?: return
                if (p.optString("callId") != c.id) return
                FamilyLink.log("sec", "✓ Warning signed by ${FamilyLink.nameOf(gid)}'s phone")
                main.removeCallbacksAndMessages(null)
                c.status = "warned"
                overlay.show(Level.DANGER, "“${p.optString("text")}”",
                    "✓ Verified message from ${FamilyLink.nameOf(gid)}'s phone.",
                    listOf(Action("HANG UP", Style.BAD) { hangUp() }, Action("Dismiss") { overlay.hide() }))
            }
            "guardian-call" -> {
                val c = call ?: return
                if (body.optString("callId") != c.id) return
                FamilyLink.log("info", "📞 ${FamilyLink.nameOf(from)} is calling Mum.")
                overlay.show(Level.INFO, "📞 ${FamilyLink.nameOf(from)} is calling you now",
                    "Hang up this video call and answer ${FamilyLink.nameOf(from)}.",
                    listOf(Action("Hang up", Style.BAD) { hangUp() }, Action("Dismiss") { overlay.hide() }), autoHideMs = 0)
            }
            "face" -> FamilyLink.log("info", "${FamilyLink.nameOf(from)} shared a face from the family app. " +
                "This detector uses its own model: enrol ${FamilyLink.nameOf(from)} with 📷 selfies here instead.")
            "demo" -> when (body.optString("action")) {
                "reset", "end" -> { overlay.hide(); end() }
                "expire" -> call?.let { c ->  // monitor: "Skip to no reply"
                    if (c.status == "verifying") { FamilyLink.log("info", "Presenter skipped to \"no reply\"."); onReply(c.target!!, c.nonce!!, "timeout") }
                }
            }
        }
    }

    private fun onReply(role: String, nonce: String, answer: String) {
        val c = call
        // Late answer to the request that just expired (same call, same nonce, signed by the right phone)
        if (c != null && c.expiredNonce == nonce && c.target == role && answer != "timeout") {
            val who = FamilyLink.nameOf(role)
            if (answer == "no") {
                c.expiredNonce = null // single use
                main.removeCallbacksAndMessages(null)
                FamilyLink.log("info", "🔴 Late reply: $who says it is NOT them. Switching to SCAM.")
                scam(c, role)
            } else {
                FamilyLink.log("info", "$who confirmed after the ${FamilyLink.replySeconds}s window. Too late to count: keep using the secret question.")
            }
            return
        }
        if (c == null || c.status != "verifying" || c.nonce != nonce || c.target != role) {
            if (answer != "timeout") FamilyLink.log("attack", "✗ Ignored a reply from ${FamilyLink.nameOf(role)}: it doesn't match an open request (stale or replayed)")
            return
        }
        main.removeCallbacksAndMessages(null)
        c.nonce = null // single use
        val who = FamilyLink.nameOf(role)
        when (answer) {
            "yes" -> {
                c.status = "A"
                FamilyLink.log("info", "🟢 $who confirmed on their own phone. Call verified.")
                overlay.show(Level.OK, "🟢 This is really $who", "$who confirmed on their own phone.", autoHideMs = 12_000)
                alertGuardians(c, "log", "Mum took a verified call from $who", about = role)
            }
            "no" -> {
                FamilyLink.log("info", "🔴 $who says it is NOT them. Scam call.")
                scam(c, role)
            }
            else -> {
                FamilyLink.sendTo(role, JSONObject().put("kind", "expire-ping").put("nonce", nonce))
                c.expiredNonce = nonce
                noReply(c, offline = false)
            }
        }
    }

    private fun scam(c: Call, role: String) {
        val who = FamilyLink.nameOf(role)
        c.status = "B"
        alertGuardians(c, "urgent", "🚨 Someone pretending to be $who is video-calling Mum",
            "$who confirmed it's NOT them. Mum has been told to hang up.", about = role)
        overlay.show(Level.DANGER, "⛔ SCAM. Hang up.",
            "The real $who says this is NOT them. Don't send money or share codes.${toldText(c)}",
            listOf(Action("HANG UP", Style.BAD) { hangUp() }, Action("Dismiss") { overlay.hide() }))
    }

    /** No answer in time, or their phone is offline: fall back to the family secret question. */
    private fun noReply(c: Call, offline: Boolean) {
        val who = FamilyLink.nameOf(c.target ?: "")
        c.status = "C"
        FamilyLink.log("info", "🟠 ${if (offline) "Couldn't reach" else "No reply from"} $who. Asking the secret question.")
        alertGuardians(c, "urgent", "⚠️ Couldn't verify a caller who looks like $who",
            if (offline) "$who's phone is offline. Please check on Mum." else "$who didn't reply. Please check on Mum.", about = c.target)
        overlay.show(Level.WARN, "🟠 Couldn't check with $who",
            "Ask the caller: “${FamilyLink.secretQuestion}”\nNo answer, no money.${toldText(c)}",
            listOf(
                Action("✓ Answered right", Style.GOOD) { secretAnswer(c, true) },
                Action("✗ Couldn't answer", Style.BAD) { secretAnswer(c, false) },
                Action("Hang up", Style.NEUTRAL) { hangUp() },
            ), autoHideMs = 0)
    }

    private fun secretAnswer(c: Call, ok: Boolean) {
        if (call !== c) { overlay.hide(); return }
        c.status = if (ok) "secret-ok" else "secret-fail"
        if (ok) {
            FamilyLink.log("info", "🟢 Caller answered the family secret question.")
            overlay.show(Level.OK, "🟢 Caller answered the family question",
                "Checked by question, not by phone. Still be careful with money.", autoHideMs = 10_000)
        } else {
            FamilyLink.log("info", "🔴 Caller could NOT answer the secret question.")
            alertGuardians(c, "urgent", "🚨 Caller failed Mum's family secret question",
                "Mum has been told to hang up. Please call her.", about = c.target)
            overlay.show(Level.DANGER, "⛔ No answer, no money", "They couldn't answer. Hang up now.${toldText(c)}",
                listOf(Action("HANG UP", Style.BAD) { hangUp() }, Action("Dismiss") { overlay.hide() }))
        }
    }

    // ---------------------------------------------------------------- guardians
    /** "Screen says Son but the face isn't Son": no one to ask, but guardians should know. */
    fun nameButNotFace(claimedRole: String) {
        val c = call ?: return
        val who = FamilyLink.nameOf(claimedRole)
        alertGuardians(c, "urgent", "🚨 Someone using $who's name is video-calling Mum",
            "The face doesn't match $who. Mum has been warned.", about = claimedRole)
    }

    private fun alertGuardians(c: Call, level: String, title: String, body: String = "", about: String? = null) {
        if (!FamilyLink.elderMode || !FamilyLink.connected) return
        var to = FamilyLink.guardians().filter { it != about }
        if (to.isEmpty()) to = FamilyLink.guardians()
        if (to.isEmpty()) { FamilyLink.log("warn", "Mum has no guardians chosen, so no one was alerted."); return }
        val payload = JSONObject()
            .put("type", "guardian-alert").put("level", level).put("title", title).put("body", body)
            .put("about", about ?: JSONObject.NULL).put("callId", c.id).put("from", "mum")
            .put("ts", System.currentTimeMillis()).put("nonce", FamilyLink.randHex(12))
        val env = DeviceKey.sign(payload)
        val sentTo = to.filter { FamilyLink.sendTo(it, JSONObject().put("kind", "alert").put("env", env)) }
        c.notified += sentTo
        val label = mapOf("urgent" to "🚨 Urgent alert", "info" to "Heads-up", "log" to "Silent log")[level]
        FamilyLink.log("sec", "$label signed by Mum's phone → ${to.joinToString(" + ") { FamilyLink.nameOf(it) }}" +
            if (sentTo.size < to.size) " (${(to - sentTo.toSet()).joinToString { FamilyLink.nameOf(it) }} offline)" else "")
    }

    private fun toldText(c: Call) =
        if (c.notified.isEmpty()) "" else "\n${c.notified.joinToString(" and ") { FamilyLink.nameOf(it) }} ${if (c.notified.size > 1) "have" else "has"} been told."
}
