package com.gaslab.microgas

import android.app.Application
import androidx.compose.runtime.Composable

fun createSampleSource(application: Application): SampleSource = SimulatedSampleSource()
@Composable fun SourcePermissions() { }
