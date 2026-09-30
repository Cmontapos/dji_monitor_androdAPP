package com.gaslab.microgas

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

fun createSampleSource(application: Application): SampleSource = DjiSampleSource(application)

fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.READ_EXTERNAL_STORAGE)
    if (Build.VERSION.SDK_INT <= 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

fun hasDjiPermissions(context: Context) = requiredPermissions().all {
    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun SourcePermissions() {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(!hasDjiPermissions(context)) }
    var dismissed by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = !hasDjiPermissions(context)
    }
    if (missing && !dismissed) AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Permisos para DJI") },
        text = { Text("MSDK necesita ubicación y, en Android 10, acceso al almacenamiento. Si los deniegas, puedes habilitarlos en los ajustes de MicroGas DJI.") },
        confirmButton = { TextButton(onClick = { launcher.launch(requiredPermissions()) }) { Text("Permitir") } },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Ahora no") } },
    )
}
