package dev.subvox.desktop

import dev.subvox.core.MotionSample
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.math.abs
import kotlin.math.max

/** Multi-channel line plot of motion samples, drawn with Java2D so it keeps up at sensor rate. */
class SignalPlot(
    var title: String,
    var channels: List<Channel>,
    var minScale: Double = 0.02,
    height: Int = 90,
    private val showsHeader: Boolean = true,
) : JComponent() {
    data class Channel(val name: String, val color: Color, val value: (MotionSample) -> Double)

    companion object {
        val acceleration = listOf(
            Channel("x", Color(0xE0, 0x3A, 0x3A)) { it.ax }, Channel("y", Color(0x2E, 0xA0, 0x4A)) { it.ay }, Channel("z", Color(0x2F, 0x6F, 0xE0)) { it.az })
        val rotation = listOf(
            Channel("x", Color(0xE0, 0x3A, 0x3A)) { it.rx }, Channel("y", Color(0x2E, 0xA0, 0x4A)) { it.ry }, Channel("z", Color(0x2F, 0x6F, 0xE0)) { it.rz })
        val attitude = listOf(
            Channel("pitch", Color(0xE0, 0x8A, 0x1E)) { it.pitch }, Channel("roll", Color(0x8E, 0x44, 0xC8)) { it.roll }, Channel("yaw", Color(0x1E, 0x9E, 0x9E)) { it.yaw })
    }

    var samples: List<MotionSample> = emptyList()
        set(value) { field = value; repaint() }
    /** Visible time span; defaults to the extent of [samples]. */
    var span: ClosedRange<Double>? = null

    init {
        preferredSize = Dimension(400, height + if (showsHeader) 18 else 0)
        minimumSize = Dimension(120, height)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val headerH = if (showsHeader) 18 else 0
        val range = span ?: ((samples.firstOrNull()?.t ?: 0.0)..(samples.lastOrNull()?.t ?: 1.0))
        val visible = samples.filter { it.t in range }
        val scale = max(minScale, channels.flatMap { c -> visible.map { abs(c.value(it)) } }.maxOrNull() ?: 0.0)

        if (showsHeader) {
            g2.font = font.deriveFont(Font.BOLD, 11f)
            g2.color = foreground
            g2.drawString(title, 2, 12)
            var x = 2 + g2.fontMetrics.stringWidth(title) + 10
            g2.font = font.deriveFont(10f)
            for (c in channels) {
                g2.color = c.color
                g2.fillOval(x, 5, 7, 7)
                g2.drawString(c.name, x + 10, 12)
                x += 16 + g2.fontMetrics.stringWidth(c.name) + 8
            }
            g2.color = Color.GRAY
            val s = String.format("±%.3f", scale)
            g2.drawString(s, width - g2.fontMetrics.stringWidth(s) - 4, 12)
        }

        val plotH = height - headerH
        g2.color = Color(0, 0, 0, 14)
        g2.fill(RoundRectangle2D.Double(0.0, headerH.toDouble(), width.toDouble(), plotH.toDouble(), 8.0, 8.0))
        val mid = headerH + plotH / 2.0
        g2.color = Color(128, 128, 128, 80)
        g2.drawLine(0, mid.toInt(), width, mid.toInt())

        val widthT = max(range.endInclusive - range.start, 1e-3)
        g2.stroke = BasicStroke(1.2f)
        for (c in channels) {
            val path = Path2D.Double()
            for ((i, s) in visible.withIndex()) {
                val px = (s.t - range.start) / widthT * width
                val py = mid - c.value(s) / scale * (plotH / 2.0 - 2)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            g2.color = c.color
            g2.draw(path)
        }
    }
}

/** Horizontal 0...1 meter with an optional threshold marker. */
class LevelMeter : JComponent() {
    var value: Double = 0.0
        set(v) { field = v.coerceIn(0.0, 1.0); repaint() }
    var marker: Double? = null
        set(v) { field = v; repaint() }
    var tint: Color = Color(0x2E, 0xA0, 0x4A)
        set(v) { field = v; repaint() }

    init { preferredSize = Dimension(160, 10); minimumSize = Dimension(40, 10) }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val h = height.toDouble()
        g2.color = Color(0, 0, 0, 25)
        g2.fill(RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), h, h, h))
        g2.color = tint
        g2.fill(RoundRectangle2D.Double(0.0, 0.0, width * value, h, h, h))
        marker?.let {
            g2.color = foreground
            val x = (width * it.coerceIn(0.0, 1.0)).toInt()
            g2.fillRect(x - 1, 0, 2, height)
        }
    }
}

/** Mouse alternative to holding Space. */
class HoldButton(var title: String, val onBegin: () -> Unit, val onEnd: () -> Unit) : JPanel() {
    var isCapturing = false
        set(v) { field = v; refresh() }
    private val icon = JLabel("", SwingConstants.CENTER)
    private val label = JLabel(title, SwingConstants.CENTER)
    private val hint = JLabel("Hold Space or press and hold here", SwingConstants.CENTER)
    private var pressed = false

    init {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        isOpaque = false
        icon.font = icon.font.deriveFont(28f)
        label.font = label.font.deriveFont(Font.BOLD, 15f)
        hint.font = hint.font.deriveFont(11f)
        hint.foreground = Color.GRAY
        for (c in listOf(icon, label, hint)) { c.alignmentX = CENTER_ALIGNMENT; add(c) }
        preferredSize = Dimension(400, 120)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (isEnabled && !pressed) { pressed = true; onBegin() } }
            override fun mouseReleased(e: MouseEvent) { if (pressed) { pressed = false; onEnd() } }
        })
        refresh()
    }

    private fun refresh() {
        icon.text = if (isCapturing) "●" else "○"
        icon.foreground = if (isCapturing) Color(0xD0, 0x30, 0x30) else Color(0x2F, 0x6F, 0xE0)
        label.text = if (isCapturing) "Capturing…" else title
        repaint()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (c in components) c.isEnabled = enabled
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val fill = if (isCapturing) Color(0xD0, 0x30, 0x30, 46) else Color(0x2F, 0x6F, 0xE0, 26)
        val edge = if (isCapturing) Color(0xD0, 0x30, 0x30) else Color(0x2F, 0x6F, 0xE0, 110)
        g2.color = if (isEnabled) fill else Color(128, 128, 128, 20)
        g2.fill(RoundRectangle2D.Double(0.0, 0.0, width - 1.0, height - 1.0, 14.0, 14.0))
        g2.color = if (isEnabled) edge else Color(128, 128, 128, 60)
        g2.stroke = BasicStroke(if (isCapturing) 2f else 1f)
        g2.draw(RoundRectangle2D.Double(0.0, 0.0, width - 1.0, height - 1.0, 14.0, 14.0))
        super.paintComponent(g)
    }
}

/** A panel that fills the scroll pane's width, so only vertical scrolling happens. */
class VerticalScrollable(content: JComponent) : JPanel(java.awt.BorderLayout()), javax.swing.Scrollable {
    init { add(content, java.awt.BorderLayout.CENTER) }
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = 16
    override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = 120
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

fun scroll(content: JComponent): javax.swing.JScrollPane = javax.swing.JScrollPane(VerticalScrollable(content)).apply {
    border = null
    horizontalScrollBarPolicy = javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    verticalScrollBar.unitIncrement = 16
}

fun titled(title: String, content: JComponent): JPanel = JPanel(java.awt.BorderLayout()).apply {
    border = BorderFactory.createTitledBorder(title)
    add(content, java.awt.BorderLayout.CENTER)
}

fun caption(text: String, width: Int = 420): JLabel = JLabel("<html><body style='width:${width}px'>$text</body></html>").apply {
    font = font.deriveFont(11f)
    foreground = Color.GRAY
}

fun vbox(vararg items: java.awt.Component, gap: Int = 8): JPanel = JPanel().apply {
    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
    for ((i, c) in items.withIndex()) {
        (c as? JComponent)?.alignmentX = JComponent.LEFT_ALIGNMENT
        add(c)
        if (i < items.lastIndex) add(javax.swing.Box.createVerticalStrut(gap))
    }
}

fun hbox(vararg items: java.awt.Component, gap: Int = 8): JPanel = JPanel().apply {
    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
    for ((i, c) in items.withIndex()) {
        add(c)
        if (i < items.lastIndex) add(javax.swing.Box.createHorizontalStrut(gap))
    }
}
