package com.antiscam.guard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Step 2: each member enrols THEIR OWN face once (selfies, ticked "This is me"); the phone sends the embeddings,
 * signed, to the other members, so every phone can recognise everyone. No photo ever leaves the phone.
 *
 *   {kind:"face", env: signed {type:"face", v:2, model:"mobilefacenet-192", from, name, embeddings:[[192 floats]…], ts}}
 *
 * Only sent to members that speak step 2 (they sent a signed profile). Family Verify uses a different face model
 * (face-api, 128-d), so its "face" messages are ignored here and ours are never sent to it.
 */
object FaceShare {
    const val MODEL = "mobilefacenet-192"
    private lateinit var app: Context

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    /**
     * Send my enrolled face to [to], or to every online step-2 member. Re-sent whenever a member comes online, so a phone
     * that was off when the face changed still gets it. If the owner deleted their face, an empty list is sent, so the
     * other phones drop their copy. Returns how many phones it was sent to.
     */
    fun sendMyFace(to: String? = null): Int {
        if (!::app.isInitialized) return 0
        if (!FamilyLink.connected) { LiveLog.add("Face not shared yet: not connected to the family (it's sent when you reconnect)"); return 0 }
        val mine = FaceStore.owner(app, FamilyLink.myName)
            ?: if (FaceStore.ownerRemoved(app)) Person(FamilyLink.myName, true, mutableListOf())   // deleted: tell them to drop it
            else { if (to == null) LiveLog.add("Face not shared: no face marked as mine on this phone (add one with “This is me”)"); return 0 }
        val targets = if (to != null) listOf(to) else FamilyLink.members.values.filter { it.online && it.v2 }.map { it.id }
        if (targets.isEmpty()) {
            LiveLog.add("Face not shared yet: no family phone online (" +
                FamilyLink.members.values.joinToString { "${it.id} online=${it.online} v2=${it.v2}" }.ifEmpty { "none" } +
                "). It's sent when they come online.")
            return 0
        }
        val payload = JSONObject().put("type", "face").put("v", 2).put("model", MODEL).put("from", FamilyLink.me)
            .put("name", FamilyLink.myName).put("ts", System.currentTimeMillis())
            .put("embeddings", JSONArray().apply { mine.embeddings.forEach { e -> put(JSONArray().apply { e.forEach { put(it.toDouble()) } }) } })
        val body = JSONObject().put("kind", "face").put("env", DeviceKey.sign(payload))
        val sent = targets.filter { FamilyLink.sendTo(it, body) }
        if (sent.isNotEmpty()) FamilyLink.log("face", if (mine.embeddings.isEmpty())
            "Told ${sent.joinToString { FamilyLink.nameOf(it) }} I removed my face (they delete their copy)"
            else "Shared my face (${mine.embeddings.size} sample(s), signed, no photo) with ${sent.joinToString { FamilyLink.nameOf(it) }}")
        return sent.size
    }

    private val asked = mutableMapOf<String, Long>()

    /** Ask [member]'s phone for its face if we don't have it (at most once a minute per member). */
    fun requestIfMissing(member: String) {
        if (!::app.isInitialized || !FamilyLink.isOnline(member)) return
        if (FaceStore.load(app).any { it.memberId == member }) return
        val now = System.currentTimeMillis()
        if (now - (asked[member] ?: 0L) < 60_000) return
        asked[member] = now
        if (FamilyLink.sendTo(member, JSONObject().put("kind", "face-request")))
            LiveLog.add("Asked ${FamilyLink.nameOf(member)}'s phone for their face (we don't have it yet)")
    }

    /** "🔄 Sync faces": send mine to everyone and ask everyone for theirs. */
    fun syncNow(): Int {
        if (!::app.isInitialized || !FamilyLink.connected) return 0
        asked.clear()
        val online = FamilyLink.members.values.filter { it.online }
        online.forEach { FamilyLink.sendTo(it.id, JSONObject().put("kind", "face-request")) }
        sendMyFace(null)
        online.filter { !it.v2 }.forEach { sendMyFace(it.id) }   // also try phones we haven't heard a profile from yet
        LiveLog.add("Sync faces: sent mine and asked ${online.size} family phone(s) for theirs")
        return online.size
    }

    fun onFace(from: String, body: JSONObject) {
        if (!::app.isInitialized) return
        val env = body.optJSONObject("env") ?: return
        val p = try { JSONObject(env.getString("body")) } catch (_: Throwable) { return }
        if (p.optString("model") != MODEL) {
            LiveLog.add("${FamilyLink.nameOf(from)} shared a face from a different app/model. Not used: enrol them here with 📷 instead.")
            return
        }
        if (p.optString("from") != from || !FamilyLink.verifyFrom(env, from)) {
            FamilyLink.log("attack", "✗ Rejected face data from ${FamilyLink.nameOf(from)}: signature doesn't match the key paired for them" +
                if (from in FamilyLink.keyMismatch) " (KEY MISMATCH: an old key is pinned; tap Forget paired family keys or rejoin with the QR)" else "")
            return
        }
        val arr = p.optJSONArray("embeddings") ?: return
        val embs = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONArray(i)?.takeIf { it.length() == 192 }?.let { e -> FloatArray(192) { k -> e.getDouble(k).toFloat() } }
        }.take(10)
        val name = p.optString("name").ifBlank { FamilyLink.nameOf(from) }
        val had = FaceStore.load(app).any { it.memberId == from }
        FaceStore.setShared(app, from, name, embs)
        if (embs.isEmpty()) { if (had) FamilyLink.log("face", "$name removed their face; their old copy was deleted here.") }
        else FamilyLink.log("face", "✓ $name shared their face from their own phone (${embs.size} sample(s), signed). No photo was sent.")
        FamilyLink.bump()
    }
}
