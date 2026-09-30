package com.gaslab.microgas.receptor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.nio.file.Files
import java.util.Locale

class Sen66ContractTest {
    private fun frame() = javaClass.getResourceAsStream("/sen66-v2.ndjson")!!.bufferedReader().use { it.readText() }
    @Test fun controlFrameSurvivesByteFragmentationAndCsvArchive() {
        val previous = Locale.getDefault(); val directory = Files.createTempDirectory("receiver-sen66").toFile()
        try {
            Locale.setDefault(Locale.GERMANY)
            val received = mutableListOf<Measurement>(); val framer = NdjsonFramer()
            frame().toByteArray().forEach { byte -> framer.feed(byteArrayOf(byte)) { received.add(MeasurementParser.parse(it)!!) } }
            val m = received.single()
            assertEquals(24.5f, m.temperatureC!!, 0f); assertEquals(60.25f, m.humidityPct!!, 0f)
            assertEquals(5.1f, m.pm1!!, 0f); assertEquals(8.3f, m.pm2_5!!, 0f)
            assertEquals(10.5f, m.pm4!!, 0f); assertEquals(12.75f, m.pm10!!, 0f)
            assertEquals(100f, m.vocIndex!!, 0f); assertEquals(20f, m.noxIndex!!, 0f)
            val archive = CsvArchive(directory); archive.append("nine.csv", m)
            val output = ByteArrayOutputStream(); CsvArchive(directory).copy("nine.csv", output)
            val memory = StringWriter(); MeasurementRepository.exportCsv(received, memory)
            assertEquals(memory.toString(), output.toString("UTF-8"))
            val rows = memory.toString().trimEnd().lines().map { it.split(',') }
            assertEquals(16, rows[0].size)
            assertEquals("5.1", rows[1][rows[0].indexOf("pm1_0")])
            assertEquals("100.0", rows[1][rows[0].indexOf("voc_index")])
        } finally { Locale.setDefault(previous); directory.deleteRecursively() }
    }
    @Test fun invalidOptionalFieldsBecomeMissingWithoutLosingCo2() {
        val m = MeasurementParser.parse(frame().replace("5.100", "-1").replace("100.000", "0").replace("20.000", "501"))!!
        assertNull(m.pm1); assertNull(m.vocIndex); assertNull(m.noxIndex)
        assertEquals(650.5f, m.co2Ppm, 0f)
    }
    @Test fun legacyCsvStillExportsUnchanged() {
        val directory = Files.createTempDirectory("legacy-csv").toFile()
        try {
            val legacy = "fecha_hora_utc,co2_ppm\n2026-09-25T00:00:00.000Z,600\n"
            java.io.File(directory, "legacy.csv").writeText(legacy)
            val out = ByteArrayOutputStream(); CsvArchive(directory).copy("legacy.csv", out)
            assertEquals(legacy, out.toString("UTF-8"))
        } finally { directory.deleteRecursively() }
    }
}
