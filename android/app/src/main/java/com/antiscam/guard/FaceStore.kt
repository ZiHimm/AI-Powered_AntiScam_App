package com.antiscam.guard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** A family member (or the phone's owner) and their face embeddings. No photos are stored. */
/** memberId is set for faces another member shared from their own phone (step 2). */
data class Person(val name: String, val isOwner: Boolean, val embeddings: MutableList<FloatArray>, val memberId: String? = null)

data class Match(val person: Person?, val distance: Float)

/** Embeddings + settings in private SharedPreferences, on this phone only. */
object FaceStore {
    private const val PREFS = "faces"
    private const val KEY_PEOPLE = "people"
    private const val KEY_THRESHOLD = "threshold"
    const val DEFAULT_THRESHOLD = 1.0f // L2 distance between normalised embeddings; lower = stricter
    /** Asking someone's phone needs a clearer match than a "looks like" hint: threshold minus this… */
    const val CONFIDENT_GAP = 0.08f // 0.92 by default: real Son matched 0.66–0.92, wrong matches were 0.96–0.98 (test 21 Nov)
    /** …and the best person must beat the next-closest person by at least this much. */
    const val MARGIN = 0.05f

    fun load(ctx: Context): MutableList<Person> {
        val raw = prefs(ctx).getString(KEY_PEOPLE, null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val embs = o.getJSONArray("embeddings")
            Person(
                o.getString("name"),
                o.optBoolean("owner"),
                MutableList(embs.length()) { j ->
                    val e = embs.getJSONArray(j)
                    FloatArray(e.length()) { k -> e.getDouble(k).toFloat() }
                },
                o.optString("member").takeIf { it.isNotEmpty() }
            )
        }
    }

    private fun save(ctx: Context, people: List<Person>) {
        val arr = JSONArray()
        for (p in people) {
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("owner", p.isOwner)
                p.memberId?.let { put("member", it) }
                put("embeddings", JSONArray().apply {
                    p.embeddings.forEach { e -> put(JSONArray().apply { e.forEach { put(it.toDouble()) } }) }
                })
            })
        }
        prefs(ctx).edit().putString(KEY_PEOPLE, arr.toString()).apply()
    }

    fun addSample(ctx: Context, name: String, isOwner: Boolean, embedding: FloatArray) {
        val people = load(ctx)
        val p = people.find { it.name.equals(name, ignoreCase = true) }
            ?: Person(name, isOwner, mutableListOf()).also { people.add(it) }
        p.embeddings.add(embedding)
        save(ctx, people)
    }

    /** Faces a member shared from their own phone replace that member's previously shared faces. */
    fun setShared(ctx: Context, memberId: String, name: String, embeddings: List<FloatArray>) {
        val people = load(ctx).filterNot { it.memberId == memberId }.toMutableList()
        if (embeddings.isNotEmpty()) people.add(Person(name, false, embeddings.toMutableList(), memberId))
        save(ctx, people)
    }

    fun remove(ctx: Context, name: String) = save(ctx, load(ctx).filterNot { it.name == name })

    fun threshold(ctx: Context) = prefs(ctx).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
    fun setThreshold(ctx: Context, v: Float) = prefs(ctx).edit().putFloat(KEY_THRESHOLD, v).apply()

    /** Closest enrolled person to [embedding] (over all their samples), or person=null if nobody is enrolled. */
    fun best(people: List<Person>, embedding: FloatArray): Match {
        var best: Person? = null
        var bestD = Float.MAX_VALUE
        for (p in people) for (e in p.embeddings) {
            val d = distance(e, embedding)
            if (d < bestD) { bestD = d; best = p }
        }
        return Match(best, bestD)
    }

    /** Every enrolled person with their closest sample's distance, best first. */
    fun ranked(people: List<Person>, embedding: FloatArray): List<Match> =
        people.map { p -> Match(p, p.embeddings.minOfOrNull { distance(it, embedding) } ?: Float.MAX_VALUE) }
            .sortedBy { it.distance }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        var s = 0f
        for (i in a.indices) { val d = a[i] - b[i]; s += d * d }
        return sqrt(s)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
