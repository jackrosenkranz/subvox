package dev.subvox.desktop

import dev.subvox.core.ALL_FEATURES
import dev.subvox.core.CaptureController
import dev.subvox.core.Dataset
import dev.subvox.core.Evaluation
import dev.subvox.core.FeatureOption
import dev.subvox.core.FeatureSet
import dev.subvox.core.HeadsetProfile
import dev.subvox.core.Headsets
import dev.subvox.core.MotionMonitor
import dev.subvox.core.MotionSample
import dev.subvox.core.MotionSource
import dev.subvox.core.Prediction
import dev.subvox.core.ReplayMotionSource
import dev.subvox.core.SessionReader
import dev.subvox.core.Template
import dev.subvox.core.TemplateClassifier
import dev.subvox.core.TemplateStore
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

data class DecodedWord(val prediction: Prediction) {
    val text: String get() = prediction.best?.label ?: "?"
}

/**
 * Desktop counterpart of the macOS `AppModel`. Every field is read and written on the Swing
 * event thread; motion and audio threads hand their samples over with `SwingUtilities.invokeLater`.
 */
class AppModel {
    enum class Screen(val title: String) { SENSORS("Sensors"), TRAIN("Train"), DECODE("Decode") }

    val audio = AudioService()
    val transcriber = Transcriber()
    val capture = CaptureController()
    val recorder = SessionRecorder()
    val monitor = MotionMonitor()
    private val store = TemplateStore(Platform.datasetFile)

    var motion: MotionSource = NoMotionSource(if (Platform.isWindows) NoMotionSource.windowsReason else "No live headphone-motion source on this platform; replay a session or receive UDP.")
        private set
    var motionStatus: MotionSource.Status = motion.status
        private set

    var screen: Screen = Screen.TRAIN
        set(value) { field = value; syncCaptureMode(); changed() }

    // Training
    var dataset: Dataset = store.load()
        private set
    var selectedWord: String? = dataset.vocabulary.firstOrNull()
        set(value) { field = value; changed() }
    var featureSet: FeatureSet = ALL_FEATURES
        set(value) { field = value; rebuildClassifier(); changed() }
    var classifier: TemplateClassifier? = null
        private set
    var evaluation: Evaluation? = null
        private set
    var isEvaluating = false
        private set
    var lastTemplateID: String? = null
        private set

    // Decoding
    var decodeMode: CaptureController.Mode = CaptureController.Mode.HOLD
        set(value) { field = value; syncCaptureMode(); changed() }
    var maxDistance = 1.5
        set(value) { field = value; changed() }
    var minConfidence = 0.05
        set(value) { field = value; changed() }
    val decoded = ArrayList<DecodedWord>()
    var lastSegment: List<MotionSample> = emptyList()
        private set

    /** The headset the active microphone belongs to, when the OS name is recognizable. */
    val headset: HeadsetProfile? get() = audio.activeDevice?.headset ?: selectedDevice?.headset
    var selectedDevice: AudioService.Device? = null
        set(value) { field = value; changed() }

    private val listeners = ArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() { for (l in listeners.toList()) l() }

    init {
        rebuildClassifier()
        capture.onSegment = ::handleSegment
        capture.onChange = { changed() }
        audio.onChange = { SwingUtilities.invokeLater { changed() } }
        transcriber.onChange = { SwingUtilities.invokeLater { changed() } }
        recorder.onChange = { changed() }
        audio.sink.onLevel = { level -> SwingUtilities.invokeLater { micLevel = level; changed() } }
        installKeyDispatcher()
    }

    var micLevel = 0f
        private set

    // MARK: Motion sources

    fun useMotionSource(source: MotionSource) {
        motion.stop()
        motion.onSample = null
        motion.onStatus = null
        monitor.reset()
        motion = source
        motionStatus = source.status
        source.onStatus = { st -> SwingUtilities.invokeLater { motionStatus = st; changed() } }
        source.onSample = { s -> SwingUtilities.invokeLater { ingest(s) } }
        source.start()
        changed()
    }

    fun replaySession(directory: File, speed: Double = 1.0) {
        val samples = SessionReader.motion(directory)
        useMotionSource(ReplayMotionSource(samples, directory.name, Platform::clock, speed = speed, loop = true))
    }

    fun listenUdp(port: Int = UdpMotionSource.DEFAULT_PORT) = useMotionSource(UdpMotionSource(port))

    fun stopMotion() = useMotionSource(NoMotionSource(if (Platform.isWindows) NoMotionSource.windowsReason else "Motion source stopped."))

    private fun ingest(sample: MotionSample) {
        monitor.ingest(sample)
        if (motionStatus !is MotionSource.Status.Streaming) motionStatus = MotionSource.Status.Streaming(motion.name)
        capture.ingest(sample)
        recorder.record(sample)
        changed()
    }

    val motionStreaming: Boolean get() = motionStatus is MotionSource.Status.Streaming

    private fun syncCaptureMode() {
        capture.autoEnabled = screen == Screen.DECODE && decodeMode == CaptureController.Mode.AUTO
    }

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
                val prediction = c.predict(segment, maxDistance, minConfidence)
                decoded += DecodedWord(prediction)
                recorder.logEvent(segment, if (prediction.accepted) "decode" else "reject", prediction.best?.label ?: "", prediction.confidence)
            }
            Screen.SENSORS -> recorder.logEvent(segment, "segment", recorder.prompt, null)
        }
        changed()
    }

    // MARK: Space bar = hold to capture

    private fun installKeyDispatcher() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(KeyEventDispatcher { e ->
            if (e.keyCode != KeyEvent.VK_SPACE) return@KeyEventDispatcher false
            if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is JTextComponent) return@KeyEventDispatcher false
            if (!spaceCapturesMotion) return@KeyEventDispatcher false
            when (e.id) {
                KeyEvent.KEY_PRESSED -> capture.begin()
                KeyEvent.KEY_RELEASED -> capture.end()
            }
            true
        })
    }

    private val spaceCapturesMotion: Boolean
        get() = when (screen) {
            Screen.TRAIN -> selectedWord != null && motionStreaming
            Screen.DECODE -> decodeMode == CaptureController.Mode.HOLD && classifier != null && motionStreaming
            Screen.SENSORS -> recorder.isRecording
        }

    // MARK: Dataset editing

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

    fun addTemplate(template: Template) {
        val vocab = if (template.label in dataset.vocabulary) dataset.vocabulary else dataset.vocabulary + template.label
        dataset = Dataset(vocab, dataset.templates + template)
        lastTemplateID = template.id
        persist()
    }

    fun addTemplates(templates: List<Template>) {
        if (templates.isEmpty()) return
        val vocab = dataset.vocabulary + templates.map { it.label }.filter { it !in dataset.vocabulary }.distinct()
        dataset = Dataset(vocab, dataset.templates + templates)
        lastTemplateID = templates.last().id
        persist()
    }

    fun deleteTemplate(id: String) {
        dataset = dataset.copy(templates = dataset.templates.filter { it.id != id })
        persist()
    }

    /** Imports the labelled takes of a recorded session (from any platform) as templates. */
    fun importSession(directory: File): Int {
        val templates = SessionReader.templates(directory, fallbackLabel = selectedWord)
        addTemplates(templates)
        return templates.size
    }

    private fun persist() {
        try { store.save(dataset) } catch (e: Exception) { System.err.println("Subvox: failed to save dataset: ${e.message}") }
        rebuildClassifier()
        changed()
    }

    private fun rebuildClassifier() {
        classifier = TemplateClassifier.build(dataset.templates, featureSet)
        evaluation = null
    }

    fun toggleFeature(option: FeatureOption, on: Boolean) {
        featureSet = if (on) featureSet + option else featureSet - option
    }

    fun evaluate() {
        if (isEvaluating) return
        isEvaluating = true
        changed()
        val templates = dataset.templates
        val set = featureSet
        Thread({
            val result = Evaluation.run(templates, set)
            SwingUtilities.invokeLater { evaluation = result; isEvaluating = false; changed() }
        }, "subvox-evaluate").apply { isDaemon = true }.start()
    }

    // MARK: Decode transcript

    val transcript: String get() = decoded.filter { it.prediction.accepted }.joinToString(" ") { it.text }

    fun clearDecoded() {
        decoded.clear()
        lastSegment = emptyList()
        changed()
    }

    // MARK: Mic

    fun toggleMic() {
        if (audio.isRunning) {
            transcriber.stop()
            audio.stop()
        } else {
            val device = selectedDevice ?: AudioService.Device(null, "System default input")
            audio.start(device)
            if (audio.isRunning) transcriber.start(audio.sink)
        }
        changed()
    }

    fun shutdown() {
        if (recorder.isRecording) recorder.stop()
        transcriber.stop()
        audio.stop()
        motion.stop()
    }
}
