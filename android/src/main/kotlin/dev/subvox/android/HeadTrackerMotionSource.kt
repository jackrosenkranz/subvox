package dev.subvox.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import dev.subvox.core.MotionSample
import dev.subvox.core.MotionSource
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Head motion from `Sensor.TYPE_HEAD_TRACKER` (Android 13+). The sensor exists only while a
 * headset that reports head tracking through the spatializer stack is connected (Pixel Buds Pro
 * on Pixel phones, some LE Audio headsets). Shokz headsets never expose it; with them this source
 * reports [MotionSource.Status.Unsupported].
 *
 * The event carries a rotation vector (axis-angle, radians) and angular velocity (rad/s). There
 * is no linear acceleration, so `ax, ay, az` stay zero and the ACCELERATION feature is empty.
 * Callbacks arrive on the sensor thread.
 */
class HeadTrackerMotionSource(context: Context) : MotionSource, SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        if (Build.VERSION.SDK_INT >= 33) manager.getDefaultSensor(Sensor.TYPE_HEAD_TRACKER) else null

    override val name = "Head tracker"
    @Volatile override var status: MotionSource.Status = MotionSource.Status.Waiting
        private set
    override var onSample: ((MotionSample) -> Unit)? = null
    override var onStatus: ((MotionSource.Status) -> Unit)? = null

    override fun start() {
        if (Build.VERSION.SDK_INT < 33) {
            set(MotionSource.Status.Unsupported("Head tracking needs Android 13 or newer.")); return
        }
        if (sensor == null) {
            set(MotionSource.Status.Unsupported("No head-tracker sensor. The connected headset does not report head motion to Android (Shokz headsets never do)."))
            return
        }
        set(MotionSource.Status.Waiting)
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    override fun stop() {
        manager.unregisterListener(this)
        set(MotionSource.Status.Waiting)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 6) return
        // Axis-angle rotation vector -> quaternion.
        val angle = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
        val (qw, qx, qy, qz) = if (angle < 1e-9) doubleArrayOf(1.0, 0.0, 0.0, 0.0).toList() else {
            val s = sin(angle / 2) / angle
            listOf(cos(angle / 2), v[0] * s, v[1] * s, v[2] * s)
        }
        val sample = MotionSample.fromQuaternion(
            t = Clock.fromSensorNanos(event.timestamp),
            qw = qw, qx = qx, qy = qy, qz = qz,
            rx = v[3].toDouble(), ry = v[4].toDouble(), rz = v[5].toDouble(),
        )
        if (status !is MotionSource.Status.Streaming) set(MotionSource.Status.Streaming(sensor?.name ?: "head tracker"))
        onSample?.invoke(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun set(s: MotionSource.Status) { status = s; onStatus?.invoke(s) }
}

/**
 * The phone's own inertial sensors, for testing the pipeline with the phone held against the
 * jaw or strapped to a headband. It is not headphone motion; sessions recorded with it say so
 * in `meta.json` through the source name.
 */
class PhoneMotionSource(context: Context) : MotionSource, SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccel = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    override val name = "Phone IMU"
    @Volatile override var status: MotionSource.Status = MotionSource.Status.Waiting
        private set
    override var onSample: ((MotionSample) -> Unit)? = null
    override var onStatus: ((MotionSource.Status) -> Unit)? = null

    private var q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    private var gyro = doubleArrayOf(0.0, 0.0, 0.0)
    private var accel = doubleArrayOf(0.0, 0.0, 0.0)

    override fun start() {
        if (gyroscope == null || rotationVector == null) {
            set(MotionSource.Status.Unsupported("This phone has no gyroscope or rotation vector sensor.")); return
        }
        set(MotionSource.Status.Waiting)
        for (s in listOfNotNull(rotationVector, gyroscope, linearAccel)) manager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        manager.unregisterListener(this)
        set(MotionSource.Status.Waiting)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                // Android rotation vector: (x*sin(a/2), y*sin(a/2), z*sin(a/2), cos(a/2)).
                val w = if (v.size >= 4) v[3].toDouble() else sqrt((1 - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]).coerceAtLeast(0f).toDouble())
                q = doubleArrayOf(w, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // m/s^2 -> g, to match Core Motion's userAcceleration.
                accel = doubleArrayOf(v[0] / 9.80665, v[1] / 9.80665, v[2] / 9.80665)
                return
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro = doubleArrayOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                // The gyroscope is the fastest stream; emit one sample per gyro event.
                val sample = MotionSample.fromQuaternion(
                    t = Clock.fromSensorNanos(event.timestamp),
                    qw = q[0], qx = q[1], qy = q[2], qz = q[3],
                    rx = gyro[0], ry = gyro[1], rz = gyro[2],
                    ax = accel[0], ay = accel[1], az = accel[2],
                )
                if (status !is MotionSource.Status.Streaming) set(MotionSource.Status.Streaming("phone IMU"))
                onSample?.invoke(sample)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun set(s: MotionSource.Status) { status = s; onStatus?.invoke(s) }
}
