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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import dev.subvox.core.Condition
import dev.subvox.core.MotionSource

@Composable
fun SensorsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    vm.tick
    val latest = vm.monitor.latest
    val span = latest?.let { (it.t - 8)..it.t }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Headphone motion") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (choice in MotionChoice.values()) {
                    FilterChip(selected = vm.motionChoice == choice, onClick = { vm.chooseMotion(choice) }, label = { Text(choice.label.substringBefore(" (")) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Status: ${vm.motionStatus.label}")
            Text(String.format("Sample rate: %.1f Hz · samples: %d", vm.monitor.sampleRate, vm.monitor.sampleCount))
            latest?.let { Text(String.format("Gravity: %.2f, %.2f, %.2f", it.gx, it.gy, it.gz)) }
            val st = vm.motionStatus
            if (st is MotionSource.Status.Unsupported) Caption(st.reason)
            if (vm.motionChoice == MotionChoice.PHONE) Caption("Phone IMU is for testing the pipeline with the phone pressed to the jaw or strapped to a headband. It is not headphone motion.")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.udpHost, { vm.udpHost = it }, label = { Text("Forward to desktop (IP)") }, singleLine = true, modifier = Modifier.weight(1f), enabled = vm.udpSender == null)
                OutlinedButton(onClick = { vm.toggleUdp() }, enabled = vm.udpHost.isNotBlank()) { Text(if (vm.udpSender == null) "Send UDP" else "Stop") }
            }
            vm.udpSender?.let { Caption("Sending to ${vm.udpHost}:${it.port} · ${it.sent} samples" + (it.error?.let { e -> " · $e" } ?: "")) }
        }

        Section("Microphone") {
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.refreshDevices(); open = true }, enabled = !vm.audio.isRunning) { Text(vm.selectedDevice.name) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (d in vm.devices) DropdownMenuItem(text = { Text(d.name) }, onClick = { vm.selectedDevice = d; open = false })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.toggleMic() }) { Text(if (vm.audio.isRunning) "Stop Mic" else "Start Mic") }
                LevelMeter(vm.micLevel.toDouble(), modifier = Modifier.weight(1f))
            }
            if (vm.audio.isRunning) Caption("Capturing from ${vm.audio.activeDevice?.name} at ${vm.audio.sampleRate} Hz mono." +
                if (vm.selectedDevice.isBluetoothSco) " Bluetooth SCO link: call-grade bandwidth, headset noise processing applied." else "")
            else Caption("A Bluetooth headset's microphone (a Shokz, for example) reaches Android over the hands-free SCO link at 16 kHz or 8 kHz mono. Choosing it switches the headset out of the stereo profile while the mic is open.")
            (vm.audio.error)?.let { Caption(it, Color(0xFFC03030)) }
            vm.headset?.let { hs ->
                Spacer(Modifier.height(6.dp))
                Text(hs.name, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                Caption(hs.mic.description)
                hs.mic.sensitivity?.let { Caption("Mic sensitivity (spec sheet): $it") }
                Caption("Motion: ${hs.motion.label}.")
            }
        }

        Section("Live motion (last 8 s)") {
            SignalPlot("User acceleration (g)", vm.monitor.history, Channels.acceleration, span, minScale = 0.01)
            Spacer(Modifier.height(8.dp))
            SignalPlot("Rotation rate (rad/s)", vm.monitor.history, Channels.rotation, span, minScale = 0.05)
            Spacer(Modifier.height(8.dp))
            SignalPlot("Attitude (rad)", vm.monitor.history, Channels.attitude, span, minScale = 0.1)
        }

        Section("Experiment recording") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in Condition.values()) FilterChip(selected = vm.recorder.condition == c, onClick = { vm.recorder.condition = c }, label = { Text(c.label) }, enabled = !vm.recorder.isRecording)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(vm.recorder.prompt, { vm.recorder.prompt = it }, label = { Text("Prompt / what you're saying") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.toggleRecording() }) { Text(if (vm.recorder.isRecording) "Stop" else "Record") }
                if (vm.recorder.isRecording) {
                    val secs = vm.recorder.startedClock?.let { Clock.now() - it } ?: 0.0
                    Text(String.format("%d:%02d · %d samples · %d marks", (secs / 60).toInt(), (secs % 60).toInt(), vm.recorder.motionCount, vm.recorder.eventCount))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { val n = vm.importLastSession(); vm.recorder.prompt = vm.recorder.prompt }, enabled = vm.recorder.lastSessionDir != null || vm.recorder.root.exists()) { Text("Import last session takes") }
            }
            Caption(if (vm.audio.isRunning) "Motion + audio (audio.wav) will be recorded. While recording, hold volume-down to mark each utterance."
            else "Motion only. Start the mic above to also record audio. Hold volume-down to mark each utterance.")
            Caption("Sessions: ${vm.recorder.root.path}")
            vm.recorder.error?.let { Caption(it, Color(0xFFC03030)) }
        }
    }
}
