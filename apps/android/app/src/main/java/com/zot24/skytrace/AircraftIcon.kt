package com.zot24.skytrace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat

private fun iconDrawable(kind: Silhouette): Int = when (kind) {
    Silhouette.WIDE_BODY -> R.drawable.ic_ac_wide_body
    Silhouette.NARROW_BODY -> R.drawable.ic_ac_narrow_body
    Silhouette.REGIONAL_JET -> R.drawable.ic_ac_regional_jet
    Silhouette.BIZJET -> R.drawable.ic_ac_bizjet
    Silhouette.TURBOPROP -> R.drawable.ic_ac_turboprop
    Silhouette.LIGHT_PISTON -> R.drawable.ic_ac_light_piston
    Silhouette.HELI_LIGHT -> R.drawable.ic_ac_heli_light
    Silhouette.HELI_MEDIUM -> R.drawable.ic_ac_heli_medium
    Silhouette.HELI_HEAVY -> R.drawable.ic_ac_heli_heavy
}

fun aircraftIcon(context: Context, kind: Silhouette, selected: Boolean): Bitmap {
    val drawable = ContextCompat.getDrawable(context, iconDrawable(kind))!!.mutate()
    if (selected) {
        drawable.setTint(0xFFFFB030.toInt())
        drawable.setTintMode(PorterDuff.Mode.SRC_IN)
    }
    // MapLibre sets pixelRatio from bitmap.density / 160.
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, 64, 64)
    drawable.draw(Canvas(bitmap))
    return bitmap
}
