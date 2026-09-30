package com.gaslab.microgas.receptor

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

fun isCo2AlertActive(co2: Float?, threshold: Int, ageMs: Long, visible: Boolean): Boolean =
    visible && co2 != null && co2.isFinite() && co2 >= threshold && ageMs in 0..5_000

class AlertManager(context: Context) {
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private var active = false
    fun update(next: Boolean) {
        if (next && !active) vibrator.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE))
        if (!next && active) vibrator.cancel()
        active = next
    }
    fun close() { vibrator.cancel(); active = false }
}
