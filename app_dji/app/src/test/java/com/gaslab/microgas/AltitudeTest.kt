package com.gaslab.microgas

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AltitudeTest {
    @Test fun simulatedFrameMatchesReceiverContractAndCsvKeepsZero() {
        val sample = simulatedMeasurement(0, 1000)
        val expected = javaClass.getResourceAsStream("/route.ndjson")!!.bufferedReader().use { it.readText() }
        assertEquals(expected, LiveTelemetry.encode(sample, "route", 0))
        val dir = Files.createTempDirectory("altitude").toFile()
        try {
            val journal = CsvJournal(dir)
            val name = journal.append(CsvReading(1000, sample, 0, true))
            journal.append(CsvReading(1100, sample, 0, false))
            val rows = java.io.File(dir, name).readLines().map { it.split(',') }
            val altitude = rows[0].indexOf("altura_m")
            assertEquals("0.0", rows[1][altitude]); assertEquals("0.0", rows[2][altitude])
            assertEquals("simulado", rows[1][rows[0].indexOf("gps_origen")])
        } finally { dir.deleteRecursively() }
    }
    @Test fun rawHeightPreservesNegativeValuesAndInvalidHeightDoesNotLoseCoordinates() {
        val tracker = AircraftPositionTracker().apply { connectionChanged(true) }
        val token = tracker.connectionToken()!!
        tracker.update(token, 9.0, -84.0, 4, 1000, 100, -12.5)
        val first = tracker.snapshot(100)!!
        assertEquals(-12.5, first.altitudeM!!, 0.0)
        tracker.update(token, 9.0, -84.0, 4, 1100, 200, Double.NaN)
        assertNull(tracker.snapshot(200)!!.altitudeM)
        assertEquals(-12.5, first.altitudeM!!, 0.0)
        assertNull(tracker.snapshot(5201))
    }
}
