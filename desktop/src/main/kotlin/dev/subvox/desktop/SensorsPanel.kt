package dev.subvox.desktop

import dev.subvox.core.Condition
import dev.subvox.core.MotionSource
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingConstants

class SensorsPanel(private val app: AppModel) : JPanel(BorderLayout()) {
    private val status = JLabel()
    private val rate = JLabel()
    private val count = JLabel()
    private val gravity = JLabel()
    private val motionHint = caption("", 330)
    private val accelPlot = SignalPlot("User acceleration (g)", SignalPlot.acceleration, minScale = 0.01)
    private val rotPlot = SignalPlot("Rotation rate (rad/s)", SignalPlot.rotation, minScale = 0.05)
    private val attPlot = SignalPlot("Attitude (rad)", SignalPlot.attitude, minScale = 0.1)

    private val deviceBox = JComboBox<AudioService.Device>()
    private val micButton = JButton("Start Mic")
    private val micLevel = LevelMeter()
    private val micInfo = caption("", 330)
    private val micError = JLabel().apply { foreground = Color(0xC0, 0x30, 0x30) }
    private val headsetInfo = caption("", 330)

    private val conditionBox = JComboBox(Condition.values()).apply { renderer = javax.swing.DefaultListCellRenderer().also { r -> } }
    private val promptField = JTextField()
    private val recordButton = JButton("Record")
    private val recordInfo = JLabel()
    private val showButton = JButton("Show Last Session")
    private val recordHint = caption("")

    init {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        conditionBox.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                super.getListCellRendererComponent(list, (value as? Condition)?.label ?: value, index, isSelected, cellHasFocus); return this
            }
        }
        conditionBox.selectedItem = app.recorder.condition

        val replayButton = JButton("Replay session…").apply { addActionListener { chooseSession()?.let(app::replaySession) } }
        val udpButton = JButton("Listen on UDP ${UdpMotionSource.DEFAULT_PORT}").apply { addActionListener { app.listenUdp() } }
        val stopMotion = JButton("Stop").apply { addActionListener { app.stopMotion() } }
        val motionCard = titled("Headphone motion", vbox(
            grid("Status" to status, "Sample rate" to rate, "Samples" to count, "Gravity" to gravity),
            hbox(replayButton, udpButton, stopMotion),
            motionHint,
        ).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })

        micButton.addActionListener { app.toggleMic() }
        deviceBox.addActionListener { app.selectedDevice = deviceBox.selectedItem as? AudioService.Device }
        val refresh = JButton("Refresh").apply { addActionListener { reloadDevices() } }
        val micCard = titled("Microphone", vbox(
            hbox(JLabel("Input"), deviceBox, refresh),
            hbox(micButton, micLevel),
            micInfo, micError, headsetInfo,
        ).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })

        val top = JPanel(GridLayout(1, 2, 12, 0)).apply { add(motionCard); add(micCard) }

        val plots = titled("Live motion (last 8 s)", vbox(accelPlot, rotPlot, attPlot).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })

        recordButton.addActionListener {
            if (app.recorder.isRecording) app.recorder.stop() else {
                app.recorder.condition = conditionBox.selectedItem as Condition
                app.recorder.prompt = promptField.text
                app.recorder.start(app.audio, app.headset)
            }
            refresh()
        }
        showButton.addActionListener { app.recorder.lastSessionDir?.let(Platform::reveal) }
        val importButton = JButton("Import session takes…").apply {
            toolTipText = "Turn the labelled marks of a recorded session (from any platform) into training templates"
            addActionListener {
                chooseSession()?.let { dir ->
                    val n = app.importSession(dir)
                    javax.swing.JOptionPane.showMessageDialog(this@SensorsPanel, "Imported $n take(s) from ${dir.name}.")
                }
            }
        }
        val recordCard = titled("Experiment recording", vbox(
            hbox(JLabel("Condition"), conditionBox),
            promptField.apply { toolTipText = "Prompt / what you're saying (e.g. yes no yes stop)" },
            hbox(recordButton, recordInfo, Box.createHorizontalGlue(), showButton, importButton),
            recordHint,
        ).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })

        val content = vbox(top, plots, recordCard, gap = 14)
        add(scroll(content), BorderLayout.CENTER)
        reloadDevices()
        app.addListener { refresh() }
        refresh()
    }

    private fun chooseSession(): java.io.File? {
        val chooser = JFileChooser(Platform.sessionsDir.also { it.mkdirs() }).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose a Subvox session folder (contains motion.csv)"
        }
        return if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun reloadDevices() {
        val devices = app.audio.devices()
        deviceBox.removeAllItems()
        for (d in devices) deviceBox.addItem(d)
        val shokz = devices.firstOrNull { it.headset?.maker == "Shokz" }
        if (shokz != null) deviceBox.selectedItem = shokz
        app.selectedDevice = deviceBox.selectedItem as? AudioService.Device
    }

    private fun refresh() {
        val st = app.motionStatus
        status.text = st.label
        rate.text = String.format("%.1f Hz", app.monitor.sampleRate)
        count.text = app.monitor.sampleCount.toString()
        gravity.text = app.monitor.latest?.let { String.format("%.2f, %.2f, %.2f", it.gx, it.gy, it.gz) } ?: "—"
        motionHint.text = when (st) {
            is MotionSource.Status.Unsupported -> "<html><body style='width:330px'>${st.reason}</body></html>"
            MotionSource.Status.Waiting -> "<html><body style='width:330px'>Waiting for samples from ${app.motion.name}.</body></html>"
            else -> "<html><body style='width:330px'>Source: ${app.motion.name}</body></html>"
        }
        val latest = app.monitor.latest
        val span = latest?.let { (it.t - 8)..it.t }
        for (p in listOf(accelPlot, rotPlot, attPlot)) { p.span = span; p.samples = app.monitor.history }

        micButton.text = if (app.audio.isRunning) "Stop Mic" else "Start Mic"
        micLevel.value = app.micLevel.toDouble()
        deviceBox.isEnabled = !app.audio.isRunning
        val fmt = app.audio.activeFormat
        micInfo.text = "<html><body style='width:330px'>" + (if (app.audio.isRunning && fmt != null)
            "Capturing from ${app.audio.activeDevice?.name} at ${fmt.sampleRate.toInt()} Hz, ${fmt.channels} ch." +
                (if (fmt.sampleRate <= 16000f) " That is the Bluetooth hands-free (HFP) link: mono, call-grade bandwidth." else "")
        else "On Windows, a Bluetooth headset's microphone is the 'Hands-Free AG Audio' endpoint. Selecting it switches the headset to the HFP profile and lowers playback quality while open.") + "</body></html>"
        micError.text = app.audio.error ?: app.transcriber.error ?: ""
        val hs = app.headset
        headsetInfo.text = if (hs == null) "" else "<html><body style='width:330px'><b>${hs.name}</b><br>${hs.mic.description}<br>" +
            (hs.mic.sensitivity?.let { "Mic sensitivity (spec sheet): $it<br>" } ?: "") +
            "Motion: ${hs.motion.label}.</body></html>"

        val rec = app.recorder
        recordButton.text = if (rec.isRecording) "Stop" else "Record"
        recordButton.foreground = if (rec.isRecording) Color(0xC0, 0x30, 0x30) else null
        conditionBox.isEnabled = !rec.isRecording
        recordInfo.text = if (rec.isRecording) {
            val secs = rec.startedClock?.let { Platform.clock() - it } ?: 0.0
            String.format("%d:%02d · %d samples · %d marks", (secs / 60).toInt(), (secs % 60).toInt(), rec.motionCount, rec.eventCount)
        } else ""
        showButton.isEnabled = rec.lastSessionDir != null
        recordHint.text = "<html><body style='width:600px'>" + (if (app.audio.isRunning)
            "Motion + audio will be recorded (audio.wav). While recording, hold Space to mark each utterance."
        else "Motion only. Start the mic above to also record audio. Hold Space to mark each utterance.") +
            (rec.error?.let { "<br><font color='#c03030'>$it</font>" } ?: "") + "</body></html>"
    }

    private fun grid(vararg rows: Pair<String, JLabel>): JPanel = JPanel(GridLayout(rows.size, 2, 8, 2)).apply {
        for ((k, v) in rows) { add(JLabel(k).apply { foreground = Color.GRAY }); add(v.apply { horizontalAlignment = SwingConstants.LEFT }) }
        maximumSize = Dimension(Int.MAX_VALUE, rows.size * 20)
    }
}
