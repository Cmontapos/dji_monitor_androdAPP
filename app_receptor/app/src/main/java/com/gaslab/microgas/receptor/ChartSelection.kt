package com.gaslab.microgas.receptor

/** Screen coordinates ensure picking still matches the plotted point after zoom and pan. */
data class ChartPoint(val key: Pair<String, Long>, val x: Float, val y: Float)

fun nearestChartPoint(points: List<ChartPoint>, x: Float, y: Float, radius: Float): Pair<String, Long>? {
    if (!x.isFinite() || !y.isFinite() || !radius.isFinite() || radius < 0) return null
    return points.filter { it.x.isFinite() && it.y.isFinite() }
        .minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
        ?.takeIf { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) <= radius * radius }
        ?.key
}
