package com.gaslab.microgas

import java.util.Locale

object LiveTelemetry {
    const val DEFAULT_THRESHOLD = 3000
    const val STALE_MS = 5000L
    fun fresh(ageMs: Long?) = ageMs != null && ageMs in 0..STALE_MS
    fun alarm(co2: Float?, ageMs: Long?, threshold: Int) =
        co2 != null && co2.isFinite() && co2 >= threshold && fresh(ageMs)

    private fun optional(value: Float?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.3f", it) } ?: "null"

    // NDJSON v1: one UTF-8 line per NEW measurement, never CSV hold rows.
    fun encode(sample: GasSample, session: String, sequence: Long): String =
        "{\"v\":1,\"session\":\"$session\",\"sequence\":$sequence," +
        "\"received_at_ms\":${sample.receivedAtMs},\"co2_ppm\":${String.format(Locale.US, "%.3f", sample.co2Ppm)}," +
        "\"temperature_c\":${optional(sample.temperatureC)}," +
        "\"humidity_pct\":${optional(sample.humidityPct)}," +
        "\"pm1_0\":${optional(sample.pm1)}," +
        "\"pm2_5\":${optional(sample.pm2_5)}," +
        "\"pm4_0\":${optional(sample.pm4)}," +
        "\"pm10\":${optional(sample.pm10)}," +
        "\"voc_index\":${optional(sample.vocIndex)}," +
        "\"nox_index\":${optional(sample.noxIndex)}," +
        "\"origin\":\"${if (sample.origin == "dji") "dji" else "simulado"}\"," +
        "\"sender_boot\":${sample.senderBoot},\"sender_sequence\":${sample.senderSequence}," +
        "\"acquired_uptime_ms\":${sample.acquiredUptimeMs}," +
        "\"aircraft_position\":" + (sample.aircraftPosition?.let { gps ->
            "{\"latitude\":${gps.latitude},\"longitude\":${gps.longitude}," +
                "\"source\":\"${gps.source}\",\"received_at_ms\":${gps.receivedAtMs}," +
                "\"altitude_m\":${gps.altitudeM},\"age_at_sample_ms\":${gps.ageAtSampleMs},\"signal_level\":${gps.signalLevel}}"
        } ?: "null") + "}\n"
}
