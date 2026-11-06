package dev.subvox.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous speech-to-text with Android's `SpeechRecognizer`, used as the reference transcript.
 * The recognizer captures the microphone itself (it cannot be fed buffers), so while it runs the
 * app's own `AudioRecord` may receive silence on some devices. Record audio to a session or
 * transcribe, not both, unless the device is known to allow concurrent capture.
 *
 * Runs on the main thread, as the SpeechRecognizer API requires.
 */
class AndroidTranscriber(private val context: Context) {
    var isRunning = false
        private set
    var text = ""
        private set
    var isOnDevice = false
        private set
    var error: String? = null
        private set
    var onChange: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var finalized = StringBuilder()

    fun start() {
        if (isRunning) return
        error = null
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { error = "No speech recognizer on this device."; onChange?.invoke(); return }
        isOnDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = (if (isOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)).also {
            it.setRecognitionListener(listener)
        }
        isRunning = true
        listen()
        onChange?.invoke()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        recognizer?.let { it.stopListening(); it.destroy() }
        recognizer = null
        onChange?.invoke()
    }

    fun clear() { finalized = StringBuilder(); text = ""; onChange?.invoke() }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (Build.VERSION.SDK_INT >= 33) putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        }
        recognizer?.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(code: Int) {
            if (!isRunning) return
            when (code) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { error = "Microphone permission not granted."; stop() }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> restart(500)
                else -> restart(300) // no match, timeout: keep listening
            }
        }

        override fun onResults(results: Bundle?) {
            val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!best.isNullOrBlank()) finalized.append(best).append(' ')
            text = finalized.toString()
            onChange?.invoke()
            restart(50)
        }

        override fun onPartialResults(partial: Bundle?) {
            val p = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            text = finalized.toString() + p
            onChange?.invoke()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun restart(delayMillis: Long) {
        if (!isRunning) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (isRunning) { recognizer?.cancel(); listen() } }, delayMillis)
    }
}
