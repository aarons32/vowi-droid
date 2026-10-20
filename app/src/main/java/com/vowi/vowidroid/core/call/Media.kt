package com.vowi.vowidroid.core.call

import com.vowi.vowidroid.core.net.IpPacket
import com.vowi.vowidroid.core.net.NetStack
import com.vowi.vowidroid.core.net.UdpSocket
import com.vowi.vowidroid.core.util.CoreLog
import com.vowi.vowidroid.core.util.Rng
import com.vowi.vowidroid.core.util.putU16
import com.vowi.vowidroid.core.util.putU32
import com.vowi.vowidroid.core.util.u16
import com.vowi.vowidroid.core.util.u32
import com.vowi.vowidroid.core.util.u8
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A speech codec: one 20 ms frame at a time. */
interface AudioCodec : AutoCloseable {
    /** The RTP encoding name, e.g. AMR-WB. */
    val name: String

    /** The RTP clock rate and the PCM sample rate (equal for speech codecs). */
    val sampleRate: Int

    /** PCM samples per 20 ms frame. */
    val frameSamples: Int get() = sampleRate / 50

    /** Encodes one frame; the result is the RTP payload for this codec. */
    fun encode(pcm: ShortArray): ByteArray

    /** Decodes one RTP payload into PCM frames. */
    fun decode(payload: ByteArray): ShortArray

    /** One payload that stands for 20 ms of silence, sent now and then while the call is on hold. */
    fun keepalivePayload(): ByteArray = encode(ShortArray(frameSamples))

    override fun close() = Unit
}

/** Creates codecs by RTP name; the Android app adds AMR through MediaCodec. */
interface AudioCodecFactory {
    /** Codecs in preference order. */
    val supported: List<String>

    fun create(name: String, fmtp: String): AudioCodec?
}

/** G.711 only; used on the JVM and as the fallback. */
object G711Factory : AudioCodecFactory {
    override val supported: List<String> = listOf("PCMA", "PCMU")
    override fun create(name: String, fmtp: String): AudioCodec? = when (name.uppercase()) {
        "PCMA" -> G711Codec(true)
        "PCMU" -> G711Codec(false)
        else -> null
    }
}

class G711Codec(private val aLaw: Boolean) : AudioCodec {
    override val name: String = if (aLaw) "PCMA" else "PCMU"
    override val sampleRate: Int = 8000

    override fun encode(pcm: ShortArray): ByteArray = ByteArray(pcm.size) { index -> if (aLaw) G711.linearToALaw(pcm[index]) else G711.linearToMuLaw(pcm[index]) }

    override fun decode(payload: ByteArray): ShortArray = ShortArray(payload.size) { index -> if (aLaw) G711.aLawToLinear(payload[index]) else G711.muLawToLinear(payload[index]) }
}

object G711 {
    fun linearToMuLaw(sample: Short): Byte {
        var value = sample.toInt()
        var sign = 0
        if (value < 0) {
            sign = 0x80
            value = -value
            if (value > 32767) value = 32767
        }
        value += 132
        if (value > 32635) value = 32635
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && value and mask == 0) {
            exponent--
            mask = mask ushr 1
        }
        val mantissa = (value ushr (exponent + 3)) and 0x0f
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun muLawToLinear(encoded: Byte): Short {
        val value = encoded.toInt().inv() and 0xff
        var magnitude = (((value and 0x0f) shl 3) + 132) shl ((value and 0x70) ushr 4)
        magnitude -= 132
        return (if (value and 0x80 != 0) -magnitude else magnitude).toShort()
    }

    fun linearToALaw(sample: Short): Byte {
        var value = sample.toInt()
        var mask = 0xd5
        if (value < 0) {
            mask = 0x55
            value = -value - 1
        }
        if (value > 32767) value = 32767
        val encoded = if (value < 256) {
            value ushr 4
        } else {
            var exponent = 1
            var threshold = 512
            while (exponent < 7 && value >= threshold) {
                exponent++
                threshold = threshold shl 1
            }
            (exponent shl 4) or ((value ushr (exponent + 3)) and 0x0f)
        }
        return (encoded xor mask).toByte()
    }

    fun aLawToLinear(encoded: Byte): Short {
        val value = (encoded.toInt() and 0xff) xor 0x55
        var magnitude = ((value and 0x0f) shl 4) + 8
        val exponent = (value and 0x70) ushr 4
        if (exponent != 0) magnitude = (magnitude + 0x100) shl (exponent - 1)
        return (if (value and 0x80 == 0) -magnitude else magnitude).toShort()
    }
}

/**
 * RFC 4867 AMR/AMR-WB payload framing. Frames use the codec's "storage"
 * layout, one header octet `(FT << 3) | 0x04` followed by the frame bits,
 * which is what Android's MediaCodec produces and accepts.
 */
object AmrPayload {
    val NB_FRAME_BYTES = intArrayOf(12, 13, 15, 17, 19, 20, 26, 31, 5, 0, 0, 0, 0, 0, 0, 0)
    val WB_FRAME_BYTES = intArrayOf(17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0)
    val NB_FRAME_BITS = intArrayOf(95, 103, 118, 134, 148, 159, 204, 244, 39, 0, 0, 0, 0, 0, 0, 0)
    val WB_FRAME_BITS = intArrayOf(132, 177, 253, 285, 317, 365, 397, 461, 477, 40, 0, 0, 0, 0, 0, 0)

    /** Frame type 15 (NO_DATA) in storage format: a frame that carries no speech. */
    private const val NO_DATA_FRAME: Byte = 0x7C

    /** A payload with one NO_DATA frame (RFC 4867 §4.3.2, TS 26.101): silence without touching the encoder. */
    fun noData(wideband: Boolean, octetAligned: Boolean): ByteArray = pack(listOf(byteArrayOf(NO_DATA_FRAME)), wideband, octetAligned)

    /** Packs storage-format frames into one RTP payload. */
    fun pack(frames: List<ByteArray>, wideband: Boolean, octetAligned: Boolean, cmr: Int = 15): ByteArray {
        val sizes = if (wideband) WB_FRAME_BYTES else NB_FRAME_BYTES
        val bits = if (wideband) WB_FRAME_BITS else NB_FRAME_BITS
        if (octetAligned) {
            val out = ArrayList<Byte>()
            out.add((cmr shl 4).toByte())
            for ((index, frame) in frames.withIndex()) {
                val frameType = (frame.u8(0) ushr 3) and 0x0f
                val quality = (frame.u8(0) ushr 2) and 1
                val more = if (index + 1 < frames.size) 0x80 else 0
                out.add((more or (frameType shl 3) or (quality shl 2)).toByte())
            }
            for (frame in frames) {
                val frameType = (frame.u8(0) ushr 3) and 0x0f
                val length = minOf(sizes[frameType], frame.size - 1)
                for (offset in 1..length) out.add(frame[offset])
            }
            return out.toByteArray()
        }
        val writer = BitWriter()
        writer.write(cmr, 4)
        for ((index, frame) in frames.withIndex()) {
            val frameType = (frame.u8(0) ushr 3) and 0x0f
            val quality = (frame.u8(0) ushr 2) and 1
            writer.write(if (index + 1 < frames.size) 1 else 0, 1)
            writer.write(frameType, 4)
            writer.write(quality, 1)
        }
        for (frame in frames) {
            val frameType = (frame.u8(0) ushr 3) and 0x0f
            writer.writeBits(frame, 1, bits[frameType])
        }
        return writer.toByteArray()
    }

    /** Unpacks an RTP payload into storage-format frames. */
    fun unpack(payload: ByteArray, wideband: Boolean, octetAligned: Boolean): List<ByteArray> {
        val sizes = if (wideband) WB_FRAME_BYTES else NB_FRAME_BYTES
        val bits = if (wideband) WB_FRAME_BITS else NB_FRAME_BITS
        val frames = ArrayList<ByteArray>()
        if (payload.isEmpty()) return frames
        if (octetAligned) {
            var index = 1
            val tocs = ArrayList<Int>()
            while (index < payload.size) {
                val toc = payload.u8(index++)
                tocs.add(toc)
                if (toc and 0x80 == 0) break
            }
            for (toc in tocs) {
                val frameType = (toc ushr 3) and 0x0f
                val length = sizes[frameType]
                if (index + length > payload.size) break
                val frame = ByteArray(1 + length)
                frame[0] = ((frameType shl 3) or (toc and 0x04)).toByte()
                System.arraycopy(payload, index, frame, 1, length)
                frames.add(frame)
                index += length
            }
            return frames
        }
        val reader = BitReader(payload)
        reader.read(4)
        val tocs = ArrayList<Int>()
        while (true) {
            if (reader.remaining < 6) return frames
            val more = reader.read(1)
            val frameType = reader.read(4)
            val quality = reader.read(1)
            tocs.add((frameType shl 3) or (quality shl 2))
            if (more == 0) break
        }
        for (toc in tocs) {
            val frameType = (toc ushr 3) and 0x0f
            val length = bits[frameType]
            if (reader.remaining < length) break
            val frame = ByteArray(1 + (length + 7) / 8)
            frame[0] = toc.toByte()
            reader.readBits(frame, 1, length)
            frames.add(frame)
        }
        return frames
    }

    private class BitWriter {
        private val bytes = ArrayList<Byte>()
        private var current = 0
        private var count = 0

        fun write(value: Int, width: Int) {
            for (bit in width - 1 downTo 0) {
                current = (current shl 1) or ((value ushr bit) and 1)
                count++
                if (count == 8) {
                    bytes.add(current.toByte())
                    current = 0
                    count = 0
                }
            }
        }

        fun writeBits(source: ByteArray, offset: Int, width: Int) {
            for (bit in 0 until width) {
                val byte = source.u8(offset + bit / 8)
                write((byte ushr (7 - bit % 8)) and 1, 1)
            }
        }

        fun toByteArray(): ByteArray {
            val out = ArrayList(bytes)
            if (count > 0) out.add((current shl (8 - count)).toByte())
            return out.toByteArray()
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var position = 0
        val remaining: Int get() = data.size * 8 - position

        fun read(width: Int): Int {
            var value = 0
            repeat(width) {
                val byte = data.u8(position / 8)
                value = (value shl 1) or ((byte ushr (7 - position % 8)) and 1)
                position++
            }
            return value
        }

        fun readBits(destination: ByteArray, offset: Int, width: Int) {
            for (bit in 0 until width) {
                val value = read(1)
                val index = offset + bit / 8
                destination[index] = (destination[index].toInt() or (value shl (7 - bit % 8))).toByte()
            }
        }
    }
}

/** Audio-line parameters from the remote SDP. */
class RemoteMedia(val address: ByteArray, val port: Int, val codec: String, val payloadType: Int, val fmtp: String, val dtmfPayloadType: Int, val clockRate: Int)

/** An SDP media direction (RFC 3264 §5.1), as the side that wrote it sees it. */
enum class MediaDirection(val sends: Boolean, val receives: Boolean, val attribute: String) {
    SENDRECV(true, true, "sendrecv"),
    SENDONLY(true, false, "sendonly"),
    RECVONLY(false, true, "recvonly"),
    INACTIVE(false, false, "inactive");

    /** The same stream seen from the other end: their sendonly is our recvonly. */
    val reversed: MediaDirection get() = of(sends = receives, receives = sends)

    /** What both allow. */
    infix fun and(other: MediaDirection): MediaDirection = of(sends && other.sends, receives && other.receives)

    companion object {
        fun of(sends: Boolean, receives: Boolean): MediaDirection = when {
            sends && receives -> SENDRECV
            sends -> SENDONLY
            receives -> RECVONLY
            else -> INACTIVE
        }

        fun parse(attribute: String): MediaDirection? = entries.firstOrNull { it.attribute.equals(attribute.trim(), ignoreCase = true) }
    }
}

object Sdp {
    class AudioLine(
        val address: ByteArray?,
        val port: Int,
        val formats: List<Int>,
        val names: Map<Int, String>,
        val rates: Map<Int, Int>,
        val fmtp: Map<Int, String>,
        /** The audio stream's direction; a 0.0.0.0 address (RFC 2543 hold) counts as not receiving. */
        val direction: MediaDirection = MediaDirection.SENDRECV,
        /** The size of the body this came from. */
        val size: Int = 0,
    )

    fun parse(body: ByteArray): AudioLine {
        var sessionIp: ByteArray? = null
        var mediaIp: ByteArray? = null
        var port = 0
        val formats = ArrayList<Int>()
        val names = HashMap<Int, String>()
        val rates = HashMap<Int, Int>()
        val fmtp = HashMap<Int, String>()
        var inAudio = false
        var seenMedia = false
        var sessionDirection: MediaDirection? = null
        var mediaDirection: MediaDirection? = null
        for (raw in String(body, Charsets.ISO_8859_1).split("\r\n", "\n")) {
            val line = raw.trim()
            when {
                line.startsWith("a=") && MediaDirection.parse(line.substring(2)) != null -> {
                    val direction = MediaDirection.parse(line.substring(2))
                    if (!seenMedia) sessionDirection = direction else if (inAudio) mediaDirection = direction
                }
                line.startsWith("m=") -> {
                    seenMedia = true
                    val fields = line.substring(2).split(Regex("\\s+"))
                    inAudio = fields.size >= 4 && fields[0].equals("audio", ignoreCase = true) && fields[2].uppercase().startsWith("RTP/AVP")
                    if (inAudio) {
                        port = fields[1].substringBefore('/').toIntOrNull() ?: 0
                        formats.clear()
                        fields.drop(3).mapNotNullTo(formats) { it.toIntOrNull()?.takeIf { value -> value in 0..127 } }
                    }
                }
                line.startsWith("c=") -> {
                    val fields = line.substring(2).split(Regex("\\s+"))
                    if (fields.size >= 3) {
                        val address = runCatching { java.net.InetAddress.getByName(fields[2].substringBefore('/')).address }.getOrNull()
                        if (inAudio) mediaIp = address else sessionIp = address
                    }
                }
                inAudio && line.lowercase().startsWith("a=rtpmap:") -> {
                    val fields = line.substring(9).split(Regex("\\s+"))
                    if (fields.size == 2) {
                        val payloadType = fields[0].toIntOrNull() ?: continue
                        val parts = fields[1].split('/')
                        names[payloadType] = parts[0]
                        rates[payloadType] = parts.getOrNull(1)?.toIntOrNull() ?: 8000
                    }
                }
                inAudio && line.lowercase().startsWith("a=fmtp:") -> {
                    val rest = line.substring(7)
                    val space = rest.indexOf(' ')
                    if (space > 0) rest.substring(0, space).toIntOrNull()?.let { fmtp[it] = rest.substring(space + 1).trim() }
                }
            }
        }
        val address = mediaIp ?: sessionIp
        var direction = mediaDirection ?: sessionDirection ?: MediaDirection.SENDRECV
        // RFC 3264 §8.4: an agent offering 0.0.0.0 wants neither RTP nor RTCP sent to it.
        if (address != null && address.all { it == 0.toByte() }) direction = MediaDirection.of(sends = direction.sends, receives = false)
        return AudioLine(address, port, formats, names, rates, fmtp, direction, body.size)
    }

    fun codecName(line: AudioLine, payloadType: Int): String = line.names[payloadType]?.uppercase() ?: when (payloadType) {
        0 -> "PCMU"
        8 -> "PCMA"
        else -> ""
    }
}

/**
 * One call's RTP stream on the tunnel's stack: SDP offer and answer, codec
 * negotiation, the media direction that hold changes, 20 ms framing and
 * RFC 4733 DTMF.
 */
class RtpMedia(stack: NetStack, private val localAddress: ByteArray, private val codecs: AudioCodecFactory) : AutoCloseable {
    private val socket: UdpSocket = stack.openUdp(0, if (localAddress.size == 4) 4 else 6)
    val localPort: Int get() = socket.localPort
    private val lock = Any()

    @Volatile
    private var remote: RemoteMedia? = null

    @Volatile
    private var codec: AudioCodec? = null
    private var octetAligned = true
    private var pending = ShortArray(0)
    private var sequence = Rng.bytes(2).u16(0)
    private var timestamp = Rng.bytes(4).u32(0).toLong()
    private val ssrc = Rng.bytes(4).u32(0).toLong()
    private var lastSentNanos = 0L
    private val downlink = LinkedBlockingQueue<ShortArray>(64)
    private val closed = AtomicBoolean(false)
    private val reader = Thread(::receive, "vowi-rtp").apply { isDaemon = true }
    private var keepalive: Thread? = null

    /** RFC 3264 §8: every SDP of the call keeps one o= session, its version raised by one each time. */
    private val sessionId = System.currentTimeMillis() / 1000
    private var sessionVersion = sessionId

    /** The direction in force, from this side: whether we may send and whether we take media in. */
    @Volatile
    var direction: MediaDirection = MediaDirection.SENDRECV
        private set

    /** The direction of the far end's latest offer, from its side. Sendonly or inactive means it holds us. */
    @Volatile
    var remoteOfferDirection: MediaDirection = MediaDirection.SENDRECV
        private set

    /** What our latest offer asked for. */
    @Volatile
    private var offered: MediaDirection = MediaDirection.SENDRECV

    /**
     * True while we hold the call. Our offers then only send (TS 24.610), the
     * microphone is cut off and the far end gets silence until we resume.
     */
    @Volatile
    var localHold: Boolean = false
        set(value) {
            field = value
            if (value) {
                downlink.clear()
                synchronized(lock) { pending = ShortArray(0) }
                startKeepalive()
            }
        }

    init {
        reader.start()
    }

    val codecName: String get() = remote?.codec ?: ""
    val sampleRate: Int get() = codec?.sampleRate ?: 8000
    val ready: Boolean get() = remote != null && codec != null

    private val offerFormats: List<Triple<Int, String, Int>>
        get() {
            val formats = ArrayList<Triple<Int, String, Int>>()
            if (codecs.supported.contains("AMR-WB")) formats.add(Triple(104, "AMR-WB", 16000))
            if (codecs.supported.contains("AMR")) formats.add(Triple(102, "AMR", 8000))
            formats.add(Triple(8, "PCMA", 8000))
            formats.add(Triple(0, "PCMU", 8000))
            return formats
        }

    /** An offer with every codec we run: the first INVITE, or a reply to an INVITE that came without SDP. */
    fun offerSdp(): ByteArray {
        val attributes = ArrayList<String>()
        for ((payloadType, name, rate) in offerFormats) {
            attributes.add("a=rtpmap:$payloadType $name/$rate")
            if (name.startsWith("AMR")) attributes.add("a=fmtp:$payloadType mode-change-capability=2;max-red=0")
        }
        attributes.add("a=rtpmap:100 telephone-event/8000")
        attributes.add("a=fmtp:100 0-15")
        val direction = offerDirection()
        offered = direction
        return buildSdp(offerFormats.map { it.first } + listOf(100), attributes, direction)
    }

    /** An offer inside the call (hold, resume). It names only the codec in use, so the audio path stays as it is. */
    fun reofferSdp(): ByteArray {
        val current = remote ?: return offerSdp()
        val direction = offerDirection()
        offered = direction
        return currentCodecSdp(current, direction)
    }

    /** The answer to the far end's latest offer. */
    fun answerSdp(): ByteArray {
        val current = remote ?: return offerSdp()
        return currentCodecSdp(current, direction)
    }

    private fun currentCodecSdp(current: RemoteMedia, direction: MediaDirection): ByteArray {
        val attributes = mutableListOf("a=rtpmap:${current.payloadType} ${current.codec}/${current.clockRate}")
        if (current.fmtp.isNotEmpty()) attributes.add("a=fmtp:${current.payloadType} ${current.fmtp}")
        val formats = mutableListOf(current.payloadType)
        if (current.dtmfPayloadType >= 0) {
            formats.add(current.dtmfPayloadType)
            attributes.add("a=rtpmap:${current.dtmfPayloadType} telephone-event/8000")
            attributes.add("a=fmtp:${current.dtmfPayloadType} 0-15")
        }
        return buildSdp(formats, attributes, direction)
    }

    /**
     * Our side of a new offer (RFC 3264 §8.4, TS 24.610): while we hold, we
     * only send; while the far end holds us, there is nobody to send to.
     */
    private fun offerDirection(): MediaDirection = MediaDirection.of(sends = remoteOfferDirection.receives, receives = !localHold)

    /** What we want right now: everything, or only to send while we hold. */
    private val wanted: MediaDirection get() = if (localHold) MediaDirection.SENDONLY else MediaDirection.SENDRECV

    private fun buildSdp(formats: List<Int>, attributes: List<String>, direction: MediaDirection): ByteArray {
        val family = if (localAddress.size == 4) "IP4" else "IP6"
        val address = IpPacket.addressString(localAddress)
        val version = synchronized(lock) { ++sessionVersion }
        val lines = mutableListOf(
            "v=0",
            "o=- $sessionId $version IN $family $address",
            "s=VowiDroid",
            "c=IN $family $address",
            "t=0 0",
            "m=audio $localPort RTP/AVP ${formats.joinToString(" ")}",
        )
        lines.addAll(attributes)
        lines.add("a=ptime:20")
        lines.add("a=${direction.attribute}")
        lines.add("")
        return lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    /** Takes the far end's offer: the codec, and the direction we answer with. Throws when nothing is usable. */
    fun applyOffer(body: ByteArray) {
        val line = Sdp.parse(body)
        configure(line)
        remoteOfferDirection = line.direction
        setDirection(wanted and line.direction.reversed)
    }

    /** Takes the far end's answer to our latest offer. Throws when nothing is usable. */
    fun applyAnswer(body: ByteArray) {
        val line = Sdp.parse(body)
        configure(line)
        setDirection(offered and line.direction.reversed)
    }

    private fun setDirection(value: MediaDirection) {
        if (direction != value) CoreLog.i(TAG, "media direction ${value.attribute}")
        direction = value
    }

    /**
     * Points the stream at the far end and picks the codec. A codec already
     * running stays when the new SDP still has it, so hold and resume do not
     * reset the audio path.
     */
    private fun configure(line: Sdp.AudioLine) {
        val parsed = line.address
        if (parsed == null || line.port !in 1..65535 || line.formats.isEmpty()) {
            CoreLog.w(TAG, "unusable remote SDP: ${describe(line)}")
            throw IOException("remote SDP has no usable audio endpoint")
        }
        val previous = remote
        // RFC 2543 hold (0.0.0.0): nothing goes there, and the old address stays for when the call resumes.
        val unspecified = parsed.all { it == 0.toByte() }
        val address = if (unspecified && previous != null) previous.address else parsed
        val port = if (unspecified && previous != null) previous.port else line.port
        var dtmf = -1
        for (payloadType in line.formats) if (Sdp.codecName(line, payloadType) == "TELEPHONE-EVENT") dtmf = payloadType
        val running = codec
        if (previous != null && running != null) {
            for (payloadType in line.formats) {
                val fmtp = line.fmtp[payloadType].orEmpty()
                if (Sdp.codecName(line, payloadType) != previous.codec) continue
                if (fmtp.lowercase().contains("octet-align=1") != octetAligned) continue
                synchronized(lock) {
                    remote = RemoteMedia(address, port, previous.codec, payloadType, fmtp, dtmf, line.rates[payloadType] ?: running.sampleRate)
                }
                return
            }
        }
        for (payloadType in line.formats) {
            val name = Sdp.codecName(line, payloadType)
            if (name.isEmpty() || name == "TELEPHONE-EVENT") continue
            val fmtp = line.fmtp[payloadType].orEmpty()
            val created = codecs.create(name, fmtp) ?: continue
            synchronized(lock) {
                codec?.close()
                codec = created
                octetAligned = fmtp.lowercase().contains("octet-align=1")
                remote = RemoteMedia(address, port, name, payloadType, fmtp, dtmf, line.rates[payloadType] ?: created.sampleRate)
                pending = ShortArray(0)
            }
            CoreLog.i(TAG, "media negotiated: $name/${created.sampleRate} to ${IpPacket.addressString(address)}:$port${if (dtmf >= 0) " with DTMF" else ""}")
            return
        }
        CoreLog.w(TAG, "no usable codec in remote SDP: ${describe(line)}; this device runs ${codecs.supported}")
        throw IOException("remote SDP offers no codec this device supports")
    }

    /** What the far end's SDP said, for the log: never keys, never free text from the body. */
    private fun describe(line: Sdp.AudioLine): String {
        val audio = if (line.port == 0 && line.formats.isEmpty()) "no audio m= line" else {
            "audio port ${line.port}, formats " + line.formats.joinToString(" ") { payloadType ->
                val name = Sdp.codecName(line, payloadType)
                if (name.isEmpty()) "$payloadType" else "$payloadType=$name"
            }
        }
        val address = line.address?.let { if (it.all { byte -> byte == 0.toByte() }) "unspecified (0.0.0.0)" else IpPacket.addressString(it) } ?: "none"
        return "${line.size} bytes, $audio, address $address, ${line.direction.attribute}"
    }

    /** Queues PCM at the codec's sample rate; complete 20 ms frames go out while the direction allows sending. */
    fun writePcm(samples: ShortArray) {
        val current = remote ?: throw IOException("RTP media is not negotiated")
        val encoder = codec ?: throw IOException("RTP media is not negotiated")
        // On hold, or held by the far end: the microphone goes nowhere.
        if (localHold || !direction.sends) return
        synchronized(lock) {
            val joined = ShortArray(pending.size + samples.size)
            System.arraycopy(pending, 0, joined, 0, pending.size)
            System.arraycopy(samples, 0, joined, pending.size, samples.size)
            pending = joined
            val frame = encoder.frameSamples
            var offset = 0
            while (pending.size - offset >= frame) {
                val payload = encoder.encode(pending.copyOfRange(offset, offset + frame))
                if (payload.isNotEmpty()) sendPacket(current.payloadType, payload, marker = catchUp(frame))
                timestamp += frame
                offset += frame
            }
            pending = pending.copyOfRange(offset, pending.size)
        }
    }

    /**
     * After a pause in sending (hold, a stalled microphone) the RTP clock
     * moves on by the time that passed, and the packet starts a new talkspurt.
     * Returns the marker bit for the packet about to go out.
     */
    private fun catchUp(frame: Int): Boolean {
        val now = System.nanoTime()
        val last = lastSentNanos
        lastSentNanos = now
        if (last == 0L) return true
        val gapMs = (now - last) / 1_000_000
        if (gapMs < SILENCE_GAP_MS) return false
        timestamp += (gapMs / 20 - 1) * frame
        return true
    }

    /** Sends one RFC 4733 event: the digit with its duration, ended three times. */
    fun sendDtmf(digit: Char, durationMs: Int = 160) {
        val current = remote ?: return
        if (current.dtmfPayloadType < 0 || localHold || !direction.sends) return
        val event = when (digit) {
            in '0'..'9' -> digit - '0'
            '*' -> 10
            '#' -> 11
            in 'A'..'D' -> 12 + (digit - 'A')
            else -> return
        }
        val duration = durationMs * 8
        synchronized(lock) {
            val start = timestamp
            var elapsed = 160
            var first = true
            while (elapsed < duration) {
                sendPacket(current.dtmfPayloadType, dtmfPayload(event, false, elapsed), marker = first, eventTimestamp = start)
                first = false
                elapsed += 160
            }
            repeat(3) { sendPacket(current.dtmfPayloadType, dtmfPayload(event, true, duration), marker = first, eventTimestamp = start) }
            timestamp = start + duration
            lastSentNanos = System.nanoTime()
        }
    }

    private fun dtmfPayload(event: Int, end: Boolean, duration: Int): ByteArray {
        val payload = ByteArray(4)
        payload[0] = event.toByte()
        payload[1] = ((if (end) 0x80 else 0) or 10).toByte()
        payload.putU16(2, duration)
        return payload
    }

    private fun sendPacket(payloadType: Int, payload: ByteArray, marker: Boolean, eventTimestamp: Long = timestamp) {
        val current = remote ?: return
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
        packet.putU16(2, sequence)
        packet.putU32(4, eventTimestamp and 0xffffffffL)
        packet.putU32(8, ssrc)
        System.arraycopy(payload, 0, packet, 12, payload.size)
        sequence = (sequence + 1) and 0xffff
        try {
            socket.send(packet, current.address, current.port)
        } catch (error: IOException) {
            CoreLog.d(TAG, "RTP send failed: ${error.message}")
        }
    }

    private fun startKeepalive() {
        synchronized(lock) {
            if (keepalive != null || closed.get()) return
            keepalive = Thread(::keepaliveLoop, "vowi-rtp-hold").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * While we hold the call and still send (sendonly), a frame of silence
     * every 160 ms (for AMR a NO_DATA frame) keeps the stream from looking
     * dead to the network's media inactivity checks.
     */
    private fun keepaliveLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!localHold || !direction.sends) continue
            val current = remote ?: continue
            val encoder = codec ?: continue
            synchronized(lock) {
                val payload = try {
                    encoder.keepalivePayload()
                } catch (error: Exception) {
                    CoreLog.d(TAG, "hold keepalive failed: ${error.message}")
                    ByteArray(0)
                }
                if (payload.isNotEmpty()) {
                    sendPacket(current.payloadType, payload, marker = catchUp(encoder.frameSamples))
                    timestamp += encoder.frameSamples
                }
            }
        }
    }

    /** Waits for decoded downlink PCM; null on timeout. */
    fun readPcm(timeoutMs: Long): ShortArray? = downlink.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun receive() {
        while (!closed.get()) {
            val datagram = try {
                socket.receive(1000) ?: continue
            } catch (_: Exception) {
                return
            }
            val current = remote ?: continue
            if (!current.address.contentEquals(datagram.sourceAddress)) continue
            if (current.port != datagram.sourcePort) {
                // Symmetric RTP: follow the port the far end actually sends from.
                remote = RemoteMedia(current.address, datagram.sourcePort, current.codec, current.payloadType, current.fmtp, current.dtmfPayloadType, current.clockRate)
            }
            // Media we did not ask for (we hold, or answered inactive) is not played.
            if (localHold || !direction.receives) continue
            val packet = datagram.data
            if (packet.size < 12 || packet.u8(0) ushr 6 != 2) continue
            val payloadType = packet.u8(1) and 0x7f
            if (payloadType != current.payloadType) continue
            var header = 12 + (packet.u8(0) and 0x0f) * 4
            if (packet.u8(0) and 0x10 != 0) {
                if (packet.size < header + 4) continue
                header += 4 + packet.u16(header + 2) * 4
            }
            if (header >= packet.size) continue
            val decoder = codec ?: continue
            val samples = try {
                decoder.decode(packet.copyOfRange(header, packet.size))
            } catch (error: Exception) {
                CoreLog.d(TAG, "decode failed: ${error.message}")
                continue
            }
            if (samples.isEmpty()) continue
            if (!downlink.offer(samples)) {
                downlink.poll()
                downlink.offer(samples)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        keepalive?.interrupt()
        socket.close()
        synchronized(lock) { codec?.close() }
    }

    companion object {
        private const val TAG = "RTP"
        private const val KEEPALIVE_INTERVAL_MS = 160L
        private const val SILENCE_GAP_MS = 100L
    }
}
