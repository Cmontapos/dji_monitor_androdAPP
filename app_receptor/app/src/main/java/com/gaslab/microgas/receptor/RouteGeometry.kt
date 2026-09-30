package com.gaslab.microgas.receptor

import kotlin.math.*

/** Local equirectangular projection in metres, anchored to the first valid fix. */
data class RoutePoint(val sample: Measurement, val east: Double, val north: Double)
fun routePoints(samples: List<Measurement>): List<RoutePoint?> {
    fun valid(m: Measurement) = m.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        m.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    val origin = samples.firstOrNull(::valid) ?: return samples.map { null }
    return samples.map { m ->
        if (!valid(m)) null else {
            val deltaLon = ((m.longitude!! - origin.longitude!! + 540) % 360) - 180
            RoutePoint(m, Math.toRadians(deltaLon) * 6_371_000 * cos(Math.toRadians(origin.latitude!!)),
                Math.toRadians(m.latitude!! - origin.latitude) * 6_371_000)
        }
    }
}
