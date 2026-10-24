package com.zot24.skytrace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

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

private const val SIZE = 72
private const val PAD = 4
private const val REACH = 4

/**
 * A signed distance field of the silhouette, so the layer can colour it by altitude and draw a dark halo.
 * The edge sits at alpha 192, which is where MapLibre cuts an SDF icon.
 */
fun aircraftIcon(context: Context, kind: Silhouette): Bitmap {
    val drawable = ContextCompat.getDrawable(context, iconDrawable(kind))!!.mutate()
    val mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    drawable.setBounds(PAD, PAD, SIZE - PAD, SIZE - PAD)
    drawable.draw(Canvas(mask))
    val px = IntArray(SIZE * SIZE)
    mask.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
    val inside = BooleanArray(px.size) { (px[it] ushr 24) > 127 }
    val out = IntArray(px.size)
    for (y in 0 until SIZE) for (x in 0 until SIZE) {
        val me = inside[y * SIZE + x]
        var best = (REACH + 1).toFloat()
        for (dy in -REACH..REACH) for (dx in -REACH..REACH) {
            val nx = x + dx
            val ny = y + dy
            val other = if (nx in 0 until SIZE && ny in 0 until SIZE) inside[ny * SIZE + nx] else false
            if (other != me) {
                val d = sqrt((dx * dx + dy * dy).toFloat())
                if (d < best) best = d
            }
        }
        // Inside rises to opaque. Outside falls to zero within REACH pixels, which is the room the halo has.
        val a = if (me) (192f + (best - 0.5f) * 24f) else (192f - (best - 0.5f) * 48f)
        val alpha = a.coerceIn(0f, 255f).toInt()
        out[y * SIZE + x] = (alpha shl 24) or 0xFFFFFF
    }
    mask.setPixels(out, 0, SIZE, 0, 0, SIZE, SIZE)
    return mask
}
