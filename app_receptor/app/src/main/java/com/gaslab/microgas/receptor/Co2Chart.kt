package com.gaslab.microgas.receptor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

private val localTime = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
fun timeLabel(ms: Long): String = localTime.format(Instant.ofEpochMilli(ms))
fun numberLabel(value: Number?, decimals: Int = 1): String =
    value?.let { String.format(Locale.getDefault(), "%.${decimals}f", it.toDouble()) } ?: "—"

@Composable
fun Co2Chart(
    samples: List<Measurement>, title: String, minutes: Int?,
    interactive: Boolean = false, value: (Measurement) -> Float? = { it.co2Ppm },
    onOpenMap: ((Measurement) -> Unit)? = null,
    sharedSelection: Pair<String, Long>? = null,
    onSelectionChange: ((Pair<String, Long>?) -> Unit)? = null,
) {
    var localSelection by remember(minutes) { mutableStateOf<Pair<String, Long>?>(null) }
    val selectedKey = if (onSelectionChange != null) sharedSelection else localSelection
    fun select(key: Pair<String, Long>?) {
        if (onSelectionChange != null) onSelectionChange(key) else localSelection = key
    }
    var zoom by remember(minutes) { mutableFloatStateOf(1f) }
    // Absolute viewport anchor keeps a panned historical view stationary as new samples arrive.
    var anchoredEnd by remember(minutes) { mutableStateOf<Double?>(null) }
    val points = remember(samples, minutes, value) {
        val end = samples.maxOfOrNull { it.receivedAtMs } ?: 0L
        val start = minutes?.let { end - it * 60_000L } ?: Long.MIN_VALUE
        samples.filter { it.receivedAtMs >= start }.sortedBy { it.receivedAtMs }
    }
    val end = (points.lastOrNull()?.receivedAtMs ?: 0L).toDouble()
    val start = minutes?.let { end - it * 60_000.0 }
        ?: (points.firstOrNull()?.receivedAtMs?.toDouble() ?: end - 60_000.0)
    val fullSpan = max(1000.0, end - start)
    val gestureBounds by rememberUpdatedState(Triple(start, end, fullSpan))
    val span = fullSpan / zoom
    val viewEnd = (anchoredEnd ?: end).coerceIn(min(end, start + span), end)
    val viewStart = viewEnd - span
    LaunchedEffect(sharedSelection) {
        if (onSelectionChange != null && sharedSelection != null) {
            val sample = points.firstOrNull { (it.session to it.sequence) == sharedSelection }
            if (sample != null && sample.receivedAtMs.toDouble() !in viewStart..viewEnd) {
                zoom = 1f; anchoredEnd = null
            }
        }
    }
    val visiblePoints = points.filter { it.receivedAtMs.toDouble() in viewStart..viewEnd && value(it)?.isFinite() == true }
    val values = visiblePoints.mapNotNull(value)
    val low = values.minOrNull()?.toDouble() ?: 0.0
    val high = values.maxOrNull()?.toDouble() ?: 1.0
    val padding = max(1.0, (high - low) * .12)
    val minY = low - padding
    val maxY = high + padding
    val selected = visiblePoints.firstOrNull { (it.session to it.sequence) == selectedKey }
    val selectionColor = MaterialTheme.colorScheme.onSurface
    val selectPoint by rememberUpdatedState<(Offset, Float, Float, Float, Float, Float) -> Unit>(
        { tap, left, top, width, height, radius ->
            select(nearestChartPoint(visiblePoints.map { sample ->
                ChartPoint(sample.session to sample.sequence,
                    ((sample.receivedAtMs - viewStart) / span).toFloat() * width + left,
                    (1 - (value(sample)!! - minY) / (maxY - minY)).toFloat() * height + top)
            }, tap.x, tap.y, radius))
        }
    )
    val primary = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val hasValues = points.any { value(it)?.isFinite() == true }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (interactive) TextButton(onClick = { zoom = 1f; anchoredEnd = null; select(null) }) { Text("Restablecer") }
        }
        if (!hasValues) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("Sin datos") }
        } else {
            val gesture = if (interactive) Modifier.pointerInput(minutes) {
                detectTransformGestures { centroid, pan, scale, _ ->
                    val (start, end, fullSpan) = gestureBounds
                    val width = (size.width - 76.dp.toPx()).coerceAtLeast(1f)
                    val fraction = ((centroid.x - 58.dp.toPx()) / width).coerceIn(0f, 1f)
                    val oldSpan = fullSpan / zoom
                    val oldEnd = (anchoredEnd ?: end).coerceIn(min(end, start + oldSpan), end)
                    val newZoom = (zoom * scale).coerceIn(1f, 60f)
                    val newSpan = fullSpan / newZoom
                    val anchor = oldEnd - oldSpan * (1 - fraction)
                    val newEnd = (anchor + newSpan * (1 - fraction) - pan.x / width * newSpan)
                        .coerceIn(min(end, start + newSpan), end)
                    zoom = newZoom
                    anchoredEnd = if (newZoom == 1f || abs(newEnd - end) < 1) null else newEnd
                }
            } else Modifier
            Canvas(Modifier.fillMaxWidth().height(210.dp).then(gesture)
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        selectPoint(tap, 58.dp.toPx(), 14.dp.toPx(),
                            size.width - 76.dp.toPx(), size.height - 46.dp.toPx(), 32.dp.toPx())
                    }
                }
                .semantics { contentDescription = "$title. ${points.size} muestras. Toca un punto para ver hora, valor y GPS. ${if (interactive) "Pellizca para ampliar y arrastra horizontalmente." else ""}" }) {
                val left = 58.dp.toPx(); val right = size.width - 18.dp.toPx()
                val top = 14.dp.toPx(); val bottom = size.height - 32.dp.toPx()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = label.toArgb(); textSize = 10.dp.toPx() }
                for (i in 0..4) {
                    val y = bottom - (bottom - top) * i / 4
                    drawLine(grid, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y))
                    paint.textAlign = Paint.Align.RIGHT
                    drawContext.canvas.nativeCanvas.drawText(String.format(Locale.getDefault(), "%.0f", minY + (maxY - minY) * i / 4), left - 6.dp.toPx(), y + 4.dp.toPx(), paint)
                }
                for (i in 0..2) {
                    paint.textAlign = when (i) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                    drawContext.canvas.nativeCanvas.drawText(timeLabel((viewStart + span * i / 2).toLong().coerceAtLeast(0)), left + (right - left) * i / 2, size.height - 8.dp.toPx(), paint)
                }
                val path = Path(); var drawing = false; var previousSession: String? = null
                points.forEach { sample ->
                    val v = value(sample)
                    if (sample.receivedAtMs.toDouble() !in viewStart..viewEnd || v == null || !v.isFinite()) {
                        drawing = false
                    } else {
                        val x = left + ((sample.receivedAtMs - viewStart) / span).toFloat() * (right - left)
                        val y = bottom - ((v - minY) / (maxY - minY)).toFloat() * (bottom - top)
                        if (drawing && previousSession == sample.session) path.lineTo(x, y) else path.moveTo(x, y)
                        drawCircle(primary, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(x, y))
                        drawing = true; previousSession = sample.session
                    }
                }
                drawPath(path, primary, style = Stroke(2.dp.toPx()))
                selected?.let { sample ->
                    val x = left + ((sample.receivedAtMs - viewStart) / span).toFloat() * (right - left)
                    val y = bottom - ((value(sample)!! - minY) / (maxY - minY)).toFloat() * (bottom - top)
                    drawLine(selectionColor.copy(alpha = .5f), Offset(x, top), Offset(x, bottom))
                    drawCircle(selectionColor.copy(alpha = .25f), 12.dp.toPx(), Offset(x, y))
                    drawCircle(selectionColor, 7.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
                    drawCircle(primary, 3.dp.toPx(), Offset(x, y))
                }
            }
            selected?.let { sample ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Hora: ${DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(sample.receivedAtMs))}")
                        Text("$title: ${numberLabel(value(sample))}")
                        if (title != "CO₂ (ppm)") Text("CO₂: ${numberLabel(sample.co2Ppm)} ppm")
                        if (sample.latitude != null && sample.longitude != null) {
                            Column(Modifier.fillMaxWidth().then(
                                if (onOpenMap != null) Modifier.clickable(onClickLabel = "Ver muestra en el mapa") { onOpenMap(sample) }
                                else Modifier
                            ).padding(vertical = 8.dp)) {
                                Text("Latitud: ${numberLabel(sample.latitude, 6)}°")
                                Text("Longitud: ${numberLabel(sample.longitude, 6)}°")
                                if (onOpenMap != null) Text("Ver muestra en el mapa →", color = MaterialTheme.colorScheme.primary)
                            }
                        } else Text("GPS no disponible para esta muestra")
                        Text("Altura: ${numberLabel(sample.altitudeM)} m")
                        TextButton(onClick = { select(null) }) { Text("Cerrar detalle") }
                    }
                }
            }
            Text("Toca un punto para ver hora, valor y GPS", style = MaterialTheme.typography.labelSmall)
            if (interactive) Text("Pellizca para ampliar · arrastra para recorrer", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun WindowSelector(selected: Int?, options: List<Int?>, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Ventana: ${selected?.let { "$it min" } ?: "Todo"}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { minutes -> DropdownMenuItem(
                text = { Text(minutes?.let { "$it minutos" } ?: "Todo") },
                onClick = { onSelect(minutes); expanded = false },
            ) }
        }
    }
}
