package dev.subvox.desktop

import dev.subvox.core.MotionSource
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Subvox for Windows (and any other JVM desktop). Same three screens as the macOS app.
 *
 * Motion: Windows has no headphone-motion API and Shokz headsets have no motion sensor, so the
 * live motion sources here are a replayed session or a UDP stream from the Android app.
 * Microphone: any Windows input, including a Shokz headset's hands-free endpoint.
 */
fun main(args: Array<String>) {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (e: Exception) { }
    val options = parseArgs(args)
    SwingUtilities.invokeLater {
        val model = AppModel()
        options.screen?.let { model.screen = it }
        options.import?.let { model.importSession(it) }
        options.replay?.let { model.replaySession(it, options.speed) }
        if (options.udp) model.listenUdp()
        if (options.auto) { model.decodeMode = dev.subvox.core.CaptureController.Mode.AUTO; model.capture.calibrate() }
        MainWindow(model).isVisible = true
    }
}

/**
 * Command line: `--replay <session folder> [--speed 1.0] [--import <session folder>] [--screen sensors|train|decode] [--udp] [--auto]`.
 * `--auto` starts the Decode screen's auto-detect segmentation and calibrates at once. `--import` turns a session's labelled marks into training templates before the window opens.
 * Lets a Windows machine open straight onto a replayed recording, and lets a test harness drive
 * the app without clicking.
 */
data class Options(val replay: java.io.File? = null, val speed: Double = 1.0, val screen: AppModel.Screen? = null, val udp: Boolean = false, val import: java.io.File? = null, val auto: Boolean = false)

fun parseArgs(args: Array<String>): Options {
    var o = Options()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--replay" -> { o = o.copy(replay = java.io.File(args.getOrNull(++i) ?: error("--replay needs a folder"))) }
            "--speed" -> { o = o.copy(speed = args.getOrNull(++i)?.toDoubleOrNull() ?: 1.0) }
            "--screen" -> { val name = args.getOrNull(++i); o = o.copy(screen = AppModel.Screen.values().firstOrNull { it.name.equals(name, ignoreCase = true) }) }
            "--udp" -> o = o.copy(udp = true)
            "--auto" -> o = o.copy(auto = true)
            "--import" -> { o = o.copy(import = java.io.File(args.getOrNull(++i) ?: error("--import needs a folder"))) }
            "--help", "-h" -> { println("Subvox desktop. Options: --replay <session folder> [--speed N] [--import <session folder>] [--screen sensors|train|decode] [--udp] [--auto]"); kotlin.system.exitProcess(0) }
        }
        i++
    }
    return o
}

class MainWindow(private val app: AppModel) : JFrame("Subvox") {
    private val cards = CardLayout()
    private val content = JPanel(cards)
    private val status = JLabel()
    private val recordButton = JButton("Record Session")

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(980, 680)
        setSize(1120, 760)
        setLocationRelativeTo(null)

        val screens = AppModel.Screen.values()
        val nav = JList(screens.map { it.title }.toTypedArray()).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = screens.indexOf(app.screen)
            fixedCellHeight = 30
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            addListSelectionListener { if (!it.valueIsAdjusting && selectedIndex >= 0) app.screen = screens[selectedIndex] }
        }
        content.add(SensorsPanel(app), AppModel.Screen.SENSORS.name)
        content.add(TrainPanel(app), AppModel.Screen.TRAIN.name)
        content.add(DecodePanel(app), AppModel.Screen.DECODE.name)

        recordButton.addActionListener {
            if (app.recorder.isRecording) app.recorder.stop() else app.recorder.start(app.audio, app.headset)
        }
        recordButton.toolTipText = "Record synchronized motion CSV + audio WAV to ${Platform.sessionsDir.path}"
        val toolbar = JToolBar().apply {
            isFloatable = false
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            add(status)
            add(javax.swing.Box.createHorizontalGlue())
            add(recordButton)
        }

        layout = BorderLayout()
        add(toolbar, BorderLayout.NORTH)
        add(nav.apply { preferredSize = Dimension(170, 0) }, BorderLayout.WEST)
        add(content, BorderLayout.CENTER)

        app.addListener { refresh() }
        Timer(250) { refresh() }.start() // keeps the recording timer and status fresh
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { app.shutdown() }
        })
        refresh()
    }

    private fun refresh() {
        cards.show(content, app.screen.name)
        title = "Subvox — ${app.screen.title}"
        val st = app.motionStatus
        val dot = when (st) { is MotionSource.Status.Streaming -> "●"; MotionSource.Status.Waiting -> "●"; else -> "●" }
        status.foreground = when (st) {
            is MotionSource.Status.Streaming -> Color(0x2E, 0xA0, 0x4A)
            MotionSource.Status.Waiting -> Color(0xE0, 0x8A, 0x1E)
            else -> Color(0xC0, 0x30, 0x30)
        }
        status.text = if (st is MotionSource.Status.Streaming) "$dot ${app.motion.name} · ${app.monitor.sampleRate.toInt()} Hz" else "$dot ${st.label}"
        recordButton.text = if (app.recorder.isRecording) "Stop Recording" else "Record Session"
        recordButton.foreground = if (app.recorder.isRecording) Color(0xC0, 0x30, 0x30) else null
    }
}
