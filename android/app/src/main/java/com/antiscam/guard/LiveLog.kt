package com.antiscam.guard

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
        lines.addFirst("${fmt.format(Date())}  $msg")
        while (lines.size > 60) lines.removeLast()
        version++
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}
