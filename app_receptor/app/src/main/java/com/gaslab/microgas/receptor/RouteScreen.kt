package com.gaslab.microgas.receptor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable
fun RouteScreen(samples: List<Measurement>, modifier: Modifier = Modifier,
    selectedKey: Pair<String, Long>? = null, onSelectionChange: (Pair<String, Long>?) -> Unit = {},
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Ruta y altura", style = MaterialTheme.typography.titleLarge)
            Text("Recorrido local sin fondo cartográfico · norte arriba")
            Text(if (samples.lastOrNull()?.origin == "simulado")
                "SIMULACIÓN · coordenadas sintéticas y altura desde cero"
                else "Altura entregada por el dron · referencia pendiente de definir")
            Text("${samples.size} muestras en memoria", style = MaterialTheme.typography.bodySmall)
        }
        item { RouteMap(samples, selectedKey, onSelectionChange) }
        item { Co2Chart(samples, "Altura (m)", null, interactive = true, sharedSelection = selectedKey, onSelectionChange = onSelectionChange,
            value = { it.altitudeM?.toFloat()?.takeIf { value -> value.isFinite() } }) }
    }
}

@Composable
private fun RouteMap(samples: List<Measurement>, selectedKey: Pair<String, Long>?,
    onSelectionChange: (Pair<String, Long>?) -> Unit,
) {
    val select by rememberUpdatedState(onSelectionChange)
    val points = remember(samples) { routePoints(samples) }
    val valid = points.filterNotNull()
    val session = samples.lastOrNull()?.session
    var zoom by remember(session) { mutableFloatStateOf(1f) }
    var pan by remember(session) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Freeze fitted bounds while navigating; new data must not move the inspected route.
    var frozenBounds by remember(session) { mutableStateOf<List<Double>?>(null) }
    val liveBounds = listOf(valid.minOfOrNull { it.east } ?: 0.0, valid.maxOfOrNull { it.east } ?: 0.0,
        valid.minOfOrNull { it.north } ?: 0.0, valid.maxOfOrNull { it.north } ?: 0.0)
    val bounds = frozenBounds ?: liveBounds
    val width = canvasSize.width.toFloat(); val height = canvasSize.height.toFloat()
    val scale = min(width * .85 / max(20.0, bounds[1] - bounds[0]),
        height * .85 / max(20.0, bounds[3] - bounds[2])) * zoom
    fun screen(p: RoutePoint) = Offset(
        (width / 2 + (p.east - (bounds[0] + bounds[1]) / 2) * scale).toFloat() + pan.x,
        (height / 2 - (p.north - (bounds[2] + bounds[3]) / 2) * scale).toFloat() + pan.y)
    val screenPoints = points.map { p -> p?.let { it to screen(it) } }
    LaunchedEffect(selectedKey) {
        val selected = screenPoints.filterNotNull().firstOrNull { (it.first.sample.session to it.first.sample.sequence) == selectedKey }
        if (selected != null && (selected.second.x !in 0f..width || selected.second.y !in 0f..height)) {
            zoom = 1f; pan = Offset.Zero; frozenBounds = null
        }
    }
    val currentPoints by rememberUpdatedState(screenPoints)
    val currentBounds by rememberUpdatedState(liveBounds)
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val selectedColor = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Recorrido · N ↑", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero; frozenBounds = null; select(null) }) { Text("Recentrar") }
        }
        if (valid.isEmpty()) Text("Sin coordenadas. Conecta MicroGas Simulado o recibe GPS válido del dron.")
        else {
            Canvas(Modifier.fillMaxWidth().height(300.dp).onSizeChanged { canvasSize = it }
                .pointerInput(session) {
                    detectTransformGestures { centroid, movement, factor, _ ->
                        if (frozenBounds == null) frozenBounds = currentBounds
                        val next = (zoom * factor).coerceIn(1f, 60f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        pan = centroid - center - (centroid - center - pan) * (next / zoom) + movement
                        zoom = next
                    }
                }.pointerInput(session) {
                    detectTapGestures { tap ->
                        select(nearestChartPoint(currentPoints.filterNotNull().map { (p, pos) ->
                            ChartPoint(p.sample.session to p.sample.sequence, pos.x, pos.y)
                        }, tap.x, tap.y, 32.dp.toPx()))
                    }
                }.semantics { contentDescription = "Recorrido geográfico. Norte arriba. Pellizca para ampliar, arrastra y toca una muestra para inspeccionarla." }) {
                clipRect {
                    for (i in 0..4) {
                        drawLine(outline, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height))
                        drawLine(outline, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4))
                    }
                    var previous: Pair<RoutePoint, Offset>? = null
                    screenPoints.forEach { current ->
                        if (current != null) {
                            val (p, pos) = current
                            previous?.let { (before, beforePos) ->
                                if (before.sample.session == p.sample.session && before.sample.sequence + 1 == p.sample.sequence)
                                    drawLine(primary, beforePos, pos, 2.dp.toPx())
                            }
                            drawCircle(primary, 2.dp.toPx(), pos)
                            if (selectedKey == (p.sample.session to p.sample.sequence)) {
                                drawCircle(selectedColor.copy(alpha = .25f), 13.dp.toPx(), pos)
                                drawCircle(selectedColor, 8.dp.toPx(), pos, style = Stroke(3.dp.toPx()))
                            }
                        }
                        previous = current
                    }
                    screenPoints.lastOrNull()?.let { (_, pos) -> drawCircle(primary, 5.dp.toPx(), pos) }
                }
            }
            Text("Pellizca para ampliar · arrastra en cualquier dirección · toca una muestra", style = MaterialTheme.typography.labelSmall)
        }
        valid.firstOrNull { selectedKey == (it.sample.session to it.sample.sequence) }?.sample?.let { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(m.receivedAtMs)))
                    Text("Latitud: ${numberLabel(m.latitude, 6)}° · Longitud: ${numberLabel(m.longitude, 6)}°")
                    Text("Altura: ${numberLabel(m.altitudeM)} m · CO₂: ${numberLabel(m.co2Ppm)} ppm")
                    Text(if (m.origin == "simulado") "Muestra simulada · altura base cero" else "Muestra del dron · referencia de altura pendiente")
                    TextButton(onClick = { select(null) }) { Text("Cerrar detalle") }
                }
            }
        }
    }
}
