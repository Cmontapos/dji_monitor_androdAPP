package com.gaslab.microgas

import org.junit.Assert.*
import org.junit.Test

class SampleHoldTest {
    @Test fun noRowsUntilFirstValidSample() {
        val hold = SampleHold()
        assertNull(hold.reading(0))
        assertFalse(hold.accept(GasSample(0, Float.NaN)))
        assertNull(hold.reading(100))
    }

    @Test fun tenStorageTicksContainOnlyOneNewMeasurement() {
        val hold = SampleHold()
        hold.accept(GasSample(0, 540f))
        val rows = (0..9).map { tick ->
            hold.reading(tick * 100L)!!.also { hold.saved(it) }
        }
        assertEquals(1, rows.count { it.newData })
        assertTrue(rows.all { it.sample.co2Ppm == 540f && it.sample.receivedAtMs == 0L })
        assertEquals(900L, rows.last().recordedAtMs)
        // Equal values can be genuinely new samples; never compare only the CO2 value.
        hold.accept(GasSample(1000, 540f))
        assertTrue(hold.reading(1000)!!.newData)
        assertEquals(2L, hold.reading(1000)!!.sequence)
    }

    @Test fun invalidSamplesKeepLastKnownValueWithoutNewFlag() {
        val hold = SampleHold()
        hold.accept(GasSample(0, 540f))
        hold.saved(hold.reading(0)!!)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertFalse(hold.accept(GasSample(100, value)))
        }
        assertEquals(540f, hold.reading(5000)!!.sample.co2Ppm, 0f)
        assertFalse(hold.reading(5000)!!.newData)
    }

    @Test fun failedWriteAndArrivalDuringWritePreserveNewFlag() {
        val hold = SampleHold()
        hold.accept(GasSample(0, 540f))
        val old = hold.reading(0)!!
        // A failed write isn't acknowledged.
        assertTrue(hold.reading(100)!!.newData)
        hold.accept(GasSample(1000, 545f))
        hold.saved(old)
        val next = hold.reading(1100)!!
        assertTrue(next.newData)
        hold.saved(next)
        assertFalse(hold.reading(1200)!!.newData)
    }
}
