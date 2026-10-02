package com.gaslab.microgas.receptor

import java.io.Writer
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistoryState(
    val samples: List<Measurement> = emptyList(),
    val home: Measurement? = null,
    val highest: Map<RouteMetric, List<Measurement>> = emptyMap(),
    val missingSequences: Long = 0,
    val sessionChanges: Int = 0,
    val ignoredSamples: Long = 0,
    val evictedSamples: Long = 0,
)

class MeasurementRepository(private val capacity: Int = 7_200) {
    init { require(capacity > 0) }
    private val buffer = ArrayDeque<Measurement>()
    private val retiredSessions = mutableSetOf<String>()
    private var last: Measurement? = null
    private val mutable = MutableStateFlow(HistoryState())
    val state = mutable.asStateFlow()

    @Synchronized fun clear() {
        buffer.clear(); retiredSessions.clear(); last = null; mutable.value = HistoryState()
    }

    @Synchronized fun append(sample: Measurement): Boolean {
        val previous = last
        var state = mutable.value
        if (!sample.co2Ppm.isFinite() || sample.co2Ppm < 0 ||
            sample.session in retiredSessions ||
            (previous?.session == sample.session && sample.sequence <= previous.sequence)) {
            mutable.value = state.copy(ignoredSamples = state.ignoredSamples + 1)
            return false
        }
        if (previous != null) {
            if (previous.session != sample.session) {
                retiredSessions.add(previous.session)
                state = state.copy(sessionChanges = state.sessionChanges + 1)
            } else {
                state = state.copy(missingSequences = state.missingSequences + sample.sequence - previous.sequence - 1)
            }
        }
        if (buffer.size == capacity) {
            buffer.removeFirst()
            state = state.copy(evictedSamples = state.evictedSamples + 1)
        }
        buffer.addLast(sample); last = sample
        mutable.value = state.copy(samples = buffer.toList(),
            home = state.home ?: sample.takeIf(::hasRouteFix),
            highest = RouteMetric.entries.associateWith { updateHighest(state.highest[it].orEmpty(), sample, it) })
        return true
    }

    companion object {
        private val utc = DateTimeFormatterBuilder().appendInstant(3).toFormatter(Locale.US)
        fun exportCsv(samples: List<Measurement>, writer: Writer) {
            writer.write("fecha_hora_utc,co2_ppm,temperatura_c,humedad_pct,latitud,longitud,origin,session,sequence,pm1_0,pm2_5,pm4_0,pm10,voc_index,nox_index,altura_m\n")
            samples.forEach { m ->
                writer.write(listOf(
                    utc.format(Instant.ofEpochMilli(m.receivedAtMs)), m.co2Ppm.toString(),
                    m.temperatureC?.toString().orEmpty(), m.humidityPct?.toString().orEmpty(),
                    m.latitude?.toString().orEmpty(), m.longitude?.toString().orEmpty(),
                    m.origin, m.session, m.sequence.toString(),
                    m.pm1?.toString().orEmpty(),
                    m.pm2_5?.toString().orEmpty(),
                    m.pm4?.toString().orEmpty(),
                    m.pm10?.toString().orEmpty(),
                    m.vocIndex?.toString().orEmpty(),
                    m.noxIndex?.toString().orEmpty(),
                    m.altitudeM?.toString().orEmpty(),
                ).joinToString(",") { csvField(it) } + "\n")
            }
        }
        private fun csvField(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
