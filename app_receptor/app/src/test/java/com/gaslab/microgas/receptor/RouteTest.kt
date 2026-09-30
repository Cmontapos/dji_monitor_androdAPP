package com.gaslab.microgas.receptor

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class RouteTest {
    private fun frame() = javaClass.getResourceAsStream("/route.ndjson")!!.bufferedReader().use { it.readText() }
    private fun sample() = MeasurementParser.parse(frame())!!
    @Test fun simulatorHeightSurvivesParsingExportAndArchive() {
        val m = sample()
        assertEquals(0.0, m.altitudeM!!, 0.0)
        assertEquals(9.93, m.latitude!!, 0.0)
        val memory = StringWriter(); MeasurementRepository.exportCsv(listOf(m), memory)
        val rows = memory.toString().trimEnd().lines().map { it.split(',') }
        assertEquals("0.0", rows[1][rows[0].indexOf("altura_m")])
        val dir = Files.createTempDirectory("route").toFile()
        try {
            val archive = CsvArchive(dir); archive.append("route.csv", m)
            val output = ByteArrayOutputStream(); archive.copy("route.csv", output)
            assertEquals(memory.toString(), output.toString("UTF-8"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun absentAndInvalidHeightStayMissingAndNegativeHeightIsAccepted() {
        for (value in listOf("null", "1e999", "\"bad\""))
            assertNull(MeasurementParser.parse(frame().replace("\"altitude_m\":0.0", "\"altitude_m\":$value"))!!.altitudeM)
        assertNull(MeasurementParser.parse(frame().replace("\"altitude_m\":0.0,", ""))!!.altitudeM)
        assertEquals(-12.5, MeasurementParser.parse(frame().replace("\"altitude_m\":0.0", "\"altitude_m\":-12.5"))!!.altitudeM!!, 0.0)
    }
    @Test fun projectionUsesNorthEastMetresAndKeepsMissingFixGaps() {
        val m = sample().copy(latitude = 0.0, longitude = 0.0)
        val points = routePoints(listOf(m, m.copy(latitude = null), m.copy(latitude = .001, longitude = .001)))
        assertEquals(0.0, points[0]!!.east, 0.0); assertNull(points[1])
        assertEquals(111.195, points[2]!!.east, .01); assertEquals(111.195, points[2]!!.north, .01)
        assertTrue(routePoints(listOf(m.copy(longitude = 179.999), m.copy(longitude = -179.999)))[1]!!.east < 223)
    }
}
