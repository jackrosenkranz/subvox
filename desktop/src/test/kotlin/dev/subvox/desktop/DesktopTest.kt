package dev.subvox.desktop

import dev.subvox.core.MotionSample
import dev.subvox.core.MotionSource
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTest {
    @Test
    fun udpSourceParsesRowsAndRestampsOntoLocalClock() {
        val port = DatagramSocket(0).use { it.localPort }
        val source = UdpMotionSource(port)
        val got = java.util.Collections.synchronizedList(ArrayList<MotionSample>())
        val latch = CountDownLatch(3)
        source.onSample = { got += it; latch.countDown() }
        source.start()
        Thread.sleep(200)
        val before = Platform.clock()
        DatagramSocket().use { s ->
            val rows = listOf(
                MotionSample(t = 100.00, rx = 1.0), MotionSample(t = 100.04, rx = 2.0), MotionSample(t = 100.08, rx = 3.0),
            )
            // Two rows in one packet plus a header line that must be ignored, then one more packet.
            val first = (MotionSample.CSV_HEADER + "\n" + rows[0].csvRow(0.0) + "\n" + rows[1].csvRow(0.0)).toByteArray()
            s.send(DatagramPacket(first, first.size, InetAddress.getLoopbackAddress(), port))
            Thread.sleep(50)
            val second = rows[2].csvRow(0.0).toByteArray()
            s.send(DatagramPacket(second, second.size, InetAddress.getLoopbackAddress(), port))
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "did not receive 3 samples")
        source.stop()
        assertEquals(listOf(1.0, 2.0, 3.0), got.map { it.rx })
        assertTrue(got.all { it.t >= before - 0.01 && it.t <= Platform.clock() + 0.1 }, "restamped onto local clock")
        assertEquals(0.04, got[1].t - got[0].t, 1e-6)
        assertTrue(source.status == MotionSource.Status.Waiting)
    }

    @Test
    fun resamplerDownmixesAndChangesRate() {
        val stereo48k = AudioFormat(48000f, 16, 2, true, false)
        // 480 stereo frames (10 ms): left = 1000, right = 3000, so mono = 2000.
        val bytes = ByteArray(480 * 4)
        for (f in 0 until 480) {
            bytes[f * 4] = (1000 and 0xff).toByte(); bytes[f * 4 + 1] = (1000 shr 8).toByte()
            bytes[f * 4 + 2] = (3000 and 0xff).toByte(); bytes[f * 4 + 3] = (3000 shr 8).toByte()
        }
        val out = Resampler(16000).convert(bytes, bytes.size, stereo48k)
        val frames = out.size / 2
        assertTrue(frames in 158..160, "got $frames frames")
        val v = (out[1].toInt() shl 8) or (out[0].toInt() and 0xff)
        assertEquals(2000, v)

        val mono16k = AudioFormat(16000f, 16, 1, true, false)
        val same = Resampler(16000).convert(bytes, 320, mono16k)
        assertEquals(320, same.size)
    }
}
