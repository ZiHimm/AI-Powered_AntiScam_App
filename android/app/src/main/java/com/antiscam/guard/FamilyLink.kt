package com.antiscam.guard

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.PublicKey
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Connects this phone to the family relay in mobile/relay (the laptop stand-in for FCM push) as
 * whichever family role this phone has (Mum = protected phone with the detector; Son/Daughter = family mode).
 * Family Verify (mobile/) speaks the same protocol, so any mix of the two apps works.
 *
 * Same protocol as mobile/www/app.js:
 *   → {t:"hello", role:"mum"|"son"|"daughter", pub:<jwk>, name}
 *   ← {t:"peers", peers:{son:{online,pub}, …}}      keys are pinned on first sight
 *   ↔ {t:"msg", to|from, body:{kind:…}}
 *   → {t:"log", tag, html}                         shows up on the laptop monitor
 *
 * All state is touched on the main thread only.
 */
object FamilyLink {

    /** Classic demo roles (Family Verify understands only these); in step 2 they're just member ids. */
    val ROLES = listOf("mum", "son", "daughter")
    private val CLASSIC = mapOf("mum" to "Mum", "son" to "Son", "daughter" to "Daughter")

    /** Member id from a display name: "Grandma Lim" → "grandma-lim", "Mum" → "mum" (so classic names stay compatible). */
    fun idForName(name: String): String = memberIdFor(name)

    fun nameOf(id: String): String = when (id) {
        me -> myName
        else -> members[id]?.name?.takeIf { it.isNotBlank() } ?: CLASSIC[id] ?: id.replaceFirstChar { it.uppercase() }
    }

    /** Enrolled face named "Son" ↔ the member whose name (or id) is "son". */
    fun roleForName(name: String): String? {
        val n = name.trim()
        members.values.firstOrNull { it.name.equals(n, true) }?.let { return it.id }
        val id = idForName(n)
        return if (id.isNotEmpty() && id != me && (id in members || id in ROLES)) id else null
    }

    interface Listener { fun onFamilyMessage(from: String, body: JSONObject) }

    /** Another family member, as seen from this phone. */
    class Member(val id: String) {
        var name = ""
        var online = false
        var senior = false
        var protect = false
        var v2 = false              // speaks the step-2 protocol (sent us a signed profile)
        var guardians: List<String> = emptyList()
        var phone = ""
    }
    val members = linkedMapOf<String, Member>()

    // ---------------------------------------------------------------- settings (private prefs)
    private const val PREFS = "family"
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** This phone's member id, from the owner's name (null until set up). Kept as "role" for compatibility. */
    var myRole: String?
        get() = prefs.getString("role", null)
        set(v) = prefs.edit().putString("role", v).apply()
    var myName: String
        get() = prefs.getString("name", null) ?: CLASSIC[myRole ?: ""] ?: (myRole ?: "Me").replaceFirstChar { it.uppercase() }
        set(v) = prefs.edit().putString("name", v.trim()).apply()
    /** Step 1 compatibility: "Mum" was the only protected phone. */
    val isMum get() = myRole == "mum"
    val me get() = myRole ?: "mum"

    /** Senior citizen: if a scammer targets me, my guardians get signed alerts (was "Elder Mode"). */
    var senior: Boolean
        get() = prefs.getBoolean("elder", myRole == "mum")   // new users: not a senior unless they say so
        set(v) { prefs.edit().putBoolean("elder", v).apply(); profileChanged() }
    var elderMode: Boolean
        get() = senior
        set(v) { senior = v }
    /** Check my own incoming video calls on this phone (needs the call watcher). */
    var protect: Boolean
        get() = prefs.getBoolean("protect", senior)   // default: on for seniors, optional for everyone else
        set(v) { prefs.edit().putBoolean("protect", v).apply(); profileChanged() }

    var relay: String
        get() = prefs.getString("relay", "") ?: ""
        set(v) = prefs.edit().putString("relay", v.trim()).apply()
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
    /** My phone number (optional): lets family tap "Call <me> now" to actually dial. */
    var myPhone: String
        get() = prefs.getString("phone", "") ?: ""
        set(v) { prefs.edit().putString("phone", v.trim()).apply(); profileChanged() }
    var familyName: String
        get() = prefs.getString("family_name", "") ?: ""
        set(v) = prefs.edit().putString("family_name", v.trim()).apply()
    /** I created this family (shown in the UI; anyone in the family can still invite). */
    var isAdmin: Boolean
        get() = prefs.getBoolean("admin", false)
        set(v) = prefs.edit().putBoolean("admin", v).apply()
    /** The family code from the relay address (…/f/CODE), if any. */
    val familyCode: String? get() = Regex("/f/([^/]+)").find(relay)?.groupValues?.get(1)

    var secretQuestion: String
        get() = prefs.getString("secret", null) ?: "What did we eat after Grandma's 70th birthday?"
        set(v) = prefs.edit().putString("secret", v).apply()
    /** How long the asked person gets to answer. 15 s was too short on real phones (unlock + read + fingerprint). */
    var replySeconds: Int
        get() = prefs.getInt("reply_s", 30)
        set(v) = prefs.edit().putInt("reply_s", v.coerceIn(10, 60)).apply()

    fun isGuardian(id: String) = prefs.getBoolean("guardian_$id", true)
    fun setGuardian(id: String, on: Boolean) { prefs.edit().putBoolean("guardian_$id", on).apply(); profileChanged() }
    /** My guardians: the members I've chosen (default: everyone else in the family). */
    fun guardians() = members.keys.filter { isGuardian(it) }
    /** Other members, online first. */
    fun others(): List<Member> = members.values.sortedByDescending { it.online }

    // ---------------------------------------------------------------- connection state
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var generation = 0
    var connected = false; private set
    var status = "Off"; private set
    val online = mutableMapOf<String, Boolean>()   // kept for compatibility: id -> online
    private val peerKeys = mutableMapOf<String, PublicKey>()
    val keyMismatch = mutableSetOf<String>()
    /** Everyone who wants relay messages on this phone: MemberHub (asked / guardian), FamilyCheck (my calls). */
    val listeners = linkedSetOf<Listener>()
    var version = 0L; private set // bumps on any change, for the settings screen

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext; FaceShare.init(ctx) }

    fun start() {
        if (!enabled || relay.isBlank()) { stop("Off"); return }
        connect()
    }

    fun stop(why: String = "Off") {
        generation++
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye"); ws = null
        connected = false; online.clear(); members.values.forEach { it.online = false }; setStatus(why)
    }

    fun restart() { stop(); start() }

    private fun url(): String {
        var r = relay.trim().removeSuffix("/").removeSuffix("/ws")
        r = r.replaceFirst(Regex("^https://"), "wss://").replaceFirst(Regex("^http://"), "ws://")
        if (!r.startsWith("ws://") && !r.startsWith("wss://")) r = "ws://$r"
        return "$r/ws"
    }

    private fun connect() {
        val gen = ++generation
        ws?.cancel()
        setStatus("Connecting to ${relay}…")
        val request = try { Request.Builder().url(url()).build() } catch (t: Throwable) {
            setStatus("Bad relay address: ${t.message}"); return
        }
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = main.post {
                if (gen != generation) return@post
                connected = true
                webSocket.send(JSONObject().put("t", "hello").put("role", me)
                    .put("pub", DeviceKey.jwk()).put("name", myName).put("v", 2).toString())
                setStatus("Connected as ${nameOf(me)}'s phone · ${relay}")
                log("sec", "${nameOf(me)}'s phone connected. Device key ${DeviceKey.fingerprint}")
            }.let {}

            override fun onMessage(webSocket: WebSocket, text: String) = main.post {
                if (gen == generation) handle(text)
            }.let {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                main.post { if (gen == generation) dropped(code, reason) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = main.post {
                if (gen == generation) dropped(-1, t.message ?: "connection failed")
            }.let {}
        })
    }

    private fun dropped(code: Int, reason: String) {
        connected = false; online.clear(); members.values.forEach { it.online = false }
        if (code == 4000) {
            // relay: "replaced by a newer connection": another device took this role
            setStatus("Another phone is connected as ${nameOf(me)}. Close it, then reconnect here.")
            LiveLog.add("Relay: another phone took the ${nameOf(me)} role. Not reconnecting.")
            return
        }
        setStatus("Disconnected ($reason). Retrying…")
        val gen = generation
        main.postDelayed({ if (gen == generation && enabled) connect() }, 2000)
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (_: Throwable) { return }
        when (m.optString("t")) {
            "peers" -> onPeers(m.getJSONObject("peers"))
            "msg" -> {
                val from = m.optString("from"); val body = m.optJSONObject("body") ?: JSONObject()
                when (body.optString("kind")) {
                    "profile" -> onProfile(from, body)
                    "face" -> FaceShare.onFace(from, body)
                    else -> listeners.toList().forEach { it.onFamilyMessage(from, body) }
                }
            }
            "undeliverable" -> LiveLog.add("Relay: ${nameOf(m.optString("to"))}'s phone isn't connected (${m.optString("kind")})")
        }
    }

    private fun onPeers(peers: JSONObject) {
        for (id in peers.keys()) {
            if (id == me) continue
            val p = peers.optJSONObject(id) ?: continue
            val isOnline = p.optBoolean("online")
            // the relay always lists the classic roles; only show members that are online or that we've met before
            if (!isOnline && id !in members && pinOf(id) == null) continue
            val m = members.getOrPut(id) { Member(id) }
            if (m.name.isBlank()) p.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { m.name = it }
            val was = m.online
            m.online = isOnline
            online[id] = isOnline
            p.optJSONObject("pub")?.let { learnKey(id, it) }
            if (was != isOnline) {
                LiveLog.add("${nameOf(id)}'s phone is ${if (isOnline) "online" else "OFFLINE"}")
                if (isOnline) sendProfile(id)   // introduce ourselves to a member who just came online
            }
        }
        sendConfig()
        bump()
    }

    // ---------------------------------------------------------------- profiles (step 2): name, senior, guardians
    private fun profileJson() = JSONObject().put("type", "profile").put("v", 2).put("from", me).put("name", myName)
        .put("senior", senior).put("protect", protect).put("guardians", org.json.JSONArray(guardians())).put("phone", myPhone)
        .put("ts", System.currentTimeMillis())

    private fun sendProfile(to: String) { if (connected) sendTo(to, JSONObject().put("kind", "profile").put("env", DeviceKey.sign(profileJson()))) }

    private fun profileChanged() {
        if (!::app.isInitialized) return
        members.values.filter { it.online }.forEach { sendProfile(it.id) }
        sendConfig(); bump()
    }

    private fun onProfile(from: String, body: JSONObject) {
        val env = body.optJSONObject("env") ?: return
        val p = try { JSONObject(env.getString("body")) } catch (_: Throwable) { return }
        if (p.optString("from") != from || !verifyFrom(env, from)) {
            log("attack", "✗ Ignored a profile claiming to be from ${nameOf(from)}: bad signature"); return
        }
        val m = members.getOrPut(from) { Member(from) }
        val first = !m.v2
        m.name = p.optString("name", m.name); m.senior = p.optBoolean("senior"); m.protect = p.optBoolean("protect")
        m.v2 = true
        m.phone = p.optString("phone")
        m.guardians = p.optJSONArray("guardians")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        if (first) {
            sendProfile(from)                // make sure they know us too
            FaceShare.sendMyFace(from)       // and our face, so their phone can recognise us
        }
        bump()
    }

    // ---------------------------------------------------------------- key pinning (trust on first use)
    private fun learnKey(role: String, jwk: JSONObject) {
        val fp = DeviceKey.fingerprint(jwk)
        val pinned = prefs.getString("pin_$role", null)
        if (pinned != null && pinned != fp) {
            if (keyMismatch.add(role)) log("attack",
                "✗ A device claiming to be ${nameOf(role)} has a different key ($fp) from the paired one ($pinned). Not trusted.")
            peerKeys.remove(role)
            return
        }
        keyMismatch.remove(role)
        if (pinned == null) {
            prefs.edit().putString("pin_$role", fp).apply()
            log("sec", "Paired with ${nameOf(role)}'s phone. Key $fp pinned on ${nameOf(me)}'s phone.")
        }
        peerKeys[role] = DeviceKey.publicKeyFromJwk(jwk)
    }

    fun pinOf(role: String): String? = prefs.getString("pin_$role", null)

    private fun clearPins() {
        val all = prefs.all.keys.filter { it.startsWith("pin_") }
        prefs.edit().apply { all.forEach { remove(it) } }.apply()
        peerKeys.clear(); keyMismatch.clear(); members.clear(); online.clear()
    }

    // ---------------------------------------------------------------- create / join a family (QR invite)
    /** An invite to this phone's current family (anyone in the family can invite; the anchor key is mine). */
    fun currentInvite(): Invite? {
        val code = familyCode ?: return null
        return Invite(Invite.baseOf(relay), code, familyName, me, myName, DeviceKey.fingerprint)
    }

    /** Admin: start a new family on [relayBase] with a fresh random family code. Old family keys are forgotten. */
    fun createFamily(relayBase: String, name: String): Invite {
        clearPins()
        relay = "${Invite.baseOf(relayBase)}/f/${Invite.newCode()}"
        familyName = name; isAdmin = true; enabled = true
        LiveLog.add("Created the family \"$name\". Invite others with the QR code.")
        return currentInvite()!!
    }

    /** Join from an invite: the inviter's key is pinned from the QR, so it's trusted from the start. */
    fun joinFamily(inv: Invite) {
        clearPins()
        if (inv.inviterId.isNotEmpty() && inv.inviterKey.isNotEmpty()) prefs.edit().putString("pin_${inv.inviterId}", inv.inviterKey).apply()
        relay = inv.relayRoom; familyName = inv.familyName; isAdmin = false; enabled = true
        LiveLog.add("Joined \"${inv.familyName}\", invited by ${inv.inviterName} (key ${inv.inviterKey} pinned from the invite).")
    }

    fun forgetPins() {
        prefs.edit().apply { (ROLES + members.keys).forEach { remove("pin_$it") } }.apply()
        peerKeys.clear(); keyMismatch.clear()
        LiveLog.add("Forgot paired family keys. They'll be re-paired when the phones reconnect.")
        if (enabled) restart()
    }

    fun verifyFrom(env: JSONObject, role: String): Boolean = peerKeys[role]?.let { DeviceKey.verify(env, it) } ?: false

    // ---------------------------------------------------------------- sending
    fun isOnline(id: String) = connected && members[id]?.online == true

    fun sendTo(role: String, body: JSONObject): Boolean {
        if (!isOnline(role)) return false
        return ws?.send(JSONObject().put("t", "msg").put("to", role).put("body", body).toString()) == true
    }

    /** Tells each member whether they're one of my guardians (Family Verify shows it; classic protocol). */
    fun sendConfig() {
        if (!connected || !::app.isInitialized) return
        for (m in members.values) {
            if (!m.online) continue
            sendTo(m.id, JSONObject().put("kind", "config").put("guardian", isGuardian(m.id))
                .put("primary", false).put("elderMode", senior))
        }
    }

    fun isMember(id: String) = id in members

    /** Line in this app's Activity log, and on the laptop monitor. */
    fun log(tag: String, text: String) {
        LiveLog.add(text)
        if (connected) ws?.send(JSONObject().put("t", "log").put("tag", tag).put("html", escape(text)).toString())
    }

    private val rng = SecureRandom()
    fun randHex(bytes: Int) = ByteArray(bytes).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun setStatus(s: String) { status = s; bump() }
    fun bump() { version++ }
}

/** "Grandma Lim" → "grandma-lim"; "Mum" → "mum". Must match the relay's /^[a-z][a-z0-9-]{1,23}$/. Pure (unit-tested). */
internal fun memberIdFor(name: String): String {
    val slug = name.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .let { if (it.isNotEmpty() && !it[0].isLetter()) "m-$it" else it }
        .take(24).trimEnd('-')
    if (slug.length >= 2) return slug
    if (name.isBlank()) return ""
    if (slug.isNotEmpty() && slug[0].isLetter()) return "$slug-1"
    // names with no Latin letters (陈, சிவா, …): a stable id from the name itself
    val h = java.security.MessageDigest.getInstance("SHA-256").digest(name.trim().toByteArray())
    return "m-" + h.take(4).joinToString("") { "%02x".format(it) }
}
