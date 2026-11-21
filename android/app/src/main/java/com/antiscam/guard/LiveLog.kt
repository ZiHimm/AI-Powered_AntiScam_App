package com.antiscam.guard

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log shown on the app screen, so you can see what the background service is doing. */
object LiveLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var serviceConnected = false
    @Volatile var version = 0L; private set

    @Synchronized fun add(msg: String) {
        Log.w("AntiScamGuard", msg) // also readable over USB: adb logcat -s AntiScamGuard
        lines.addFirst("${fmt.format(Date())}  $msg")
        while (lines.size > 60) lines.removeLast()
        version++
    }

    @Synchronized fun text(): String = lines.joinToString("\n")

    // ---------------------------------------------------------------- one-line summary of the current/last call (home screen)
    @Volatile var callLine: String? = null; private set
    private var callApp = ""
    private var verdict: String? = null
    private var callStartedAt = 0L
    private val hhmm = SimpleDateFormat("HH:mm", Locale.US)

    @Synchronized fun callStarted(app: String) {
        callApp = app; verdict = null; callStartedAt = System.currentTimeMillis()
        callLine = "📞 $app call: checking the caller now…"; version++
    }
    @Synchronized fun callVerdict(v: String) { verdict = v; callLine = "📞 $callApp call: $v"; version++ }
    @Synchronized fun callEnded() {
        if (callApp.isEmpty()) return
        callLine = "Last call: $callApp at ${hhmm.format(Date(callStartedAt))} · ${verdict ?: "no family face seen"}"; version++
    }
}
