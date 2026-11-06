package dev.subvox.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAndDatasetTest {
    private fun tempDir(): File = Files.createTempDirectory("subvox-test").toFile()

    @Test
    fun datasetWrittenBySwiftLoadsUnchanged() {
        // Exactly what Swift's JSONEncoder writes for the macOS app's Dataset: uppercase UUID,
        // Date as seconds since 2001-01-01, no optional fields.
        val swiftJson = javaClass.getResource("/swift-dataset.json")!!.readText()
        val dir = tempDir()
        val file = File(dir, "dataset.json").apply { writeText(swiftJson) }
        val store = TemplateStore(file)
        val dataset = store.load()
        assertEquals(listOf("yes", "no"), dataset.vocabulary)
        assertEquals(1, dataset.templates.size)
        val t = dataset.templates.single()
        assertEquals("yes", t.label)
        assertEquals("3B9D1E52-5E57-4A33-9A69-2F6D1C5B4E10", t.id)
        assertEquals(2, t.samples.size)
        assertEquals(0.123456, t.samples[1].rx, 1e-12)
        // 2026-11-06T00:00:00Z in Apple seconds.
        assertEquals(1_793_750_400_000L, Template.appleSecondsToEpochMillis(t.createdAt))

        // Round-trip keeps the shape Swift expects.
        store.save(dataset)
        val back = Json.parseToJsonElement(file.readText()).jsonObject
        val template = back["templates"]!!.jsonArray[0].jsonObject
        assertEquals("3B9D1E52-5E57-4A33-9A69-2F6D1C5B4E10", template["id"]!!.jsonPrimitive.content)
        assertNotNull(template["createdAt"]!!.jsonPrimitive.doubleOrNull)
        val sample = template["samples"]!!.jsonArray[0].jsonObject
        assertEquals(17, sample.size, "all 17 MotionSample fields are written")
    }

    @Test
    fun missingOrCorruptDatasetFallsBackToStarter() {
        val dir = tempDir()
        assertEquals(Dataset.starter, TemplateStore(File(dir, "none.json")).load())
        val bad = File(dir, "bad.json").apply { writeText("{not json") }
        assertEquals(Dataset.starter, TemplateStore(bad).load())
    }

    @Test
    fun sessionRoundTripAndTemplateImport() {
        val dir = File(tempDir(), "20261106T120000-silent")
        val writer = SessionWriter(dir, Condition.SILENT, "yes no", startClockSeconds = 1000.0)
        val samples = (0 until 100).map { i -> MotionSample(t = 1000.0 + i * 0.04, rx = if (i in 20..40) 0.3 else 0.0) }
        samples.forEach(writer::record)
        writer.logEvent(samples.subList(18, 43), "train", "yes", null)
        writer.logEvent(samples.subList(60, 80), "decode", "no", 0.42)
        writer.logEvent(samples.subList(85, 95), "segment", "", null)
        writer.finish(nowClockSeconds = 1004.0, audioDevice = "Headset (OpenRun Hands-Free AG Audio)",
            audioSampleRate = 16000, audioStartOffsetSeconds = 0.25, headset = Headsets.shokzOpenRun, platform = "windows")

        assertEquals(101, File(dir, "motion.csv").readLines().size)
        assertEquals(MotionSample.CSV_HEADER, File(dir, "motion.csv").readLines().first())
        assertEquals(4, File(dir, "events.csv").readLines().size)
        val meta = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
        assertEquals("Silent mouthing", meta["condition"]!!.jsonPrimitive.content)
        assertEquals(100, meta["motionSamples"]!!.jsonPrimitive.content.toInt())
        assertEquals("NONE", meta["headsetMotion"]!!.jsonPrimitive.content)
        assertEquals(0.25, meta["audioStartOffsetSeconds"]!!.jsonPrimitive.content.toDouble())

        val motion = SessionReader.motion(dir)
        assertEquals(100, motion.size)
        assertEquals(0.0, motion.first().t, 1e-9)
        assertEquals(0.3, motion[25].rx, 1e-9)

        val events = SessionReader.events(dir)
        assertEquals(3, events.size)
        assertEquals(0.42, events[1].confidence)
        assertNull(events[0].confidence)

        // Only ground-truth marks become templates; the unlabeled segment takes the fallback.
        val templates = SessionReader.templates(dir, fallbackLabel = "stop")
        assertEquals(listOf("yes", "stop"), templates.map { it.label })
        assertEquals(25, templates[0].samples.size)
        assertEquals(10, templates[1].samples.size)
        assertEquals("yes no", SessionReader.promptOf(dir))
    }

    @Test
    fun wavWriterProducesValidHeader() {
        val file = File(tempDir(), "audio.wav")
        val w = WavWriter(file, 16000, 1)
        val frames = ByteArray(3200) // 0.1 s of silence
        w.write(frames)
        w.write(frames, 0, 1600)
        w.close()
        val bytes = file.readBytes()
        assertEquals(44 + 4800, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, bb.getInt(24))
        assertEquals(32000, bb.getInt(28))
        assertEquals(4800, bb.getInt(40))
    }

    @Test
    fun pcmLevelMapsSilenceAndFullScale() {
        assertEquals(0f, pcm16Level(ByteArray(64)))
        val full = ByteArray(64)
        for (i in 0 until 32) { full[2 * i] = 0xff.toByte(); full[2 * i + 1] = 0x7f }
        assertTrue(pcm16Level(full) > 0.99f)
    }

    @Test
    fun csvRowParsesWhatItWrites() {
        val s = MotionSample.fromQuaternion(t = 5.5, qw = 0.7071068, qx = 0.0, qy = 0.0, qz = 0.7071068, rx = 0.1, ry = 0.2, rz = 0.3)
        assertEquals(Math.PI / 2, s.yaw, 1e-6)
        assertEquals(-1.0, s.gz, 1e-6)
        val row = s.csvRow(0.0)
        val back = assertNotNull(MotionSample.fromCsvRow(row))
        assertEquals(s.t, back.t, 1e-6)
        assertEquals(s.yaw, back.yaw, 1e-6)
        assertNull(MotionSample.fromCsvRow("1,2,3"))
        assertNull(MotionSample.fromCsvRow(MotionSample.CSV_HEADER))
    }
}
