package com.zot24.skytrace

import android.view.MotionEvent
import org.maplibre.android.maps.MapView

/** Replays a multi-finger gesture straight into the map view, in its own coordinates. */
object MapTouchRouter {
    var view: MapView? = null
    /** Set by the map's touch listener in SkyMap when a gesture's first finger reached the map. */
    var mapSawDown = false
    var routing = false

    fun forward(map: MapView, ev: MotionEvent) {
        val loc = IntArray(2)
        map.getLocationOnScreen(loc)
        val local = MotionEvent.obtain(ev).apply { offsetLocation(ev.rawX - ev.x - loc[0], ev.rawY - ev.y - loc[1]) }
        map.dispatchTouchEvent(local)
        local.recycle()
    }

    /** A single-pointer ACTION_DOWN for the first finger, so the map sees a whole gesture from its start. */
    fun firstDown(ev: MotionEvent): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().also { ev.getPointerProperties(0, it) })
        val coords = arrayOf(MotionEvent.PointerCoords().also { ev.getPointerCoords(0, it) })
        return MotionEvent.obtain(ev.downTime, ev.eventTime, MotionEvent.ACTION_DOWN, 1, props, coords, ev.metaState, ev.buttonState, 1f, 1f, ev.deviceId, 0, ev.source, ev.flags)
    }
}
