package com.gaslab.microgas

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32
import java.nio.file.Files
import java.io.ByteArrayOutputStream

class Sen66ContractTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }
    private fun packet() = resource("sen66-v2.hex").trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun changed(offset: Int, value: Float): ByteArray {
        val bytes = packet()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(offset, value); b.putInt(60, CRC32().apply { update(bytes, 0, 60) }.value.toInt())
        return bytes
    }
    @Test fun cPacketDecodesAndProducesExactReceiverJsonUnderGermanLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val sample = PayloadProtocol().decode(packet(), 1234)!!
            assertEquals(24.5f, sample.temperatureC!!, 0f)
            assertEquals(60.25f, sample.humidityPct!!, 0f)
            assertEquals(5.1f, sample.pm1!!, 0f)
            assertEquals(8.3f, sample.pm2_5!!, 0f)
            assertEquals(10.5f, sample.pm4!!, 0f)
            assertEquals(12.75f, sample.pm10!!, 0f)
            assertEquals(100f, sample.vocIndex!!, 0f)
            assertEquals(20f, sample.noxIndex!!, 0f)
            assertEquals(resource("sen66-v2.ndjson"), LiveTelemetry.encode(sample, "fixture", 7))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun rejectsCorruptExtendedCrcWrongVersionAndMismatchedLength() {
        assertNull(PayloadProtocol().decode(packet().apply { this[59] = (this[59].toInt() xor 1).toByte() }, 0))
        assertNull(PayloadProtocol().decode(packet().copyOf(32), 0))
        assertNull(PayloadProtocol().decode(packet().apply { this[4] = 1 }, 0))
        assertNull(PayloadProtocol().decode(changed(24, Float.NaN), 0))
    }
    @Test fun optionalInvalidChannelDoesNotDiscardValidCo2() {
        assertNull(PayloadProtocol().decode(changed(28, Float.NaN), 0)!!.temperatureC)
        assertNull(PayloadProtocol().decode(changed(32, 101f), 0)!!.humidityPct)
        assertNull(PayloadProtocol().decode(changed(36, -1f), 0)!!.pm1)
        assertNull(PayloadProtocol().decode(changed(52, 0f), 0)!!.vocIndex)
        assertNull(PayloadProtocol().decode(changed(56, 501f), 0)!!.noxIndex)
        assertEquals(-5f, PayloadProtocol().decode(changed(28, -5f), 0)!!.temperatureC!!, 0f)
    }
    @Test fun extendedPacketsKeepSequenceProtection() {
        val decoder = PayloadProtocol()
        assertNotNull(decoder.decode(packet(), 0))
        assertNull(decoder.decode(packet(), 1))
        val bytes = packet(); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(12, 8); b.putInt(60, CRC32().apply { update(bytes, 0, 60) }.value.toInt())
        assertNotNull(decoder.decode(bytes, 2))
    }
    @Test fun csvHoldKeepsAllNineChannelsAndLegacyStaysEmpty() {
        val directory = Files.createTempDirectory("sen66-csv").toFile()
        try {
            val journal = CsvJournal(directory); val hold = SampleHold()
            hold.accept(PayloadProtocol().decode(packet(), 1234)!!)
            val fresh = hold.reading(1300)!!; val name = journal.append(fresh); hold.saved(fresh)
            journal.append(hold.reading(1400)!!)
            journal.append(CsvReading(1500, GasSample(1500, 650f), 2, true))
            val out = ByteArrayOutputStream(); journal.snapshot(name).copyTo(out)
            val rows = out.toString("UTF-8").trimEnd().lines().map { it.split(',') }
            assertEquals(24, rows.first().size)
            for (column in listOf("temperatura_c", "humedad_pct", "pm1_0", "pm2_5", "pm4_0", "pm10", "voc_index", "nox_index")) {
                val index = rows[0].indexOf(column)
                assertNotEquals("", rows[1][index]); assertEquals(rows[1][index], rows[2][index]); assertEquals("", rows[3][index])
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun demoEmitsNineChangingChannelsWithSyntheticPosition() {
        val first = simulatedMeasurement(0, 0); val second = simulatedMeasurement(8, 1000)
        assertEquals("simulado", first.origin); assertEquals("simulado", first.aircraftPosition!!.source);
        assertEquals(0.0, first.aircraftPosition.altitudeM!!, 0.0);
        assertNotEquals(first.aircraftPosition.latitude, second.aircraftPosition!!.latitude);
        assertNotEquals(first.aircraftPosition.longitude, second.aircraftPosition.longitude);
        assertTrue(second.aircraftPosition.altitudeM!! > 0); assertNull(first.senderBoot)
        fun channels(m: GasSample) = listOf(m.co2Ppm, m.temperatureC, m.humidityPct, m.pm1, m.pm2_5, m.pm4, m.pm10, m.vocIndex, m.noxIndex)
        channels(first).zip(channels(second)).forEach { (a,b) -> assertNotNull(a); assertNotNull(b); assertNotEquals(a,b) }
        assertTrue(LiveTelemetry.encode(first, "demo", 1).contains("\"origin\":\"simulado\""))
    }
}
