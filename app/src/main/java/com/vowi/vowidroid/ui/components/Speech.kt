package com.vowi.vowidroid.ui.components

/** Text for screen readers, after AOSP's spoken descriptions in the dialer and Messaging. */
object Speech {
    /**
     * A phone number read digit by digit with a pause between groups, as
     * AOSP marks numbers with a telephone TtsSpan: "+1 206-555-0100" becomes
     * "+ 1, 2 0 6, 5 5 5, 0 1 0 0" rather than "two hundred six".
     */
    fun number(formatted: String): String {
        val groups = ArrayList<String>()
        val current = StringBuilder()
        fun close() {
            if (current.isNotEmpty()) groups.add(current.toString().trim())
            current.setLength(0)
        }
        for (character in formatted) {
            when {
                character.isDigit() || character == '*' || character == '#' -> current.append(character).append(' ')
                character == '+' -> current.append("+ ")
                character == ' ' || character == '-' || character == '(' || character == ')' || character == '.' -> close()
                else -> current.append(character)
            }
        }
        close()
        return groups.filter { it.isNotEmpty() }.joinToString(", ")
    }
}
