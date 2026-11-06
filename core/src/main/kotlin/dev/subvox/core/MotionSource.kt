package dev.subvox.core

/**
 * A stream of [MotionSample]s. Platforms provide the live implementations (Core Motion on
 * macOS, the head-tracker sensor on Android); [ReplayMotionSource] plays a recorded session
 * back on any platform, which is the only motion source available on Windows.
 */
interface MotionSource {
    sealed class Status {
        /** No headphone motion API exists here, or the connected headset has no motion sensor. */
        data class Unsupported(val reason: String) : Status()
        object Denied : Status()
        object Waiting : Status()
        data class Streaming(val sourceName: String) : Status()
        data class Error(val message: String) : Status()

        val label: String
            get() = when (this) {
                is Unsupported -> "Headphone motion unavailable"
                Denied -> "Motion access denied"
                Waiting -> "Waiting for headset"
                is Streaming -> "Streaming ($sourceName)"
                is Error -> message
            }
    }

    val name: String
    val status: Status

    /** Called on an arbitrary thread; implementations document which. */
    var onSample: ((MotionSample) -> Unit)?
    var onStatus: ((Status) -> Unit)?

    fun start()
    fun stop()
}

/** Keeps the recent history and measured sample rate for any [MotionSource]. */
class MotionMonitor(val historySeconds: Double = 10.0) {
    var latest: MotionSample? = null
        private set
    val history = ArrayList<MotionSample>()
    var sampleRate: Double = 0.0
        private set
    var sampleCount: Int = 0
        private set

    private val rateWindow = ArrayList<Double>()

    fun ingest(sample: MotionSample) {
        latest = sample
        sampleCount++
        history += sample
        val cutoff = sample.t - historySeconds
        val firstKept = history.indexOfFirst { it.t >= cutoff }
        if (firstKept > 0) history.subList(0, firstKept).clear()

        rateWindow += sample.t
        rateWindow.removeAll { it < sample.t - 1 }
        val first = rateWindow.firstOrNull()
        if (first != null && sample.t > first) sampleRate = (rateWindow.size - 1) / (sample.t - first)
    }

    fun reset() {
        latest = null
        history.clear()
        rateWindow.clear()
        sampleRate = 0.0
    }
}

/**
 * Plays a recorded `motion.csv` back as if it were live, re-stamped onto the caller's clock so
 * the capture controller, recorder and audio alignment behave as they do with a real headset.
 * Samples are delivered on a private thread.
 */
class ReplayMotionSource(
    val samples: List<MotionSample>,
    val sourceName: String,
    private val clock: () -> Double,
    val speed: Double = 1.0,
    val loop: Boolean = true,
) : MotionSource {
    override val name = "Replay: $sourceName"
    override var status: MotionSource.Status = MotionSource.Status.Waiting
        private set
    override var onSample: ((MotionSample) -> Unit)? = null
    override var onStatus: ((MotionSource.Status) -> Unit)? = null

    private var thread: Thread? = null

    override fun start() {
        if (thread != null) return
        if (samples.size < 2) {
            setStatus(MotionSource.Status.Error("Session has too few motion samples to replay."))
            return
        }
        val t = Thread({ run() }, "subvox-replay").apply { isDaemon = true }
        thread = t
        t.start()
    }

    override fun stop() {
        thread?.interrupt()
        thread = null
        setStatus(MotionSource.Status.Waiting)
    }

    private fun run() {
        setStatus(MotionSource.Status.Streaming(sourceName))
        try {
            do {
                val base = clock()
                val t0 = samples.first().t
                for (s in samples) {
                    val due = base + (s.t - t0) / speed
                    val wait = due - clock()
                    if (wait > 0) Thread.sleep((wait * 1000).toLong(), ((wait * 1000 % 1) * 1_000_000).toInt())
                    onSample?.invoke(s.copy(t = due))
                }
            } while (loop && !Thread.currentThread().isInterrupted)
            setStatus(MotionSource.Status.Waiting)
        } catch (e: InterruptedException) {
            // stopped
        }
    }

    private fun setStatus(s: MotionSource.Status) {
        status = s
        onStatus?.invoke(s)
    }
}
