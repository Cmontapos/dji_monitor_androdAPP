package com.gaslab.microgas.receptor

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Process-local session shared by the foreground service and any recreated activity. */
class ReceiverEngine private constructor(private val application: Application) {
    companion object {
        @Volatile private var instance: ReceiverEngine? = null
        fun get(application: Application): ReceiverEngine = instance ?: synchronized(this) {
            instance ?: ReceiverEngine(application).also { instance = it }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private val repository = MeasurementRepository()
    private val archive = CsvArchive(java.io.File(application.filesDir, "sessions"))
    private var recordingName: String? = null
    private var pendingArchive: String? = null
    private val client = BluetoothClient(application, scope)
    private val alerts = AlertManager(application)
    private val preferences = application.getSharedPreferences("monitor", 0)
    private val mutable = MutableStateFlow(MonitorUiState(threshold = preferences.getInt("co2_threshold", 3000).coerceIn(1, 100_000)))
    val state = mutable.asStateFlow()
    private var visible = false
    private var lastArrival: Long? = null
    private var pendingExport: List<Measurement>? = null

    init {
        refreshFiles()
        scope.launch { repository.state.collect { history ->
            mutable.update { it.copy(history = history) }
        } }
        scope.launch { client.state.collect { connection ->
            mutable.update { it.copy(connection = connection) }
            if (connection.phase != ConnectionPhase.CONNECTED) lastArrival = null
            refreshAlert()
        } }
    }

    fun refreshDevices() {
        try {
            val devices = client.pairedDevices()
            mutable.update { it.copy(devices = devices, deviceMessage =
                if (devices.isEmpty()) "No hay dispositivos emparejados. Empareja el control desde los ajustes Bluetooth." else null) }
        } catch (_: SecurityException) {
            mutable.update { it.copy(devices = emptyList(), deviceMessage = "Concede el permiso de dispositivos cercanos para conectar.") }
        } catch (e: Exception) {
            mutable.update { it.copy(devices = emptyList(), deviceMessage = e.message) }
        }
    }

    fun connect(device: PairedDevice) {
        try {
            application.startForegroundService(android.content.Intent(application, ReceiverService::class.java)
                .setAction(ReceiverService.CONNECT)
                .putExtra("address", device.address).putExtra("name", device.name))
        } catch (error: Exception) { message("No se pudo iniciar la recepción: ${error.message}") }
    }
    internal fun startSession(device: PairedDevice) {
        client.disconnect()
        repository.clear()
        recordingName = CsvArchive.newName()
        mutable.update { it.copy(activeCsv = recordingName, savedRows = 0, failedRows = 0, storageError = null) }
        lastArrival = null
        ticker?.cancel()
        ticker = scope.launch { while (isActive) { refreshAlert(); delay(250) } }
        startConnection(device)
    }
    private fun startConnection(device: PairedDevice) {
        val sessionFile = recordingName ?: return
        client.connect(device) { sample ->
            if (repository.append(sample)) {
                lastArrival = SystemClock.elapsedRealtime()
                refreshAlert()
                withContext(NonCancellable) {
                    val result = withContext(Dispatchers.IO) { runCatching { archive.append(sessionFile, sample) } }
                    if (recordingName == sessionFile) mutable.update { current ->
                        result.fold(
                            { current.copy(savedRows = current.savedRows + 1, storageError = null,
                                csvFiles = (current.csvFiles + sessionFile).distinct().sortedDescending()) },
                            { current.copy(failedRows = current.failedRows + 1, storageError = it.message ?: "Error de escritura") },
                        )
                    }
                }
            }
        }
    }
    fun disconnect() {
        stopReception()
        application.stopService(android.content.Intent(application, ReceiverService::class.java))
    }
    internal fun stopReception() {
        ticker?.cancel(); ticker = null
        recordingName = null
        mutable.update { it.copy(activeCsv = null) }
        client.disconnect()
        refreshFiles()
        lastArrival = null
        refreshAlert()
    }
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value) { refreshFiles(); refreshDevices() }
        refreshAlert()
    }
    private fun refreshAlert() {
        val age = lastArrival?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
        val fresh = age in 0..5_000
        val alarm = isCo2AlertActive(repository.state.value.samples.lastOrNull()?.co2Ppm, mutable.value.threshold, age, visible)
        alerts.update(alarm)
        mutable.update { it.copy(fresh = fresh, alarm = alarm) }
    }
    fun threshold(value: Int) {
        if (value !in 1..100_000) return
        preferences.edit().putInt("co2_threshold", value).apply()
        mutable.update { it.copy(threshold = value) }
        refreshAlert()
    }
    fun monitorWindow(value: Int) { mutable.update { it.copy(monitorMinutes = value) } }
    fun historyWindow(value: Int?) { mutable.update { it.copy(historyMinutes = value) } }
    fun tab(value: Int) { mutable.update { it.copy(selectedTab = value) } }
    fun message(value: String?) { mutable.update { it.copy(message = value) } }

    fun refreshFiles() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { archive.sessions() } }
            result.onSuccess { files -> mutable.update { it.copy(csvFiles = files) } }
                .onFailure { message("No se pudieron leer los respaldos: ${it.message}") }
        }
    }
    fun deleteFile(name: String) {
        if (mutable.value.exportBusy || mutable.value.deleting || name == recordingName) return
        mutable.update { it.copy(deleting = true) }
        val active = recordingName
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { archive.delete(name, active) } }
            mutable.update { it.copy(deleting = false, message = result.fold(
                { "Archivo borrado" }, { "No se pudo borrar: ${it.message}" })) }
            refreshFiles()
        }
    }
    fun prepareArchiveExport(name: String): Boolean {
        if (mutable.value.exportBusy || mutable.value.deleting) return false
        pendingArchive = name
        mutable.update { it.copy(exportBusy = true, message = null) }
        return true
    }
    fun prepareExport(): Boolean {
        if (mutable.value.exportBusy || mutable.value.deleting || repository.state.value.samples.isEmpty()) return false
        pendingExport = repository.state.value.samples
        mutable.update { it.copy(exportBusy = true, message = null) }
        return true
    }
    fun export(uri: Uri?) {
        val archiveName = pendingArchive
        pendingArchive = null
        val snapshot = pendingExport
        pendingExport = null
        if (uri == null || (snapshot == null && archiveName == null)) {
            mutable.update { it.copy(exportBusy = false) }; return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = application.contentResolver.openOutputStream(uri, "wt")
                        ?: error("No se pudo abrir el destino")
                    if (archiveName != null) output.use { archive.copy(archiveName, it) }
                    else output.bufferedWriter(Charsets.UTF_8).use { MeasurementRepository.exportCsv(requireNotNull(snapshot), it) }
                }
            }
            mutable.update { it.copy(exportBusy = false, message = result.fold(
                { if (archiveName != null) "Copia exportada: $archiveName" else "CSV guardado: ${snapshot?.size} muestras" },
                { "No se pudo guardar el CSV: ${it.message}. El destino puede estar incompleto; vuelve a exportar." })) }
        }
    }
}
