package com.gaslab.microgas.receptor

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.util.Locale

class ReceiverTest {
    private fun sample(sequence: Long, session: String = "a") = Measurement(
        receivedAtMs = 1790000000123, co2Ppm = 3000.5f, origin = "dji", session = session, sequence = sequence,
    )
    private val packet = """{"v":1,"session":"a","sequence":15,"received_at_ms":1790000000123,"co2_ppm":3000.500,"origin":"dji","sender_boot":4,"sender_sequence":99,"acquired_uptime_ms":1000,"aircraft_position":{"latitude":9.123456,"longitude":-84.987654,"signal_level":4}}"""

    @Test fun parsesExistingPacketAndGps() {
        val m = requireNotNull(MeasurementParser.parse(packet))
        assertEquals(3000.5f, m.co2Ppm)
        assertEquals(9.123456, m.latitude!!, 0.000001)
        assertEquals(-84.987654, m.longitude!!, 0.000001)
        assertEquals(4L, m.senderBoot)
        assertNull(m.temperatureC); assertNull(m.humidityPct)
    }
    @Test fun nullableSimulationAndUnknownFields() {
        val m = requireNotNull(MeasurementParser.parse("""{"v":1,"session":"b","sequence":1,"received_at_ms":0,"co2_ppm":500,"origin":"simulado","sender_boot":null,"aircraft_position":null,"future":{"anything":1}}"""))
        assertNull(m.latitude); assertNull(m.senderBoot)
    }
    @Test fun futureTemperatureAndHumidity() {
        val m = requireNotNull(MeasurementParser.parse(packet.dropLast(1) + ",\"temperature_c\":24.2,\"humidity_pct\":65.0}"))
        assertEquals(24.2f, m.temperatureC); assertEquals(65f, m.humidityPct)
    }
    @Test fun rejectsInvalidPackets() {
        listOf("not json", packet.replace("\"v\":1", "\"v\":2"), packet.replace("3000.500", "-2"),
            packet.replace("3000.500", "1e100"), packet.replace("\"sequence\":15", "\"sequence\":-1"))
            .forEach { assertNull(MeasurementParser.parse(it)) }
    }
    @Test fun invalidGpsDoesNotLoseCo2() {
        val m = requireNotNull(MeasurementParser.parse(packet.replace("9.123456", "91")))
        assertNull(m.latitude); assertNull(m.longitude); assertEquals(3000.5f, m.co2Ppm)
    }
    @Test fun framerHandlesChunksMultipleLinesCrLfAndUtf8() {
        val framer = NdjsonFramer(); val lines = mutableListOf<String>()
        "CO₂\r\nsegunda\nparcial".toByteArray(Charsets.UTF_8).forEach { framer.feed(byteArrayOf(it), onLine = lines::add) }
        assertEquals(listOf("CO₂", "segunda"), lines)
        framer.feed(" final\n".toByteArray(), onLine = lines::add)
        assertEquals("parcial final", lines.last())
    }
    @Test fun oversizedLineResynchronizesAtNewline() {
        val lines = mutableListOf<String>(); val framer = NdjsonFramer(5)
        framer.feed("123456789\nok\n".toByteArray(), onLine = lines::add)
        assertEquals(listOf("ok"), lines)
    }
    @Test fun boundedHistoryTracksMissingAndDuplicateSequences() {
        val r = MeasurementRepository(2)
        assertTrue(r.append(sample(1))); assertTrue(r.append(sample(4)))
        assertFalse(r.append(sample(4))); assertFalse(r.append(sample(2)))
        r.append(sample(5))
        assertEquals(listOf(4L, 5L), r.state.value.samples.map { it.sequence })
        assertEquals(2L, r.state.value.missingSequences)
        assertEquals(2L, r.state.value.ignoredSamples)
        assertEquals(1L, r.state.value.evictedSamples)
    }
    @Test fun restartAcceptsNewSequenceAndRejectsRetiredSession() {
        val r = MeasurementRepository()
        r.append(sample(100)); r.append(sample(1, "b"))
        assertFalse(r.append(sample(101)))
        assertEquals(1, r.state.value.sessionChanges)
        assertEquals(0L, r.state.value.missingSequences)
        r.clear(); assertTrue(r.append(sample(0)))
        assertEquals(1, r.state.value.samples.size)
    }
    @Test fun historyIsImmutableSnapshot() {
        val r = MeasurementRepository(2); r.append(sample(1))
        val snapshot = r.state.value.samples
        r.append(sample(2)); r.append(sample(3))
        assertEquals(listOf(1L), snapshot.map { it.sequence })
    }
    @Test fun csvUsesUtcMillisecondsDotDecimalsAndEmptyOptionalFields() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val output = StringWriter()
            MeasurementRepository.exportCsv(listOf(sample(1)), output)
            val row = output.toString().lines()[1].split(',')
            assertEquals(16, row.size)
            assertTrue(row[0].endsWith(".123Z")); assertEquals("3000.5", row[1])
            assertEquals(listOf("", "", "", ""), row.subList(2, 6))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun csvEscapesQuotedTextAndPropagatesWriteFailure() {
        val output = StringWriter()
        MeasurementRepository.exportCsv(listOf(sample(1).copy(origin = "test,\"quoted\"")), output)
        assertTrue(output.toString().contains("\"test,\"\"quoted\"\"\""))
        val broken = object : java.io.Writer() {
            override fun write(c: CharArray, off: Int, len: Int) { throw java.io.IOException("full") }
            override fun flush() {}
            override fun close() {}
        }
        assertThrows(java.io.IOException::class.java) { MeasurementRepository.exportCsv(listOf(sample(1)), broken) }
    }
    @Test fun alarmThresholdAndFreshnessBoundaries() {
        assertTrue(isCo2AlertActive(3000f, 3000, 5000, true))
        assertFalse(isCo2AlertActive(3000f, 3000, 5001, true))
        assertFalse(isCo2AlertActive(2999f, 3000, 0, true))
        assertFalse(isCo2AlertActive(3000f, 3000, 0, false))
        assertFalse(isCo2AlertActive(Float.NaN, 3000, 0, true))
        assertFalse(isCo2AlertActive(3000f, 3000, -1, true))
    }
    @Test fun backoffCapsAtThirtySeconds() {
        assertEquals(listOf(5, 10, 20, 30, 30, 30), (0..5).map(BluetoothClient::retryDelaySeconds))
    }
}
