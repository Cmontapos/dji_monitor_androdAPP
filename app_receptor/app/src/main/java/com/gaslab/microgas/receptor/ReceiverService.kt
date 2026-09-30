package com.gaslab.microgas.receptor

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Owns Bluetooth reception independently of activity visibility and recreation. */
class ReceiverService : Service() {
    companion object {
        const val CONNECT = "com.gaslab.microgas.receptor.CONNECT"
        const val STOP = "com.gaslab.microgas.receptor.STOP"
        private const val CHANNEL = "receiver"
        private const val NOTIFICATION_ID = 2
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: ReceiverEngine
    private var updates: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        engine = ReceiverEngine.get(application)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recepción MicroGas", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            engine.stopReception()
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra("address")
        if (intent?.action != CONNECT || address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val initial = notification("Iniciando recepción Bluetooth")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, initial)
            engine.startSession(PairedDevice(intent.getStringExtra("name") ?: address, address))
            if (updates == null) updates = scope.launch {
                engine.state.map { state ->
                    val connection = when (state.connection.phase) {
                        ConnectionPhase.CONNECTED -> "Conectado · ${state.connection.deviceName}"
                        ConnectionPhase.CONNECTING -> "Conectando · ${state.connection.deviceName}"
                        ConnectionPhase.RETRYING -> "Reconectando · ${state.connection.detail}"
                        ConnectionPhase.DISCONNECTED -> "Desconectado"
                    }
                    "$connection · CSV: ${state.savedRows}" + (state.storageError?.let { " · Error: $it" } ?: "")
                }.distinctUntilChanged().collect { detail ->
                    // Notification denial must not interrupt reception or CSV storage.
                    runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(detail)) }
                }
            }
        } catch (error: Exception) {
            engine.message("No se pudo mantener la recepción: ${error.message}")
            engine.stopReception()
            stopSelf()
        }
        // Do not silently start a new session after process death; saved CSV remains on disk.
        return START_NOT_STICKY
    }
    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("MicroGas Receptor activo")
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Desconectar", stop).build()).build()
    }
    override fun onDestroy() {
        scope.cancel()
        engine.stopReception()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
