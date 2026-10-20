package com.vowi.vowidroid.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The core's log: a bounded in-memory ring the Logs screen shows, plus an
 * optional sink (logcat in the app). Protocol code must never log keys,
 * IMSI/ICCID or message bodies; callers pass counts and shapes instead.
 */
object CoreLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val time: Long, val level: Level, val tag: String, val message: String) {
        fun format(): String = "${TIME_FORMAT.format(Date(time))} ${level.name.first()} $tag: $message"
    }

    private const val CAPACITY = 4000
    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<Entry>(CAPACITY)
    private val lock = Any()
    private val versionFlow = MutableStateFlow(0L)

    /** Receives every entry; the app points this at logcat. */
    @Volatile
    var sink: ((Entry) -> Unit)? = null

    @Volatile
    var minimumLevel: Level = Level.DEBUG

    /** Increments on every entry so screens can refresh. */
    val version: StateFlow<Long> get() = versionFlow

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, withError(message, error))
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, withError(message, error))

    private fun withError(message: String, error: Throwable?): String =
        if (error == null) message else "$message: ${error.javaClass.simpleName}: ${error.message}"

    fun log(level: Level, tag: String, message: String) {
        if (level < minimumLevel) return
        val entry = Entry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(entry)
        }
        versionFlow.value = versionFlow.value + 1
        sink?.invoke(entry)
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        versionFlow.value = versionFlow.value + 1
    }
}
