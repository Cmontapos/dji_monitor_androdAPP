package com.gaslab.microgas.receptor

import org.junit.Assert.*
import org.junit.Test

class ChartSelectionTest {
    @Test fun selectsNearestPointInBothDimensions() {
        val points = listOf(ChartPoint("a" to 1L, 100f, 20f), ChartPoint("a" to 2L, 100f, 90f))
        assertEquals("a" to 2L, nearestChartPoint(points, 102f, 88f, 32f))
        assertEquals("a" to 1L, nearestChartPoint(points, 100f, 20f, 32f))
    }
    @Test fun emptySpaceClearsSelection() {
        assertNull(nearestChartPoint(emptyList(), 0f, 0f, 32f))
        assertNull(nearestChartPoint(listOf(ChartPoint("a" to 1L, 100f, 100f)), 0f, 0f, 32f))
    }
    @Test fun sessionIdentityAndTouchBoundaryArePreserved() {
        val points = listOf(ChartPoint("old" to 1L, 10f, 10f), ChartPoint("new" to 1L, 100f, 100f))
        assertEquals("new" to 1L, nearestChartPoint(points, 132f, 100f, 32f))
        assertNull(nearestChartPoint(points, 133f, 100f, 32f))
    }
}
