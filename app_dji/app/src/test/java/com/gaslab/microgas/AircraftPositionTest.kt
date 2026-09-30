package com.gaslab.microgas

import org.junit.Assert.*
import org.junit.Test

class AircraftPositionTest {
    private fun connected() = AircraftPositionTracker().apply { connectionChanged(true) }

    @Test fun recentCoordinatesIncludeAgeAndExpireUsingMonotonicTime() {
        val tracker = connected()
        assertTrue(tracker.update(tracker.connectionToken()!!, 9.9, -84.1, 4, 1700000000000, 100))
        val fix = tracker.snapshot(350)!!
        assertEquals(250L, fix.ageAtSampleMs)
        assertEquals(1700000000000L, fix.receivedAtMs)
        assertNotNull(tracker.snapshot(5100))
        assertNull(tracker.snapshot(5101))
        assertNull(tracker.snapshot(99))
    }

    @Test fun nullNonFiniteAndOutOfRangeCoordinatesClearPreviousFix() {
        val tracker = connected()
        val token = tracker.connectionToken()!!
        for ((lat, lon) in listOf(null to 0.0, 0.0 to null, Double.NaN to 0.0,
            0.0 to Double.POSITIVE_INFINITY, 90.1 to 0.0, -90.1 to 0.0, 0.0 to 180.1, 0.0 to -180.1)) {
            assertTrue(tracker.update(token, 9.0, -84.0, 4, 1000, 100))
            assertFalse(tracker.update(token, lat, lon, 4, 2000, 200))
            assertNull(tracker.snapshot(200))
        }
    }

    @Test fun zeroAndValidBoundaryCoordinatesAreNotMissingValues() {
        val tracker = connected()
        val token = tracker.connectionToken()!!
        for ((lat, lon) in listOf(0.0 to 0.0, -90.0 to -180.0, 90.0 to 180.0)) {
            assertTrue(tracker.update(token, lat, lon, 3, 1000, 100))
            assertEquals(lat, tracker.snapshot(100)!!.latitude, 0.0)
        }
    }

    @Test fun missingOrWeakSignalIsRejectedAndRecoveryIsAccepted() {
        val tracker = connected()
        val token = tracker.connectionToken()!!
        for (level in listOf(null, -1, 0, 1, 2, 6, 255)) {
            assertFalse(tracker.update(token, 9.0, -84.0, level, 1000, 100))
            assertNull(tracker.snapshot(100))
        }
        for (level in listOf(3, 4, 5, 10)) assertTrue(tracker.update(token, 9.0, -84.0, level, 1000, 100))
    }

    @Test fun reconnectRejectsLateResponsesFromPreviousAircraft() {
        val tracker = connected()
        val old = tracker.connectionToken()!!
        tracker.update(old, 9.0, -84.0, 4, 1000, 100)
        tracker.connectionChanged(false)
        assertNull(tracker.snapshot(100))
        assertNull(tracker.connectionToken())
        assertFalse(tracker.update(old, 9.0, -84.0, 4, 1000, 100))
        tracker.connectionChanged(true)
        val current = tracker.connectionToken()!!
        assertFalse(tracker.update(old, 9.0, -84.0, 4, 1000, 100))
        assertNull(tracker.snapshot(100))
        assertTrue(tracker.update(current, 10.0, -85.0, 4, 2000, 200))
        tracker.invalidate(old)
        assertEquals(10.0, tracker.snapshot(200)!!.latitude, 0.0)
    }

    @Test fun repeatedCoordinatesRefreshWithoutChangingPreviouslyPairedSample() {
        val tracker = connected()
        val token = tracker.connectionToken()!!
        tracker.update(token, 9.0, -84.0, 4, 1000, 100)
        val sample = GasSample(1050, 700f, "dji", aircraftPosition = tracker.snapshot(150))
        tracker.update(token, 9.0, -84.0, 4, 2000, 6000)
        assertNotNull(tracker.snapshot(6000))
        assertEquals(50L, sample.aircraftPosition!!.ageAtSampleMs)
        assertEquals(1000L, sample.aircraftPosition.receivedAtMs)
        tracker.invalidate(token)
        assertNull(tracker.snapshot(6000))
        assertNotNull(sample.aircraftPosition)
    }
}
