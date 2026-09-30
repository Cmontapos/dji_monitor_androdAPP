package com.gaslab.microgas

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitorControls() {
    val context = LocalContext.current
    val status by MonitorService.status.collectAsStateWithLifecycle()
    val preferences = remember { context.getSharedPreferences("monitor", 0) }
    var threshold by rememberSaveable { mutableStateOf(preferences.getInt("threshold", LiveTelemetry.DEFAULT_THRESHOLD).toString()) }
    var message by rememberSaveable { mutableStateOf("") }
    var popup by rememberSaveable { mutableStateOf(false) }
    fun start() {
        try {
            context.startForegroundService(Intent(context, MonitorService::class.java).apply { action = if (popup) MonitorService.SHOW else MonitorService.HIDE })
            message = "Adquisición iniciada"
            // Keep activity visible until the user explicitly returns to Pilot 2.
        } catch (e: Exception) { message = "No se pudo iniciar: ${e.localizedMessage}" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        start()
    }
    fun requestStart() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) start() else permissions.launch(missing.toTypedArray())
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) requestStart()
        else message = "Sin permiso flotante; puedes iniciar en segundo plano."
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                popup = true
                if (Settings.canDrawOverlays(context)) requestStart()
                else try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                catch (_: Exception) { message = "Habilita Mostrar sobre otras apps en los ajustes de Android." }
            }) { Text("Iniciar pop-up") }
            OutlinedButton(onClick = { popup = false; requestStart() }) { Text("Segundo plano") }
            OutlinedButton(onClick = { (context as? Activity)?.moveTaskToBack(true) }) { Text("Ocultar app") }
            TextButton(onClick = {
                if (status != "Detenido") context.startService(Intent(context, MonitorService::class.java).setAction(MonitorService.STOP))
            }) { Text("Detener") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(threshold, { threshold = it }, Modifier.width(180.dp), label = { Text("Alarma CO₂ (ppm)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            TextButton(onClick = {
                preferences.edit().putInt("threshold", threshold.toInt()).apply()
                message = "Umbral guardado: $threshold ppm"
            }, enabled = threshold.toIntOrNull()?.let { it in 1..100000 } == true) { Text("Guardar umbral") }
            TextButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                catch (_: Exception) { message = "Abre Bluetooth desde los ajustes del control." }
            }) { Text("Emparejar Bluetooth") }
        }
        Text(if (message.isEmpty()) status.replace('\n', ' ') else "$message · ${status.replace('\n', ' ')}", maxLines = 3)
    }
}
