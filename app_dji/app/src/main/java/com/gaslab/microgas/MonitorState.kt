package com.gaslab.microgas

/** Timestamp de recepción local. El timestamp del payload se definirá con su protocolo. */
data class GasSample(
    val receivedAtMs: Long, val co2Ppm: Float,
    val origin: String = "simulado",
    val senderBoot: Long? = null, val senderSequence: Long? = null,
    val acquiredUptimeMs: Long? = null,
    val aircraftPosition: AircraftPosition? = null,
    val temperatureC: Float? = null,
    val humidityPct: Float? = null,
    val pm1: Float? = null,
    val pm2_5: Float? = null,
    val pm4: Float? = null,
    val pm10: Float? = null,
    val vocIndex: Float? = null,
    val noxIndex: Float? = null,
)

data class MonitorState(
    val samples: List<GasSample> = emptyList(),
    val maxCo2: Float = 0f,
    val paused: Boolean = false,
    val visibleCount: Int = 10,
    val samplesBack: Int = 0,
    val showCo2: Boolean = true,
) {
    val visibleSamples: List<GasSample> get() {
        val end = (samples.size - samplesBack).coerceIn(0, samples.size)
        return samples.subList((end - visibleCount).coerceAtLeast(0), end)
    }
    val current: GasSample? get() = samples.lastOrNull()
    val progress: Float get() = if (maxCo2 > 0f) {
        ((current?.co2Ppm ?: 0f) / maxCo2).coerceIn(0f, 1f)
    } else 0f

    fun accept(sample: GasSample): MonitorState {
        if (!sample.co2Ppm.isFinite() || sample.co2Ppm < 0f) return this
        val history = (samples + sample).takeLast(HISTORY_LIMIT)
        return copy(
            samples = history, maxCo2 = maxOf(maxCo2, sample.co2Ppm),
            // Keep the selected historical window stationary as new measurements arrive.
            samplesBack = if (samplesBack == 0) 0 else (samplesBack + 1).coerceAtMost((history.size - visibleCount).coerceAtLeast(0)),
        )
    }

    fun selectHistory(count: Int, back: Int, co2: Boolean): MonitorState {
        val boundedCount = count.coerceIn(1, HISTORY_LIMIT)
        return copy(
            visibleCount = boundedCount,
            samplesBack = back.coerceIn(0, (samples.size - boundedCount).coerceAtLeast(0)),
            showCo2 = co2,
        )
    }

    companion object { const val HISTORY_LIMIT = 3600 }

    fun resetMaximum(): MonitorState = copy(maxCo2 = current?.co2Ppm ?: 0f)
}
