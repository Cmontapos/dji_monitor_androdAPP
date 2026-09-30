package com.gaslab.microgas

/** Colors follow the configured alarm threshold; missing readings stay white. */
fun co2ReadingColor(co2: Float?, threshold: Int, alarm: Boolean, whitePhase: Boolean): Long = when {
    co2 == null || !co2.isFinite() || threshold <= 0 -> 0xFFFFFFFF
    alarm -> if (whitePhase) 0xFFFFFFFF else 0xFFFF5252
    co2.toDouble() > threshold * 0.9 -> 0xFFFF5252
    co2.toDouble() >= threshold * 0.7 -> 0xFFFFD600
    else -> 0xFFFFFFFF
}
