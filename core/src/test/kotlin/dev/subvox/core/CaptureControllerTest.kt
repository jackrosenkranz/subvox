package dev.subvox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureControllerTest {
    private fun quiet(t: Double) = MotionSample(t = t, rx = 0.001)
    private fun loud(t: Double) = MotionSample(t = t, rx = 0.5, ry = 0.4)

    @Test
    fun holdCaptureIncludesPrerollAndEmitsOnRelease() {
        val c = CaptureController()
        val segments = ArrayList<List<MotionSample>>()
        c.onSegment = { segments += it }
        var t = 0.0
        repeat(25) { c.ingest(quiet(t)); t += 0.04 }
        c.begin()
        assertTrue(c.isCapturing)
        repeat(15) { c.ingest(loud(t)); t += 0.04 }
        c.end()
        assertFalse(c.isCapturing)
        assertEquals(1, segments.size)
        val seg = segments.single()
        // Pre-roll keeps about 0.3 s of samples before begin().
        assertTrue(seg.first().t < 1.0 - 0.2, "first ${seg.first().t}")
        assertTrue(seg.first().t >= 1.0 - 0.35, "first ${seg.first().t}")
        assertEquals(15 + 8, seg.size, "size ${seg.size}")
    }

    @Test
    fun tooShortHoldIsDropped() {
        val c = CaptureController()
        var emitted = 0
        c.onSegment = { emitted++ }
        c.begin()
        c.ingest(loud(0.0)); c.ingest(loud(0.04)); c.ingest(loud(0.08))
        c.end()
        assertEquals(0, emitted)
    }

    @Test
    fun holdIsCappedAtMaxDuration() {
        val c = CaptureController()
        val lengths = ArrayList<Int>()
        c.onSegment = { lengths += it.size }
        c.begin()
        var t = 0.0
        repeat(200) { c.ingest(loud(t)); t += 0.04 } // 8 s > 6 s cap
        assertFalse(c.isCapturing)
        assertEquals(1, lengths.size)
    }

    @Test
    fun autoModeCalibratesThenTriggersAndHangsOver() {
        val c = CaptureController()
        val segments = ArrayList<List<MotionSample>>()
        c.onSegment = { segments += it }
        c.autoEnabled = true
        var t = 0.0
        assertNull(c.noiseFloor)
        c.calibrate()
        assertTrue(c.isCalibrating)
        repeat(60) { c.ingest(quiet(t)); t += 0.04 } // 2.4 s of stillness
        assertFalse(c.isCalibrating)
        val floor = assertNotNull(c.noiseFloor)
        assertTrue(floor < 0.01)
        assertEquals(floor * 3, c.threshold!!, 1e-12)

        repeat(10) { c.ingest(quiet(t)); t += 0.04 }
        assertFalse(c.isCapturing)
        repeat(12) { c.ingest(loud(t)); t += 0.04 }
        assertTrue(c.isCapturing)
        // Energy decays with the 0.6/0.4 smoother; hangover is 0.35 s after it drops below 0.7x threshold.
        repeat(40) { c.ingest(quiet(t)); t += 0.04 }
        assertFalse(c.isCapturing)
        assertEquals(1, segments.size)
        assertTrue(segments.single().size >= 12)
    }

    @Test
    fun disablingAutoFinishesAnAutoCapture() {
        val c = CaptureController()
        var emitted = 0
        c.onSegment = { emitted++ }
        c.autoEnabled = true
        c.calibrate()
        var t = 0.0
        repeat(60) { c.ingest(quiet(t)); t += 0.04 }
        repeat(12) { c.ingest(loud(t)); t += 0.04 }
        assertTrue(c.isCapturing)
        c.autoEnabled = false
        assertFalse(c.isCapturing)
        assertEquals(1, emitted)
    }
}
