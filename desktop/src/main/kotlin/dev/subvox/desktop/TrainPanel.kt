package dev.subvox.desktop

import dev.subvox.core.Evaluation
import dev.subvox.core.FeatureOption
import dev.subvox.core.Template
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JMenuItem
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

class TrainPanel(private val app: AppModel) : JPanel(BorderLayout()) {
    private val vocabModel = DefaultListModel<String>()
    private val vocabList = JList(vocabModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val newWord = JTextField()
    private val instruction = caption("")
    private val hold = HoldButton("Mouth a word", app.capture::begin, app.capture::end)
    private val motionWarning = JLabel().apply { foreground = Color(0xC0, 0x80, 0x10) }
    private val takesPanel = JPanel(GridLayout(0, 3, 10, 10))
    private val takesTitle = titled("Takes", takesPanel)
    private val evalButton = JButton("Evaluate")
    private val evalResult = JPanel(BorderLayout())
    private val featureBoxes = FeatureOption.values().associateWith { JCheckBox(it.name.lowercase().replaceFirstChar(Char::uppercase), true) }
    private var suppress = false

    init {
        vocabList.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                val word = value as String
                super.getListCellRendererComponent(list, "$word  (${app.templates(word).size})", index, isSelected, cellHasFocus); return this
            }
        }
        vocabList.addListSelectionListener { if (!suppress && !it.valueIsAdjusting) app.selectedWord = vocabList.selectedValue }
        vocabList.componentPopupMenu = JPopupMenu().apply {
            add(JMenuItem("Delete word and its takes").apply { addActionListener { vocabList.selectedValue?.let(app::removeWord) } })
        }
        val addButton = JButton("Add").apply { addActionListener { addWord() } }
        newWord.addActionListener { addWord() }
        val left = JPanel(BorderLayout()).apply {
            add(JScrollPane(vocabList), BorderLayout.CENTER)
            add(hbox(newWord, addButton).apply { border = BorderFactory.createEmptyBorder(8, 8, 8, 8) }, BorderLayout.SOUTH)
            preferredSize = Dimension(220, 400)
        }

        val features = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("Features:"))
            for ((opt, box) in featureBoxes) { box.addActionListener { app.toggleFeature(opt, box.isSelected) }; add(box) }
            add(Box.createHorizontalStrut(16))
            add(evalButton)
        }
        evalButton.addActionListener { app.evaluate() }
        val evalCard = titled("Is there signal? (leave-one-out + permutation test)", vbox(features, evalResult).apply { border = BorderFactory.createEmptyBorder(6, 6, 6, 6) })

        val right = vbox(instruction, hold, motionWarning, takesTitle, evalCard, gap = 12).apply { border = BorderFactory.createEmptyBorder(16, 16, 16, 16) }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, scroll(right)).apply { dividerLocation = 230 }, BorderLayout.CENTER)
        app.addListener { refresh() }
        refresh()
    }

    private fun addWord() {
        app.addWord(newWord.text)
        newWord.text = ""
    }

    private fun refresh() {
        suppress = true
        val vocab = app.dataset.vocabulary
        if (vocabModel.size != vocab.size || (0 until vocabModel.size).any { vocabModel[it] != vocab[it] }) {
            vocabModel.clear(); vocab.forEach(vocabModel::addElement)
        }
        if (vocabList.selectedValue != app.selectedWord) vocabList.setSelectedValue(app.selectedWord, true)
        vocabList.repaint()
        suppress = false

        val word = app.selectedWord ?: "—"
        instruction.text = "<html><body style='width:480px'>Silently mouth “$word” while holding Space. Keep your head still; aim for 8 to 10 takes per word. " +
            "Or import the labelled takes of a recorded session from the Sensors screen.</body></html>"
        hold.title = "Mouth “$word”"
        hold.isCapturing = app.capture.isCapturing
        hold.isEnabled = app.selectedWord != null && app.motionStreaming
        motionWarning.text = if (app.motionStreaming) "" else app.motionStatus.label

        val takes = app.selectedWord?.let(app::templates)?.asReversed() ?: emptyList()
        (takesTitle.border as javax.swing.border.TitledBorder).title = "Takes for “$word” (${takes.size})"
        takesPanel.removeAll()
        if (takes.isEmpty()) takesPanel.add(JLabel("No takes yet.").apply { foreground = Color.GRAY })
        for (t in takes) takesPanel.add(takeTile(t))
        takesPanel.revalidate(); takesPanel.repaint()

        for ((opt, box) in featureBoxes) box.isSelected = opt in app.featureSet
        evalButton.text = if (app.isEvaluating) "Evaluating…" else "Evaluate"
        evalButton.isEnabled = !app.isEvaluating && app.featureSet.isNotEmpty()
        evalResult.removeAll()
        val result = app.evaluation
        evalResult.add(if (result == null) caption("Needs at least 2 words with at least 2 takes each. Each take is classified against all the others; the same is repeated with shuffled labels to show what chance looks like.")
        else evaluationView(result), BorderLayout.CENTER)
        evalResult.revalidate(); evalResult.repaint()
    }

    private fun takeTile(t: Template): JPanel {
        val plot = SignalPlot("", SignalPlot.rotation, minScale = 0.05, height = 44, showsHeader = false).apply { samples = t.samples; preferredSize = Dimension(150, 44) }
        val info = JLabel(String.format("%.2f s · %d", t.duration, t.samples.size)).apply { font = font.deriveFont(10f); foreground = Color.GRAY }
        val delete = JButton("Delete").apply { font = font.deriveFont(10f); addActionListener { app.deleteTemplate(t.id) } }
        return JPanel(BorderLayout(4, 4)).apply {
            add(plot, BorderLayout.CENTER)
            add(hbox(info, Box.createHorizontalGlue(), delete), BorderLayout.SOUTH)
            border = BorderFactory.createLineBorder(if (t.id == app.lastTemplateID) Color(0x2F, 0x6F, 0xE0) else Color(0, 0, 0, 30), if (t.id == app.lastTemplateID) 2 else 1, true)
        }
    }

    private fun evaluationView(r: Evaluation): JPanel {
        fun pct(v: Double) = String.format("%.0f%%", v * 100)
        fun metric(title: String, value: String, big: Boolean = false) = vbox(
            JLabel(title).apply { font = font.deriveFont(11f); foreground = Color.GRAY },
            JLabel(value).apply { font = font.deriveFont(if (big) Font.BOLD else Font.PLAIN, if (big) 20f else 16f) }, gap = 0)
        val metrics = hbox(
            metric("Accuracy", pct(r.accuracy), big = true), metric("Chance", pct(r.chance)),
            metric("Shuffled 95th pct", pct(r.permutationP95)),
            metric("p-value", if (r.pValue < 0.001) "< 0.001" else String.format("%.3f", r.pValue)), gap = 24)
        val verdict = JLabel(if (r.isSignificant) "Motion carries word information beyond chance across ${r.templateCount} takes."
        else "Not distinguishable from chance yet. Record more takes or try other feature sets.").apply {
            foreground = if (r.isSignificant) Color(0x2E, 0x8A, 0x3A) else Color(0xC0, 0x80, 0x10)
        }
        val n = r.labels.size
        val grid = JPanel(GridLayout(n + 1, n + 1, 2, 2))
        grid.add(JLabel("actual ↓ / predicted →").apply { font = font.deriveFont(10f); foreground = Color.GRAY })
        for (l in r.labels) grid.add(JLabel(l, SwingConstants.CENTER).apply { font = font.deriveFont(10f) })
        for (row in 0 until n) {
            grid.add(JLabel(r.labels[row], SwingConstants.RIGHT).apply { font = font.deriveFont(10f) })
            val total = maxOf(r.confusion[row].sum(), 1)
            for (col in 0 until n) {
                val count = r.confusion[row][col]
                val base = if (row == col) Color(0x2E, 0xA0, 0x4A) else Color(0xE0, 0x3A, 0x3A)
                grid.add(JLabel(count.toString(), SwingConstants.CENTER).apply {
                    isOpaque = true
                    background = Color(base.red, base.green, base.blue, (count.toDouble() / total * 178).toInt())
                    preferredSize = Dimension(36, 24)
                })
            }
        }
        grid.maximumSize = Dimension((n + 1) * 60, (n + 1) * 26)
        return vbox(metrics, verdict, hbox(grid, Box.createHorizontalGlue()), gap = 10)
    }
}
