package com.gaslab.microgas.receptor

/** Stable ties retain the earlier accepted measurement. Null/non-finite values are excluded. */
enum class RouteMetric(val label: String, val value: (Measurement) -> Float?) {
    CO2("CO₂ (ppm)", { it.co2Ppm }),
    TEMPERATURE("Temperatura (°C)", { it.temperatureC }),
    HUMIDITY("Humedad (%)", { it.humidityPct }),
    PM1("PM1 (µg/m³)", { it.pm1 }),
    PM25("PM2.5 (µg/m³)", { it.pm2_5 }),
    PM4("PM4 (µg/m³)", { it.pm4 }),
    PM10("PM10 (µg/m³)", { it.pm10 }),
    VOC("VOC", { it.vocIndex }),
    NOX("NOx", { it.noxIndex });
}

fun hasRouteFix(m: Measurement): Boolean =
    m.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
    m.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true

fun updateHighest(previous: List<Measurement>, sample: Measurement, metric: RouteMetric): List<Measurement> {
    if (metric.value(sample)?.isFinite() != true) return previous
    return (previous + sample).sortedByDescending { metric.value(it) }.take(10)
}
