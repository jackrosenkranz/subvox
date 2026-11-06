package dev.subvox.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.subvox.core.MotionSource

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        permissions.launch(wanted.toTypedArray())
        setContent { MaterialTheme { SubvoxApp(vm) } }
    }

    /** Volume-down is the hold-to-capture key, so a pocketed phone can mark takes. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && vm.holdKeyCapturesMotion) {
            if (event.repeatCount == 0) vm.capture.begin()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && vm.capture.isCapturing) { vm.capture.end(); return true }
        return super.onKeyUp(keyCode, event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubvoxApp(vm: AppViewModel) {
    vm.tick // subscribe to every change
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.screen.title) },
                actions = {
                    val st = vm.motionStatus
                    val color = when (st) {
                        is MotionSource.Status.Streaming -> Color(0xFF2EA04A)
                        MotionSource.Status.Waiting -> Color(0xFFE08A1E)
                        else -> Color(0xFFC03030)
                    }
                    Row(Modifier.padding(end = 8.dp)) {
                        Text("● ", color = color)
                        Text(if (st is MotionSource.Status.Streaming) "${vm.motion.name} · ${vm.monitor.sampleRate.toInt()} Hz" else st.label,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = { vm.toggleRecording() }) {
                        Icon(if (vm.recorder.isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = if (vm.recorder.isRecording) "Stop recording" else "Record session",
                            tint = if (vm.recorder.isRecording) Color(0xFFC03030) else MaterialTheme.colorScheme.onSurface)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = vm.screen == Screen.SENSORS, onClick = { vm.screen = Screen.SENSORS; vm.syncCaptureMode() },
                    icon = { Icon(Icons.Default.GraphicEq, null) }, label = { Text("Sensors") })
                NavigationBarItem(selected = vm.screen == Screen.TRAIN, onClick = { vm.screen = Screen.TRAIN; vm.syncCaptureMode() },
                    icon = { Icon(Icons.Default.School, null) }, label = { Text("Train") })
                NavigationBarItem(selected = vm.screen == Screen.DECODE, onClick = { vm.screen = Screen.DECODE; vm.syncCaptureMode() },
                    icon = { Icon(Icons.Default.ChatBubble, null) }, label = { Text("Decode") })
            }
        },
    ) { padding ->
        when (vm.screen) {
            Screen.SENSORS -> SensorsScreen(vm, Modifier.padding(padding))
            Screen.TRAIN -> TrainScreen(vm, Modifier.padding(padding))
            Screen.DECODE -> DecodeScreen(vm, Modifier.padding(padding))
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
