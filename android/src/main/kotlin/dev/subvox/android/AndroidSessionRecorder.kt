package dev.subvox.android

import android.content.Context
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
 * Records motion CSV + microphone WAV + segment events to
 * `Android/data/dev.subvox.android/files/Sessions/<timestamp>-<condition>/`, the same layout as
 * the macOS and Windows apps, readable over USB or with a file manager.
 */
class AndroidSessionRecorder(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Sessions")

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
    private var sourceName: String = ""

    fun start(audio: AndroidAudioService, headset: HeadsetProfile?, motionSourceName: String) {
        if (isRecording) return
        error = null
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val dir = File(root, "$stamp-${condition.slug}")
        val start = Clock.now()
        val w = try { SessionWriter(dir, condition, prompt, start) } catch (e: Exception) {
            error = "Cannot create ${dir.path}: ${e.message}"; onChange?.invoke(); return
        }
        writer = w
        startedClock = start
        this.headset = headset
        sourceName = motionSourceName
        if (audio.isRunning) {
            val ww = WavWriter(File(dir, "audio.wav"), audio.sampleRate, 1)
            wav = ww
            audioDevice = audio.activeDevice?.name
            audioRate = audio.sampleRate
            sink = audio.sink
            sinkID = audio.sink.add { chunk ->
                synchronized(ww) {
                    if (ww.firstFrameClockSeconds == null) ww.firstFrameClockSeconds = chunk.clockSeconds
                    ww.write(chunk.bytes, 0, chunk.length)
                }
            }
        }
        isRecording = true
        onChange?.invoke()
    }

    fun stop() {
        val w = writer ?: return
        isRecording = false
        sinkID?.let { sink?.remove(it) }; sinkID = null
        var offset: Double? = null
        wav?.let { ww -> synchronized(ww) { offset = ww.firstFrameClockSeconds?.let { it - w.startClockSeconds }; ww.close() } }
        wav = null
        w.finish(Clock.now(), audioDevice, audioRate, offset, headset, platform = "android/$sourceName")
        lastSessionDir = w.directory
        writer = null
        onChange?.invoke()
    }

    fun record(sample: MotionSample) { if (isRecording) writer?.record(sample) }

    fun logEvent(segment: List<MotionSample>, kind: String, label: String, confidence: Double?) {
        if (isRecording) { writer?.logEvent(segment, kind, label, confidence); onChange?.invoke() }
    }
}
