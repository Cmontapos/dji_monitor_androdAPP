package com.gaslab.microgas.receptor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFF80D5CD), onPrimary = Color.White,
                onBackground = Color.White, onSurface = Color.White,
                onSurfaceVariant = Color.White, onSecondaryContainer = Color.White,
            )
            MaterialTheme(colorScheme = colors) { ReceptorApp() }
        }
    }
}

@Composable
private fun ReceptorApp(vm: MonitorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var routeSelection by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val openMap: (Measurement) -> Unit = { sample ->
        routeSelection = sample.session to sample.sequence
        vm.tab(2)
    }
    LaunchedEffect(state.history.samples) {
        if (routeSelection != null && (state.history.samples + state.history.highest.values.flatten() + listOfNotNull(state.history.home)).none { (it.session to it.sequence) == routeSelection }) routeSelection = null
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var connectionDialog by rememberSaveable { mutableStateOf(false) }
    var filesDialog by rememberSaveable { mutableStateOf(false) }
    var manageFiles by rememberSaveable { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<String?>(null) }
    var thresholdDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDevice by remember { mutableStateOf<PairedDevice?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshDevices() }
    fun refreshWithPermission() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permission.launch(missing.toTypedArray()) else vm.refreshDevices()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), vm::export)
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.setVisible(true)
            if (event == Lifecycle.Event.ON_STOP) vm.setVisible(false)
        }
        lifecycle.addObserver(observer)
        vm.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); vm.setVisible(false) }
    }
    LaunchedEffect(Unit) { refreshWithPermission() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (state.alarm) Surface(color = Color(0xFFB00020), contentColor = Color.White) {
                Text("ALERTA · CO₂ ≥ ${state.threshold} ppm", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("MicroGas Receptor", style = MaterialTheme.typography.titleMedium)
                    Text(when (state.connection.phase) {
                        ConnectionPhase.DISCONNECTED -> "Desconectado"
                        ConnectionPhase.CONNECTING -> "Conectando…"
                        ConnectionPhase.CONNECTED -> "Conectado · ${state.connection.deviceName}"
                        ConnectionPhase.RETRYING -> "Reintento en ${state.connection.retrySeconds} s"
                    }, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { connectionDialog = true; refreshWithPermission() }) { Text("Bluetooth") }
                TextButton(onClick = { thresholdDialog = true }) { Text("Umbral") }
            }
            if (state.connection.phase == ConnectionPhase.CONNECTING || state.connection.phase == ConnectionPhase.RETRYING)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { message ->
                Row(Modifier.padding(horizontal = 12.dp)) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.message(null) }) { Text("Cerrar") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { filesDialog = true; vm.refreshFiles() }) { Text("Archivos CSV (${state.csvFiles.size})") }
                Text("Guardadas: ${state.savedRows}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.storageError != null || state.failedRows > 0) Text(
                "Respaldo: ${state.storageError ?: "escritura recuperada"} · ${state.failedRows} muestras no guardadas",
                Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error,
            )
            TabRow(selectedTabIndex = state.selectedTab) {
                listOf("Monitor", "Histórico", "Ruta y altura").forEachIndexed { index, name ->
                    Tab(selected = state.selectedTab == index, onClick = { vm.tab(index) }, text = { Text(name) })
                }
            }
            if (state.selectedTab == 2) {
                RouteScreen(state.history.samples, Modifier.weight(1f), routeSelection, state.history.home, state.history.highest) { routeSelection = it }
            } else if (state.connection.phase == ConnectionPhase.DISCONNECTED && state.history.samples.isEmpty()) {
                ConnectionPanel(state, onRefresh = { refreshWithPermission() }, onConnect = vm::connect, modifier = Modifier.weight(1f))
            } else if (state.selectedTab == 0) {
                MonitorScreen(state, vm::monitorWindow, Modifier.weight(1f), onOpenMap = openMap)
            } else {
                HistoryScreen(state, vm::historyWindow, onExport = {
                    if (vm.prepareExport()) {
                        try { export.launch("MicroGas_${Instant.now().toString().replace(':', '-')}.csv") }
                        catch (e: Exception) { vm.export(null); vm.message("No se pudo abrir el selector: ${e.message}") }
                    }
                }, modifier = Modifier.weight(1f), onOpenMap = openMap)
            }
        }
    }
    if (filesDialog) AlertDialog(
        onDismissRequest = { filesDialog = false },
        title = { Text("Respaldos CSV del teléfono") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Se guardan automáticamente y permanecen al cerrar la app. Exportar conserva el original.") }
                if (state.csvFiles.isEmpty()) item { Text("Todavía no hay archivos guardados.") }
                items(state.csvFiles, key = { it }) { name ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodySmall)
                        if (name == state.activeCsv) Text("Sesión activa · protegida", style = MaterialTheme.typography.labelSmall)
                        Row {
                            TextButton(enabled = !state.exportBusy && !state.deleting, onClick = {
                                if (vm.prepareArchiveExport(name)) {
                                    try { export.launch(name) } catch (e: Exception) { vm.export(null); vm.message("No se pudo abrir el selector: ${e.message}") }
                                }
                            }) { Text("Exportar") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { filesDialog = false }) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = { filesDialog = false; manageFiles = true }) { Text("Administrar CSV") } },
    )
    if (manageFiles) AlertDialog(
        onDismissRequest = { manageFiles = false; deleteCandidate = null },
        title = { Text("Administrar archivos CSV") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { Text("Selecciona un respaldo para eliminarlo. Cada borrado requiere confirmación.") }
                if (state.csvFiles.isEmpty()) item { Text("No hay archivos guardados.") }
                items(state.csvFiles, key = { it }) { name ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodySmall)
                        if (name == state.activeCsv) Text("Sesión activa · protegida")
                        TextButton(enabled = name != state.activeCsv && !state.exportBusy && !state.deleting,
                            onClick = { deleteCandidate = name }) { Text("Eliminar…") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { manageFiles = false; deleteCandidate = null }) { Text("Cerrar") } },
    )
    deleteCandidate?.let { name -> AlertDialog(
        onDismissRequest = { deleteCandidate = null },
        title = { Text("¿Borrar respaldo del teléfono?") },
        text = { Text("Se eliminará $name. Las copias exportadas y los archivos del control se conservan.") },
        confirmButton = { TextButton(enabled = name != state.activeCsv && !state.exportBusy && !state.deleting, onClick = { vm.deleteFile(name); deleteCandidate = null }) { Text("Borrar definitivamente") } },
        dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancelar") } },
    ) }
    if (connectionDialog) AlertDialog(
        onDismissRequest = { connectionDialog = false },
        title = { Text("Conexión Bluetooth") },
        text = { Column {
            Text(state.connection.detail)
            ConnectionPanel(state, onRefresh = { refreshWithPermission() }, onConnect = { device ->
                if (state.history.samples.isNotEmpty()) pendingDevice = device else { vm.connect(device); connectionDialog = false }
            }, modifier = Modifier.heightIn(max = 360.dp))
        } },
        confirmButton = { TextButton(onClick = { connectionDialog = false }) { Text("Cerrar") } },
        dismissButton = { TextButton(onClick = { vm.disconnect(); connectionDialog = false }) { Text("Desconectar") } },
    )
    pendingDevice?.let { device -> AlertDialog(
        onDismissRequest = { pendingDevice = null },
        title = { Text("Iniciar nueva sesión") },
        text = { Text("Se reemplazarán las ${state.history.samples.size} muestras en memoria. Los respaldos CSV automáticos se conservan.") },
        confirmButton = { TextButton(onClick = { vm.connect(device); pendingDevice = null; connectionDialog = false }) { Text("Conectar") } },
        dismissButton = { TextButton(onClick = { pendingDevice = null }) { Text("Cancelar") } },
    ) }
    if (thresholdDialog) {
        var text by remember { mutableStateOf(state.threshold.toString()) }
        val value = text.toIntOrNull()
        AlertDialog(onDismissRequest = { thresholdDialog = false }, title = { Text("Umbral de CO₂") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("ppm (1–100.000)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                isError = value == null || value !in 1..100_000) },
            confirmButton = { TextButton(enabled = value != null && value in 1..100_000, onClick = { value?.let(vm::threshold); thresholdDialog = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { thresholdDialog = false }) { Text("Cancelar") } })
    }
}

@Composable
private fun ConnectionPanel(state: MonitorUiState, onRefresh: () -> Unit, onConnect: (PairedDevice) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Dispositivos emparejados", style = MaterialTheme.typography.titleMedium)
            Text("Activa MicroGas en el control y selecciona su nombre.")
            state.deviceMessage?.let { Text(it) }
            Row {
                TextButton(onClick = onRefresh) { Text("Actualizar") }
                TextButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) { onRefresh() }
                }) { Text("Ajustes Bluetooth") }
            }
        }
        items(state.devices, key = { it.address }) { device ->
            OutlinedButton(onClick = { onConnect(device) }, modifier = Modifier.fillMaxWidth()) {
                Column { Text(device.name); Text(device.address, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
