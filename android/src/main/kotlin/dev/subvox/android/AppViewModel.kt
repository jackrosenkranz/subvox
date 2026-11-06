package dev.subvox.android

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.subvox.core.ALL_FEATURES
import dev.subvox.core.CaptureController
import dev.subvox.core.Dataset
import dev.subvox.core.Evaluation
import dev.subvox.core.FeatureOption
import dev.subvox.core.FeatureSet
import dev.subvox.core.HeadsetProfile
import dev.subvox.core.MotionMonitor
import dev.subvox.core.MotionSample
import dev.subvox.core.MotionSource
import dev.subvox.core.Prediction
import dev.subvox.core.ReplayMotionSource
import dev.subvox.core.SessionReader
import dev.subvox.core.Template
import dev.subvox.core.TemplateClassifier
import dev.subvox.core.TemplateStore
import java.io.File

data class DecodedWord(val prediction: Prediction) { val text: String get() = prediction.best?.label ?: "?" }

enum class Screen(val title: String) { SENSORS("Sensors"), TRAIN("Train"), DECODE("Decode") }

enum class MotionChoice(val label: String) {
    HEAD_TRACKER("Headset head tracker"),
    PHONE("Phone IMU (held to the jaw)"),
    REPLAY("Replay last session"),
    NONE("None"),
}

/**
 * Android counterpart of the macOS `AppModel`. All state lives in Compose snapshot state and is
 * touched on the main thread; sensor and audio threads post through [main].
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app
    private val main = Handler(Looper.getMainLooper())
    private val store = TemplateStore(File(context.filesDir, "dataset.json"))

    val audio = AndroidAudioService(context)
    val transcriber = AndroidTranscriber(context)
    val capture = CaptureController()
    val recorder = AndroidSessionRecorder(context)
    val monitor = MotionMonitor()

    /** Bumped on every change so composables reading plain objects (capture, monitor) refresh. */
    var tick by mutableIntStateOf(0)
        private set
    private fun changed() { tick++ }

    var motion: MotionSource = PhoneMotionSource(context).also { it.stop() }
        private set
    var motionChoice by mutableStateOf(MotionChoice.NONE)
        private set
    var motionStatus by mutableStateOf<MotionSource.Status>(MotionSource.Status.Unsupported("Choose a motion source."))
        private set
    val motionStreaming: Boolean get() = motionStatus is MotionSource.Status.Streaming

    var screen by mutableStateOf(Screen.TRAIN)
    var dataset by mutableStateOf(store.load())
        private set
    var selectedWord by mutableStateOf(dataset.vocabulary.firstOrNull())
    var featureSet by mutableStateOf<FeatureSet>(ALL_FEATURES)
        private set
    var classifier by mutableStateOf<TemplateClassifier?>(null)
        private set
    var evaluation by mutableStateOf<Evaluation?>(null)
        private set
    var isEvaluating by mutableStateOf(false)
        private set
    var lastTemplateID by mutableStateOf<String?>(null)
        private set

    var decodeMode by mutableStateOf(CaptureController.Mode.HOLD)
    var maxDistance by mutableStateOf(1.5)
    var minConfidence by mutableStateOf(0.05)
    val decoded = mutableStateListOf<DecodedWord>()
    var lastSegment by mutableStateOf<List<MotionSample>>(emptyList())
        private set

    var devices by mutableStateOf(audio.devices())
        private set
    var selectedDevice by mutableStateOf(devices.firstOrNull { it.headset?.maker == "Shokz" } ?: devices.first())
    var micLevel by mutableStateOf(0f)
        private set
    val headset: HeadsetProfile? get() = audio.activeDevice?.headset ?: selectedDevice.headset

    var udpHost by mutableStateOf("")
    var udpSender by mutableStateOf<UdpMotionSender?>(null)
        private set

    init {
        rebuildClassifier()
        capture.onSegment = ::handleSegment
        capture.onChange = { changed() }
        audio.onChange = { main.post { changed() } }
        transcriber.onChange = { changed() }
        recorder.onChange = { changed() }
        audio.sink.onLevel = { l -> main.post { micLevel = l } }
    }

    // MARK: Motion

    fun chooseMotion(choice: MotionChoice) {
        motion.stop(); motion.onSample = null; motion.onStatus = null
        monitor.reset()
        motionChoice = choice
        val source: MotionSource? = when (choice) {
            MotionChoice.HEAD_TRACKER -> HeadTrackerMotionSource(context)
            MotionChoice.PHONE -> PhoneMotionSource(context)
            MotionChoice.REPLAY -> {
                val dir = recorder.root.listFiles()?.filter { File(it, "motion.csv").isFile }?.maxByOrNull { it.name }
                if (dir == null) { motionStatus = MotionSource.Status.Error("No recorded session to replay."); null }
                else ReplayMotionSource(SessionReader.motion(dir), dir.name, Clock::now)
            }
            MotionChoice.NONE -> { motionStatus = MotionSource.Status.Unsupported("No motion source selected. Shokz headsets have no motion sensor; use the phone IMU or a tracking headset."); null }
        }
        if (source == null) { changed(); return }
        motion = source
        motionStatus = source.status
        source.onStatus = { st -> main.post { motionStatus = st } }
        source.onSample = { s -> main.post { ingest(s) } }
        source.start()
        changed()
    }

    private fun ingest(sample: MotionSample) {
        monitor.ingest(sample)
        if (motionStatus !is MotionSource.Status.Streaming) motionStatus = MotionSource.Status.Streaming(motion.name)
        capture.ingest(sample)
        recorder.record(sample)
        udpSender?.send(sample)
        changed()
    }

    fun toggleUdp() {
        udpSender?.let { it.close(); udpSender = null; return }
        if (udpHost.isNotBlank()) udpSender = try { UdpMotionSender(udpHost.trim()) } catch (e: Exception) { null }
    }

    fun syncCaptureMode() { capture.autoEnabled = screen == Screen.DECODE && decodeMode == CaptureController.Mode.AUTO }

    private fun handleSegment(segment: List<MotionSample>) {
        lastSegment = segment
        when (screen) {
            Screen.TRAIN -> {
                val word = selectedWord ?: return
                addTemplate(Template(label = word, samples = segment))
                recorder.logEvent(segment, "train", word, null)
            }
            Screen.DECODE -> {
                val c = classifier ?: return
                val p = c.predict(segment, maxDistance, minConfidence)
                decoded += DecodedWord(p)
                recorder.logEvent(segment, if (p.accepted) "decode" else "reject", p.best?.label ?: "", p.confidence)
            }
            Screen.SENSORS -> recorder.logEvent(segment, "segment", recorder.prompt, null)
        }
    }

    /** Volume-down works as the hold key when the phone is in a pocket. */
    val holdKeyCapturesMotion: Boolean
        get() = when (screen) {
            Screen.TRAIN -> selectedWord != null && motionStreaming
            Screen.DECODE -> decodeMode == CaptureController.Mode.HOLD && classifier != null && motionStreaming
            Screen.SENSORS -> recorder.isRecording
        }

    // MARK: Dataset

    fun templates(word: String): List<Template> = dataset.templates(word)

    fun addWord(raw: String) {
        val word = raw.trim().lowercase()
        if (word.isEmpty() || word in dataset.vocabulary) return
        dataset = dataset.copy(vocabulary = dataset.vocabulary + word)
        selectedWord = word
        persist()
    }

    fun removeWord(word: String) {
        dataset = Dataset(dataset.vocabulary - word, dataset.templates.filter { it.label != word })
        if (selectedWord == word) selectedWord = dataset.vocabulary.firstOrNull()
        persist()
    }

    fun addTemplate(t: Template) {
        val vocab = if (t.label in dataset.vocabulary) dataset.vocabulary else dataset.vocabulary + t.label
        dataset = Dataset(vocab, dataset.templates + t)
        lastTemplateID = t.id
        persist()
    }

    fun deleteTemplate(id: String) { dataset = dataset.copy(templates = dataset.templates.filter { it.id != id }); persist() }

    fun importLastSession(): Int {
        val dir = recorder.root.listFiles()?.filter { File(it, "events.csv").isFile }?.maxByOrNull { it.name } ?: return 0
        val templates = SessionReader.templates(dir, fallbackLabel = selectedWord)
        if (templates.isEmpty()) return 0
        val vocab = dataset.vocabulary + templates.map { it.label }.filter { it !in dataset.vocabulary }.distinct()
        dataset = Dataset(vocab, dataset.templates + templates)
        lastTemplateID = templates.last().id
        persist()
        return templates.size
    }

    private fun persist() {
        try { store.save(dataset) } catch (e: Exception) { }
        rebuildClassifier()
    }

    private fun rebuildClassifier() {
        classifier = TemplateClassifier.build(dataset.templates, featureSet)
        evaluation = null
    }

    fun toggleFeature(option: FeatureOption, on: Boolean) {
        featureSet = if (on) featureSet + option else featureSet - option
        rebuildClassifier()
    }

    fun evaluate() {
        if (isEvaluating) return
        isEvaluating = true
        val templates = dataset.templates
        val set = featureSet
        Thread({
            val result = Evaluation.run(templates, set)
            main.post { evaluation = result; isEvaluating = false }
        }, "subvox-evaluate").apply { isDaemon = true }.start()
    }

    fun clearDecoded() { decoded.clear(); lastSegment = emptyList() }

    // MARK: Mic

    fun refreshDevices() { devices = audio.devices(); if (selectedDevice !in devices) selectedDevice = devices.first() }

    fun toggleMic() {
        if (audio.isRunning) audio.stop() else audio.start(selectedDevice)
        changed()
    }

    fun toggleTranscriber() {
        if (transcriber.isRunning) transcriber.stop() else transcriber.start()
        changed()
    }

    fun toggleRecording() {
        if (recorder.isRecording) recorder.stop() else recorder.start(audio, headset, motion.name)
        changed()
    }

    override fun onCleared() {
        if (recorder.isRecording) recorder.stop()
        transcriber.stop()
        audio.stop()
        motion.stop()
        udpSender?.close()
    }
}
