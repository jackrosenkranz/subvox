package dev.subvox.core

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * One recorded take of a word. [createdAt] is seconds since 2001-01-01 UTC, the encoding Swift's
 * `JSONEncoder` uses for `Date`, so a dataset written by the macOS app loads here unchanged and
 * vice versa.
 */
@Serializable
data class Template(
    val id: String = UUID.randomUUID().toString().uppercase(),
    val label: String,
    val samples: List<MotionSample>,
    val createdAt: Double = appleSecondsNow(),
) {
    val duration: Double
        get() = if (samples.size < 2) 0.0 else samples.last().t - samples.first().t

    companion object {
        /** Seconds between the Unix epoch and Apple's reference date (2001-01-01T00:00:00Z). */
        const val APPLE_EPOCH_OFFSET_SECONDS = 978_307_200.0

        fun appleSecondsNow(): Double = System.currentTimeMillis() / 1000.0 - APPLE_EPOCH_OFFSET_SECONDS
        fun appleSecondsToEpochMillis(seconds: Double): Long = ((seconds + APPLE_EPOCH_OFFSET_SECONDS) * 1000).toLong()
    }
}

data class RankedLabel(val label: String, val distance: Double)

data class Prediction(
    val ranked: List<RankedLabel>,
    /** 0...1 margin between the best and second-best class. */
    val confidence: Double,
    val accepted: Boolean,
) {
    val best: RankedLabel? get() = ranked.firstOrNull()
}

/**
 * Small-vocabulary recognizer: nearest-neighbour DTW against user-recorded templates.
 * Deliberately simple. The question this prototype answers is whether the signal is there at
 * all, not how far a big model can push it.
 */
class TemplateClassifier private constructor(
    val featureSet: FeatureSet,
    private val entries: List<Pair<String, Frames>>,
    private val scales: DoubleArray,
) {
    val labels: List<String> get() = entries.map { it.first }.toSet().sorted()

    fun rank(samples: List<MotionSample>): List<RankedLabel> {
        val query = MotionFeatures.scaled(MotionFeatures.frames(MotionFeatures.trim(samples), featureSet), scales)
        val best = HashMap<String, Double>()
        for ((label, frames) in entries) {
            val d = DTW.distance(query, frames)
            best[label] = min(best[label] ?: Double.POSITIVE_INFINITY, d)
        }
        return best.map { RankedLabel(it.key, it.value) }.sortedBy { it.distance }
    }

    fun predict(samples: List<MotionSample>, maxDistance: Double, minConfidence: Double): Prediction {
        val ranked = rank(samples)
        val confidence = margin(ranked.map { it.distance })
        val accepted = (ranked.firstOrNull()?.distance ?: Double.POSITIVE_INFINITY) <= maxDistance &&
            confidence >= minConfidence
        return Prediction(ranked, confidence, accepted)
    }

    companion object {
        /** Returns null when fewer than two labels are present or no feature is selected. */
        fun build(templates: List<Template>, featureSet: FeatureSet): TemplateClassifier? {
            if (featureSet.isEmpty()) return null
            if (templates.map { it.label }.toSet().size < 2) return null
            val raw = templates.map { MotionFeatures.frames(MotionFeatures.trim(it.samples), featureSet) }
            val scales = MotionFeatures.channelScales(raw)
            val entries = templates.zip(raw) { t, f -> t.label to MotionFeatures.scaled(f, scales) }
            return TemplateClassifier(featureSet, entries, scales)
        }

        fun margin(sortedDistances: List<Double>): Double {
            if (sortedDistances.size < 2 || sortedDistances[1] <= 0) return 0.0
            return max(0.0, 1 - sortedDistances[0] / sortedDistances[1])
        }
    }
}
