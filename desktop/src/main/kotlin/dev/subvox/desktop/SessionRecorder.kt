package dev.subvox.desktop

import dev.subvox.core.Condition
import dev.subvox.core.HeadsetProfile
import dev.subvox.core.MotionSample
import dev.subvox.core.SessionWriter
import dev.subvox.core.WavWriter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Records synchronized motion CSV + microphone WAV + labelled segment events to
 * `~/Documents/Subvox/Sessions/<timestamp>-<condition>/`, the layout the macOS app writes.
 */
class SessionRecorder {
    var condition: Condition = Condition.SILENT
    var prompt: String = ""
    var isRecording = false
        private set
    var startedClock: Double? = null
        private set
    var lastSessionDir: File? = null
        private set
    var error: String? = null
        private set
    val motionCount: Int get() = writer?.motionCount ?: 0
    val eventCount: Int get() = writer?.eventCount ?: 0
    var onChange: (() -> Unit)? = null

    private var writer: SessionWriter? = null
    private var wav: WavWriter? = null
    private var sinkID: UUID? = null
    private var sink: AudioSink? = null
    private var audioDevice: String? = null
    private var audioRate: Int? = null
    private var headset: HeadsetProfile? = null

    fun start(audio: AudioService, headset: HeadsetProfile?) {
        if (isRecording) return
        error = null
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val dir = File(Platform.sessionsDir, "$stamp-${condition.slug}")
        val start = Platform.clock()
        val w = try { SessionWriter(dir, condition, prompt, start) } catch (e: Exception) {
            error = "Cannot create ${dir.path}: ${e.message}"; onChange?.invoke(); return
        }
        writer = w
        startedClock = start
        this.headset = headset
        if (audio.isRunning) {
            val format = audio.activeFormat
            if (format != null) {
                val wavWriter = WavWriter(File(dir, "audio.wav"), format.sampleRate.toInt(), format.channels)
                wav = wavWriter
                audioDevice = audio.activeDevice?.name
                audioRate = format.sampleRate.toInt()
                sink = audio.sink
                sinkID = audio.sink.add { chunk ->
                    synchronized(wavWriter) {
                        if (wavWriter.firstFrameClockSeconds == null) wavWriter.firstFrameClockSeconds = chunk.clockSeconds
                        wavWriter.write(chunk.bytes, 0, chunk.length)
                    }
                }
            }
        }
        isRecording = true
        onChange?.invoke()
    }

    fun stop() {
        val w = writer ?: return
        isRecording = false
        sinkID?.let { sink?.remove(it) }
        sinkID = null
        var audioOffset: Double? = null
        wav?.let { ww -> synchronized(ww) { audioOffset = ww.firstFrameClockSeconds?.let { it - w.startClockSeconds }; ww.close() } }
        wav = null
        w.finish(Platform.clock(), audioDevice, audioRate, audioOffset, headset, platform = if (Platform.isWindows) "windows" else Platform.os)
        lastSessionDir = w.directory
        writer = null
        audioDevice = null
        audioRate = null
        onChange?.invoke()
    }

    fun record(sample: MotionSample) {
        if (isRecording) writer?.record(sample)
    }

    fun logEvent(segment: List<MotionSample>, kind: String, label: String, confidence: Double?) {
        if (isRecording) { writer?.logEvent(segment, kind, label, confidence); onChange?.invoke() }
    }
}
