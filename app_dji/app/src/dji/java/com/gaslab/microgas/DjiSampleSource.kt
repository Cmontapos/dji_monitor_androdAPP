package com.gaslab.microgas

import android.app.Application
import android.content.pm.PackageManager
import android.os.SystemClock
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.listener.PayloadDataListener
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicLong

class DjiSampleSource(private val application: Application) : SampleSource {
    override val status = MutableStateFlow("DJI: preparando conexión")
    @Volatile private var connected = false
    @Volatile private var initialized = false
    @Volatile private var registered = false
    @Volatile private var registrationError: String? = null

    override fun samples() = callbackFlow<GasSample> {
        connected = false
        initialized = false
        registered = false
        registrationError = null
        val key = application.packageManager.getApplicationInfo(application.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.dji.sdk.API_KEY")
        if (key.isNullOrBlank()) {
            status.value = "DJI: falta App Key MSDK; configura dji.msdk.apiKey y recompila"
            awaitClose { }
            return@callbackFlow
        }
        while (!hasDjiPermissions(application)) {
            status.value = "DJI: esperando permisos; habilítalos en ajustes"
            delay(1000)
        }
        val gps = DjiAircraftPosition()
        val decoder = PayloadProtocol()
        val lastReceived = AtomicLong(0)
        val ignored = AtomicLong(0)
        val dropped = AtomicLong(0)
        var bound: Any? = null
        var detach: (() -> Unit)? = null
        val listener = PayloadDataListener { data ->
            if (!connected || !registered) return@PayloadDataListener
            val sample = decoder.decode(data, System.currentTimeMillis())
            if (sample == null) ignored.incrementAndGet()
            else {
                lastReceived.set(SystemClock.elapsedRealtime())
                if (trySend(sample.copy(aircraftPosition = gps.snapshot())).isFailure) dropped.incrementAndGet()
            }
        }
        val sdk = SDKManager.getInstance()
        val gpsJob = launch { gps.run { initialized && registered && connected } }
        try {
            sdk.init(application, object : SDKManagerCallback {
                override fun onRegisterSuccess() { registered = true; registrationError = null }
                override fun onRegisterFailure(error: IDJIError) { registrationError = error.toString() }
                override fun onProductDisconnect(productId: Int) { connected = false; gps.connectionChanged(false) }
                override fun onProductConnect(productId: Int) { gps.connectionChanged(true); connected = true }
                override fun onProductChanged(productId: Int) { gps.connectionChanged(true); connected = true }
                override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                    if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                        initialized = true
                        sdk.registerApp()
                    }
                }
                override fun onDatabaseDownloadProgress(current: Long, total: Long) { }
            })
            var attempts = 0
            while (isActive) {
                if (initialized && !registered && ++attempts % 15 == 0) sdk.registerApp()
                // Mavic 3 Enterprise: use the UP port, as in DJI's payload sample default.
                // Do not combine different payload streams into one CO2 history.
                val manager = if (registered && connected)
                    PayloadCenter.getInstance().payloadManager[PayloadIndexType.UP] else null
                if (manager !== bound) {
                    detach?.invoke()
                    bound = manager
                    detach = null
                    if (manager != null) {
                        manager.addPayloadDataListener(listener)
                        detach = { manager.removePayloadDataListener(listener) }
                    }
                }
                val age = if (lastReceived.get() == 0L) null else (SystemClock.elapsedRealtime() - lastReceived.get()) / 1000
                status.value = when {
                    !initialized -> "DJI: inicializando MSDK"
                    !registered -> registrationError?.let { "Registro DJI falló: $it" } ?: "DJI: registrando app"
                    !connected -> "DJI: dron desconectado · última lectura ${age?.let { "$it s atrás" } ?: "no disponible"}"
                    manager == null -> "DJI: dron conectado; esperando payload"
                    age == null -> "DJI UP: esperando paquete MicroGas v1 · ${ignored.get()} ignorados"
                    else -> "DJI: última lectura hace $age s · ${ignored.get()} ignorados · ${dropped.get()} descartados"
                }
                status.value += " · ${gps.status()}"
                delay(1000)
            }
        } finally {
            connected = false
            gps.connectionChanged(false)
            withContext(NonCancellable) { gpsJob.cancelAndJoin() }
            try { detach?.invoke() } finally { sdk.destroy() }
        }
    }
}
