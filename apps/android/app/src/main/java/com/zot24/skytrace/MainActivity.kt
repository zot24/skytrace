package com.zot24.skytrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            SyntheticTraffic.planes = intent.getIntExtra("synthetic", 0)
            SyntheticTraffic.camera = intent.getStringExtra("camera")
        }
        MapLibre.getInstance(this)
        enableEdgeToEdge()
        setContent {
            SkytraceTheme {
                SkyScreen()
            }
        }
    }
}
