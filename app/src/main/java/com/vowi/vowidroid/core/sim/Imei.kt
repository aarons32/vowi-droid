package com.vowi.vowidroid.core.sim

import java.security.SecureRandom

/**
 * The device identity some ePDGs ask for (TS 24.302 DEVICE_IDENTITY) and that
 * names the SIP instance: 15 digits, the last a Luhn check digit over the
 * first 14 (3GPP TS 23.003 §6.2.1). A line may carry the phone's own, one the
 * user typed, or one made up here.
 */
object Imei {
    /** Just the digits of what the user typed. */
    fun normalize(input: String): String = input.filter(Char::isDigit)

    /** Fifteen digits, as the ePDG and the SIP instance URI want it. */
    fun isWellFormed(digits: String): Boolean = digits.length == 15 && digits.all { it in '0'..'9' }

    /** Whether the fifteenth digit is the Luhn check digit of the first fourteen. */
    fun checkDigitMatches(digits: String): Boolean = isWellFormed(digits) && checkDigit(digits.substring(0, 14)) == digits[14]

    /** The Luhn check digit for a 14-digit type allocation code and serial number. */
    fun checkDigit(first14: String): Char {
        require(first14.length == 14 && first14.all { it in '0'..'9' }) { "an IMEI check digit covers 14 digits" }
        var sum = 0
        first14.forEachIndexed { index, digit ->
            var value = digit - '0'
            // Every second digit from the right of the 14, i.e. the even positions counted from 1 on the left, is doubled.
            if (index % 2 == 1) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
        }
        return '0' + (10 - sum % 10) % 10
    }

    /**
     * A well-formed random IMEI: a type allocation code in the 35 range that
     * reporting bodies hand out, a random serial, and the right check digit.
     */
    fun generate(random: java.util.Random = SecureRandom()): String {
        val body = StringBuilder("35")
        repeat(12) { body.append('0' + random.nextInt(10)) }
        return body.toString() + checkDigit(body.toString())
    }
}
