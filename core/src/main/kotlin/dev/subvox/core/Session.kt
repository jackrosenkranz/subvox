package dev.subvox.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.util.Locale

/** Speaking condition for an experiment session. Slugs name the session folder. */
enum class Condition(val label: String, val slug: String) {
    NORMAL("Normal speech", "normal"),
    WHISPER("Whisper", "whisper"),
    SILENT("Silent mouthing", "silent");
}

/** One row of `events.csv`: a captured segment with its label or prediction. */
data class SessionEvent(
    val start: Double,
    val end: Double,
    val kind: String,
    val label: String,
    val confidence: Double?,
) {
    companion object {
        const val CSV_HEADER = "start,end,kind,label,confidence"

        fun fromCsvRow(row: String): SessionEvent? {
            val f = row.split(',')
            if (f.size < 4) return null
            val start = f[0].trim().toDoubleOrNull() ?: return null
            val end = f[1].trim().toDoubleOrNull() ?: return null
            val confidence = f.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            return SessionEvent(start, end, f[2].trim(), f[3].trim(), confidence)
        }
    }
}

/**
 * Writes one experiment session: `motion.csv`, `events.csv` and `meta.json` into [directory].
 * Audio is written by the platform's recorder (see [WavWriter]); the platform passes its
 * start offset to [finish] so the files can be aligned afterwards.
 *
 * Output layout and the meaning of `t` match the macOS app: every time is seconds relative to
 * [startClockSeconds] on the platform's monotonic clock.
 */
class SessionWriter(
    val directory: File,
    val condition: Condition,
    val prompt: String,
    val startClockSeconds: Double,
    val startedAt: Instant = Instant.now(),
) {
    var motionCount = 0
        private set
    var eventCount = 0
        private set

    private val motion: BufferedWriter
    private val events: BufferedWriter

    init {
        directory.mkdirs()
        motion = File(directory, "motion.csv").bufferedWriter().also { it.write(MotionSample.CSV_HEADER); it.newLine() }
        events = File(directory, "events.csv").bufferedWriter().also { it.write(SessionEvent.CSV_HEADER); it.newLine() }
    }

    fun record(sample: MotionSample) {
        motion.write(sample.csvRow(startClockSeconds)); motion.newLine()
        motionCount++
    }

    fun logEvent(segment: List<MotionSample>, kind: String, label: String, confidence: Double?) {
        if (segment.isEmpty()) return
        val row = listOf(
            String.format(Locale.ROOT, "%.4f", segment.first().t - startClockSeconds),
            String.format(Locale.ROOT, "%.4f", segment.last().t - startClockSeconds),
            kind,
            label.replace(',', ' '),
            confidence?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "",
        ).joinToString(",")
        events.write(row); events.newLine()
        eventCount++
    }

    /**
     * Closes the CSV files and writes `meta.json`. [audioStartOffsetSeconds] is where the first
     * audio sample sits relative to [startClockSeconds]; null when no audio was recorded.
     */
    fun finish(
        nowClockSeconds: Double,
        audioDevice: String? = null,
        audioSampleRate: Int? = null,
        audioStartOffsetSeconds: Double? = null,
        headset: HeadsetProfile? = null,
        platform: String = "jvm",
    ) {
        motion.close()
        events.close()
        val meta: Map<String, JsonElement> = mapOf(
            "condition" to JsonPrimitive(condition.label),
            "prompt" to JsonPrimitive(prompt),
            "startedAt" to JsonPrimitive(startedAt.toString()),
            "startUptime" to JsonPrimitive(startClockSeconds),
            "durationSeconds" to JsonPrimitive(nowClockSeconds - startClockSeconds),
            "motionSamples" to JsonPrimitive(motionCount),
            "events" to JsonPrimitive(eventCount),
            "audioDevice" to (audioDevice?.let(::JsonPrimitive) ?: JsonNull),
            "audioSampleRate" to (audioSampleRate?.let(::JsonPrimitive) ?: JsonNull),
            "audioStartOffsetSeconds" to (audioStartOffsetSeconds?.let(::JsonPrimitive) ?: JsonNull),
            "headset" to (headset?.let { JsonPrimitive(it.name) } ?: JsonNull),
            "headsetMotion" to (headset?.let { JsonPrimitive(it.motion.name) } ?: JsonNull),
            "platform" to JsonPrimitive(platform),
            "note" to JsonPrimitive("Motion t and audioStartOffsetSeconds are relative to startUptime (seconds on the platform's monotonic clock)."),
        )
        val json = Json { prettyPrint = true }
        File(directory, "meta.json").writeText(json.encodeToString(JsonObject(meta.toSortedMap())))
    }
}

/** Reads a session folder back, from any platform, for replay or to turn marked takes into templates. */
object SessionReader {
    fun motion(directory: File): List<MotionSample> {
        val file = File(directory, "motion.csv")
        if (!file.isFile) return emptyList()
        return file.useLines { lines -> lines.drop(1).mapNotNull(MotionSample::fromCsvRow).toList() }
    }

    fun events(directory: File): List<SessionEvent> {
        val file = File(directory, "events.csv")
        if (!file.isFile) return emptyList()
        return file.useLines { lines -> lines.drop(1).mapNotNull(SessionEvent::fromCsvRow).toList() }
    }

    fun promptOf(directory: File): String? {
        val file = File(directory, "meta.json")
        if (!file.isFile) return null
        return try {
            val obj = Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
            obj["prompt"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Turns the marked segments of a session into templates. `train` events use their label;
     * `segment` events (marked on the Sensors screen) take [fallbackLabel] when given. Decode
     * and reject events are skipped: they carry predictions, not ground truth.
     */
    fun templates(directory: File, fallbackLabel: String? = null): List<Template> {
        val motion = motion(directory)
        if (motion.isEmpty()) return emptyList()
        return events(directory).mapNotNull { event ->
            val label = when (event.kind) {
                "train" -> event.label
                "segment" -> event.label.ifBlank { fallbackLabel ?: return@mapNotNull null }
                else -> return@mapNotNull null
            }
            val slice = motion.filter { it.t >= event.start && it.t <= event.end }
            if (slice.size < CaptureController.MIN_SEGMENT_SAMPLES) null else Template(label = label.lowercase(), samples = slice)
        }
    }
}

/** Minimal 16-bit PCM WAV writer; both the desktop and Android recorders feed it raw frames. */
class WavWriter(val file: File, val sampleRate: Int, val channels: Int) {
    private val out = java.io.RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    /** Monotonic-clock seconds of the first frame written, set by the caller. */
    var firstFrameClockSeconds: Double? = null

    init {
        out.setLength(0)
        out.write(ByteArray(44))
    }

    fun write(pcm16: ByteArray, offset: Int = 0, length: Int = pcm16.size) {
        out.write(pcm16, offset, length)
        dataBytes += length
    }

    fun close() {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        out.seek(0)
        out.write(header.array())
        out.close()
    }
}

/** RMS of 16-bit little-endian PCM mapped from -60...0 dBFS onto 0...1, for level meters. */
fun pcm16Level(buffer: ByteArray, length: Int = buffer.size): Float {
    val n = length / 2
    if (n == 0) return 0f
    var sum = 0.0
    for (i in 0 until n) {
        val lo = buffer[2 * i].toInt() and 0xff
        val hi = buffer[2 * i + 1].toInt()
        val s = ((hi shl 8) or lo) / 32768.0
        sum += s * s
    }
    val rms = kotlin.math.sqrt(sum / n)
    val db = 20 * kotlin.math.log10(kotlin.math.max(rms, 1e-7))
    return ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
}
