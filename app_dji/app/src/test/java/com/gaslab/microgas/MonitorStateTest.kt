package com.gaslab.microgas

import org.junit.Assert.*
import org.junit.Test

class MonitorStateTest {
    @Test fun historyShowsLatestTenAndKeepsOlderReadings() {
        var state = MonitorState().accept(GasSample(0, 1000f))
        for (i in 1..11) state = state.accept(GasSample(i.toLong(), i.toFloat()))
        assertEquals(12, state.samples.size)
        assertEquals(10, state.visibleSamples.size)
        assertEquals(2L, state.visibleSamples.first().receivedAtMs)
        assertEquals(11L, state.current!!.receivedAtMs)
        assertEquals(1000f, state.maxCo2, 0f)
        assertEquals(0.011f, state.progress, 0.0001f)
    }

    @Test fun historicalWindowStaysFixedWhileCurrentValueUpdates() {
        var state = MonitorState()
        repeat(40) { state = state.accept(GasSample(it.toLong(), it.toFloat())) }
        state = state.selectHistory(10, 20, false)
        assertEquals((10L..19L).toList(), state.visibleSamples.map { it.receivedAtMs })
        val advanced = state.accept(GasSample(40, 40f))
        assertEquals(state.visibleSamples, advanced.visibleSamples)
        assertEquals(40f, advanced.current!!.co2Ppm, 0f)
        assertFalse(advanced.showCo2)
        assertEquals(40L, advanced.selectHistory(10, 0, true).visibleSamples.last().receivedAtMs)
    }

    @Test fun memoryIsBoundedAndMaximumSurvivesEviction() {
        var state = MonitorState().accept(GasSample(0, 9999f))
        repeat(MonitorState.HISTORY_LIMIT + 1) { state = state.accept(GasSample(it + 1L, 10f)) }
        assertEquals(MonitorState.HISTORY_LIMIT, state.samples.size)
        assertEquals(9999f, state.maxCo2, 0f)
        val selected = state.selectHistory(100, Int.MAX_VALUE, true)
        assertEquals(100, selected.visibleSamples.size)
        assertEquals(state.samples.first(), selected.visibleSamples.first())
    }

    @Test fun invalidSamplesDoNotAffectHistoryOrScale() {
        val state = MonitorState().accept(GasSample(0, 500f))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertEquals(state, state.accept(GasSample(1, invalid)))
        }
    }

    @Test fun resetUsesCurrentValueAndKeepsHistory() {
        val state = MonitorState().accept(GasSample(0, 900f)).accept(GasSample(1, 450f))
        val reset = state.resetMaximum()
        assertEquals(450f, reset.maxCo2, 0f)
        assertEquals(1f, reset.progress, 0f)
        assertEquals(state.samples, reset.samples)
    }

    @Test fun zeroAndEmptySamplesHaveFiniteProgress() {
        assertEquals(0f, MonitorState().progress, 0f)
        assertEquals(0f, MonitorState().accept(GasSample(0, 0f)).progress, 0f)
    }
}
