package dev.subvox.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.subvox.core.CaptureController

@Composable
fun DecodeScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    vm.tick
    val c = vm.capture
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.classifier == null) Caption("Train at least two words in the Train tab first.", Color(0xFFC08010))

        Section("Segmentation") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in CaptureController.Mode.values()) FilterChip(selected = vm.decodeMode == m, onClick = { vm.decodeMode = m; vm.syncCaptureMode() }, label = { Text(m.label) })
            }
            Spacer(Modifier.height(8.dp))
            if (vm.decodeMode == CaptureController.Mode.HOLD) {
                HoldButton("Mouth a word", c.isCapturing, enabled = vm.classifier != null && vm.motionStreaming, onBegin = c::begin, onEnd = c::end)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.calibrate() }, enabled = !c.isCalibrating && vm.motionStreaming) { Text(if (c.isCalibrating) "Calibrating… stay still" else "Calibrate noise floor") }
                    c.noiseFloor?.let { Caption(String.format("floor %.4f", it)) }
                    Spacer(Modifier.weight(1f))
                    Text(if (c.isCapturing) "● Capturing" else "○ Listening", color = if (c.isCapturing) Color(0xFFC03030) else Color.Gray)
                }
                val scaleMax = maxOf((c.threshold ?: 0.05) * 2, 0.02)
                LevelMeter(c.energy / scaleMax, marker = c.threshold?.let { it / scaleMax }, tint = if (c.isCapturing) Color(0xFFC03030) else Color(0xFF2F6FE0))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trigger × floor", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                    Slider(c.triggerRatio.toFloat(), { c.triggerRatio = it.toDouble() }, valueRange = 1.5f..10f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text(String.format("%.1f×", c.triggerRatio))
                }
                if (c.noiseFloor == null) Caption("Calibrate first: keep still for 2 s so the detector learns your resting motion.")
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max distance", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Slider(vm.maxDistance.toFloat(), { vm.maxDistance = it.toDouble() }, valueRange = 0.2f..4f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(String.format("%.2f", vm.maxDistance))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Min confidence", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Slider(vm.minConfidence.toFloat(), { vm.minConfidence = it.toDouble() }, valueRange = 0f..0.5f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(String.format("%.2f", vm.minConfidence))
            }
        }

        Section("Decoded from motion") {
            Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { vm.clearDecoded() }, enabled = vm.decoded.isNotEmpty()) { Text("Clear") } }
            if (vm.decoded.isEmpty()) Text("Mouth a trained word…", fontSize = 26.sp, color = Color.LightGray)
            else Text(decodedText(vm), fontSize = 26.sp)
            Caption("Faded words were rejected by the distance/confidence thresholds.")
        }

        Section("Last segment") {
            val p = vm.decoded.lastOrNull()?.prediction
            if (p == null) Caption("DTW distance to each word's closest take appears here.")
            else {
                val worst = p.ranked.lastOrNull()?.distance ?: 1.0
                for (cand in p.ranked) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(cand.label, Modifier.width(70.dp))
                    LevelMeter(1 - cand.distance / maxOf(worst * 1.2, 1e-6), tint = if (cand == p.best) Color(0xFF2EA04A) else Color.Gray, modifier = Modifier.weight(1f))
                    Text(String.format("%.2f", cand.distance), Modifier.width(44.dp))
                }
                Caption(String.format("Confidence %.2f · %s", p.confidence, if (p.accepted) "accepted" else "rejected"))
                SignalPlot("Rotation rate", vm.lastSegment, Channels.rotation, minScale = 0.05, height = 60.dp)
            }
        }

        Section("Microphone transcript (reference)") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.toggleTranscriber() }) { Text(if (vm.transcriber.isRunning) "Stop transcribing" else "Transcribe") }
                TextButton(onClick = { vm.transcriber.clear() }) { Text("Clear") }
            }
            Text(vm.transcriber.text.ifEmpty { "Whisper or speak to compare against the motion decoder." }, color = if (vm.transcriber.text.isEmpty()) Color.Gray else Color.Unspecified)
            if (vm.transcriber.isRunning) Caption(if (vm.transcriber.isOnDevice) "On-device recognition" else "Google recognition service")
            Caption("Android's recognizer opens the microphone itself. Record session audio or transcribe, not both at once, unless this phone allows concurrent capture.")
            vm.transcriber.error?.let { Caption(it, Color(0xFFC03030)) }
        }
    }
}

private fun decodedText(vm: AppViewModel): AnnotatedString = buildAnnotatedString {
    for (w in vm.decoded) {
        if (w.prediction.accepted) append(w.text + " ")
        else withStyle(SpanStyle(color = Color.Gray.copy(alpha = 0.45f))) { append(w.text + " ") }
    }
}
