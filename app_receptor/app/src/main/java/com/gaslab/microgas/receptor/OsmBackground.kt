package com.gaslab.microgas.receptor

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File

/** Geographic window shown by the route canvas; the map below is only a passive backdrop. */
data class GeoWindow(val north: Double, val south: Double, val east: Double, val west: Double)

/** Passive OSM backdrop: gestures are disabled and the view always follows [window]. Without network it stays grey. */
@Composable
fun OsmBackground(window: GeoWindow?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember(context) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.filesDir, "osmdroid")
            osmdroidTileCache = File(context.filesDir, "osmdroid/tiles")
        }
    }
    val map = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 2.0
            maxZoomLevel = 22.0
            setOnTouchListener { _, _ -> true }
            clipToOutline = true
            clipChildren = true
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, map) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    Box(modifier.clipToBounds()) {
        AndroidView(factory = { map }, modifier = Modifier.matchParentSize(), update = { view ->
            if (window != null && view.width > 0 && view.height > 0 &&
                window.north > window.south && window.east > window.west) {
                view.zoomToBoundingBox(BoundingBox(window.north, window.east, window.south, window.west), false)
            }
        })
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
    }
}
