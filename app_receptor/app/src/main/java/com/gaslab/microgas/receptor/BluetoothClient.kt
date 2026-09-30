package com.gaslab.microgas.receptor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PairedDevice(val name: String, val address: String)
enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, RETRYING }
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val deviceName: String = "",
    val detail: String = "Selecciona un dispositivo emparejado",
    val retrySeconds: Int = 0,
)

@SuppressLint("MissingPermission") // UI requests CONNECT; failures/revocation handled below.
class BluetoothClient(context: Context, private val scope: CoroutineScope) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutable = MutableStateFlow(ConnectionState())
    val state = mutable.asStateFlow()
    private var worker: Job? = null
    private val socketLock = Any()
    private var socket: BluetoothSocket? = null
    private var generation = 0L

    fun pairedDevices(): List<PairedDevice> {
        check(adapter != null) { "Este teléfono no dispone de Bluetooth" }
        check(adapter.isEnabled) { "Activa Bluetooth en los ajustes del teléfono" }
        return adapter.bondedDevices.map { PairedDevice(it.name ?: "Sin nombre", it.address) }
            .sortedBy { it.name }
    }

    fun connect(device: PairedDevice, onSample: suspend (Measurement) -> Unit) {
        disconnect()
        val token = generation
        worker = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                var activeSocket: BluetoothSocket? = null
                try {
                    publish(token, ConnectionState(ConnectionPhase.CONNECTING, device.name, "Conectando…"))
                    val bt = adapter ?: throw IOException("Bluetooth no disponible")
                    if (!bt.isEnabled) throw IOException("Bluetooth apagado")
                    val candidate = bt.getRemoteDevice(device.address).createRfcommSocketToServiceRecord(SERVICE_UUID)
                    activeSocket = candidate
                    synchronized(socketLock) {
                        if (generation != token) { candidate.close(); throw CancellationException() }
                        socket = candidate
                    }
                    // Blocking connect/read must be interrupted by closing the socket, not only cancellation.
                    val watchdog = launch {
                        delay(15_000)
                        runCatching { candidate.close() }
                    }
                    try { candidate.connect() } finally { watchdog.cancel() }
                    ensureActive()
                    publish(token, ConnectionState(ConnectionPhase.CONNECTED, device.name, "Esperando datos…"))
                    val framer = NdjsonFramer()
                    val bytes = ByteArray(4096)
                    while (isActive) {
                        val count = candidate.inputStream.read(bytes)
                        if (count < 0) throw IOException("El emisor cerró la conexión")
                        val batch = mutableListOf<Measurement>()
                        framer.feed(bytes, count) { line -> MeasurementParser.parse(line)?.let(batch::add) }
                        if (batch.isNotEmpty()) {
                            attempt = 0
                            // Await delivery: no unbounded queue of callbacks if the UI is busy.
                            withContext(Dispatchers.Main.immediate) {
                                batch.forEach {
                                    ensureActive()
                                    if (generation == token) onSample(it)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    val seconds = retryDelaySeconds(attempt++)
                    publish(token, ConnectionState(ConnectionPhase.RETRYING, device.name,
                        error.message ?: "Conexión interrumpida", seconds))
                } finally {
                    runCatching { activeSocket?.close() }
                    synchronized(socketLock) { if (socket === activeSocket) socket = null }
                }
                delay(retryDelaySeconds(attempt - 1) * 1000L)
            }
        }
    }

    private fun publish(token: Long, value: ConnectionState) = synchronized(socketLock) {
        if (generation == token) mutable.value = value
    }

    fun disconnect(detail: String = "Desconectado") {
        synchronized(socketLock) {
            generation++
            worker?.cancel(); worker = null
            runCatching { socket?.close() }; socket = null
            mutable.value = ConnectionState(detail = detail)
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("bb239920-bdbc-4d51-8a12-a5351875d891")
        fun retryDelaySeconds(attempt: Int): Int = when (attempt.coerceAtLeast(0)) {
            0 -> 5
            1 -> 10
            2 -> 20
            else -> 30
        }
    }
}
