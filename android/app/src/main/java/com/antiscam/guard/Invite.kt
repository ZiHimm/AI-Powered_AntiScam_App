package com.antiscam.guard

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * A family invite: everything a new phone needs to join, carried in a link / QR code.
 *
 *   https://zihimm.github.io/AI-Powered_AntiScam_App/join.html#r=<relay>&f=<code>&n=<family>&a=<inviter id>&an=<inviter name>&k=<inviter key fp>
 *
 * - Everything is after "#", which browsers never send to a server, so the family code isn't leaked to GitHub.
 * - The QR code holds the same link, so the in-app scanner AND any phone camera can use it.
 * - k = the inviter's key fingerprint: the joining phone pins it up front, so the inviter's key is trusted from the QR
 *   instead of "whoever connects first" (fixes the stale-key problem from testing).
 * - The family code is the secret: anyone with the invite can join the family room.
 */
data class Invite(
    val relayBase: String,      // e.g. https://words.trycloudflare.com or 192.168.1.104:8000
    val code: String,           // e.g. K7Q2-9XPD-M4TR
    val familyName: String,
    val inviterId: String,
    val inviterName: String,
    val inviterKey: String,     // fingerprint like ab:cd:ef:01:23:45
) {
    /** The relay address for this family's room. */
    val relayRoom: String get() = "${relayBase.trimEnd('/')}/f/$code"

    fun toLink(): String = PAGE + "#" + listOf("r" to relayBase, "f" to code, "n" to familyName,
        "a" to inviterId, "an" to inviterName, "k" to inviterKey)
        .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }

    companion object {
        const val PAGE = "https://zihimm.github.io/AI-Powered_AntiScam_App/join.html"
        private const val ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // no 0/O/1/I mix-ups (same as the relay)

        /** 12 random characters (60 bits), e.g. K7Q2-9XPD-M4TR. */
        fun newCode(rng: java.util.Random = SecureRandom()): String =
            (1..12).map { ABC[rng.nextInt(ABC.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

        /** "https://x/f/CODE" or "https://x/f/CODE/ws" → "https://x"; anything else unchanged. */
        fun baseOf(relay: String): String = relay.trim().trimEnd('/').replace(Regex("/ws$"), "").replace(Regex("/f/[^/]+$"), "")

        /** Parses the https link (QR code / shared link) or antiscam://join?… (opened from the join page). */
        fun parse(text: String): Invite? {
            val t = text.trim()
            val params = when {
                t.startsWith("antiscam://join") -> t.substringAfter('?', "")
                t.contains("join.html#") -> t.substringAfter('#')
                else -> return null
            }
            val p = params.split('&').mapNotNull { kv ->
                val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i) to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }.toMap()
            val relay = p["r"]?.trim().orEmpty(); val code = p["f"]?.trim().orEmpty()
            if (relay.isEmpty() || code.replace("-", "").length < 8) return null
            return Invite(relay, code, p["n"].orEmpty(), p["a"].orEmpty(), p["an"].orEmpty(), p["k"].orEmpty())
        }
    }
}
