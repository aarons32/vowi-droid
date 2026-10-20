package com.vowi.vowidroid.data

import java.text.Normalizer

/**
 * Keypad (T9) matching for dialpad suggestions, after AOSP's
 * SmartDialNameMatcher: typed digits match the start of any word of a name,
 * or a run of word starts such as initials ("57" finds John Smith). Numbers
 * match on their digits.
 */
object SmartDial {
    private val letters = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3', 'g' to '4', 'h' to '4', 'i' to '4',
        'j' to '5', 'k' to '5', 'l' to '5', 'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7',
        's' to '7', 't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9',
    )
    private val wordBreak = Regex("[^\\p{L}\\p{Nd}]+")

    /** The keypad digit for a character (accents ignored), or null when it has none. */
    fun keyFor(character: Char): Char? {
        if (character in '0'..'9') return character
        val plain = Normalizer.normalize(character.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar() ?: return null
        return letters[plain]
    }

    /** Turns letters into keypad digits and drops what cannot be dialled, as a pasted "1-800-FLOWERS" needs. */
    fun dialable(text: String): String = buildString {
        for (character in text) {
            when {
                character in "+*#,;" -> append(character)
                else -> keyFor(character)?.let(::append)
            }
        }
    }

    /** The words of [name] as keypad digits. */
    fun keyWords(name: String): List<String> =
        name.split(wordBreak).map { word -> word.mapNotNull(::keyFor).joinToString("") }.filter { it.isNotEmpty() }

    fun matchesName(name: String, query: String): Boolean {
        if (query.isEmpty() || query.any { it !in '0'..'9' }) return false
        val words = keyWords(name)
        return words.indices.any { start -> matchFrom(words, start, query) }
    }

    /** True when [query] starts word [index], possibly continuing into the starts of later words. */
    private fun matchFrom(words: List<String>, index: Int, query: String): Boolean {
        val word = words[index]
        for (length in minOf(word.length, query.length) downTo 1) {
            if (!word.startsWith(query.substring(0, length))) continue
            if (length == query.length) return true
            for (next in index + 1 until words.size) if (matchFrom(words, next, query.substring(length))) return true
        }
        return false
    }

    /** Digits match a number from its start, after a country code, or anywhere once three are typed. */
    fun matchesNumber(number: String, query: String): Boolean {
        if (query.isEmpty()) return false
        val digits = number.filter(Char::isDigit)
        if (digits.startsWith(query)) return true
        if (digits.length > 10 && digits.takeLast(10).startsWith(query)) return true
        return query.length >= 3 && digits.contains(query)
    }
}
