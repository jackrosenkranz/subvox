package dev.subvox.core

import kotlin.math.min
import kotlin.random.Random

/**
 * Leave-one-out accuracy plus a label-permutation test: the honest answer to
 * "is the motion stream statistically distinguishable between words?"
 */
data class Evaluation(
    val labels: List<String>,
    /** confusion[actual][predicted] */
    val confusion: List<IntArray>,
    val accuracy: Double,
    /** Accuracy of always guessing the most common label. */
    val chance: Double,
    val permutationMean: Double,
    val permutationP95: Double,
    val pValue: Double,
    val permutations: Int,
    val templateCount: Int,
) {
    val isSignificant: Boolean get() = pValue < 0.05

    companion object {
        fun run(
            templates: List<Template>,
            featureSet: FeatureSet,
            permutations: Int = 500,
            random: Random = Random.Default,
        ): Evaluation? {
            val labels = templates.map { it.label }.toSet().sorted()
            val counts = templates.groupingBy { it.label }.eachCount()
            if (labels.size < 2 || counts.values.any { it < 2 } || featureSet.isEmpty()) return null

            val raw = templates.map { MotionFeatures.frames(MotionFeatures.trim(it.samples), featureSet) }
            val scales = MotionFeatures.channelScales(raw)
            val seqs = raw.map { MotionFeatures.scaled(it, scales) }
            val n = seqs.size

            // Pairwise distances once; every labelling (true or permuted) reuses them.
            val distance = Array(n) { DoubleArray(n) }
            for (i in 0 until n) for (j in i + 1 until n) {
                val d = DTW.distance(seqs[i], seqs[j])
                distance[i][j] = d
                distance[j][i] = d
            }

            val index = labels.withIndex().associate { it.value to it.index }
            val truth = IntArray(n) { index.getValue(templates[it].label) }

            fun leaveOneOut(assignment: IntArray): IntArray = IntArray(n) { i ->
                val best = DoubleArray(labels.size) { Double.POSITIVE_INFINITY }
                for (j in 0 until n) if (j != i) best[assignment[j]] = min(best[assignment[j]], distance[i][j])
                var arg = 0
                for (k in 1 until best.size) if (best[k] < best[arg]) arg = k
                arg
            }

            fun accuracy(predicted: IntArray, actual: IntArray): Double =
                predicted.indices.count { predicted[it] == actual[it] }.toDouble() / n

            val predicted = leaveOneOut(truth)
            val confusion = List(labels.size) { IntArray(labels.size) }
            for (i in 0 until n) confusion[truth[i]][predicted[i]] += 1
            val observed = accuracy(predicted, truth)

            val nullAccuracies = DoubleArray(permutations)
            val shuffled = truth.copyOf()
            for (p in 0 until permutations) {
                shuffled.shuffle(random)
                nullAccuracies[p] = accuracy(leaveOneOut(shuffled), shuffled)
            }
            nullAccuracies.sort()
            val atLeast = nullAccuracies.count { it >= observed }

            return Evaluation(
                labels = labels,
                confusion = confusion,
                accuracy = observed,
                chance = counts.values.max().toDouble() / n,
                permutationMean = if (permutations > 0) nullAccuracies.sum() / permutations else 0.0,
                permutationP95 = if (permutations == 0) 0.0 else nullAccuracies[((permutations - 1) * 0.95).toInt()],
                pValue = (atLeast + 1).toDouble() / (permutations + 1),
                permutations = permutations,
                templateCount = n,
            )
        }
    }
}
