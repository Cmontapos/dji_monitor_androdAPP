package com.gaslab.microgas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Only the visible panel consumes touches; Pilot 2 receives touches outside its bounds. */
class FloatingMonitor(
    private val context: Context,
    private val onBackground: () -> Unit,
    private val onOpen: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var reading: TextView? = null
    private var details: TextView? = null
    private var collapsed = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 24; y = 100 }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.rgb(22, 35, 47))
        }
        val title = TextView(context).apply { text = "MicroGas · arrastrar"; setTextColor(Color.WHITE); textSize = 12f }
        reading = TextView(context).apply { setTextColor(Color.WHITE); textSize = 22f }
        details = TextView(context).apply { setTextColor(Color.LTGRAY); textSize = 11f; maxWidth = dp(260) }
        val minimize = Button(context).apply {
            text = "Minimizar"
            setOnClickListener { collapsed = true; showMode() }
        }
        val open = Button(context).apply { text = "Abrir app"; setOnClickListener { onOpen() } }
        val background = Button(context).apply {
            text = "Segundo plano"
            setOnClickListener { onBackground() }
        }
        for (button in listOf(minimize, open, background)) {
            button.setTextColor(Color.WHITE)
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(44, 62, 79))
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for (button in listOf(minimize, background)) {
                button.textSize = 12f
                button.minWidth = 0
                addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        panel.addView(title); panel.addView(reading); panel.addView(details)
        panel.addView(actions, LinearLayout.LayoutParams(dp(260), LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(open)
        var initialX = 0; var initialY = 0; var downX = 0f; var downY = 0f; var moved = false
        val drag = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; downX = event.rawX; downY = event.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > dp(6)) moved = true
                    val bounds = context.resources.displayMetrics
                    params.x = (initialX + dx.toInt()).coerceIn(0, (bounds.widthPixels - panel.width).coerceAtLeast(0))
                    params.y = (initialY + dy.toInt()).coerceIn(0, (bounds.heightPixels - panel.height).coerceAtLeast(0))
                    wm.updateViewLayout(panel, params); true
                }
                MotionEvent.ACTION_UP -> { if (!moved && collapsed) { collapsed = false; showMode() }; true }
                else -> false
            }
        }
        title.setOnTouchListener(drag); reading?.setOnTouchListener(drag)
        wm.addView(panel, params); root = panel
    }
    private fun showMode() {
        val panel = root ?: return
        for (i in 0 until panel.childCount) panel.getChildAt(i).visibility =
            if (collapsed && i != 1) View.GONE else View.VISIBLE
    }
    fun update(value: String, status: String, fresh: Boolean, readingColor: Int) {
        reading?.text = if (collapsed) "$value\n${if (fresh) "RX ✓" else "RX —"} · ${if (status.contains("BT: enviando")) "BT ↑" else if (status.contains("BT: conectado")) "BT ✓" else "BT —"}" else value
        reading?.setTextColor(readingColor)
        details?.text = status
    }
    fun close() { root?.let { runCatching { wm.removeView(it) } }; root = null }
}
