package dev.subvox.android

import dev.subvox.core.MotionSample
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue

/**
 * Forwards motion samples to the desktop app as UDP datagrams, one `motion.csv` row each, the
 * format `UdpMotionSource` on the desktop expects. Lets a Windows machine run the Train and
 * Decode screens on live head motion captured by this phone.
 */
class UdpMotionSender(host: String, val port: Int = 55055) {
    private val address: InetAddress = InetAddress.getByName(host)
    private val queue = LinkedBlockingQueue<MotionSample>()
    private val socket = DatagramSocket()
    private val thread = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                val s = queue.take()
                val bytes = s.csvRow(0.0).toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, address, port))
            }
        } catch (e: InterruptedException) {
            // closed
        } catch (e: Exception) {
            error = e.message
        } finally {
            socket.close()
        }
    }, "subvox-udp-send").apply { isDaemon = true; start() }

    @Volatile var error: String? = null
        private set
    var sent = 0
        private set

    fun send(sample: MotionSample) { if (queue.size < 200) { queue.offer(sample); sent++ } }
    fun close() { thread.interrupt() }
}
