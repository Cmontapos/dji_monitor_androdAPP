package com.gaslab.microgas.receptor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MonitorScreen(state: MonitorUiState, onWindow: (Int) -> Unit, modifier: Modifier = Modifier, onOpenMap: (Measurement) -> Unit = {}) {
    var whitePhase by remember { mutableStateOf(false) }
    LaunchedEffect(state.alarm) {
        whitePhase = false
        while (state.alarm) {
            delay(500)
            whitePhase = !whitePhase
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val latest = state.history.samples.lastOrNull()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CO₂", style = MaterialTheme.typography.titleMedium)
                Text("${numberLabel(latest?.co2Ppm)} ppm", style = MaterialTheme.typography.displaySmall,
                    color = Color(co2ReadingColor(latest?.co2Ppm, state.threshold, state.alarm, whitePhase)))
                Text(when { latest == null -> "Esperando datos…"; state.fresh -> "Datos actuales"; else -> "Sin datos nuevos · última lectura" })
                latest?.let {
                    Text("${timeLabel(it.receivedAtMs)} · ${if (it.origin == "simulado") "SIMULADO" else it.origin}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        WindowSelector(state.monitorMinutes, listOf(1, 2, 5, 10, 15, 30)) { it?.let(onWindow) }
        if (latest == null) Text("Esperando datos…")
        Co2Chart(state.history.samples, "CO₂ (ppm)", state.monitorMinutes, onOpenMap = onOpenMap)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp)) {
                Text("Temperatura"); Text("${numberLabel(latest?.temperatureC)} °C", style = MaterialTheme.typography.titleLarge)
            } }
            Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp)) {
                Text("Humedad"); Text("${numberLabel(latest?.humidityPct)} % HR", style = MaterialTheme.typography.titleLarge)
            } }
        }
        Text("PM1: ${numberLabel(latest?.pm1)} µg/m³")
        Text("PM2.5: ${numberLabel(latest?.pm2_5)} µg/m³")
        Text("PM4: ${numberLabel(latest?.pm4)} µg/m³")
        Text("PM10: ${numberLabel(latest?.pm10)} µg/m³")
        Text("VOC: ${numberLabel(latest?.vocIndex)} (índice)")
        Text("NOx: ${numberLabel(latest?.noxIndex)} (índice)")
    }
}
