package com.vowi.vowidroid.core.sim

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The USB CCID driver against a scripted single-slot reader. */
class CcidTest {
    /** Answers CCID bulk messages like a one-slot reader; each XfrBlock takes one scripted card reply. */
    private class FakeReader(var atr: ByteArray = SIM_ATR, var present: Boolean = true) : CcidTransport {
        var powered = false
        val muteVoltages = HashSet<Int>()
        val replies = ArrayDeque<ByteArray>()
        val blocks = ArrayList<ByteArray>()
        val powerSelects = ArrayList<Int>()
        val parameters = ArrayList<ByteArray>()
        var timeExtend = 0
        var staleReply = false
        var chunk = 0
        private val pending = ArrayDeque<ByteArray>()

        override fun bulkOut(data: ByteArray, timeoutMs: Int) {
            val length = (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
            val body = data.copyOfRange(10, 10 + length)
            val sequence = data[6].toInt() and 0xff
            var status = if (!present) 2 else if (powered) 0 else 1
            val reply = when (data[0].toInt() and 0xff) {
                0x65, 0x63 -> {
                    if (data[0].toInt() and 0xff == 0x63) {
                        powered = false
                        if (present) status = 1
                    }
                    message(0x81, sequence, status, 0, ByteArray(0))
                }
                0x62 -> {
                    powerSelects += data[7].toInt() and 0xff
                    when {
                        !present -> message(0x80, sequence, 0x40 or 2, 0xfe, ByteArray(0))
                        (data[7].toInt() and 0xff) in muteVoltages -> message(0x80, sequence, 0x40 or 1, 0xfe, ByteArray(0))
                        else -> {
                            powered = true
                            message(0x80, sequence, 0, 0, atr)
                        }
                    }
                }
                0x61 -> {
                    parameters += body
                    message(0x82, sequence, 0, 0, body)
                }
                0x6f -> {
                    blocks += body
                    val answer = replies.removeFirstOrNull() ?: throw AssertionError("unexpected block")
                    repeat(timeExtend) { pending.addLast(message(0x80, sequence, 0x80, 1, ByteArray(0))) }
                    message(0x80, sequence, 0, 0, answer)
                }
                else -> throw AssertionError("unexpected CCID message %02x".format(data[0]))
            }
            if (staleReply) {
                staleReply = false
                pending.addLast(message(0x80, sequence - 1, 0, 0, byteArrayOf(0x6f, 0x00)))
            }
            pending.addLast(reply)
        }

        override fun bulkIn(maxLength: Int, timeoutMs: Int): ByteArray {
            val next = pending.firstOrNull() ?: throw AssertionError("no pending reply")
            // A transfer never spans two messages, but one message may need several transfers.
            var count = minOf(next.size, maxLength)
            if (chunk > 0) count = minOf(count, chunk)
            val part = next.copyOf(count)
            if (count == next.size) pending.removeFirst() else pending[0] = next.copyOfRange(count, next.size)
            return part
        }

        private fun message(type: Int, sequence: Int, status: Int, code: Int, data: ByteArray): ByteArray {
            val out = ByteArray(10 + data.size)
            out[0] = type.toByte()
            out[1] = data.size.toByte()
            out[2] = (data.size shr 8).toByte()
            out[6] = sequence.toByte()
            out[7] = status.toByte()
            out[8] = code.toByte()
            data.copyInto(out, 10)
            return out
        }
    }

    @Test
    fun theClassDescriptorIsFoundInTheRawDescriptors() {
        val descriptor = CcidDescriptor.parse(raw(CcidDescriptor.EXCHANGE_SHORT_APDU or CcidDescriptor.FEATURE_AUTO_VOLTAGE, 0x07))
        assertNotNull(descriptor)
        assertEquals(2, descriptor!!.interfaceNumber)
        assertEquals(1L, descriptor.protocols)
        assertEquals(271L, descriptor.maxMessageLength)
        assertEquals(CcidDescriptor.EXCHANGE_SHORT_APDU, descriptor.exchangeLevel)
        assertNull("no class descriptor", CcidDescriptor.parse(raw(0, 0).copyOf(27)))
    }

    @Test
    fun characterLevelReadersAreRefused() {
        val descriptor = CcidDescriptor.parse(raw(0, 0x07))!!
        assertFalse(descriptor.supported)
        assertThrows(IllegalArgumentException::class.java) { CcidReader(FakeReader(), descriptor) }
    }

    @Test
    fun anInsertedCardIsPoweredAtTheReadersOwnVoltageAndRemovalIsSeen() {
        val fake = FakeReader().apply { chunk = 7 }
        val reader = reader(fake, CcidDescriptor.EXCHANGE_SHORT_APDU or CcidDescriptor.FEATURE_AUTO_VOLTAGE)
        assertEquals(CcidReader.Slot.INACTIVE, reader.slotStatus())
        assertArrayEquals(SIM_ATR, reader.powerOn())
        assertEquals(listOf(0x00), fake.powerSelects)
        assertEquals(CcidReader.Slot.ACTIVE, reader.slotStatus())

        fake.present = false
        fake.powered = false
        assertEquals(CcidReader.Slot.ABSENT, reader.slotStatus())
        assertNull(reader.atr)
        assertThrows(NoCardException::class.java) { reader.powerOn() }
    }

    @Test
    fun aMuteCardIsTriedAgainAtTheNextVoltage() {
        val fake = FakeReader().apply { muteVoltages += 0x03 }
        reader(fake, CcidDescriptor.EXCHANGE_SHORT_APDU).powerOn()
        assertEquals("1.8 V, then 3 V", listOf(0x03, 0x02), fake.powerSelects)
    }

    @Test
    fun tpduReadersGetT0CommandsAndGetResponse() {
        val fake = FakeReader().apply {
            replies += byteArrayOf(0x61, 0x04)
            replies += byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(), 0x90.toByte(), 0x00)
        }
        val reader = reader(fake, CcidDescriptor.EXCHANGE_TPDU or CcidDescriptor.FEATURE_AUTO_VOLTAGE)
        reader.powerOn()
        assertEquals(1, fake.parameters.size)
        assertArrayEquals(byteArrayOf(0x11, 0x00, 0x00, 0x0a, 0x00), fake.parameters[0])
        val response = reader.transmit(byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x04, 0x02, 0xa0.toByte(), 0x00, 0x00))
        assertEquals(0x9000, response.sw)
        assertArrayEquals(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()), response.data)
        // Case 4 goes out as case 3, then GET RESPONSE fetches the data.
        assertArrayEquals(byteArrayOf(0x00, 0xa4.toByte(), 0x04, 0x04, 0x02, 0xa0.toByte(), 0x00), fake.blocks[0])
        assertArrayEquals(byteArrayOf(0x00, 0xc0.toByte(), 0x00, 0x00, 0x04), fake.blocks[1])
    }

    @Test
    fun apduReadersWaitOutTimeExtensionsAndIgnoreStaleReplies() {
        val fake = FakeReader().apply {
            timeExtend = 2
            replies += byteArrayOf(0x90.toByte(), 0x00)
        }
        val reader = reader(fake, CcidDescriptor.EXCHANGE_SHORT_APDU or CcidDescriptor.FEATURE_AUTO_VOLTAGE)
        reader.powerOn()
        assertTrue("APDU readers set their own parameters", fake.parameters.isEmpty())
        fake.staleReply = true
        val command = byteArrayOf(0x00, 0x88.toByte(), 0x00, 0x81.toByte(), 0x01, 0x00, 0x00)
        assertEquals(0x9000, reader.transmit(command).sw)
        assertArrayEquals("APDU readers get the APDU unchanged", command, fake.blocks[0])
    }

    @Test
    fun apdusMapOntoT0Tpdus() {
        assertArrayEquals(byteArrayOf(0x00, 0x70, 0x00, 0x00, 0x00), CcidReader.apduToT0Tpdu(byteArrayOf(0x00, 0x70, 0x00, 0x00)))
        assertArrayEquals(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x00, 0x0a), CcidReader.apduToT0Tpdu(byteArrayOf(0x00, 0xb0.toByte(), 0x00, 0x00, 0x0a)))
        val select = byteArrayOf(0x00, 0xa4.toByte(), 0x00, 0x04, 0x02, 0x3f, 0x00)
        assertArrayEquals(select, CcidReader.apduToT0Tpdu(select))
        assertArrayEquals(select, CcidReader.apduToT0Tpdu(select + byteArrayOf(0x00)))
        assertThrows(SimCardException::class.java) { CcidReader.apduToT0Tpdu(byteArrayOf(0x00, 0xa4.toByte(), 0x00, 0x04, 0x05, 0x01)) }
    }

    @Test
    fun theAtrNamesT0AndItsParameters() {
        val parsed = Atr.parse(SIM_ATR)
        assertTrue(parsed.offersT0)
        assertEquals(0x96, parsed.ta1)
        assertFalse(parsed.inverse)
        assertFalse("only T=1", Atr.parse(byteArrayOf(0x3b, 0x80.toByte(), 0x01, 0x01)).offersT0)
    }

    private fun reader(fake: FakeReader, features: Long): CcidReader = CcidReader(fake, CcidDescriptor.parse(raw(features, 0x07))!!)

    companion object {
        val SIM_ATR = byteArrayOf(0x3b, 0x9e.toByte(), 0x96.toByte(), 0x80.toByte(), 0x1f, 0xc7.toByte(), 0x80.toByte(), 0x31, 0xe0.toByte(), 0x73, 0xfe.toByte(), 0x21, 0x1b, 0x66, 0xd0.toByte(), 0x01, 0x7b, 0x9c.toByte(), 0x00, 0x10)

        /** A minimal device and configuration descriptor set with one smart-card interface, as getRawDescriptors returns it. */
        fun raw(features: Long, voltages: Int): ByteArray {
            val device = byteArrayOf(0x12, 0x01, 0x10, 0x01, 0, 0, 0, 0x40, 0x5e, 0x07, 0x2a, 0x00, 0, 1, 1, 2, 3, 1)
            val config = byteArrayOf(0x09, 0x02, 0, 0, 1, 1, 0, 0x80.toByte(), 50)
            val face = byteArrayOf(0x09, 0x04, 2, 0, 3, 0x0b, 0, 0, 0)
            val ccid = ByteArray(0x36)
            ccid[0] = 0x36
            ccid[1] = 0x21
            ccid[2] = 0x10
            ccid[3] = 0x01
            ccid[5] = voltages.toByte()
            ccid[6] = 0x01
            for (index in 0 until 4) ccid[40 + index] = (features shr (8 * index)).toByte()
            ccid[44] = (271 and 0xff).toByte()
            ccid[45] = (271 shr 8).toByte()
            val endpoint = byteArrayOf(0x07, 0x05, 0x81.toByte(), 0x02, 0x40, 0x00, 0)
            return device + config + face + ccid + endpoint
        }
    }
}
