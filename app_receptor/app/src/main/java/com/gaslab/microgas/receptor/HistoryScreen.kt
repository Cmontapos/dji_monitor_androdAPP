package com.gaslab.microgas.receptor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(state: MonitorUiState, onWindow: (Int?) -> Unit, onExport: () -> Unit, modifier: Modifier = Modifier, onOpenMap: (Measurement) -> Unit = {}) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("${state.history.samples.size} / 7.200 muestras", style = MaterialTheme.typography.titleMedium)
            Text("Huecos de secuencia: ${state.history.missingSequences} · Reinicios del emisor: ${state.history.sessionChanges}")
            if (state.history.ignoredSamples > 0) Text("Duplicadas o fuera de orden ignoradas: ${state.history.ignoredSamples}")
            if (state.history.evictedSamples > 0) Text("${state.history.evictedSamples} muestras antiguas salieron del límite de memoria.")
            WindowSelector(state.historyMinutes, listOf(1, 2, 5, 10, 15, 30, 60, null), onWindow)
            Button(onClick = onExport, enabled = state.history.samples.isNotEmpty() && !state.exportBusy) {
                Text(if (state.exportBusy) "Exportando…" else "Exportar CSV")
            }
        }
        item { Co2Chart(state.history.samples, "CO₂ (ppm)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true) }
        item { Co2Chart(state.history.samples, "Temperatura (°C)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.temperatureC }) }
        item { Co2Chart(state.history.samples, "Humedad (% HR)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.humidityPct }) }
        item { Co2Chart(state.history.samples, "PM1 (µg/m³)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.pm1 }) }
        item { Co2Chart(state.history.samples, "PM2.5 (µg/m³)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.pm2_5 }) }
        item { Co2Chart(state.history.samples, "PM4 (µg/m³)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.pm4 }) }
        item { Co2Chart(state.history.samples, "PM10 (µg/m³)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.pm10 }) }
        item { Co2Chart(state.history.samples, "VOC (índice)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.vocIndex }) }
        item { Co2Chart(state.history.samples, "NOx (índice)", state.historyMinutes, onOpenMap = onOpenMap, interactive = true, value = { it.noxIndex }) }
        item {
            Text("Datos crudos · toda la sesión", style = MaterialTheme.typography.titleMedium)
            Text("Las reconexiones conservan la sesión. Una conexión manual inicia un histórico nuevo.", style = MaterialTheme.typography.bodySmall)
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxWidth().horizontalScroll(scroll).width(1755.dp)) {
                DataRow(listOf("Hora", "CO₂ (ppm)", "Temp. (°C)", "Hum. (% HR)", "Latitud", "Longitud", "PM1 (µg/m³)", "PM2.5 (µg/m³)", "PM4 (µg/m³)", "PM10 (µg/m³)", "VOC (índice)", "NOx (índice)", "Altura (m)"))
                HorizontalDivider()
                LazyColumn(Modifier.height(320.dp).fillMaxWidth()) {
                    items(state.history.samples.asReversed(), key = { "${it.session}:${it.sequence}" }) { m ->
                        DataRow(listOf(timeLabel(m.receivedAtMs), numberLabel(m.co2Ppm), numberLabel(m.temperatureC),
                            numberLabel(m.humidityPct), numberLabel(m.latitude, 6), numberLabel(m.longitude, 6),
                            numberLabel(m.pm1), numberLabel(m.pm2_5), numberLabel(m.pm4), numberLabel(m.pm10),
                            numberLabel(m.vocIndex), numberLabel(m.noxIndex), numberLabel(m.altitudeM)))
                    }
                }
            }
        }
    }
}

@Composable
private fun DataRow(values: List<String>) {
    Row(Modifier.padding(vertical = 8.dp)) { values.forEach { Text(it, Modifier.width(135.dp).padding(horizontal = 4.dp)) } }
}
