package com.antiscam.guard

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Family mode (Son's / Daughter's phone): the native equivalent of Family Verify's member side, same protocol.
 *
 *  ← ping {req}                  "Are you calling Mum right now?" → Yes/No (after fingerprint) → signed reply to Mum
 *  ← expire-ping / cancel-ping   the question closed on Mum's side
 *  ← alert {env}                 guardian alert, shown ONLY after: Mum's pinned key signed it, nonce unused, < 2 min old
 *  → guardian-call / guardian-warn (signed) / shared
 *  ← config                      am I a guardian?   ← shared   another guardian's action
 *
 * State lives here (main thread); FamilyActivity renders it, FamilyService keeps the connection alive.
 */
object MemberHub : FamilyLink.Listener {

    enum class Kind { PING, ALERT, BLOCKED, LOG }
    enum class PingState { OPEN, ANSWERING, ANSWERED, EXPIRED, CANCELLED }

    class Card(val kind: Kind, val req: JSONObject? = null, val alert: JSONObject? = null, val sig: String = "") {
        val at = System.currentTimeMillis()
        var pingState = PingState.OPEN
        var answer: String? = null
        var late = false
        var closedByMum = false
        var unsigned = false      // question came without Mum's signature (older Mum app): shown, but marked   // Mum expired/cancelled it (even while we were confirming); clocks can differ
        var title = ""
        var body = ""
        val shared = mutableListOf<String>()
        var called = false
        var warned = false
        val nonce: String? get() = req?.optString("nonce")
        val callId: String? get() = alert?.optString("callId") ?: req?.optString("callId")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    val cards = mutableListOf<Card>()          // newest first
    var guardian = false; private set
    var elderMode = false; private set
    var version = 0L; private set
    private fun bump() { version++ }

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    private val me get() = FamilyLink.myRole ?: "son"
    private val myName get() = FamilyLink.nameOf(me)

    override fun onFamilyMessage(from: String, body: JSONObject) {
        when (body.optString("kind")) {
            "ping" -> onPingMessage(body)
            "expire-ping" -> closePing(body.optString("nonce"), PingState.EXPIRED)
            "cancel-ping" -> closePing(body.optString("nonce"), PingState.CANCELLED)
            "alert" -> body.optJSONObject("env")?.let { onAlert(it) }
            "shared" -> onShared(body.optString("callId"), body.optString("text"))
            "config" -> { guardian = body.optBoolean("guardian"); elderMode = body.optBoolean("elderMode"); bump() }
            "demo" -> if (body.optString("action") == "reset") { cards.clear(); bump() }
        }
    }

    // ---------------------------------------------------------------- "Are you calling Mum?"
    /**
     * Signed question (AntiScam Guard as Mum): the signature must come from Mum's pinned key, and the signed body
     * is what's used. Unsigned (Family Verify as Mum, older protocol): still shown, marked "not verified".
     */
    private fun onPingMessage(body: JSONObject) {
        val env = body.optJSONObject("env")
        if (env != null) {
            if (!FamilyLink.verifyFrom(env, "mum")) {
                block("🛑 Fake question blocked", "Someone asked \"Are you calling Mum?\" but it was NOT signed by Mum's phone. Ignore it.")
                FamilyLink.log("sec", "$myName's phone: ✗ question NOT signed by Mum's phone. Blocked.")
                return
            }
            val req = try { JSONObject(env.getString("body")) } catch (_: Throwable) { return }
            if (cards.any { it.kind == Kind.PING && it.nonce == req.optString("nonce") }) return // same question twice
            FamilyLink.log("sec", "$myName's phone: ✓ question signed by Mum's phone")
            onPing(req, unsigned = false)
        } else {
            body.optJSONObject("req")?.let { onPing(it, unsigned = true) }
        }
    }

    private fun onPing(req: JSONObject, unsigned: Boolean) {
        val card = Card(Kind.PING, req = req).also { it.unsigned = unsigned }
        cards.add(0, card)
        val expires = req.optLong("expires")
        if (expires > 0 && System.currentTimeMillis() > expires + 2000) {
            card.pingState = PingState.EXPIRED // arrived after Mum had already given up
        } else {
            val wait = (expires + 2000 - System.currentTimeMillis()).coerceAtLeast(1000)
            main.postDelayed({ if (card.pingState == PingState.OPEN) { card.pingState = PingState.EXPIRED; bump() } }, wait)
            Notifier.familyPrompt(app, "Are you calling Mum right now?",
                "Someone who looks like you is video-calling Mum. Tap to answer.")
        }
        FamilyLink.log("push", "$myName's phone received \"Are you calling Mum right now?\" (${req.optString("nonce").take(8)}…)")
        bump()
    }

    private fun closePing(nonce: String, state: PingState) {
        cards.firstOrNull { it.kind == Kind.PING && it.nonce == nonce }?.let {
            it.closedByMum = true
            if (it.pingState == PingState.OPEN) it.pingState = state
            bump()
        }
    }

    /** After the fingerprint (or device PIN) succeeded. A late "No" is still sent: Mum uses it to escalate to SCAM. */
    fun answer(card: Card, answer: String) {
        val req = card.req ?: return
        card.late = card.closedByMum || card.pingState == PingState.EXPIRED || card.pingState == PingState.CANCELLED ||
            System.currentTimeMillis() > req.optLong("expires") + 2000
        val env = DeviceKey.sign(JSONObject().put("type", "verify-reply").put("nonce", req.optString("nonce"))
            .put("answer", answer).put("from", me).put("ts", System.currentTimeMillis()))
        val sent = FamilyLink.sendTo("mum", JSONObject().put("kind", "reply").put("env", env))
        card.answer = if (sent) answer else null
        card.pingState = PingState.ANSWERED
        card.body = when {
            !sent -> "⚠️ Couldn't reach Mum's phone. Call her directly."
            card.late && answer == "no" -> "⏱ Too late for the check. Your \"not me\" was still sent to Mum's phone. Call her now to be sure."
            card.late -> "⏱ Too late: the question had already expired. Mum still sees a warning. Call her directly."
            answer == "yes" -> "✓ You confirmed. Mum's screen shows green."
            else -> "✓ Mum has been warned to hang up." + if (req.optBoolean("elder")) " Her guardians were alerted." else ""
        }
        FamilyLink.log("push", "$myName's phone → Mum: signed \"$answer\"${if (card.late) " (late)" else ""}")
        Notifier.clearFamilyPrompt(app)
        bump()
    }

    // ---------------------------------------------------------------- guardian alerts
    private fun onAlert(env: JSONObject) {
        val p = try { JSONObject(env.getString("body")) } catch (_: Throwable) { JSONObject().put("title", "(unreadable)") }
        val title = p.optString("title")
        if (!FamilyLink.verifyFrom(env, "mum")) {
            block("🛑 Fake alert blocked", "A message saying “$title” was NOT signed by Mum's phone. Ignore it and call Mum directly.")
            FamilyLink.log("sec", "$myName's phone: ✗ signature check FAILED. Fake alert blocked.")
            return
        }
        val nonce = p.optString("nonce")
        if (nonce.isEmpty() || !rememberNonce(nonce) || System.currentTimeMillis() - p.optLong("ts") > 120_000) {
            block("🛑 Replayed alert blocked", "This alert was already delivered once. Someone is re-sending an old message.")
            FamilyLink.log("sec", "$myName's phone: ✗ nonce ${nonce.take(12)} already used or too old. Replayed alert blocked.")
            return
        }
        FamilyLink.log("sec", "$myName's phone: ✓ signed by Mum's phone, nonce fresh")
        val level = p.optString("level")
        val card = Card(if (level == "log") Kind.LOG else Kind.ALERT, alert = p, sig = env.optString("sig"))
        card.title = title; card.body = p.optString("body")
        // one card per call: a newer alert for the same call replaces the older one
        cards.removeAll { it.kind in setOf(Kind.ALERT, Kind.LOG) && it.callId == card.callId && card.callId != null }
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

    fun callMum(card: Card) {
        val callId = card.callId ?: return
        FamilyLink.sendTo("mum", JSONObject().put("kind", "guardian-call").put("callId", callId))
        card.called = true
        shareWithFamily(callId, "📞 $myName is calling Mum now")
        FamilyLink.log("push", "$myName → Mum: incoming voice call")
        bump()
    }

    fun warnMum(card: Card) {
        val p = card.alert ?: return
        val text = if (p.optString("about") == me) "Hang up. This isn't me." else "This is a scam. Hang up now."
        val env = DeviceKey.sign(JSONObject().put("type", "guardian-warning").put("from", me).put("callId", p.optString("callId"))
            .put("text", text).put("ts", System.currentTimeMillis()).put("nonce", FamilyLink.randHex(12)))
        FamilyLink.sendTo("mum", JSONObject().put("kind", "guardian-warn").put("env", env))
        card.warned = true
        shareWithFamily(p.optString("callId"), "⚠️ $myName told Mum to hang up")
        FamilyLink.log("push", "$myName → Mum's screen: signed \"$text\"")
        bump()
    }

    private fun shareWithFamily(callId: String, text: String) {
        for (role in FamilyLink.MEMBERS.keys) if (role != me && FamilyLink.isOnline(role))
            FamilyLink.sendTo(role, JSONObject().put("kind", "shared").put("callId", callId).put("text", text))
    }

    private fun onShared(callId: String, text: String) {
        cards.firstOrNull { it.kind == Kind.ALERT && it.callId == callId }?.let { it.shared += text; bump() }
    }
}
