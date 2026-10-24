package com.zot24.skytrace

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private val vm: SkyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            SyntheticTraffic.planes = intent.getIntExtra("synthetic", 0)
            SyntheticTraffic.camera = intent.getStringExtra("camera")
            SyntheticTraffic.overlay = intent.getBooleanExtra("overlay", true)
        }
        MapLibre.getInstance(this)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.openLink(intent?.dataString)
        setContent {
            SkytraceTheme {
                SkyScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.openLink(intent.dataString)
    }
}
