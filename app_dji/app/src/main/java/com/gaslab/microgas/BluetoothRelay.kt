package com.gaslab.microgas

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Authenticated RFCOMM server. Pair in Android settings; phone connects using SERVICE_UUID. */
@SuppressLint("MissingPermission") // Runtime denial/revocation is handled and displayed below.
class BluetoothRelay(context: Context, private val scope: CoroutineScope) {
    companion object { val SERVICE_UUID: UUID = UUID.fromString("bb239920-bdbc-4d51-8a12-a5351875d891") }
    val status = MutableStateFlow("BT: iniciando")
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var client: BluetoothSocket? = null
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val queue = Channel<Pair<Long, ByteArray>>(Channel.CONFLATED)
    private var sequence = 0L
    private val session = UUID.randomUUID().toString()
    private var job: Job? = null
    fun publish(sample: GasSample) {
        sequence++
        if (client != null) queue.trySend(SystemClock.elapsedRealtime() to
            LiveTelemetry.encode(sample, session, sequence).toByteArray(Charsets.UTF_8))
    }
    fun start() {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (adapter == null) { status.value = "BT: no disponible"; return@launch }
                    if (!adapter.isEnabled) { status.value = "BT: apagado"; delay(1500); continue }
                    status.value = "BT: esperando receptor"
                    val listener = adapter.listenUsingRfcommWithServiceRecord("MicroGas", SERVICE_UUID)
                    server = listener
                    ensureActive()
                    val socket = listener.accept()
                    client = socket
                    ensureActive()
                    listener.close()
                    server = null
                    status.value = "BT: conectado · esperando muestra"
                    // Read only to detect remote close, even while payload samples stop.
                    coroutineScope {
                        val reader = launch(Dispatchers.IO) {
                            try { while (socket.inputStream.read() != -1) { /* reserved */ } }
                            finally { runCatching { socket.close() } }
                        }
                        try {
                            while (isActive && !reader.isCompleted) {
                                val packet = withTimeoutOrNull(1000) { queue.receive() } ?: continue
                                if (SystemClock.elapsedRealtime() - packet.first > LiveTelemetry.STALE_MS) continue
                                val watchdog = launch { delay(3000); runCatching { socket.close() } }
                                try {
                                    socket.outputStream.write(packet.second)
                                    socket.outputStream.flush()
                                    status.value = "BT: enviando · sin confirmación remota"
                                } finally { watchdog.cancel() }
                            }
                        } finally { socket.close(); reader.cancel() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: SecurityException) { status.value = "BT: permiso requerido" }
                catch (_: Exception) { status.value = "BT: desconectado · reintentando" }
                finally {
                    runCatching { client?.close() }; client = null
                    runCatching { server?.close() }; server = null
                    while (queue.tryReceive().isSuccess) { }
                }
                delay(1500)
            }
        }
    }
    fun close() {
        job?.cancel()
        runCatching { server?.close() }
        runCatching { client?.close() }
        queue.close()
    }
}
