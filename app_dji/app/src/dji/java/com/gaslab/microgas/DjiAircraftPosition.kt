package com.gaslab.microgas

import android.os.SystemClock
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException

/** Uses the asynchronous hardware query, NOT getValue(key)'s synchronous SDK cache.
 * Polls even for stationary coordinates. Never falls back to Android GPS/home point.
 */
class DjiAircraftPosition {
    private val tracker = AircraftPositionTracker()
    @Volatile private var issue = "esperando posición"

    fun connectionChanged(connected: Boolean) {
        tracker.connectionChanged(connected)
        issue = if (connected) "esperando posición" else "dron desconectado"
    }

    fun snapshot(): AircraftPosition? = tracker.snapshot(SystemClock.elapsedRealtime())

    fun status(): String {
        if (tracker.connectionToken() == null) return "GPS dron: desconectado"
        val fix = snapshot()
        return if (fix == null) "GPS dron: $issue"
        else "GPS dron: recibido hace ${fix.ageAtSampleMs} ms · nivel ${fix.signalLevel}"
    }

    suspend fun run(ready: () -> Boolean) {
        val locationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D)
        val signalKey = KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel)
        while (currentCoroutineContext().isActive) {
            val token = tracker.connectionToken()
            if (token != null && ready()) {
                try {
                    // Bound each query pair; late callbacks after timeout/cancel cannot update tracker.
                    val (location, signal) = withTimeout(2000) {
                        coroutineScope {
                            val position = async { readHardware(locationKey) }
                            val quality = async { readHardware(signalKey) }
                            position.await() to quality.await()
                        }
                    }
                    val accepted = tracker.update(
                        token, location.value.latitude, location.value.longitude, signal.value.value(),
                        location.receivedAtMs, location.elapsedMs, altitudeM = location.value.altitude,
                    )
                    if (tracker.connectionToken() == token) {
                        issue = if (accepted) "posición desactualizada" else "sin posición válida o señal insuficiente"
                    }
                } catch (_: TimeoutCancellationException) {
                    tracker.invalidate(token)
                    if (tracker.connectionToken() == token) issue = "consulta sin respuesta"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    tracker.invalidate(token)
                    if (tracker.connectionToken() == token) issue = "no disponible (${error.localizedMessage})"
                }
            }
            delay(1000)
        }
    }

    private data class HardwareReading<T>(val value: T, val receivedAtMs: Long, val elapsedMs: Long)

    private suspend fun <T> readHardware(key: DJIKey<T>): HardwareReading<T> = suspendCancellableCoroutine { continuation ->
        KeyManager.getInstance().getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) {
                continuation.resume(HardwareReading(value, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
            }
            override fun onFailure(error: IDJIError) {
                continuation.resumeWithException(IOException(error.toString()))
            }
        })
    }
}
