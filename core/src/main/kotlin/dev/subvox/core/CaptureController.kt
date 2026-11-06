package dev.subvox.core

import kotlin.math.max

/**
 * Cuts the continuous motion stream into word-sized segments, either while a key or button is
 * held or automatically when motion energy rises above a calibrated noise floor.
 *
 * Pure logic with no threading of its own: call every method from one thread (the UI thread on
 * desktop and Android). [onSegment] fires synchronously from [ingest] or [end].
 */
class CaptureController {
    enum class Mode(val label: String) { HOLD("Hold to capture"), AUTO("Auto-detect") }

    companion object {
        const val CALIBRATION_SECONDS = 2.0
        const val PREROLL_SECONDS = 0.3
        const val HANGOVER_SECONDS = 0.35
        const val MAX_AUTO_SECONDS = 3.0
        const val MAX_HOLD_SECONDS = 6.0
        const val MIN_SEGMENT_SECONDS = 0.15
        const val MIN_SEGMENT_SAMPLES = 4
    }

    var autoEnabled: Boolean = false
        set(value) {
            field = value
            if (!value && isCapturing && !isManual) finish()
        }
    var triggerRatio: Double = 3.0

    var isCapturing: Boolean = false
        private set
    var isCalibrating: Boolean = false
        private set
    var energy: Double = 0.0
        private set
    var noiseFloor: Double? = null
        private set

    val threshold: Double? get() = noiseFloor?.let { it * triggerRatio }

    var onSegment: ((List<MotionSample>) -> Unit)? = null
    /** Called after every state change so a UI can repaint. */
    var onChange: (() -> Unit)? = null

    private var isManual = false
    private var buffer = ArrayList<MotionSample>()
    private val preroll = ArrayList<MotionSample>()
    private val calibration = ArrayList<Double>()
    private var calibrationEnd: Double? = null
    private var quietSince: Double? = null

    fun begin() {
        if (isCapturing) return
        isManual = true
        isCapturing = true
        buffer = ArrayList(preroll)
        onChange?.invoke()
    }

    fun end() {
        if (!isCapturing || !isManual) return
        finish()
    }

    fun calibrate() {
        calibration.clear()
        calibrationEnd = null
        isCalibrating = true
        onChange?.invoke()
    }

    fun ingest(sample: MotionSample) {
        val e = MotionFeatures.energy(sample)
        energy = energy * 0.6 + e * 0.4

        if (isCalibrating) {
            val end = calibrationEnd ?: (sample.t + CALIBRATION_SECONDS)
            calibrationEnd = end
            calibration += energy
            if (sample.t >= end) {
                val sorted = calibration.sorted()
                noiseFloor = max(sorted[sorted.size / 2], 1e-4)
                isCalibrating = false
            }
        }

        preroll += sample
        val keepFrom = preroll.indexOfFirst { it.t >= sample.t - PREROLL_SECONDS }
        if (keepFrom > 0) preroll.subList(0, keepFrom).clear()

        if (isCapturing) {
            buffer += sample
            val duration = sample.t - (buffer.firstOrNull()?.t ?: sample.t)
            if (isManual) {
                if (duration > MAX_HOLD_SECONDS) finish()
            } else {
                val threshold = threshold
                if (threshold != null) {
                    if (energy < threshold * 0.7) {
                        val since = quietSince ?: sample.t
                        quietSince = since
                        if (sample.t - since > HANGOVER_SECONDS) finish()
                    } else {
                        quietSince = null
                    }
                    if (isCapturing && duration > MAX_AUTO_SECONDS) finish()
                }
            }
        } else {
            val threshold = threshold
            if (autoEnabled && !isCalibrating && threshold != null && energy > threshold) {
                isManual = false
                isCapturing = true
                quietSince = null
                buffer = ArrayList(preroll)
            }
        }
        onChange?.invoke()
    }

    private fun finish() {
        val segment = buffer
        buffer = ArrayList()
        isCapturing = false
        isManual = false
        quietSince = null
        onChange?.invoke()
        if (segment.size < MIN_SEGMENT_SAMPLES) return
        if (segment.last().t - segment.first().t < MIN_SEGMENT_SECONDS) return
        onSegment?.invoke(segment)
    }
}
