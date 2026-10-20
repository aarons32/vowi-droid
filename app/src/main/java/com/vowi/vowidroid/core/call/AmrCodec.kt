package com.vowi.vowidroid.core.call

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import com.vowi.vowidroid.core.util.CoreLog
import java.nio.ByteBuffer

/**
 * AMR and AMR-WB through Android's built-in codecs. MediaCodec gives frames
 * in storage format (one header octet then the frame bits), which is what
 * [AmrPayload] packs into RTP.
 */
class AmrCodec private constructor(private val wideband: Boolean, private val octetAligned: Boolean, private val mode: Int) : AudioCodec {
    override val name: String = if (wideband) "AMR-WB" else "AMR"
    override val sampleRate: Int = if (wideband) 16000 else 8000
    private val mime = if (wideband) MediaFormat.MIMETYPE_AUDIO_AMR_WB else MediaFormat.MIMETYPE_AUDIO_AMR_NB
    private val encoder: MediaCodec
    private val decoder: MediaCodec
    private val info = MediaCodec.BufferInfo()

    init {
        val bitrate = if (wideband) WB_BITRATES[mode.coerceIn(0, 8)] else NB_BITRATES[mode.coerceIn(0, 7)]
        encoder = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createAudioFormat(mime, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(MediaFormat.createAudioFormat(mime, sampleRate, 1), null, null, 0)
        decoder.start()
    }

    override fun encode(pcm: ShortArray): ByteArray {
        val frames = ArrayList<ByteArray>()
        val input = ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) input.putShort(sample)
        input.flip()
        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            val buffer = encoder.getInputBuffer(inputIndex)!!
            buffer.clear()
            buffer.put(input)
            encoder.queueInputBuffer(inputIndex, 0, pcm.size * 2, 0, 0)
        }
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(info, if (frames.isEmpty()) TIMEOUT_US else 0)
            if (outputIndex < 0) break
            val buffer = encoder.getOutputBuffer(outputIndex)!!
            val frame = ByteArray(info.size)
            buffer.position(info.offset)
            buffer.get(frame)
            encoder.releaseOutputBuffer(outputIndex, false)
            if (frame.isNotEmpty()) frames.add(frame)
        }
        if (frames.isEmpty()) return ByteArray(0)
        return AmrPayload.pack(frames, wideband, octetAligned)
    }

    /** A NO_DATA frame: silence for the hold keepalive without running the encoder. */
    override fun keepalivePayload(): ByteArray = AmrPayload.noData(wideband, octetAligned)

    override fun decode(payload: ByteArray): ShortArray {
        val frames = AmrPayload.unpack(payload, wideband, octetAligned)
        val out = ArrayList<Short>()
        for (frame in frames) {
            val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) break
            val buffer = decoder.getInputBuffer(inputIndex)!!
            buffer.clear()
            buffer.put(frame)
            decoder.queueInputBuffer(inputIndex, 0, frame.size, 0, 0)
            while (true) {
                val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outputIndex < 0) continue
                val output = decoder.getOutputBuffer(outputIndex)!!
                output.position(info.offset)
                val pcm = output.slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val count = info.size / 2
                for (index in 0 until count) out.add(pcm.getShort(index * 2))
                decoder.releaseOutputBuffer(outputIndex, false)
                break
            }
        }
        return out.toShortArray()
    }

    override fun close() {
        runCatching { encoder.stop(); encoder.release() }
        runCatching { decoder.stop(); decoder.release() }
    }

    companion object {
        private const val TAG = "AMR"
        private const val TIMEOUT_US = 20_000L
        val NB_BITRATES = intArrayOf(4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200)
        val WB_BITRATES = intArrayOf(6600, 8850, 12650, 14250, 15850, 18250, 19850, 23050, 23850)

        /** True when the platform has both an encoder and a decoder for the codec. */
        fun available(wideband: Boolean): Boolean {
            val mime = if (wideband) MediaFormat.MIMETYPE_AUDIO_AMR_WB else MediaFormat.MIMETYPE_AUDIO_AMR_NB
            var encoder = false
            var decoder = false
            for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (codec.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                if (codec.isEncoder) encoder = true else decoder = true
            }
            return encoder && decoder
        }

        /** The highest mode the remote's mode-set allows, else the best quality. */
        fun modeFromFmtp(fmtp: String, wideband: Boolean): Int {
            val modeSet = Regex("mode-set=([0-9,]+)").find(fmtp.lowercase())?.groupValues?.get(1)
            val best = if (wideband) 8 else 7
            if (modeSet.isNullOrEmpty()) return best
            return modeSet.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..best }.maxOrNull() ?: best
        }

        fun create(name: String, fmtp: String): AudioCodec? {
            val wideband = when (name.uppercase()) {
                "AMR-WB" -> true
                "AMR" -> false
                else -> return null
            }
            if (!available(wideband)) return null
            return try {
                AmrCodec(wideband, fmtp.lowercase().contains("octet-align=1"), modeFromFmtp(fmtp, wideband))
            } catch (error: Exception) {
                CoreLog.w(TAG, "could not start the $name codec: ${error.message}")
                null
            }
        }
    }
}

/** The Android codec set: AMR-WB and AMR through MediaCodec, then G.711. */
object AndroidCodecFactory : AudioCodecFactory {
    override val supported: List<String> by lazy {
        val list = ArrayList<String>()
        if (runCatching { AmrCodec.available(true) }.getOrDefault(false)) list.add("AMR-WB")
        if (runCatching { AmrCodec.available(false) }.getOrDefault(false)) list.add("AMR")
        list.add("PCMA")
        list.add("PCMU")
        list
    }

    override fun create(name: String, fmtp: String): AudioCodec? = AmrCodec.create(name, fmtp) ?: G711Factory.create(name, fmtp)
}

@Suppress("unused")
private val keepMediaCodecInfoImport = MediaCodecInfo::class
