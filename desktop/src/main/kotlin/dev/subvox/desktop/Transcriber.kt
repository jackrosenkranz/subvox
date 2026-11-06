package dev.subvox.desktop

import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import javax.sound.sampled.AudioFormat
import kotlin.math.roundToInt

/**
 * Offline speech-to-text with Vosk, used as the reference transcript beside the motion decoder.
 * Vosk wants 16 kHz mono PCM-16; [Resampler] converts whatever the capture line delivers.
 *
 * The model is not bundled. Put an unpacked Vosk model (for English,
 * `vosk-model-small-en-us-0.15` from alphacephei.com/vosk/models) at [Platform.voskModelDir] or
 * point `SUBVOX_VOSK_MODEL` at it.
 */
class Transcriber {
    @Volatile var isRunning = false
        private set
    @Volatile var text = ""
        private set
    @Volatile var error: String? = null
        private set
    var onChange: (() -> Unit)? = null

    private var sinkID: UUID? = null
    private var sink: AudioSink? = null
    private val queue = LinkedBlockingQueue<PcmChunk>()
    private var worker: Thread? = null
    private var finalized = StringBuilder()

    val modelAvailable: Boolean get() = Platform.voskModelDir.isDirectory && File(Platform.voskModelDir, "conf").exists()

    fun start(sink: AudioSink) {
        if (isRunning) return
        error = null
        if (!modelAvailable) {
            error = "No Vosk model at ${Platform.voskModelDir.path}. Download vosk-model-small-en-us-0.15 from alphacephei.com/vosk/models and unpack it there (or set SUBVOX_VOSK_MODEL)."
            onChange?.invoke()
            return
        }
        this.sink = sink
        isRunning = true
        queue.clear()
        sinkID = sink.add { chunk -> queue.offer(chunk) }
        val t = Thread({ run() }, "subvox-vosk").apply { isDaemon = true }
        worker = t
        t.start()
        onChange?.invoke()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        sinkID?.let { sink?.remove(it) }
        sinkID = null
        worker?.interrupt()
        worker = null
        onChange?.invoke()
    }

    fun clear() {
        finalized = StringBuilder()
        text = ""
        onChange?.invoke()
    }

    private fun run() {
        try {
            org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
            val model = org.vosk.Model(Platform.voskModelDir.path)
            val recognizer = org.vosk.Recognizer(model, 16000f)
            val resampler = Resampler(16000)
            try {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val chunk = queue.take()
                    val pcm = resampler.toMono16k(chunk)
                    if (recognizer.acceptWaveForm(pcm, pcm.size)) {
                        val result = extract(recognizer.result, "text")
                        if (result.isNotBlank()) finalized.append(result).append(' ')
                        text = finalized.toString()
                    } else {
                        val partial = extract(recognizer.partialResult, "partial")
                        text = finalized.toString() + partial
                    }
                    onChange?.invoke()
                }
            } finally {
                recognizer.close()
                model.close()
            }
        } catch (e: InterruptedException) {
            // stopped
        } catch (e: Throwable) {
            error = "Speech recognizer failed: ${e.message ?: e.javaClass.simpleName}"
            isRunning = false
            onChange?.invoke()
        }
    }

    private fun extract(json: String, key: String): String {
        val marker = "\"$key\" : \""
        val i = json.indexOf(marker)
        if (i < 0) return ""
        val start = i + marker.length
        val end = json.indexOf('"', start)
        return if (end < 0) "" else json.substring(start, end)
    }
}

/** Downmixes to mono and linearly resamples PCM-16 little-endian audio to a target rate. */
class Resampler(val targetRate: Int) {
    private var phase = 0.0

    fun toMono16k(chunk: PcmChunk): ByteArray = convert(chunk.bytes, chunk.length, chunk.format)

    fun convert(bytes: ByteArray, length: Int, format: AudioFormat): ByteArray {
        val channels = format.channels
        val frames = length / (2 * channels)
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                acc += ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff))
            }
            mono[f] = (acc / channels).toShort()
        }
        val srcRate = format.sampleRate.toDouble()
        if (srcRate.roundToInt() == targetRate) {
            val out = ByteArray(frames * 2)
            for (f in 0 until frames) { out[2 * f] = (mono[f].toInt() and 0xff).toByte(); out[2 * f + 1] = (mono[f].toInt() shr 8).toByte() }
            return out
        }
        val step = srcRate / targetRate
        val out = ArrayList<Short>((frames / step).toInt() + 2)
        var pos = phase
        while (pos < frames - 1) {
            val i = pos.toInt()
            val frac = pos - i
            val v = mono[i] * (1 - frac) + mono[i + 1] * frac
            out += v.roundToInt().coerceIn(-32768, 32767).toShort()
            pos += step
        }
        phase = pos - frames
        val result = ByteArray(out.size * 2)
        for ((k, s) in out.withIndex()) { result[2 * k] = (s.toInt() and 0xff).toByte(); result[2 * k + 1] = (s.toInt() shr 8).toByte() }
        return result
    }
}
