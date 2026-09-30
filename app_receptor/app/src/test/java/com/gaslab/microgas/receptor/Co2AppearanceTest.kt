package com.gaslab.microgas.receptor

import org.junit.Assert.assertEquals
import org.junit.Test

class Co2AppearanceTest {
    @Test fun proximityBoundariesFollowConfiguredThreshold() {
        for (threshold in listOf(1000, 3000, 10000)) {
            assertEquals(0xFFFFFFFF, co2ReadingColor(threshold * .7f - 1, threshold, false, false))
            assertEquals(0xFFFFD600, co2ReadingColor(threshold * .7f, threshold, false, false))
            assertEquals(0xFFFFD600, co2ReadingColor(threshold * .9f, threshold, false, false))
            assertEquals(0xFFFF5252, co2ReadingColor(threshold * .9f + 1, threshold, false, false))
        }
    }
    @Test fun onlyActiveAlarmBlinks() {
        assertEquals(0xFFFF5252, co2ReadingColor(3000f, 3000, true, false))
        assertEquals(0xFFFFFFFF, co2ReadingColor(3000f, 3000, true, true))
        assertEquals(0xFFFF5252, co2ReadingColor(3000f, 3000, false, true))
        assertEquals(0xFFFFFFFF, co2ReadingColor(null, 3000, false, false))
        assertEquals(0xFFFFFFFF, co2ReadingColor(Float.NaN, 3000, false, false))
    }
}
