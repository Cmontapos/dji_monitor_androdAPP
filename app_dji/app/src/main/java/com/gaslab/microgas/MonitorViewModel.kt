package com.gaslab.microgas

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordingState(
    val saved: Long = 0,
    val failed: Long = 0,
    val error: String? = null,
    val sessions: List<String> = emptyList(),
    val choosingSession: Boolean = false,
    val exporting: Boolean = false,
    val deleting: Boolean = false,
    val activeSession: String? = null,
    val exportMessage: String? = null,
)

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = MonitorEngine.get(application)
    val running = engine.running
    val state = engine.state
    val recording = engine.recording
    val sourceStatus = engine.sourceStatus
    fun showSessions() { engine.showSessions() }
    fun dismissSessions() { engine.dismissSessions() }
    fun deleteSession(name: String) { engine.deleteSession(name) }
    fun exportNotice(message: String) { engine.exportNotice(message) }
    fun export(name: String, uri: Uri) { engine.export(name, uri) }
    fun selectHistory(count: Int, back: Int, co2: Boolean) { engine.selectHistory(count, back, co2) }
    fun togglePause() { engine.togglePause() }
    fun resetMaximum() { engine.resetMaximum() }
}

class MonitorEngine(private val application: Application) {
    private val source: SampleSource = createSampleSource(application)
    val sourceStatus = source.status
    private val journal = CsvJournal(File(application.filesDir, "sessions"), BuildConfig.DATA_ORIGIN)
    private val mutableState = MutableStateFlow(MonitorState())
    val state = mutableState.asStateFlow()
    private val mutableRecording = MutableStateFlow(RecordingState())
    val recording = mutableRecording.asStateFlow()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private var acquisition: kotlinx.coroutines.Job? = null
    private var storage: kotlinx.coroutines.Job? = null
    val failure = MutableStateFlow<String?>(null)
    val live = MutableStateFlow<GasSample?>(null)
    val running = MutableStateFlow(false)
    var receivedElapsed = 0L
        private set
    var onSample: (GasSample) -> Unit = {}

    fun start() {
        if (running.value) return
        running.value = true
        failure.value = null
        val previousAcquisition = acquisition
        val previousStorage = storage
        // Each run has its own hold; no retained reading is written after a restart.
        val runHold = SampleHold()
        // Acquisition and disk scheduling are independent. Holding a value is not a new sample.
        acquisition = scope.launch {
            previousAcquisition?.join()
            source.samples().catch { error ->
                if (error is CancellationException) throw error
                failure.value = "Adquisición falló: ${error.localizedMessage} · detener e iniciar"
            }.collect { sample ->
                if (runHold.accept(sample)) {
                    live.value = sample
                    receivedElapsed = SystemClock.elapsedRealtime()
                    onSample(sample)
                    mutableState.update { if (it.paused) it else it.accept(sample) }
                }
            }
        }
        storage = scope.launch(Dispatchers.IO) {
            previousStorage?.join()
            try {
                var nextTick = SystemClock.elapsedRealtime()
                while (isActive) {
                    runHold.reading(System.currentTimeMillis())?.let { reading ->
                        try {
                            val activeName = journal.append(reading)
                            runHold.saved(reading)
                            mutableRecording.update { it.copy(saved = it.saved + 1, error = null, activeSession = activeName) }
                        } catch (error: IOException) {
                            mutableRecording.update { it.copy(failed = it.failed + 1, error = "No se guardó la fila: ${error.localizedMessage}", activeSession = journal.activeSession()) }
                        }
                    }
                    nextTick += 100
                    val now = SystemClock.elapsedRealtime()
                    // Do not fabricate backdated rows or burst writes if storage stalls.
                    if (nextTick <= now) nextTick = now + 100
                    delay(nextTick - now)
                }
            } finally {
                journal.finishSession()
                mutableRecording.update { it.copy(activeSession = null) }
            }
        }
    }

    fun cancelAcquisition() {
        acquisition?.cancel()
        storage?.cancel()
        live.value = null
        receivedElapsed = 0L
        running.value = false
    }

    suspend fun stop() {
        acquisition?.cancel()
        storage?.cancel()
        acquisition?.join()
        storage?.join()
        live.value = null
        receivedElapsed = 0L
        running.value = false
    }

    companion object {
        @Volatile private var instance: MonitorEngine? = null
        fun get(application: Application): MonitorEngine = instance ?: synchronized(this) {
            instance ?: MonitorEngine(application).also { instance = it }
        }
    }

    fun showSessions() = scope.launch {
        if (mutableRecording.value.deleting || mutableRecording.value.exporting) return@launch
        try {
            val sessions = withContext(Dispatchers.IO) { journal.sessions() }
            mutableRecording.update { it.copy(sessions = sessions, choosingSession = true, exportMessage = null) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            exportNotice("No se pudieron listar las sesiones: ${error.localizedMessage}")
        }
    }

    fun deleteSession(name: String) = scope.launch {
        if (mutableRecording.value.exporting || mutableRecording.value.deleting) return@launch
        mutableRecording.update { it.copy(deleting = true, exportMessage = null) }
        try {
            val sessions = withContext(Dispatchers.IO) {
                journal.deleteSession(name)
                journal.sessions()
            }
            mutableRecording.update { it.copy(sessions = sessions, exportMessage = "CSV borrado del dispositivo. Las copias exportadas se conservan.") }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { exportNotice("No se borró el CSV: ${error.localizedMessage}") }
        finally { mutableRecording.update { it.copy(deleting = false) } }
    }

    fun dismissSessions() = mutableRecording.update { it.copy(choosingSession = false) }
    fun exportNotice(message: String) = mutableRecording.update { it.copy(exportMessage = message) }

    fun export(name: String, uri: Uri) = scope.launch {
        if (mutableRecording.value.deleting || mutableRecording.value.exporting) return@launch
        mutableRecording.update { it.copy(exporting = true, exportMessage = null) }
        try {
            withContext(Dispatchers.IO) {
                val snapshot = journal.snapshot(name)
                val resolver = application.contentResolver
                val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("No se pudo abrir el destino")
                stream.use { snapshot.copyTo(it) }
            }
            exportNotice("CSV exportado. El original sigue en el control.")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            exportNotice("Falló la exportación; el destino puede estar incompleto. El original se conserva. ${error.localizedMessage}")
        } finally {
            mutableRecording.update { it.copy(exporting = false) }
        }
    }

    fun selectHistory(count: Int, back: Int, co2: Boolean) = mutableState.update {
        it.selectHistory(count, back, co2)
    }

    fun togglePause() = mutableState.update { it.copy(paused = !it.paused) }
    fun resetMaximum() = mutableState.update { it.resetMaximum() }
}
