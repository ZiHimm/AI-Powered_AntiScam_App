package com.antiscam.guard

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * The "someone else's call" side, on every phone (step 2). Another member's phone ("the callee", e.g. Mum or Dad) asks:
 *
 *  ← ping {req, env}             "Are you calling <callee> right now?" → Yes/No (after fingerprint) → signed reply to them
 *  ← expire-ping / cancel-ping   the question closed on the callee's side
 *  ← alert {env}                 I'm one of the callee's guardians; shown ONLY after: the callee's pinned key signed it,
 *                                its sender is who the relay says, nonce unused, < 2 min old
 *  → guardian-call / guardian-warn (signed) / shared
 *
 * Every check uses the key of the member who sent it, so a stranger (or another member) can't speak for the callee.
 * Classic demo: the callee is "mum"; Family Verify as Mum sends unsigned questions, shown with a warning.
 * State lives here (main thread); FamilyActivity renders it, FamilyService keeps the connection alive.
 */
object MemberHub : FamilyLink.Listener {

    enum class Kind { PING, ALERT, BLOCKED, LOG }
    enum class PingState { OPEN, ANSWERING, ANSWERED, EXPIRED, CANCELLED }

    class Card(val kind: Kind, val req: JSONObject? = null, val alert: JSONObject? = null, val sig: String = "",
               val from: String = "") {
        val at = System.currentTimeMillis()
        var pingState = PingState.OPEN
        var answer: String? = null
        var late = false
        var closedByCallee = false  // the callee expired/cancelled it (even while we were confirming); clocks can differ
        var unsigned = false        // question without the callee's signature (Family Verify as Mum): shown, but marked
        var title = ""
        var body = ""
        val shared = mutableListOf<String>()
        var called = false
        var warned = false
        val nonce: String? get() = req?.optString("nonce")
        val callId: String? get() = alert?.optString("callId") ?: req?.optString("callId")
        /** Who is getting the suspicious call. */
        val calleeName: String get() = (req ?: alert)?.optString("calleeName")?.takeIf { it.isNotBlank() } ?: FamilyLink.nameOf(from)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    val cards = mutableListOf<Card>()          // newest first
    var guardian = false; private set
    var elderMode = false; private set
    var version = 0L; private set
    private fun bump() { version++ }

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    private val myName get() = FamilyLink.myName
    val openQuestions get() = cards.count { it.kind == Kind.PING && it.pingState == PingState.OPEN }

    override fun onFamilyMessage(from: String, body: JSONObject) {
        when (body.optString("kind")) {
            "ping" -> onPingMessage(from, body)
            "expire-ping" -> closePing(from, body.optString("nonce"), PingState.EXPIRED)
            "cancel-ping" -> closePing(from, body.optString("nonce"), PingState.CANCELLED)
            "alert" -> body.optJSONObject("env")?.let { onAlert(from, it) }
            "shared" -> onShared(body.optString("callId"), body.optString("text"))
            "config" -> { guardian = body.optBoolean("guardian"); elderMode = body.optBoolean("elderMode"); bump() }
            "demo" -> if (body.optString("action") == "reset") { cards.clear(); bump() }
        }
    }

    // ---------------------------------------------------------------- "Are you calling <callee>?"
    private fun onPingMessage(from: String, body: JSONObject) {
        val who = FamilyLink.nameOf(from)
        val env = body.optJSONObject("env")
        if (env != null) {
            val req = try { JSONObject(env.getString("body")) } catch (_: Throwable) { return }
            val calleeOk = req.optString("callee", from) == from   // a member can only ask about THEIR OWN call
            if (!calleeOk || !FamilyLink.verifyFrom(env, from)) {
                block("🛑 Fake question blocked", "Someone asked \"Are you calling $who?\" but it was NOT signed by $who's phone. Ignore it.")
                FamilyLink.log("sec", "$myName's phone: ✗ question NOT signed by $who's phone. Blocked.")
                return
            }
            if (cards.any { it.kind == Kind.PING && it.nonce == req.optString("nonce") }) return // same question twice
            FamilyLink.log("sec", "$myName's phone: ✓ question signed by $who's phone")
            onPing(from, req, unsigned = false)
        } else {
            body.optJSONObject("req")?.let { onPing(from, it, unsigned = true) }
        }
    }

    private fun onPing(from: String, req: JSONObject, unsigned: Boolean) {
        val card = Card(Kind.PING, req = req, from = from).also { it.unsigned = unsigned }
        cards.add(0, card)
        val callee = card.calleeName
        val expires = req.optLong("expires")
        if (expires > 0 && System.currentTimeMillis() > expires + 2000) {
            card.pingState = PingState.EXPIRED // arrived after the callee had already given up
        } else {
            val wait = (expires + 2000 - System.currentTimeMillis()).coerceAtLeast(1000)
            main.postDelayed({ if (card.pingState == PingState.OPEN) { card.pingState = PingState.EXPIRED; bump() } }, wait)
            Notifier.familyPrompt(app, "Are you calling $callee right now?",
                "Someone who looks like you is video-calling $callee. Tap to answer.")
        }
        FamilyLink.log("push", "$myName's phone received \"Are you calling $callee right now?\" (${req.optString("nonce").take(8)}…)")
        bump()
    }

    private fun closePing(from: String, nonce: String, state: PingState) {
        cards.firstOrNull { it.kind == Kind.PING && it.nonce == nonce && it.from == from }?.let {
            it.closedByCallee = true
            if (it.pingState == PingState.OPEN) it.pingState = state
            bump()
        }
    }

    /** After the fingerprint (or device PIN) succeeded. A late "No" is still sent: the callee uses it to escalate to SCAM. */
    fun answer(card: Card, answer: String) {
        val req = card.req ?: return
        val callee = card.calleeName
        card.late = card.closedByCallee || card.pingState == PingState.EXPIRED || card.pingState == PingState.CANCELLED ||
            System.currentTimeMillis() > req.optLong("expires") + 2000
        val env = DeviceKey.sign(JSONObject().put("type", "verify-reply").put("nonce", req.optString("nonce"))
            .put("answer", answer).put("from", FamilyLink.me).put("ts", System.currentTimeMillis()))
        val sent = FamilyLink.sendTo(card.from, JSONObject().put("kind", "reply").put("env", env))
        card.answer = if (sent) answer else null
        card.pingState = PingState.ANSWERED
        card.body = when {
            !sent -> "⚠️ Couldn't reach $callee's phone. Call them directly."
            card.late && answer == "no" -> "⏱ Too late for the check. Your \"not me\" was still sent to $callee's phone. Call them now to be sure."
            card.late -> "⏱ Too late: the question had already expired. $callee still sees a warning. Call them directly."
            answer == "yes" -> "✓ You confirmed. $callee's screen shows green."
            else -> "✓ $callee has been warned to hang up." + if (req.optBoolean("elder")) " Their guardians were alerted." else ""
        }
        FamilyLink.log("push", "$myName's phone → $callee: signed \"$answer\"${if (card.late) " (late)" else ""}")
        Notifier.clearFamilyPrompt(app)
        bump()
    }

    // ---------------------------------------------------------------- guardian alerts
    private fun onAlert(from: String, env: JSONObject) {
        val p = try { JSONObject(env.getString("body")) } catch (_: Throwable) { JSONObject().put("title", "(unreadable)") }
        val title = p.optString("title")
        val who = FamilyLink.nameOf(from)
        // the signer must be the member the relay says sent it, and the payload must say the same
        if (p.optString("from", from) != from || !FamilyLink.verifyFrom(env, from)) {
            block("🛑 Fake alert blocked", "A message saying “$title” was NOT signed by $who's phone. Ignore it and call $who directly.")
            FamilyLink.log("sec", "$myName's phone: ✗ signature check FAILED. Fake alert blocked.")
            return
        }
        val nonce = p.optString("nonce")
        if (nonce.isEmpty() || !rememberNonce(nonce) || System.currentTimeMillis() - p.optLong("ts") > 120_000) {
            block("🛑 Replayed alert blocked", "This alert was already delivered once. Someone is re-sending an old message.")
            FamilyLink.log("sec", "$myName's phone: ✗ nonce ${nonce.take(12)} already used or too old. Replayed alert blocked.")
            return
        }
        FamilyLink.log("sec", "$myName's phone: ✓ signed by $who's phone, nonce fresh")
        val level = p.optString("level")
        val card = Card(if (level == "log") Kind.LOG else Kind.ALERT, alert = p, sig = env.optString("sig"), from = from)
        card.title = title; card.body = p.optString("body")
        // one card per call: a newer alert for the same call replaces the older one
        cards.removeAll { it.kind in setOf(Kind.ALERT, Kind.LOG) && it.callId == card.callId && it.from == from && card.callId != null }
        cards.add(0, card)
        if (level != "log") Notifier.familyAlert(app, title, card.body, urgent = level == "urgent")
        bump()
    }

    private fun block(title: String, body: String) {
        cards.add(0, Card(Kind.BLOCKED).also { it.title = title; it.body = body })
        Notifier.familyAlert(app, title, body, urgent = false)
        bump()
    }

    private fun rememberNonce(nonce: String): Boolean {
        val prefs = app.getSharedPreferences("family_seen", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("nonces", emptySet())!!.toMutableSet()
        if (!seen.add(nonce)) return false
        if (seen.size > 300) seen.remove(seen.first())
        prefs.edit().putStringSet("nonces", seen).apply()
        return true
    }

    /** "📞 Call <callee> now" from an urgent alert. */
    fun callCallee(card: Card) {
        val callId = card.callId ?: return
        FamilyLink.sendTo(card.from, JSONObject().put("kind", "guardian-call").put("callId", callId))
        card.called = true
        shareWithFamily(card, "📞 $myName is calling ${card.calleeName} now")
        FamilyLink.log("push", "$myName → ${card.calleeName}: incoming voice call")
        bump()
    }

    /** Signed "Tell <callee> to hang up" from an urgent alert. */
    fun warnCallee(card: Card) {
        val p = card.alert ?: return
        val text = if (p.optString("about") == FamilyLink.me) "Hang up. This isn't me." else "This is a scam. Hang up now."
        val env = DeviceKey.sign(JSONObject().put("type", "guardian-warning").put("from", FamilyLink.me).put("callId", p.optString("callId"))
            .put("text", text).put("ts", System.currentTimeMillis()).put("nonce", FamilyLink.randHex(12)))
        FamilyLink.sendTo(card.from, JSONObject().put("kind", "guardian-warn").put("env", env))
        card.warned = true
        shareWithFamily(card, "⚠️ $myName told ${card.calleeName} to hang up")
        FamilyLink.log("push", "$myName → ${card.calleeName}'s screen: signed \"$text\"")
        bump()
    }

    private fun shareWithFamily(card: Card, text: String) {
        val callId = card.callId ?: return
        for (m in FamilyLink.members.values) if (m.id != card.from && m.online)
            FamilyLink.sendTo(m.id, JSONObject().put("kind", "shared").put("callId", callId).put("text", text))
    }

    private fun onShared(callId: String, text: String) {
        cards.firstOrNull { it.kind == Kind.ALERT && it.callId == callId }?.let { it.shared += text; bump() }
    }
}
