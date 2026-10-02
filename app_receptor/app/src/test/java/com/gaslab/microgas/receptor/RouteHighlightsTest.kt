package com.gaslab.microgas.receptor

import org.junit.Assert.*
import org.junit.Test

class RouteHighlightsTest {
    private fun sample(i: Long, co2: Float = i.toFloat()) = Measurement(
        i * 1000, co2, latitude = 9.93, longitude = -84.08,
        origin = "simulado", session = "a", sequence = i,
    )

    @Test fun maximaAndHomeSurviveEvictionAndDoNotDependOnGps() {
        val repo = MeasurementRepository(2)
        repo.append(sample(0, 10000f).copy(latitude = null, longitude = null))
        for (i in 1L..7300L) repo.append(sample(i))
        val state = repo.state.value
        assertEquals(2, state.samples.size)
        assertEquals(1L, state.home!!.sequence)
        assertEquals(listOf(0L) + (7300L downTo 7292L).toList(), state.highest[RouteMetric.CO2]!!.map { it.sequence })
        assertNull(state.highest[RouteMetric.CO2]!!.first().latitude)
        repo.clear()
        assertTrue(repo.state.value.highest.isEmpty())
        assertNull(repo.state.value.home)
    }

    @Test fun tiesAreStableAndInvalidOrRejectedValuesDoNotEnterRanking() {
        val repo = MeasurementRepository(2)
        for (i in 0L..14L) repo.append(sample(i, 500f).copy(pm2_5 = if (i == 0L) Float.NaN else i.toFloat()))
        assertEquals((0L..9L).toList(), repo.state.value.highest[RouteMetric.CO2]!!.map { it.sequence })
        assertEquals(14L, repo.state.value.highest[RouteMetric.PM25]!!.first().sequence)
        assertTrue(repo.state.value.highest[RouteMetric.VOC]!!.isEmpty())
        assertFalse(repo.append(sample(14, 99999f)))
        assertEquals(500f, repo.state.value.highest[RouteMetric.CO2]!!.first().co2Ppm)
    }

    @Test fun newSenderSessionRetainsHighestUntilUserStartsNewRecording() {
        val repo = MeasurementRepository(1)
        repo.append(sample(0, 900f))
        repo.append(sample(0, 400f).copy(session = "b"))
        assertEquals("a", repo.state.value.home!!.session)
        assertEquals(listOf("a", "b"), repo.state.value.highest[RouteMetric.CO2]!!.map { it.session })
        assertFalse(repo.append(sample(2, 9999f)))
        repo.clear()
        repo.append(sample(0, 100f).copy(session = "c"))
        assertEquals(listOf("c"), repo.state.value.highest[RouteMetric.CO2]!!.map { it.session })
    }

    @Test fun geographicTicksInvertProjectionIncludingDatelineAndPole() {
        val home = sample(0).copy(longitude = 179.999)
        val next = sample(1).copy(latitude = 9.931, longitude = -179.999)
        val p = routePoints(listOf(next), home).single()!!
        val (lat, lon) = routeCoordinate(home, p.east, p.north)
        assertEquals(next.latitude!!, lat, 1e-8)
        assertEquals(next.longitude!!, lon!!, 1e-8)
        assertNull(routeCoordinate(home.copy(latitude = 90.0), 0.0, 0.0).second)
    }

    @Test fun fixedAnchorKeepsOldAndNewMarkersInSameCoordinateSystem() {
        val home = sample(0)
        val current = sample(1).copy(latitude = 9.931)
        val point = routePoints(listOf(current), home).single()!!
        assertEquals(111.195, point.north, .01)
        assertEquals(0.0, routePoints(listOf(home), home).single()!!.north, 0.0)
    }
}
