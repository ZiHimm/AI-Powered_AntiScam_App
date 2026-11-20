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
 * **Mum's device**. Son's and Daughter's phones run the mobile/ app.
 *
 * Same protocol as mobile/www/app.js:
 *   → {t:"hello", role:"mum", pub:<jwk>, name:"Mum"}
 *   ← {t:"peers", peers:{son:{online,pub}, …}}      keys are pinned on first sight
 *   ↔ {t:"msg", to|from, body:{kind:…}}
 *   → {t:"log", tag, html}                         shows up on the laptop monitor
 *
 * All state is touched on the main thread only.
 */
object FamilyLink {

    val MEMBERS = linkedMapOf("son" to "Son", "daughter" to "Daughter")
    fun nameOf(role: String) = MEMBERS[role] ?: if (role == "mum") "Mum" else role
    /** Enrolled face named "Son" ↔ the phone with role "son". */
    fun roleForName(name: String): String? = MEMBERS.entries.firstOrNull { it.value.equals(name.trim(), true) }?.key

    interface Listener { fun onFamilyMessage(from: String, body: JSONObject) }

    // ---------------------------------------------------------------- settings (private prefs)
    private const val PREFS = "family"
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var relay: String
        get() = prefs.getString("relay", "") ?: ""
        set(v) = prefs.edit().putString("relay", v.trim()).apply()
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
    var elderMode: Boolean
        get() = prefs.getBoolean("elder", true)
        set(v) { prefs.edit().putBoolean("elder", v).apply(); sendConfig() }
    var secretQuestion: String
        get() = prefs.getString("secret", null) ?: "What did we eat after Grandma's 70th birthday?"
        set(v) = prefs.edit().putString("secret", v).apply()
    /** How long Son/Daughter get to answer. 15 s was too short on real phones (unlock + read + fingerprint). */
    var replySeconds: Int
        get() = prefs.getInt("reply_s", 30)
        set(v) = prefs.edit().putInt("reply_s", v.coerceIn(10, 60)).apply()

    fun isGuardian(role: String) = prefs.getBoolean("guardian_$role", true)
    fun setGuardian(role: String, on: Boolean) { prefs.edit().putBoolean("guardian_$role", on).apply(); sendConfig() }
    fun guardians() = MEMBERS.keys.filter { isGuardian(it) }

    // ---------------------------------------------------------------- connection state
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var generation = 0
    var connected = false; private set
    var status = "Off"; private set
    val online = mutableMapOf<String, Boolean>()
    private val peerKeys = mutableMapOf<String, PublicKey>()
    val keyMismatch = mutableSetOf<String>()
    var listener: Listener? = null
    var version = 0L; private set // bumps on any change, for the settings screen

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    fun start() {
        if (!enabled || relay.isBlank()) { stop("Off"); return }
        connect()
    }

    fun stop(why: String = "Off") {
        generation++
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye"); ws = null
        connected = false; online.clear(); setStatus(why)
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
                webSocket.send(JSONObject().put("t", "hello").put("role", "mum")
                    .put("pub", DeviceKey.jwk()).put("name", "Mum").toString())
                setStatus("Connected as Mum's phone · ${relay}")
                log("sec", "Mum's detector connected. Device key ${DeviceKey.fingerprint}")
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
        connected = false; online.clear()
        if (code == 4000) {
            // relay: "replaced by a newer connection" — another device took the Mum role
            setStatus("Another phone is connected as Mum (the mobile/ app?). Close it, then reconnect here.")
            LiveLog.add("Relay: another phone took the Mum role. Not reconnecting.")
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
            "msg" -> listener?.onFamilyMessage(m.optString("from"), m.optJSONObject("body") ?: JSONObject())
            "undeliverable" -> LiveLog.add("Relay: ${nameOf(m.optString("to"))}'s phone isn't connected (${m.optString("kind")})")
        }
    }

    private fun onPeers(peers: JSONObject) {
        for (role in MEMBERS.keys) {
            val p = peers.optJSONObject(role) ?: continue
            val was = online[role] == true
            online[role] = p.optBoolean("online")
            p.optJSONObject("pub")?.let { learnKey(role, it) }
            if (was != online[role]) LiveLog.add("${nameOf(role)}'s phone is ${if (online[role] == true) "online" else "OFFLINE"}")
        }
        sendConfig()
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
            log("sec", "Paired with ${nameOf(role)}'s phone. Key $fp pinned on Mum's phone.")
        }
        peerKeys[role] = DeviceKey.publicKeyFromJwk(jwk)
    }

    fun pinOf(role: String): String? = prefs.getString("pin_$role", null)

    fun forgetPins() {
        prefs.edit().apply { MEMBERS.keys.forEach { remove("pin_$it") } }.apply()
        peerKeys.clear(); keyMismatch.clear()
        LiveLog.add("Forgot paired family keys. They'll be re-paired when the phones reconnect.")
        if (enabled) restart()
    }

    fun verifyFrom(env: JSONObject, role: String): Boolean = peerKeys[role]?.let { DeviceKey.verify(env, it) } ?: false

    // ---------------------------------------------------------------- sending
    fun isOnline(role: String) = connected && online[role] == true

    fun sendTo(role: String, body: JSONObject): Boolean {
        if (!isOnline(role)) return false
        return ws?.send(JSONObject().put("t", "msg").put("to", role).put("body", body).toString()) == true
    }

    /** Tells Son/Daughter whether they're guardians (shown on their phones). */
    fun sendConfig() {
        if (!connected) return
        for (role in MEMBERS.keys) {
            if (online[role] != true) continue
            sendTo(role, JSONObject().put("kind", "config").put("guardian", isGuardian(role))
                .put("primary", false).put("elderMode", elderMode))
        }
    }

    /** Line in this app's Activity log, and on the laptop monitor. */
    fun log(tag: String, text: String) {
        LiveLog.add(text)
        if (connected) ws?.send(JSONObject().put("t", "log").put("tag", tag).put("html", escape(text)).toString())
    }

    private val rng = SecureRandom()
    fun randHex(bytes: Int) = ByteArray(bytes).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun setStatus(s: String) { status = s; bump() }
    private fun bump() { version++ }
}
