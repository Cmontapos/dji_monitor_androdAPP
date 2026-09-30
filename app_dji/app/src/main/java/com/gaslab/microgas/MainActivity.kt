package com.gaslab.microgas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color.White
private val Muted = Color(0xFFA4B4C4)
private val Accent = Color(0xFF59DDC5)
private val Panel = Color(0xFF16232F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: MonitorViewModel = viewModel()
            val running by model.running.collectAsStateWithLifecycle()
            val state by model.state.collectAsStateWithLifecycle()
            val recording by model.recording.collectAsStateWithLifecycle()
            val sourceStatus by model.sourceStatus.collectAsStateWithLifecycle()
            var deletingSession by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedSession by rememberSaveable { mutableStateOf<String?>(null) }
            val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                val session = selectedSession
                selectedSession = null
                if (uri != null && session != null) model.export(session, uri)
                else model.exportNotice("Exportación cancelada. El registro local continúa.")
            }
            MonitorTheme {
                SourcePermissions()
                MonitorScreen(
                    state, model::togglePause, model::resetMaximum, recording,
                    { model.showSessions() }, model::selectHistory, sourceStatus, running,
                    controls = { MonitorControls() },
                )
                if (recording.choosingSession) {
                    AlertDialog(
                        onDismissRequest = model::dismissSessions,
                        title = { Text("Archivos CSV") },
                        text = {
                            Column {
                                Text("Exporta o borra una sesión. Para borrar la sesión activa, detén la adquisición.")
                                recording.exportMessage?.let { Text(it) }
                                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                                    items(recording.sessions, key = { it }) { name ->
                                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                            Text(name, fontSize = 12.sp)
                                            if (name == recording.activeSession) Text("Grabando · protegida", color = Accent)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(enabled = !recording.deleting && !recording.exporting && selectedSession == null, onClick = {
                                                    selectedSession = name
                                                    model.dismissSessions()
                                                    try { exporter.launch(name) }
                                                    catch (_: android.content.ActivityNotFoundException) {
                                                        selectedSession = null
                                                        model.exportNotice("No hay selector de documentos disponible en este dispositivo.")
                                                    }
                                                }) { Text("Exportar") }
                                                TextButton(
                                                    enabled = name != recording.activeSession && !recording.deleting && !recording.exporting && selectedSession == null,
                                                    onClick = { deletingSession = name },
                                                ) { Text("Borrar") }
                                            }
                                            HorizontalDivider()
                                        }
                                    }
                                    if (recording.sessions.isEmpty()) item { Text("Aún no hay sesiones guardadas.") }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = model::dismissSessions) { Text("Cerrar") } },
                    )
                }
                deletingSession?.let { name ->
                    AlertDialog(
                        onDismissRequest = { deletingSession = null },
                        title = { Text("¿Borrar este CSV?") },
                        text = { Text("$name\n\nSe eliminará permanentemente del dispositivo. Las copias que hayas exportado se conservan.") },
                        confirmButton = {
                            TextButton(
                                enabled = !recording.deleting && !recording.exporting && name != recording.activeSession,
                                onClick = { deletingSession = null; model.deleteSession(name) },
                            ) { Text("Borrar definitivamente") }
                        },
                        dismissButton = { TextButton(onClick = { deletingSession = null }) { Text("Cancelar") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, background = Color(0xFF0C1620), surface = Panel,
            onBackground = Ink, onSurface = Ink, onSurfaceVariant = Ink,
            onPrimary = Ink, onSecondaryContainer = Ink,
        ), content = { CompositionLocalProvider(LocalContentColor provides Ink, content = content) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitorScreen(
    state: MonitorState, onPause: () -> Unit, onReset: () -> Unit,
    recording: RecordingState = RecordingState(), onExport: () -> Unit = {},
    onSelectHistory: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    sourceStatus: String = "Simulación",
    running: Boolean = true,
    controls: @Composable () -> Unit = {},
) {
    var selecting by rememberSaveable { mutableStateOf(false) }
    if (selecting) {
        HistorySelectionDialog(state, { selecting = false }) { count, back, co2 ->
            onSelectHistory(count, back, co2)
            selecting = false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 720.dp
        // All sections remain reachable even on a short landscape phone or with large fonts.
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { controls() }
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("MICROGAS", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Ink)
                    Text(
                        if (!running) "MONITOR DETENIDO" else if (state.paused) "VISTA PAUSADA · REGISTRO ACTIVO"
                        else if (BuildConfig.DATA_ORIGIN == "dji") "MODO DJI · DATOS REALES" else "MODO SIMULADO · 1 Hz",
                        color = Color(0xFFFFD18A), fontSize = 12.sp,
                    )
                }
            }
            if (wide) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(0.8f)) { Co2Panel(state, onPause, onReset) }
                        Column(Modifier.weight(1.2f)) {
                            HistoryPanel(state, running, sourceStatus, { selecting = true }, onSelectHistory)
                        }
                    }
                }
            } else {
                item { Co2Panel(state, onPause, onReset) }
                item { HistoryPanel(state, running, sourceStatus, { selecting = true }, onSelectHistory) }
            }
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CSV ~10 Hz · ${recording.saved} guardadas · ${recording.failed} fallidas", color = Accent, fontSize = 12.sp)
                    OutlinedButton(onClick = onExport, enabled = !recording.exporting && !recording.deleting) {
                        Text(if (recording.exporting) "Exportando…" else if (recording.deleting) "Borrando…" else "Archivos CSV")
                    }
                }
                recording.error?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp) }
                recording.exportMessage?.let { Text(it, color = Ink, fontSize = 12.sp) }
                Text(if (running) sourceStatus else "Inicia el pop-up o segundo plano para recibir datos", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Co2Panel(state: MonitorState, onPause: () -> Unit, onReset: () -> Unit) {
    val appearance by MonitorService.readingAppearance.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("monitor", 0) }
    var threshold by remember { mutableIntStateOf(preferences.getInt("threshold", LiveTelemetry.DEFAULT_THRESHOLD)) }
    DisposableEffect(preferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "threshold") threshold = prefs.getInt("threshold", LiveTelemetry.DEFAULT_THRESHOLD)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val readingColor = Color(co2ReadingColor(state.current?.co2Ppm, threshold,
        appearance.alarm && !state.paused, appearance.whitePhase))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CO₂ / SEN66", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("${state.current?.co2Ppm?.let { format(it) } ?: "—"} ppm", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = readingColor)
            LinearProgressIndicator(
                progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Accent, trackColor = Color(0xFF2C3E4F),
            )
            Text("Máximo: ${format(state.maxCo2)} ppm · escala relativa", color = Muted, fontSize = 11.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onReset, enabled = state.current != null) { Text("Reiniciar máximo") }
                TextButton(onClick = onPause) { Text(if (state.paused) "Continuar vista" else "Pausar vista") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryPanel(
    state: MonitorState, running: Boolean, sourceStatus: String, onSelect: () -> Unit,
    onSelectHistory: (Int, Int, Boolean) -> Unit,
) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val columns = if (state.showCo2) HistoryColumns else HistoryColumns.drop(1)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("HISTORIAL", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                TextButton(onClick = onSelect) { Text("Ver datos") }
                if (state.samplesBack > 0) TextButton(onClick = { onSelectHistory(state.visibleCount, 0, state.showCo2) }) {
                    Text("Volver a recientes")
                }
            }
            Text(
                if (state.samplesBack == 0) "Últimas ${state.visibleCount} lecturas · ${state.samples.size} en memoria"
                else "${state.samplesBack} muestras atrás · ${state.visibleSamples.size} lecturas",
                color = Muted, fontSize = 11.sp,
            )
            Text("Desliza ↔ para ver más columnas · —: dato no recibido", color = Muted, fontSize = 11.sp)
            Text("Hora local de recepción · coordenadas del dron asociadas a la muestra", color = Muted, fontSize = 11.sp)
            if (state.paused) Text("Vista pausada: pulsa Continuar vista para actualizar.", color = Color(0xFFFFD18A), fontSize = 12.sp)
            if (!state.showCo2) TextButton(onClick = { onSelectHistory(state.visibleCount, state.samplesBack, true) }) {
                Text("Mostrar columna CO₂")
            }
            if (state.samples.isEmpty()) Text(
                when {
                    state.paused -> "Aún no hay lecturas en la vista pausada."
                    !running -> "Inicia el pop-up o segundo plano para ver las lecturas."
                    else -> "Esperando muestras… $sourceStatus"
                }, color = Muted, fontSize = 12.sp,
            )
            // A single horizontal viewport keeps every header aligned with its column.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(columns.sumOf { it.width }.dp)) {
                    HistoryRow(columns, columns.map { it.title }, header = true)
                    HorizontalDivider(color = Color(0xFF304253))
                    LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                        items(state.visibleSamples) { sample ->
                            HistoryRow(columns, columns.map { column ->
                                when (column.key) {
                                    "co2" -> format(sample.co2Ppm)
                                    "time" -> clock.format(Date(sample.receivedAtMs))
                                    "latitude" -> sample.aircraftPosition?.latitude?.let { String.format(Locale.getDefault(), "%.6f", it) } ?: "—"
                                    "altitude" -> sample.aircraftPosition?.altitudeM?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
                                    "longitude" -> sample.aircraftPosition?.longitude?.let { String.format(Locale.getDefault(), "%.6f", it) } ?: "—"
                                    "temperature" -> sample.temperatureC?.let { format(it) } ?: "—"
                                    "humidity" -> sample.humidityPct?.let { format(it) } ?: "—"
                                    "pm1" -> sample.pm1?.let { format(it) } ?: "—"
                                    "pm2_5" -> sample.pm2_5?.let { format(it) } ?: "—"
                                    "pm4" -> sample.pm4?.let { format(it) } ?: "—"
                                    "pm10" -> sample.pm10?.let { format(it) } ?: "—"
                                    "voc" -> sample.vocIndex?.let { format(it) } ?: "—"
                                    "nox" -> sample.noxIndex?.let { format(it) } ?: "—"
                                    else -> "—"
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySelectionDialog(
    state: MonitorState, onDismiss: () -> Unit, onApply: (Int, Int, Boolean) -> Unit,
) {
    var count by rememberSaveable { mutableStateOf(state.visibleCount.toString()) }
    var back by rememberSaveable { mutableStateOf(state.samplesBack.toString()) }
    var co2 by rememberSaveable { mutableStateOf(state.showCo2) }
    val countValue = count.toIntOrNull()
    val backValue = back.toIntOrNull()
    val maximumBack = (state.samples.size - (countValue ?: 10)).coerceAtLeast(0)
    val valid = countValue != null && countValue in 1..MonitorState.HISTORY_LIMIT &&
        backValue != null && backValue in 0..maximumBack
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Datos a visualizar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Checkbox(checked = co2, onCheckedChange = { co2 = it })
                    Text("CO₂ (ppm) en la tabla", modifier = Modifier.padding(top = 12.dp))
                }
                Text("Las demás columnas permanecen visibles. «—» indica que no hay dato disponible. El GPS del dron se consulta por MSDK.", fontSize = 12.sp)
                OutlinedTextField(
                    value = count, onValueChange = { count = it }, singleLine = true,
                    label = { Text("Cantidad de muestras (1–3600)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = back, onValueChange = { back = it }, singleLine = true,
                    label = { Text("Retroceder X muestras (0–$maximumBack)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("0 sigue las lecturas recientes. Se cuentan muestras nuevas de CO₂ (~1 Hz), no filas CSV (~10 Hz).", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(countValue!!, backValue!!, co2) }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private data class HistoryColumn(val key: String, val title: String, val width: Int)

private val HistoryColumns = listOf(
    HistoryColumn("co2", "CO₂\n(ppm)", 100),
    HistoryColumn("temperature", "Temperatura\n(°C)", 128),
    HistoryColumn("humidity", "Humedad\n(% HR)", 112),
    HistoryColumn("latitude", "Latitud\n(°)", 120),
    HistoryColumn("longitude", "Longitud\n(°)", 120),
    HistoryColumn("altitude", "Altura\n(m)", 100),
    HistoryColumn("time", "Hora del día\n(local)", 124),
    HistoryColumn("pm1", "PM1\n(µg/m³)", 100),
    HistoryColumn("pm2_5", "PM2.5\n(µg/m³)", 100),
    HistoryColumn("pm4", "PM4\n(µg/m³)", 100),
    HistoryColumn("pm10", "PM10\n(µg/m³)", 100),
    HistoryColumn("voc", "VOC\n(índice)", 100),
    HistoryColumn("nox", "NOx\n(índice)", 100),
)

@Composable
private fun HistoryRow(columns: List<HistoryColumn>, values: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        columns.zip(values).forEach { (column, value) ->
            Text(
                value, modifier = Modifier.width(column.width.dp).padding(horizontal = 6.dp, vertical = 7.dp),
                color = if (header) Muted else Ink, fontSize = 12.sp,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun format(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

@Preview(name = "RC Pro Enterprise · horizontal", widthDp = 960, heightDp = 540, showBackground = true)
@Composable
private fun MonitorPreview() {
    val samples = (0..9).map { GasSample(1_700_000_000_000L + it * 1_000L, 650f + it * 8f) }
    MonitorTheme { MonitorScreen(MonitorState(samples, 810f), {}, {}) }
}

@Preview(name = "Teléfono horizontal compacto", widthDp = 640, heightDp = 320, showBackground = true)
@Composable
private fun CompactMonitorPreview() {
    val samples = (0..9).map { GasSample(1_700_000_000_000L + it * 1000L, 650f + it * 8f) }
    MonitorTheme { MonitorScreen(MonitorState(samples, 810f), {}, {}) }
}
