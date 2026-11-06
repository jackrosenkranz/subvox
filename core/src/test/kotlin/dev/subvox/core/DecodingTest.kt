package dev.subvox.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Port of the macOS `DecodingTests`, with a seeded generator so results are reproducible. */
class DecodingTest {
    private val random = Random(7)

    /** A synthetic "word": a burst of rotation at `frequency` cycles over the segment. */
    private fun take(frequency: Double, axis: Int, frames: Int = 30, noise: Double = 0.01): List<MotionSample> =
        (0 until frames).map { i ->
            val t = i / 25.0
            val envelope = sin(PI * i / (frames - 1))
            val v = 0.2 * envelope * sin(2 * PI * frequency * i / frames)
            fun jitter() = if (noise == 0.0) 0.0 else random.nextDouble(-noise, noise)
            var rx = jitter(); var ry = jitter(); var rz = jitter()
            when (axis) { 0 -> rx += v; 1 -> ry += v; else -> rz += v }
            MotionSample(t = t, rx = rx, ry = ry, rz = rz)
        }

    @Test
    fun dtwIdenticalIsZeroAndWarpTolerant() {
        val a = MotionFeatures.frames(take(2.0, 0, noise = 0.0), ROTATION_ONLY)
        val stretched = MotionFeatures.frames(take(2.0, 0, frames = 40, noise = 0.0), ROTATION_ONLY)
        val other = MotionFeatures.frames(take(2.0, 1, noise = 0.0), ROTATION_ONLY)
        assertEquals(0.0, DTW.distance(a, a), 1e-12)
        assertTrue(DTW.distance(a, stretched) < DTW.distance(a, other))
    }

    @Test
    fun classifierSeparatesDistinctWords() {
        val templates = ArrayList<Template>()
        repeat(6) {
            templates += Template(label = "yes", samples = take(2.0, 0))
            templates += Template(label = "no", samples = take(3.0, 1))
        }
        val classifier = assertNotNull(TemplateClassifier.build(templates, ALL_FEATURES))
        val prediction = classifier.predict(take(3.0, 1), maxDistance = 10.0, minConfidence = 0.0)
        assertEquals("no", prediction.best?.label)
        assertTrue(prediction.accepted)
        assertEquals(listOf("no", "yes"), classifier.labels)
    }

    @Test
    fun classifierNeedsTwoLabelsAndAFeature() {
        val one = List(4) { Template(label = "yes", samples = take(2.0, 0)) }
        assertEquals(null, TemplateClassifier.build(one, ALL_FEATURES))
        val two = one + List(4) { Template(label = "no", samples = take(3.0, 1)) }
        assertEquals(null, TemplateClassifier.build(two, emptySet()))
        assertNotNull(TemplateClassifier.build(two, ALL_FEATURES))
    }

    @Test
    fun evaluationBeatsChanceOnlyWhenSignalExists() {
        val real = ArrayList<Template>()
        val noise = ArrayList<Template>()
        repeat(8) {
            real += Template(label = "yes", samples = take(2.0, 0))
            real += Template(label = "no", samples = take(3.0, 2))
            noise += Template(label = "yes", samples = take(0.0, 0, noise = 0.05))
            noise += Template(label = "no", samples = take(0.0, 0, noise = 0.05))
        }
        val signal = assertNotNull(Evaluation.run(real, ALL_FEATURES, permutations = 200, random = Random(1)))
        assertTrue(signal.accuracy > 0.9, "accuracy ${signal.accuracy}")
        assertTrue(signal.pValue < 0.01, "p ${signal.pValue}")
        assertTrue(signal.isSignificant)
        assertEquals(16, signal.templateCount)
        assertEquals(0.5, signal.chance)

        val none = assertNotNull(Evaluation.run(noise, ALL_FEATURES, permutations = 200, random = Random(1)))
        assertTrue(none.pValue > 0.01, "p ${none.pValue}")
    }

    @Test
    fun evaluationRefusesThinData() {
        val thin = listOf(
            Template(label = "yes", samples = take(2.0, 0)),
            Template(label = "no", samples = take(3.0, 1)),
            Template(label = "no", samples = take(3.0, 1)),
        )
        assertEquals(null, Evaluation.run(thin, ALL_FEATURES, permutations = 10))
    }

    @Test
    fun trimDropsQuietPadding() {
        val quiet = (0 until 20).map { MotionSample(t = it / 25.0) }
        val burst = take(2.0, 0, noise = 0.0).map { it.shifted(0.8) }
        val trimmed = MotionFeatures.trim(quiet + burst + quiet.map { it.shifted(2.0) })
        assertTrue(trimmed.size < 40, "size ${trimmed.size}")
        assertTrue(trimmed.size >= 20, "size ${trimmed.size}")
    }

    @Test
    fun marginIsZeroWithoutRunnerUp() {
        assertEquals(0.0, TemplateClassifier.margin(listOf(0.3)))
        assertEquals(0.5, TemplateClassifier.margin(listOf(0.5, 1.0)), 1e-12)
    }
}
