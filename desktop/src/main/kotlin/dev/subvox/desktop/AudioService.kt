package dev.subvox.desktop

import dev.subvox.core.HeadsetProfile
import dev.subvox.core.Headsets
import dev.subvox.core.pcm16Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

/** A PCM-16 little-endian mono or stereo chunk with the monotonic clock time of its first frame. */
class PcmChunk(val bytes: ByteArray, val length: Int, val format: AudioFormat, val clockSeconds: Double)

/** Fans capture buffers out to consumers on the capture thread. Consumers must be cheap and thread-safe. */
class AudioSink {
    private val consumers = ConcurrentHashMap<UUID, (PcmChunk) -> Unit>()
    @Volatile var onLevel: ((Float) -> Unit)? = null

    fun add(consumer: (PcmChunk) -> Unit): UUID = UUID.randomUUID().also { consumers[it] = consumer }
    fun remove(id: UUID) { consumers.remove(id) }

    fun dispatch(chunk: PcmChunk) {
        for (c in consumers.values) c(chunk)
        onLevel?.invoke(pcm16Level(chunk.bytes, chunk.length))
    }
}

/**
 * Microphone capture through Java Sound. On Windows a Bluetooth headset such as a Shokz shows
 * two endpoints: "Headset (<model> Hands-Free AG Audio)" carries the microphone over the HFP
 * profile; "Headphones (<model> Stereo)" is playback only. Only the first appears in [devices].
 */
class AudioService {
    data class Device(val info: Mixer.Info?, val name: String) {
        val headset: HeadsetProfile? get() = Headsets.match(name)
        override fun toString() = name
    }

    /** What the HFP link can carry: 16 kHz mono (mSBC). CVSD links fall back to 8 kHz. */
    private val preferredFormats = listOf(
        AudioFormat(16000f, 16, 1, true, false),
        AudioFormat(48000f, 16, 1, true, false),
        AudioFormat(44100f, 16, 1, true, false),
        AudioFormat(8000f, 16, 1, true, false),
        AudioFormat(48000f, 16, 2, true, false),
        AudioFormat(44100f, 16, 2, true, false),
    )

    val sink = AudioSink()
    @Volatile var isRunning = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var activeDevice: Device? = null
        private set
    @Volatile var activeFormat: AudioFormat? = null
        private set
    var onChange: (() -> Unit)? = null

    private var line: TargetDataLine? = null
    private var thread: Thread? = null

    fun devices(): List<Device> {
        val out = ArrayList<Device>()
        out += Device(null, "System default input")
        for (info in AudioSystem.getMixerInfo()) {
            val mixer = try { AudioSystem.getMixer(info) } catch (e: Exception) { continue }
            val hasInput = mixer.targetLineInfo.any { TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
            if (!hasInput) continue
            if (info.name.startsWith("Port ")) continue
            if (Headsets.isWindowsStereoEndpoint(info.name)) continue
            out += Device(info, info.name)
        }
        return out
    }

    fun start(device: Device) {
        if (isRunning) return
        error = null
        var opened: TargetDataLine? = null
        var chosen: AudioFormat? = null
        for (format in preferredFormats) {
            val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
            try {
                val candidate = if (device.info == null) AudioSystem.getLine(lineInfo) as TargetDataLine
                else AudioSystem.getMixer(device.info).getLine(lineInfo) as TargetDataLine
                candidate.open(format, format.frameSize * (format.sampleRate / 10).toInt())
                opened = candidate
                chosen = format
                break
            } catch (e: Exception) {
                // try the next format
            }
        }
        if (opened == null || chosen == null) {
            error = "No usable 16-bit PCM format on ${device.name}. On Windows pick the 'Hands-Free AG Audio' endpoint, not 'Stereo'."
            onChange?.invoke()
            return
        }
        line = opened
        activeDevice = device
        activeFormat = chosen
        isRunning = true
        opened.start()
        val t = Thread({ captureLoop(opened, chosen) }, "subvox-audio").apply { isDaemon = true }
        thread = t
        t.start()
        onChange?.invoke()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.interrupt()
        thread = null
        line?.let { try { it.stop(); it.close() } catch (e: Exception) { } }
        line = null
        activeDevice = null
        activeFormat = null
        sink.onLevel?.invoke(0f)
        onChange?.invoke()
    }

    private fun captureLoop(line: TargetDataLine, format: AudioFormat) {
        val chunkFrames = (format.sampleRate / 20).toInt() // 50 ms
        val buffer = ByteArray(chunkFrames * format.frameSize)
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val n = line.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            // Stamp the chunk by when its first frame entered the line, using the line's own
            // frame counter against the monotonic clock. Good to a few milliseconds.
            val framesAvailableAfter = line.framePosition
            val chunkStart = Platform.clock() - (n / format.frameSize) / format.sampleRate.toDouble()
            sink.dispatch(PcmChunk(buffer.copyOf(n), n, format, chunkStart))
            if (framesAvailableAfter < 0) break
        }
    }
}
