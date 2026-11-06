package dev.subvox.desktop

import dev.subvox.core.CaptureController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class DecodePanel(private val app: AppModel) : JPanel(BorderLayout()) {
    private val warning = JLabel("Train at least two words in the Train tab first.").apply { foreground = Color(0xC0, 0x80, 0x10) }
    private val holdRadio = JRadioButton(CaptureController.Mode.HOLD.label, true)
    private val autoRadio = JRadioButton(CaptureController.Mode.AUTO.label)
    private val hold = HoldButton("Mouth a word", app.capture::begin, app.capture::end)
    private val calibrate = JButton("Calibrate noise floor")
    private val floorLabel = JLabel().apply { font = font.deriveFont(11f); foreground = Color.GRAY }
    private val autoState = JLabel()
    private val energyMeter = LevelMeter()
    private val triggerSlider = JSlider(15, 100, 30)
    private val triggerLabel = JLabel()
    private val autoPanel = JPanel(BorderLayout())
    private val captureArea = JPanel(BorderLayout())
    private val maxDistance = JSlider(20, 400, 150)
    private val minConfidence = JSlider(0, 50, 5)
    private val maxDistanceLabel = JLabel()
    private val minConfidenceLabel = JLabel()
    private val transcript = JTextPane().apply { isEditable = false; font = Font(Font.SANS_SERIF, Font.PLAIN, 26); preferredSize = Dimension(600, 110) }
    private val rankingPanel = JPanel(GridLayout(0, 1, 2, 4))
    private val lastInfo = JLabel().apply { font = font.deriveFont(11f); foreground = Color.GRAY }
    private val segmentPlot = SignalPlot("Rotation rate", SignalPlot.rotation, minScale = 0.05, height = 60)
    private val micButton = JButton("Start Mic")
    private val micLevel = LevelMeter()
    private val micText = JTextPane().apply { isEditable = false; preferredSize = Dimension(300, 80) }
    private val micError = JLabel().apply { foreground = Color(0xC0, 0x30, 0x30); font = font.deriveFont(11f) }

    init {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        ButtonGroup().apply { add(holdRadio); add(autoRadio) }
        holdRadio.addActionListener { app.decodeMode = CaptureController.Mode.HOLD }
        autoRadio.addActionListener { app.decodeMode = CaptureController.Mode.AUTO }
        calibrate.addActionListener { app.capture.calibrate() }
        triggerSlider.addChangeListener { app.capture.triggerRatio = triggerSlider.value / 10.0; refresh() }
        maxDistance.addChangeListener { app.maxDistance = maxDistance.value / 100.0 }
        minConfidence.addChangeListener { app.minConfidence = minConfidence.value / 100.0 }

        autoPanel.add(vbox(
            hbox(calibrate, floorLabel, Box.createHorizontalGlue(), autoState),
            energyMeter,
            hbox(JLabel("Trigger × floor"), triggerSlider, triggerLabel),
            caption("Calibrate first: keep still for 2 s so the detector learns your resting motion."),
        ), BorderLayout.CENTER)

        val controls = titled("Segmentation", vbox(
            hbox(holdRadio, autoRadio),
            captureArea,
            hbox(JLabel("Max distance"), maxDistance, maxDistanceLabel, Box.createHorizontalStrut(16), JLabel("Min confidence"), minConfidence, minConfidenceLabel),
        ).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })

        val clear = JButton("Clear").apply { addActionListener { app.clearDecoded() } }
        val transcriptCard = titled("Decoded from motion", vbox(
            hbox(Box.createHorizontalGlue(), clear),
            JScrollPane(transcript),
            caption("Faded words were rejected by the distance/confidence thresholds."),
        ).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })

        val lastCard = titled("Last segment", vbox(rankingPanel, lastInfo, segmentPlot).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })
        micButton.addActionListener { app.toggleMic() }
        val micClear = JButton("Clear").apply { addActionListener { app.transcriber.clear() } }
        val micCard = titled("Microphone transcript (reference)", vbox(
            hbox(micButton, micLevel, micClear),
            JScrollPane(micText),
            micError,
            caption("Whisper or speak to compare against the motion decoder. Offline recognition with Vosk; see docs/WINDOWS.md for the model download."),
        ).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })
        val bottom = JPanel(GridLayout(1, 2, 12, 0)).apply { add(lastCard); add(micCard) }

        val content = vbox(warning, controls, transcriptCard, bottom, gap = 14)
        add(scroll(content), BorderLayout.CENTER)
        app.addListener { refresh() }
        refresh()
    }

    private fun refresh() {
        warning.isVisible = app.classifier == null
        holdRadio.isSelected = app.decodeMode == CaptureController.Mode.HOLD
        autoRadio.isSelected = app.decodeMode == CaptureController.Mode.AUTO
        captureArea.removeAll()
        captureArea.add(if (app.decodeMode == CaptureController.Mode.HOLD) hold else autoPanel, BorderLayout.CENTER)
        captureArea.revalidate(); captureArea.repaint()
        hold.isCapturing = app.capture.isCapturing
        hold.isEnabled = app.classifier != null && app.motionStreaming

        val c = app.capture
        calibrate.text = if (c.isCalibrating) "Calibrating… stay still" else "Calibrate noise floor"
        calibrate.isEnabled = !c.isCalibrating && app.motionStreaming
        floorLabel.text = c.noiseFloor?.let { String.format("floor %.4f", it) } ?: ""
        autoState.text = if (c.isCapturing) "● Capturing" else "○ Listening"
        autoState.foreground = if (c.isCapturing) Color(0xC0, 0x30, 0x30) else Color.GRAY
        val scaleMax = maxOf((c.threshold ?: 0.05) * 2, 0.02)
        energyMeter.value = c.energy / scaleMax
        energyMeter.marker = c.threshold?.let { it / scaleMax }
        energyMeter.tint = if (c.isCapturing) Color(0xC0, 0x30, 0x30) else Color(0x2F, 0x6F, 0xE0)
        triggerLabel.text = String.format("%.1f×", c.triggerRatio)
        maxDistanceLabel.text = String.format("%.2f", app.maxDistance)
        minConfidenceLabel.text = String.format("%.2f", app.minConfidence)

        val doc = transcript.styledDocument
        doc.remove(0, doc.length)
        if (app.decoded.isEmpty()) {
            doc.insertString(0, "Mouth a trained word…", SimpleAttributeSet().apply { StyleConstants.setForeground(this, Color.LIGHT_GRAY) })
        } else for (w in app.decoded) {
            val attrs = SimpleAttributeSet().apply { StyleConstants.setForeground(this, if (w.prediction.accepted) transcript.foreground else Color(160, 160, 160, 120)) }
            doc.insertString(doc.length, w.text + " ", attrs)
        }

        rankingPanel.removeAll()
        val prediction = app.decoded.lastOrNull()?.prediction
        if (prediction == null) {
            rankingPanel.add(caption("DTW distance to each word's closest take appears here."))
            lastInfo.text = ""
        } else {
            val worst = prediction.ranked.lastOrNull()?.distance ?: 1.0
            for (cand in prediction.ranked) {
                val meter = LevelMeter().apply {
                    value = 1 - cand.distance / maxOf(worst * 1.2, 1e-6)
                    tint = if (cand == prediction.best) Color(0x2E, 0xA0, 0x4A) else Color.GRAY
                }
                rankingPanel.add(hbox(JLabel(cand.label).apply { preferredSize = Dimension(70, 18) }, meter,
                    JLabel(String.format("%.2f", cand.distance)).apply { preferredSize = Dimension(44, 18); font = font.deriveFont(11f) }))
            }
            lastInfo.text = String.format("Confidence %.2f · %s", prediction.confidence, if (prediction.accepted) "accepted" else "rejected")
        }
        rankingPanel.revalidate(); rankingPanel.repaint()
        segmentPlot.samples = app.lastSegment

        micButton.text = if (app.audio.isRunning) "Stop Mic" else "Start Mic"
        micLevel.value = app.micLevel.toDouble()
        micText.text = app.transcriber.text
        micError.text = app.audio.error ?: app.transcriber.error ?: ""
    }
}
