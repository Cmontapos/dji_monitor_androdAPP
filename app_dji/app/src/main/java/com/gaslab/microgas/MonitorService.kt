package com.gaslab.microgas

import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class MonitorService : Service() {
    companion object {
        const val SHOW = "com.gaslab.microgas.SHOW"
        const val HIDE = "com.gaslab.microgas.HIDE"
        const val STOP = "com.gaslab.microgas.STOP"
        data class ReadingAppearance(val threshold: Int = LiveTelemetry.DEFAULT_THRESHOLD, val alarm: Boolean = false, val whitePhase: Boolean = false)
        val readingAppearance = MutableStateFlow(ReadingAppearance())
        val status = MutableStateFlow("Detenido")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MonitorEngine
    private lateinit var relay: BluetoothRelay
    private var overlay: FloatingMonitor? = null
    private var tone: ToneGenerator? = null
    private var stopping = false
    private var overlayError: String? = null
    private var lastBeep = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("monitor", "Adquisición MicroGas", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("Iniciando adquisición"))
        engine = MonitorEngine.get(application)
        relay = BluetoothRelay(this, scope)
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 80) }.getOrNull()
        engine.onSample = relay::publish
        relay.start()
        engine.start()
        scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val sample = engine.live.value
                val age = if (sample == null) null else now - engine.receivedElapsed
                val threshold = getSharedPreferences("monitor", MODE_PRIVATE).getInt("threshold", LiveTelemetry.DEFAULT_THRESHOLD)
                val fresh = LiveTelemetry.fresh(age)
                val alarm = LiveTelemetry.alarm(sample?.co2Ppm, age, threshold)
                if (alarm && now - lastBeep >= 500) {
                    tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180)
                    lastBeep = now
                }
                if (!alarm) tone?.stopTone()
                val value = sample?.let { String.format(Locale.getDefault(), "%.1f ppm", it.co2Ppm) } ?: "— ppm"
                val reception = when {
                    age == null -> "Esperando datos"
                    fresh -> "Recibiendo · ${age / 1000} s"
                    else -> "SIN DATOS NUEVOS · ${age / 1000} s"
                }
                val detail = listOf(
                    "Activo · ${BuildConfig.DATA_ORIGIN}", reception, relay.status.value,
                    "Alarma ≥ $threshold ppm" + if (alarm) " · PITIDOS" else "",
                    engine.recording.value.error ?: "CSV: ${engine.recording.value.saved} filas",
                    overlayError, engine.failure.value,
                    if (!fresh) engine.sourceStatus.value else null,
                    if (tone == null) "Audio no disponible" else null,
                ).filterNotNull().joinToString("\n")
                status.value = "$value · $detail"
                val whitePhase = (now / 500) % 2 == 1L
                readingAppearance.value = ReadingAppearance(threshold, alarm, whitePhase)
                overlay?.update(value, detail, fresh, co2ReadingColor(sample?.co2Ppm, threshold, alarm, whitePhase).toInt())
                nm.notify(1, notification("$value · $reception · ${relay.status.value}"))
                delay(250)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP && !stopping) {
            stopping = true
            scope.launch {
                engine.stop()
                stopSelf()
            }
        } else if (intent?.action != SHOW && !stopping) {
            hideOverlay()
        } else if (intent?.action == SHOW && !stopping) {
            if (Settings.canDrawOverlays(this)) {
                try {
                    if (overlay == null) overlay = FloatingMonitor(this, onBackground = ::hideOverlay) {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    overlay?.show()
                    overlayError = null
                } catch (_: Exception) { overlayError = "No se pudo mostrar ventana flotante"; overlay?.close(); overlay = null }
            } else overlayError = "Falta permiso de ventana flotante"
        }
        return START_NOT_STICKY
    }
    private fun hideOverlay() {
        overlay?.close()
        overlay = null
        overlayError = null
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "monitor")
            .setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("MicroGas activo · ${BuildConfig.DATA_ORIGIN}")
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build()).build()
    }
    override fun onDestroy() {
        engine.onSample = {}
        engine.cancelAcquisition()
        relay.close(); overlay?.close(); tone?.release(); scope.cancel()
        readingAppearance.value = readingAppearance.value.copy(alarm = false, whitePhase = false)
        status.value = "Detenido"
        super.onDestroy()
    }
}
