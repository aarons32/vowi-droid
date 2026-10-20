package com.vowi.vowidroid.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Numbers the user blocked. Their calls are turned away before the phone
 * rings and logged as blocked; their texts are kept, read and archived,
 * without a notification, as AOSP Messaging treats them. Android's own
 * blocked-number list is open only to the default phone and SMS apps, so
 * VowiDroid keeps its own.
 */
object BlockedNumbers {
    private const val PREFERENCES = "blocked_numbers"
    private const val KEY = "numbers"
    private lateinit var app: Context
    private val mutableNumbers = MutableStateFlow<List<String>>(emptyList())

    val numbers: StateFlow<List<String>> = mutableNumbers.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        val stored = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
        mutableNumbers.value = stored.sorted()
    }

    /** Safe from any thread. */
    fun isBlocked(number: String): Boolean = number.isNotBlank() && mutableNumbers.value.any { PhoneNumbers.same(it, number) }

    @Synchronized
    fun block(number: String) {
        if (number.isBlank() || isBlocked(number)) return
        save(mutableNumbers.value + number)
    }

    @Synchronized
    fun unblock(number: String) {
        save(mutableNumbers.value.filterNot { PhoneNumbers.same(it, number) })
    }

    private fun save(numbers: List<String>) {
        mutableNumbers.value = numbers.sorted()
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putStringSet(KEY, numbers.toSet()).apply()
    }
}
