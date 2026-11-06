package dev.subvox.core

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One processed headphone-motion reading. [t] is seconds on a monotonic clock shared with the
 * audio capture path on the same platform, so motion and audio line up.
 *
 * Field names and order match the macOS app's `MotionSample`, so `dataset.json` and
 * `motion.csv` files move between the three platforms unchanged.
 */
@Serializable
data class MotionSample(
    val t: Double,
    // User acceleration (g), gravity removed.
    val ax: Double = 0.0, val ay: Double = 0.0, val az: Double = 0.0,
    // Rotation rate (rad/s).
    val rx: Double = 0.0, val ry: Double = 0.0, val rz: Double = 0.0,
    // Attitude (rad).
    val pitch: Double = 0.0, val roll: Double = 0.0, val yaw: Double = 0.0,
    // Gravity vector (g).
    val gx: Double = 0.0, val gy: Double = 0.0, val gz: Double = -1.0,
    // Attitude quaternion.
    val qw: Double = 1.0, val qx: Double = 0.0, val qy: Double = 0.0, val qz: Double = 0.0,
) {
    fun shifted(dt: Double): MotionSample = copy(t = t + dt)

    fun csvRow(t0: Double): String =
        doubleArrayOf(t - t0, ax, ay, az, rx, ry, rz, pitch, roll, yaw, gx, gy, gz, qw, qx, qy, qz)
            .joinToString(",") { String.format(Locale.ROOT, "%.6f", it) }

    companion object {
        const val CSV_HEADER = "t,ax,ay,az,rx,ry,rz,pitch,roll,yaw,gx,gy,gz,qw,qx,qy,qz"

        /** Parses one `motion.csv` row written by any of the apps. Returns null for a bad row. */
        fun fromCsvRow(row: String): MotionSample? {
            val f = row.split(',')
            if (f.size < 17) return null
            val v = DoubleArray(17)
            for (i in 0 until 17) v[i] = f[i].trim().toDoubleOrNull() ?: return null
            return MotionSample(
                t = v[0], ax = v[1], ay = v[2], az = v[3], rx = v[4], ry = v[5], rz = v[6],
                pitch = v[7], roll = v[8], yaw = v[9], gx = v[10], gy = v[11], gz = v[12],
                qw = v[13], qx = v[14], qy = v[15], qz = v[16],
            )
        }

        /**
         * Builds a sample from a unit quaternion (w, x, y, z) and angular velocity, the shape
         * Android's head-tracker sensor delivers. Euler angles follow Core Motion's convention
         * (pitch about x, roll about y, yaw about z) so templates stay comparable.
         */
        fun fromQuaternion(
            t: Double, qw: Double, qx: Double, qy: Double, qz: Double,
            rx: Double, ry: Double, rz: Double,
            ax: Double = 0.0, ay: Double = 0.0, az: Double = 0.0,
        ): MotionSample {
            val sinr = 2 * (qw * qx + qy * qz)
            val cosr = 1 - 2 * (qx * qx + qy * qy)
            val pitch = kotlin.math.atan2(sinr, cosr)
            val sinp = (2 * (qw * qy - qz * qx)).coerceIn(-1.0, 1.0)
            val roll = kotlin.math.asin(sinp)
            val siny = 2 * (qw * qz + qx * qy)
            val cosy = 1 - 2 * (qy * qy + qz * qz)
            val yaw = kotlin.math.atan2(siny, cosy)
            // Gravity in the device frame: rotate world "down" by the inverse attitude.
            val gx = -(2 * (qx * qz - qw * qy))
            val gy = -(2 * (qy * qz + qw * qx))
            val gz = -(1 - 2 * (qx * qx + qy * qy))
            return MotionSample(
                t, ax, ay, az, rx, ry, rz, pitch, roll, yaw, gx, gy, gz, qw, qx, qy, qz,
            )
        }
    }
}
