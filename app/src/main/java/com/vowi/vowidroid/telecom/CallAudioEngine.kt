package com.vowi.vowidroid.telecom

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.vowi.vowidroid.core.call.RtpMedia

/**
 * Connects the microphone and earpiece/speaker to one call's RTP media. The
 * media runs at the codec's rate (8 kHz for G.711 and AMR, 16 kHz for
 * AMR-WB) in 20 ms frames.
 */
class CallAudioEngine private constructor(private val media: RtpMedia) {
    private val rate = media.sampleRate
    private val frameSamples = rate / 50
    private var track: AudioTrack? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @Volatile
    private var running = false

    @Volatile
    var muted = false

    @SuppressLint("MissingPermission") // CallManager checks RECORD_AUDIO first
    private fun start(): Boolean {
        val playback = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, frameSamples * 2 * 8))
            .build()
        val capture = openCapture() ?: run {
            playback.release()
            return false
        }
        track = playback
        record = capture.first
        running = true
        Log.i(TAG, "call audio started with ${media.codecName} at $rate Hz, capture at ${capture.second} Hz")
        playback.play()
        capture.first.startRecording()
        captureThread = Thread({ captureLoop(capture.first, capture.second) }, "vowi-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        playbackThread = Thread(::playbackLoop, "vowi-speaker").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /** Tries the codec's rate first; some devices only capture at 16 or 48 kHz, which is resampled. */
    @SuppressLint("MissingPermission")
    private fun openCapture(): Pair<AudioRecord, Int>? {
        val candidates = linkedSetOf(rate, 16000, 48000, 8000)
        for (captureRate in candidates) {
            if (captureRate % rate != 0 && rate % captureRate != 0) continue
            val minimum = AudioRecord.getMinBufferSize(captureRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val candidate = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    captureRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 2, captureRate / 50 * 2 * 4),
                )
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                enableEffects(candidate.audioSessionId)
                return candidate to captureRate
            }
            candidate.release()
        }
        Log.w(TAG, "no usable microphone capture configuration")
        return null
    }

    private fun enableEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.let { it.enabled = true; effects += it }
    }

    private fun captureLoop(capture: AudioRecord, captureRate: Int) {
        val input = ShortArray(captureRate / 50)
        while (running) {
            var filled = 0
            while (filled < input.size && running) {
                val read = capture.read(input, filled, input.size - filled)
                if (read <= 0) break
                filled += read
            }
            if (!running || filled < input.size) continue
            val frame = resample(input, captureRate, rate)
            if (muted) frame.fill(0)
            runCatching { media.writePcm(frame) }
        }
    }

    private fun playbackLoop() {
        val output = track ?: return
        while (running) {
            val samples = media.readPcm(200) ?: continue
            output.write(samples, 0, samples.size)
        }
    }

    /** Integer-factor decimation (with averaging) or duplication between the capture and codec rates. */
    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return input.copyOf()
        if (from > to) {
            val factor = from / to
            return ShortArray(input.size / factor) { index ->
                var sum = 0
                for (offset in 0 until factor) sum += input[index * factor + offset]
                (sum / factor).toShort()
            }
        }
        val factor = to / from
        val out = ShortArray(input.size * factor)
        for (index in input.indices) {
            val current = input[index].toInt()
            val next = if (index + 1 < input.size) input[index + 1].toInt() else current
            for (offset in 0 until factor) out[index * factor + offset] = (current + (next - current) * offset / factor).toShort()
        }
        return out
    }

    fun stop() {
        if (!running) return
        running = false
        captureThread?.join(500)
        playbackThread?.join(500)
        releaseDevices()
    }

    private fun releaseDevices() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        record?.let { runCatching { it.stop() }; it.release() }
        track?.let { runCatching { it.stop() }; it.release() }
        record = null
        track = null
    }

    companion object {
        private const val TAG = "VowiAudio"

        /** Starts audio for an active call, or returns null when the microphone cannot be opened. */
        fun start(media: RtpMedia): CallAudioEngine? {
            val engine = CallAudioEngine(media)
            return if (engine.start()) engine else null
        }
    }
}
