package com.vowi.vowidroid.data

/** Comparing phone numbers written in different ways. */
object PhoneNumbers {
    /**
     * True when two numbers name the same phone: the same digits, or the same
     * last ten digits ("+1 555 123 4567" and "5551234567"). Short codes must
     * match exactly.
     */
    fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val left = a.filter(Char::isDigit)
        val right = b.filter(Char::isDigit)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        if (left.length < 7 || right.length < 7) return false
        val length = minOf(10, left.length, right.length)
        return left.takeLast(length) == right.takeLast(length)
    }
}
