package dev.subvox.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.subvox.core.MotionSample
import kotlin.math.abs
import kotlin.math.max

data class Channel(val name: String, val color: Color, val value: (MotionSample) -> Double)

object Channels {
    val acceleration = listOf(Channel("x", Color(0xFFE03A3A)) { it.ax }, Channel("y", Color(0xFF2EA04A)) { it.ay }, Channel("z", Color(0xFF2F6FE0)) { it.az })
    val rotation = listOf(Channel("x", Color(0xFFE03A3A)) { it.rx }, Channel("y", Color(0xFF2EA04A)) { it.ry }, Channel("z", Color(0xFF2F6FE0)) { it.rz })
    val attitude = listOf(Channel("pitch", Color(0xFFE08A1E)) { it.pitch }, Channel("roll", Color(0xFF8E44C8)) { it.roll }, Channel("yaw", Color(0xFF1E9E9E)) { it.yaw })
}

/** Multi-channel line plot drawn with Canvas so it keeps up at sensor rate. */
@Composable
fun SignalPlot(
    title: String,
    samples: List<MotionSample>,
    channels: List<Channel>,
    span: ClosedRange<Double>? = null,
    minScale: Double = 0.02,
    height: Dp = 90.dp,
    showsHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val range = span ?: ((samples.firstOrNull()?.t ?: 0.0)..(samples.lastOrNull()?.t ?: 1.0))
    val visible = samples.filter { it.t in range }
    val scale = max(minScale, channels.flatMap { c -> visible.map { abs(c.value(it)) } }.maxOrNull() ?: 0.0)
    Column(modifier) {
        if (showsHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                for (c in channels) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.color))
                    Spacer(Modifier.width(3.dp))
                    Text(c.name, style = MaterialTheme.typography.labelSmall, color = c.color)
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(String.format("±%.3f", scale), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.05f))) {
            val mid = size.height / 2
            drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
            val widthT = max(range.endInclusive - range.start, 1e-3)
            for (c in channels) {
                val path = Path()
                for ((i, s) in visible.withIndex()) {
                    val x = ((s.t - range.start) / widthT * size.width).toFloat()
                    val y = (mid - c.value(s) / scale * (mid - 2)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, c.color, style = Stroke(width = 2.5f))
            }
        }
    }
}

@Composable
fun LevelMeter(value: Double, marker: Double? = null, tint: Color = Color(0xFF2EA04A), modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val r = size.height / 2
        drawRoundRect(Color.Black.copy(alpha = 0.1f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        drawRoundRect(tint, size = androidx.compose.ui.geometry.Size((size.width * value.coerceIn(0.0, 1.0)).toFloat(), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        marker?.let { m ->
            val x = (size.width * m.coerceIn(0.0, 1.0)).toFloat()
            drawRect(Color.Black, topLeft = Offset(x - 1f, 0f), size = androidx.compose.ui.geometry.Size(2f, size.height))
        }
    }
}

/** Press-and-hold capture control. Also driven by the volume-down key from MainActivity. */
@Composable
fun HoldButton(title: String, isCapturing: Boolean, enabled: Boolean, onBegin: () -> Unit, onEnd: () -> Unit) {
    val edge = if (isCapturing) Color(0xFFD03030) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val fill = if (isCapturing) Color(0xFFD03030).copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Column(
        Modifier.fillMaxWidth().height(130.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) fill else Color.Gray.copy(alpha = 0.1f))
            .border(if (isCapturing) 2.dp else 1.dp, if (enabled) edge else Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = { onBegin(); try { tryAwaitRelease() } finally { onEnd() } })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(if (isCapturing) "●" else "○", fontSize = 28.sp, color = edge)
        Text(if (isCapturing) "Capturing…" else title, style = MaterialTheme.typography.titleMedium)
        Text("Press and hold here, or hold volume-down", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun Caption(text: String, color: Color = Color.Gray) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
