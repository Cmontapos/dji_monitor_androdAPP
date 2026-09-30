package com.gaslab.microgas

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LiveTelemetryTest {
    @Test fun thresholdIsInclusiveAndConfigurable() {
        assertFalse(LiveTelemetry.alarm(2999f, 0, 3000))
        assertTrue(LiveTelemetry.alarm(3000f, 0, 3000))
        assertTrue(LiveTelemetry.alarm(3500f, 1000, 3000))
        assertFalse(LiveTelemetry.alarm(3500f, 1000, 4000))
    }
    @Test fun lostOrInvalidReadingsDoNotKeepBeeping() {
        assertTrue(LiveTelemetry.alarm(3000f, 5000, 3000))
        assertFalse(LiveTelemetry.alarm(3000f, 5001, 3000))
        assertFalse(LiveTelemetry.alarm(3000f, null, 3000))
        assertFalse(LiveTelemetry.alarm(null, 0, 3000))
        assertFalse(LiveTelemetry.alarm(Float.NaN, 0, 3000))
        assertFalse(LiveTelemetry.alarm(Float.POSITIVE_INFINITY, 0, 3000))
        assertFalse(LiveTelemetry.alarm(3000f, -1, 3000))
    }
    @Test fun bluetoothFramePreservesIdentityAndIsLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val line = LiveTelemetry.encode(GasSample(1234, 3000.5f, "dji", 4, 99, 1000), "abc", 10)
            assertEquals("{\"v\":1,\"session\":\"abc\",\"sequence\":10,\"received_at_ms\":1234,\"co2_ppm\":3000.500,\"temperature_c\":null,\"humidity_pct\":null,\"pm1_0\":null,\"pm2_5\":null,\"pm4_0\":null,\"pm10\":null,\"voc_index\":null,\"nox_index\":null,\"origin\":\"dji\",\"sender_boot\":4,\"sender_sequence\":99,\"acquired_uptime_ms\":1000,\"aircraft_position\":null}\n", line)
            assertEquals(1, line.count { it == '\n' })
        } finally { Locale.setDefault(previous) }
    }
    @Test fun simulationIsExplicitAndAbsentPayloadMetadataIsNull() {
        val frame = LiveTelemetry.encode(GasSample(1, 700f), "abc", 1)
        assertTrue(frame.contains("\"origin\":\"simulado\""))
        assertTrue(frame.contains("\"sender_sequence\":null"))
        assertTrue(frame.contains("\"aircraft_position\":null"))
    }
    @Test fun bluetoothIncludesAircraftPositionAndItsAgeWithoutLocaleCommas() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val position = AircraftPosition(9.123456, -84.987654, 1000, 234, 4)
            val frame = LiveTelemetry.encode(GasSample(1234, 700f, "dji", aircraftPosition = position), "abc", 1)
            assertTrue(frame.contains("\"aircraft_position\":{\"latitude\":9.123456,\"longitude\":-84.987654"))
            assertTrue(frame.contains("\"source\":\"dji_aircraft_msdk\",\"received_at_ms\":1000,\"altitude_m\":null,\"age_at_sample_ms\":234,\"signal_level\":4}"))
            assertEquals(1, frame.count { it == '\n' })
        } finally { Locale.setDefault(previous) }
    }

}
