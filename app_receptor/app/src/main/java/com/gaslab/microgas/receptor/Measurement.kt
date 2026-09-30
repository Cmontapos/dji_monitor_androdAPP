package com.gaslab.microgas.receptor

import org.json.JSONObject

data class Measurement(
    val receivedAtMs: Long,
    val co2Ppm: Float,
    val temperatureC: Float? = null,
    val humidityPct: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val origin: String,
    val session: String,
    val sequence: Long,
    val senderBoot: Long? = null,
    val senderSequence: Long? = null,
    val acquiredUptimeMs: Long? = null,
    val signalLevel: Int? = null,
    val pm1: Float? = null,
    val pm2_5: Float? = null,
    val pm4: Float? = null,
    val pm10: Float? = null,
    val vocIndex: Float? = null,
    val noxIndex: Float? = null,
    val altitudeM: Double? = null,
)

object MeasurementParser {
    fun parse(line: String): Measurement? = try {
        val json = JSONObject(line)
        require(json.getInt("v") == 1)
        val co2 = json.getDouble("co2_ppm").toFloat()
        require(co2.isFinite() && co2 >= 0)
        val session = json.getString("session")
        val sequence = json.getLong("sequence")
        val timestamp = json.getLong("received_at_ms")
        require(session.isNotBlank() && sequence >= 0 && timestamp >= 0)
        val position = json.optJSONObject("aircraft_position")
        val lat = position?.finite("latitude")?.takeIf { it in -90.0..90.0 }
        val lon = position?.finite("longitude")?.takeIf { it in -180.0..180.0 }
        Measurement(
            timestamp, co2,
            json.finite("temperature_c")?.toFloat()?.takeIf { it.isFinite() },
            json.finite("humidity_pct")?.takeIf { it in 0.0..100.0 }?.toFloat(),
            lat.takeIf { lon != null }, lon.takeIf { lat != null },
            json.getString("origin"), session, sequence,
            json.nullableLong("sender_boot"), json.nullableLong("sender_sequence"),
            json.nullableLong("acquired_uptime_ms"),
            position?.nullableLong("signal_level")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
            altitudeM = position?.finite("altitude_m"),
            pm1 = json.finite("pm1_0")?.toFloat()?.takeIf { it.isFinite() && it >= 0 },
            pm2_5 = json.finite("pm2_5")?.toFloat()?.takeIf { it.isFinite() && it >= 0 },
            pm4 = json.finite("pm4_0")?.toFloat()?.takeIf { it.isFinite() && it >= 0 },
            pm10 = json.finite("pm10")?.toFloat()?.takeIf { it.isFinite() && it >= 0 },
            vocIndex = json.finite("voc_index")?.toFloat()?.takeIf { it.isFinite() && it in 1f..500f },
            noxIndex = json.finite("nox_index")?.toFloat()?.takeIf { it.isFinite() && it in 1f..500f },
        )
    } catch (_: Exception) { null }

    private fun JSONObject.finite(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)
}

/** Bounded byte framing preserves UTF-8 characters split across socket reads. */
class NdjsonFramer(private val limit: Int = 16_384) {
    private val buffer = java.io.ByteArrayOutputStream()
    private var discarding = false
    fun feed(bytes: ByteArray, count: Int = bytes.size, onLine: (String) -> Unit) {
        for (i in 0 until count) {
            val byte = bytes[i]
            if (byte == 10.toByte()) {
                if (!discarding && buffer.size() > 0) onLine(buffer.toString("UTF-8").trimEnd('\r'))
                buffer.reset()
                discarding = false
            } else if (!discarding) {
                if (buffer.size() == limit) { buffer.reset(); discarding = true }
                else buffer.write(byte.toInt())
            }
        }
    }
}
