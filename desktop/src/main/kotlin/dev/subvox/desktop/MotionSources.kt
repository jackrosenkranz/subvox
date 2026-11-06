package dev.subvox.desktop

import dev.subvox.core.MotionSample
import dev.subvox.core.MotionSource
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException

/**
 * Windows has no headphone-motion API, and Shokz headsets carry no inertial sensor on any
 * platform. This source says so instead of waiting forever.
 */
class NoMotionSource(reason: String) : MotionSource {
    override val name = "None"
    override val status: MotionSource.Status = MotionSource.Status.Unsupported(reason)
    override var onSample: ((MotionSample) -> Unit)? = null
    override var onStatus: ((MotionSource.Status) -> Unit)? = null
    override fun start() { onStatus?.invoke(status) }
    override fun stop() {}

    companion object {
        val windowsReason = "Windows exposes no headphone motion sensor. Replay a recorded session, or stream samples over UDP from the Android app."
        val shokzReason = "Shokz headsets have no motion sensor; only their microphone reaches the host."
    }
}

/**
 * Receives motion samples sent by another Subvox app as UDP datagrams, one `motion.csv` row per
 * packet (17 comma-separated fields, header optional). Samples are re-stamped onto the local
 * clock on arrival, so the capture controller and recorder see a live stream. Delivery is on a
 * private thread.
 */
class UdpMotionSource(val port: Int = DEFAULT_PORT) : MotionSource {
    companion object { const val DEFAULT_PORT = 55055 }

    override val name = "UDP :$port"
    @Volatile override var status: MotionSource.Status = MotionSource.Status.Waiting
        private set
    override var onSample: ((MotionSample) -> Unit)? = null
    override var onStatus: ((MotionSource.Status) -> Unit)? = null

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    /** Offset from the sender's clock to ours, estimated from the first packet and nudged after. */
    private var offset: Double? = null

    override fun start() {
        if (thread != null) return
        val s = try { DatagramSocket(port).apply { soTimeout = 1000 } } catch (e: Exception) {
            setStatus(MotionSource.Status.Error("Cannot listen on UDP $port: ${e.message}")); return
        }
        socket = s
        offset = null
        setStatus(MotionSource.Status.Waiting)
        val t = Thread({ loop(s) }, "subvox-udp").apply { isDaemon = true }
        thread = t
        t.start()
    }

    override fun stop() {
        thread?.interrupt()
        thread = null
        socket?.close()
        socket = null
        setStatus(MotionSource.Status.Waiting)
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        var lastPacket = 0.0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: SocketTimeoutException) {
                    if (status is MotionSource.Status.Streaming && Platform.clock() - lastPacket > 2) setStatus(MotionSource.Status.Waiting)
                    continue
                }
                val now = Platform.clock()
                lastPacket = now
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                for (line in text.lineSequence()) {
                    val sample = MotionSample.fromCsvRow(line) ?: continue
                    // Map the sender's clock onto ours once, then keep the sender's spacing; rows
                    // batched into one packet stay 1/rate apart. Re-sync only on a clock jump
                    // (sender restarted, or more than a second of drift).
                    var off = offset ?: (now - sample.t).also { offset = it }
                    if (kotlin.math.abs(sample.t + off - now) > 1.0) { off = now - sample.t; offset = off }
                    val t = sample.t + off
                    if (status !is MotionSource.Status.Streaming) setStatus(MotionSource.Status.Streaming(packet.address.hostAddress))
                    onSample?.invoke(sample.copy(t = t))
                }
            }
        } catch (e: Exception) {
            if (socket != null) setStatus(MotionSource.Status.Error("UDP receiver stopped: ${e.message}"))
        }
    }

    private fun setStatus(st: MotionSource.Status) {
        status = st
        onStatus?.invoke(st)
    }
}
