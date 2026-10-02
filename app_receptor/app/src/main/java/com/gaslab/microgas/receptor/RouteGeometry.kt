package com.gaslab.microgas.receptor

import kotlin.math.*

/** Local equirectangular projection in metres, anchored to the first valid fix. */
data class RoutePoint(val sample: Measurement, val east: Double, val north: Double)
fun routePoints(samples: List<Measurement>, anchor: Measurement? = null): List<RoutePoint?> {
    fun valid(m: Measurement) = m.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        m.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    val origin = anchor?.takeIf(::valid) ?: samples.firstOrNull(::valid) ?: return samples.map { null }
    return samples.map { m ->
        if (!valid(m)) null else {
            val deltaLon = ((m.longitude!! - origin.longitude!! + 540) % 360) - 180
            RoutePoint(m, Math.toRadians(deltaLon) * 6_371_000 * cos(Math.toRadians(origin.latitude!!)),
                Math.toRadians(m.latitude!! - origin.latitude) * 6_371_000)
        }
    }
}

/** Inverse local projection for geographic tick labels; longitude is undefined at a pole. */
fun routeCoordinate(anchor: Measurement, east: Double, north: Double): Pair<Double, Double?> {
    val latitude = anchor.latitude!! + Math.toDegrees(north / 6_371_000)
    val cosine = cos(Math.toRadians(anchor.latitude))
    val longitude = if (abs(cosine) > 1e-9)
        ((anchor.longitude!! + Math.toDegrees(east / (6_371_000 * cosine)) + 540) % 360) - 180
        else null
    return latitude to longitude
}
