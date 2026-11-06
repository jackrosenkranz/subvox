package dev.subvox.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayMotionSourceTest {
    @Test
    fun replayRestampsOntoTheCallersClockInOrder() {
        val recorded = (0 until 10).map { MotionSample(t = 500.0 + it * 0.01, rx = it.toDouble()) }
        val clock = { System.nanoTime() / 1e9 }
        val source = ReplayMotionSource(recorded, "test", clock, speed = 4.0, loop = false)
        val got = java.util.Collections.synchronizedList(ArrayList<MotionSample>())
        val done = CountDownLatch(1)
        source.onSample = { got += it }
        source.onStatus = { if (it == MotionSource.Status.Waiting && got.size == 10) done.countDown() }
        val before = clock()
        source.start()
        assertTrue(done.await(5, TimeUnit.SECONDS), "replay did not finish")
        assertEquals((0 until 10).map { it.toDouble() }, got.map { it.rx })
        assertTrue(got.first().t >= before)
        assertTrue(got.zipWithNext().all { (a, b) -> b.t > a.t })
        assertEquals(0.09 / 4.0, got.last().t - got.first().t, 1e-6)
    }
}
