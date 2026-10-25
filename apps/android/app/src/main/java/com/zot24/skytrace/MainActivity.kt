package com.zot24.skytrace

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    private val vm: SkyViewModel by viewModels()

    /** Multi-finger gestures belong to the map, even when a finger lands on a chip, the search bar, the credit line or a sheet. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val map = MapTouchRouter.view ?: return super.dispatchTouchEvent(ev)
        if (MapTouchRouter.routing) {
            MapTouchRouter.forward(map, ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) MapTouchRouter.routing = false
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) MapTouchRouter.mapSawDown = false
        // Only take over when the first finger landed on an overlay; if the map already has the gesture, leave it alone.
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 2 && !MapTouchRouter.mapSawDown) {
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            MapTouchRouter.forward(map, MapTouchRouter.firstDown(ev))
            MapTouchRouter.forward(map, ev)
            MapTouchRouter.routing = true
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

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
