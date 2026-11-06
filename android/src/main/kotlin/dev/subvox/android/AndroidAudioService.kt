package dev.subvox.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import dev.subvox.core.HeadsetProfile
import dev.subvox.core.Headsets
import dev.subvox.core.pcm16Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PcmChunk(val bytes: ByteArray, val length: Int, val sampleRate: Int, val channels: Int, val clockSeconds: Double)

class AudioSink {
    private val consumers = ConcurrentHashMap<UUID, (PcmChunk) -> Unit>()
    @Volatile var onLevel: ((Float) -> Unit)? = null
    fun add(consumer: (PcmChunk) -> Unit): UUID = UUID.randomUUID().also { consumers[it] = consumer }
    fun remove(id: UUID) { consumers.remove(id) }
    fun dispatch(chunk: PcmChunk) {
        for (c in consumers.values) c(chunk)
        onLevel?.invoke(pcm16Level(chunk.bytes, chunk.length))
    }
}

/**
 * Microphone capture with `AudioRecord`. A Bluetooth headset's microphone (a Shokz, for example)
 * reaches Android only over the SCO link of the hands-free profile: 16 kHz mono (mSBC) or 8 kHz
 * (CVSD). The headset's own noise processing (CVC on Shokz) runs before the signal gets here.
 *
 * Routing: on Android 12+ `setCommunicationDevice` selects the SCO device; before that the app
 * calls `startBluetoothSco`. `AudioRecord.setPreferredDevice` pins the record to the chosen input.
 */
class AndroidAudioService(private val context: Context) {
    data class Device(val info: AudioDeviceInfo?, val name: String) {
        val headset: HeadsetProfile? get() = Headsets.match(name)
        val isBluetoothSco: Boolean get() = info?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val sink = AudioSink()

    @Volatile var isRunning = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var activeDevice: Device? = null
        private set
    @Volatile var sampleRate: Int = 0
        private set
    var onChange: (() -> Unit)? = null

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var scoStarted = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun devices(): List<Device> {
        val out = ArrayList<Device>()
        out += Device(null, "System default input")
        for (d in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            val label = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "${d.productName} (Bluetooth headset mic)"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "${d.productName} (wired headset)"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "${d.productName} (USB)"
                else -> continue
            }
            out += Device(d, label)
        }
        return out
    }

    fun start(device: Device) {
        if (isRunning) return
        error = null
        if (!hasPermission()) { error = "Microphone permission not granted."; onChange?.invoke(); return }

        if (device.isBluetoothSco) routeToSco(device.info!!)
        val source = if (device.isBluetoothSco) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        var opened: AudioRecord? = null
        var rate = 0
        for (candidate in intArrayOf(16000, 48000, 44100, 8000)) {
            val min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = try {
                AudioRecord(source, candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, candidate / 5 * 2))
            } catch (e: Exception) { continue }
            if (r.state == AudioRecord.STATE_INITIALIZED) { opened = r; rate = candidate; break } else r.release()
        }
        if (opened == null) { error = "Could not open the microphone."; stopSco(); onChange?.invoke(); return }
        device.info?.let { opened.preferredDevice = it }
        record = opened
        sampleRate = rate
        activeDevice = device
        isRunning = true
        opened.startRecording()
        thread = Thread({ loop(opened, rate) }, "subvox-audio").apply { isDaemon = true; start() }
        onChange?.invoke()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.interrupt(); thread = null
        record?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
        record = null
        stopSco()
        activeDevice = null
        sink.onLevel?.invoke(0f)
        onChange?.invoke()
    }

    private fun loop(record: AudioRecord, rate: Int) {
        val buffer = ByteArray(rate / 20 * 2) // 50 ms mono PCM-16
        while (isRunning && !Thread.currentThread().isInterrupted) {
            val n = record.read(buffer, 0, buffer.size)
            if (n <= 0) continue
            val start = Clock.now() - (n / 2).toDouble() / rate
            sink.dispatch(PcmChunk(buffer.copyOf(n), n, rate, 1, start))
        }
    }

    private fun routeToSco(device: AudioDeviceInfo) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }
            scoStarted = true
        } catch (e: Exception) {
            error = "Could not route to the Bluetooth headset microphone: ${e.message}"
        }
    }

    private fun stopSco() {
        if (!scoStarted) return
        try {
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        } catch (e: Exception) { }
        scoStarted = false
    }
}
