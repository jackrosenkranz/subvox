package dev.subvox.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.subvox.core.Evaluation
import dev.subvox.core.FeatureOption

@Composable
fun TrainScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    vm.tick
    var newWord by remember { mutableStateOf("") }
    val word = vm.selectedWord
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Vocabulary") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    items(vm.dataset.vocabulary) { w ->
                        FilterChip(selected = w == word, onClick = { vm.selectedWord = w }, label = { Text("$w (${vm.templates(w).size})") })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newWord, { newWord = it }, label = { Text("Add word") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addWord(newWord); newWord = "" }, enabled = newWord.isNotBlank()) { Text("Add") }
                word?.let { TextButton(onClick = { vm.removeWord(it) }) { Text("Delete “$it”") } }
            }
        }

        Caption("Silently mouth “${word ?: "—"}” while holding the button or volume-down. Keep your head still; aim for 8 to 10 takes per word.")
        HoldButton("Mouth “${word ?: "—"}”", vm.capture.isCapturing, enabled = word != null && vm.motionStreaming, onBegin = vm.capture::begin, onEnd = vm.capture::end)
        if (!vm.motionStreaming) Caption(vm.motionStatus.label, Color(0xFFC08010))

        word?.let { w ->
            val takes = vm.templates(w).asReversed()
            Section("Takes for “$w” (${takes.size})") {
                if (takes.isEmpty()) Caption("No takes yet.")
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(takes, key = { it.id }) { t ->
                        Column(Modifier.width(150.dp).border(if (t.id == vm.lastTemplateID) 2.dp else 1.dp, if (t.id == vm.lastTemplateID) Color(0xFF2F6FE0) else Color.Black.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(6.dp)) {
                            SignalPlot("", t.samples, Channels.rotation, minScale = 0.05, height = 44.dp, showsHeader = false)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Caption(String.format("%.2f s · %d", t.duration, t.samples.size))
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { vm.deleteTemplate(t.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }

        Section("Is there signal? (leave-one-out + permutation test)") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (opt in FeatureOption.values()) {
                    Checkbox(opt in vm.featureSet, { vm.toggleFeature(opt, it) })
                    Text(opt.name.lowercase().replaceFirstChar(Char::uppercase), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.evaluate() }, enabled = !vm.isEvaluating && vm.featureSet.isNotEmpty()) { Text(if (vm.isEvaluating) "Evaluating…" else "Evaluate") }
            }
            val r = vm.evaluation
            if (r == null) Caption("Needs at least 2 words with at least 2 takes each. Each take is classified against all the others; the same is repeated with shuffled labels to show what chance looks like.")
            else EvaluationView(r)
        }
    }
}

@Composable
private fun EvaluationView(r: Evaluation) {
    fun pct(v: Double) = String.format("%.0f%%", v * 100)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Metric("Accuracy", pct(r.accuracy), big = true)
        Metric("Chance", pct(r.chance))
        Metric("Shuffled 95th", pct(r.permutationP95))
        Metric("p-value", if (r.pValue < 0.001) "< 0.001" else String.format("%.3f", r.pValue))
    }
    Spacer(Modifier.height(6.dp))
    Text(if (r.isSignificant) "Motion carries word information beyond chance across ${r.templateCount} takes."
    else "Not distinguishable from chance yet. Record more takes or try other feature sets.",
        color = if (r.isSignificant) Color(0xFF2E8A3A) else Color(0xFFC08010), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
    Caption("actual ↓ / predicted →")
    Row { Spacer(Modifier.width(56.dp)); for (l in r.labels) Box(Modifier.width(40.dp)) { Caption(l) } }
    for ((row, label) in r.labels.withIndex()) {
        val total = maxOf(r.confusion[row].sum(), 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp)) { Caption(label) }
            for ((col, count) in r.confusion[row].withIndex()) {
                val base = if (row == col) Color(0xFF2EA04A) else Color(0xFFE03A3A)
                Box(Modifier.size(38.dp, 24.dp).padding(1.dp).background(base.copy(alpha = (count.toFloat() / total * 0.7f))), contentAlignment = Alignment.Center) {
                    Text(count.toString(), style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun Metric(title: String, value: String, big: Boolean = false) {
    Column {
        Caption(title)
        Text(value, style = if (big) androidx.compose.material3.MaterialTheme.typography.titleLarge else androidx.compose.material3.MaterialTheme.typography.titleMedium,
            fontWeight = if (big) FontWeight.Bold else FontWeight.Normal)
    }
}
