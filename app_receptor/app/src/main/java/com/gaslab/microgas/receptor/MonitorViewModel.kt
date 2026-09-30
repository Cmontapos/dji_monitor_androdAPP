package com.gaslab.microgas.receptor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel

data class MonitorUiState(
    val history: HistoryState = HistoryState(),
    val connection: ConnectionState = ConnectionState(),
    val devices: List<PairedDevice> = emptyList(),
    val deviceMessage: String? = null,
    val threshold: Int = 3000,
    val alarm: Boolean = false,
    val fresh: Boolean = false,
    val monitorMinutes: Int = 5,
    val historyMinutes: Int? = null,
    val selectedTab: Int = 0,
    val exportBusy: Boolean = false,
    val message: String? = null,
    val csvFiles: List<String> = emptyList(),
    val activeCsv: String? = null,
    val savedRows: Long = 0,
    val failedRows: Long = 0,
    val storageError: String? = null,
    val deleting: Boolean = false,
)

class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = ReceiverEngine.get(application)
    val state = engine.state
    fun refreshDevices() = engine.refreshDevices()
    fun connect(device: PairedDevice) = engine.connect(device)
    fun disconnect() = engine.disconnect()
    fun setVisible(value: Boolean) = engine.setVisible(value)
    fun threshold(value: Int) = engine.threshold(value)
    fun monitorWindow(value: Int) = engine.monitorWindow(value)
    fun historyWindow(value: Int?) = engine.historyWindow(value)
    fun tab(value: Int) = engine.tab(value)
    fun message(value: String?) = engine.message(value)
    fun refreshFiles() = engine.refreshFiles()
    fun deleteFile(name: String) = engine.deleteFile(name)
    fun prepareArchiveExport(name: String) = engine.prepareArchiveExport(name)
    fun prepareExport() = engine.prepareExport()
    fun export(uri: Uri?) = engine.export(uri)
    // The service owns reception; destroying a screen must not close Bluetooth.
}
