package com.vowi.vowidroid.core.util

import java.security.SecureRandom

/** Produces `count` random bytes. Tests substitute deterministic sources. */
typealias RandomSource = (Int) -> ByteArray

object Rng {
    private val secure = SecureRandom()

    fun bytes(count: Int): ByteArray = ByteArray(count).also { secure.nextBytes(it) }

    /** Random bytes that are not all zero, for SPIs. */
    fun nonZero(count: Int, source: RandomSource = ::bytes): ByteArray {
        repeat(8) {
            val value = source(count)
            if (value.any { it != 0.toByte() }) return value
        }
        throw IllegalStateException("random source generated a zero value repeatedly")
    }
}

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd-length hex string" }
    return ByteArray(clean.length / 2) { index ->
        val high = Character.digit(clean[2 * index], 16)
        val low = Character.digit(clean[2 * index + 1], 16)
        require(high >= 0 && low >= 0) { "invalid hex digit" }
        ((high shl 4) or low).toByte()
    }
}

fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff
fun ByteArray.u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)
fun ByteArray.u32(offset: Int): Long = (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()

fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

fun ByteArray.putU32(offset: Int, value: Long) {
    putU16(offset, ((value ushr 16) and 0xffff).toInt())
    putU16(offset + 2, (value and 0xffff).toInt())
}

fun u16Bytes(value: Int): ByteArray = ByteArray(2).also { it.putU16(0, value) }
fun u32Bytes(value: Long): ByteArray = ByteArray(4).also { it.putU32(0, value) }

fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
    if (first.size != second.size) return false
    var difference = 0
    for (index in first.indices) difference = difference or (first[index].toInt() xor second[index].toInt())
    return difference == 0
}

/** A growable big-endian byte buffer. */
class ByteWriter(initialCapacity: Int = 128) {
    private var buffer = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }
    }

    fun byte(value: Int): ByteWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    fun bytes(value: ByteArray, from: Int = 0, to: Int = value.size): ByteWriter {
        val length = to - from
        ensure(length)
        System.arraycopy(value, from, buffer, size, length)
        size += length
        return this
    }

    fun u16(value: Int): ByteWriter {
        ensure(2)
        buffer.putU16(size, value)
        size += 2
        return this
    }

    fun u32(value: Long): ByteWriter {
        ensure(4)
        buffer.putU32(size, value)
        size += 4
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val out = ByteArray(size + other.size)
    System.arraycopy(this, 0, out, 0, size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        System.arraycopy(part, 0, out, offset, part.size)
        offset += part.size
    }
    return out
}
