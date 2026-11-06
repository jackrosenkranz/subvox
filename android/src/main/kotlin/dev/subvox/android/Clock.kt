package dev.subvox.android

import android.os.SystemClock

/** Monotonic seconds. Sensor events and audio chunks are both stamped on this clock. */
object Clock {
    fun now(): Double = SystemClock.elapsedRealtimeNanos() / 1e9
    fun fromSensorNanos(ns: Long): Double = ns / 1e9
}
