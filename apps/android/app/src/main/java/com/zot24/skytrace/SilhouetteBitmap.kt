package com.zot24.skytrace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

fun silhouetteBitmap(kind: Silhouette, selected: Boolean): Bitmap {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (selected) 0xFFFFB030.toInt() else 0xFFECF2F8.toInt()
    }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = 0xFF080A0E.toInt()
    }
    when (kind) {
        Silhouette.WIDE_BODY -> jet(canvas, fill, line, 7.2f, 30f, 12f, 3f, 60f, 4, rear = false, prop = false)
        Silhouette.NARROW_BODY -> jet(canvas, fill, line, 4.4f, 24f, 10f, 4f, 60f, 2, rear = false, prop = false)
        Silhouette.REGIONAL_JET -> jet(canvas, fill, line, 3.6f, 18f, 6f, 10f, 56f, 2, rear = true, prop = false)
        Silhouette.BIZJET -> jet(canvas, fill, line, 2.6f, 14f, 8f, 8f, 52f, 2, rear = true, prop = false)
        Silhouette.TURBOPROP -> jet(canvas, fill, line, 4f, 22f, 1.5f, 8f, 56f, 2, rear = false, prop = true)
        Silhouette.LIGHT_PISTON -> jet(canvas, fill, line, 2.8f, 20f, 0f, 14f, 50f, 1, rear = false, prop = true)
        Silhouette.HELI_LIGHT -> heli(canvas, fill, line, blades = 2, radius = 20f, tandem = false, body = 7f)
        Silhouette.HELI_MEDIUM -> heli(canvas, fill, line, blades = 4, radius = 22f, tandem = false, body = 10f)
        Silhouette.HELI_HEAVY -> heli(canvas, fill, line, blades = 3, radius = 15f, tandem = true, body = 8f)
    }
    return bitmap
}

private fun jet(
    canvas: Canvas,
    fill: Paint,
    line: Paint,
    bodyHalf: Float,
    wing: Float,
    sweep: Float,
    nose: Float,
    tail: Float,
    engines: Int,
    rear: Boolean,
    prop: Boolean,
) {
    val path = Path()
    val cx = 32f
    path.moveTo(cx, nose)
    path.cubicTo(cx + bodyHalf, nose, cx + bodyHalf, nose + 10f, cx + bodyHalf, nose + 16f)
    path.lineTo(cx + bodyHalf, tail - 14f)
    path.lineTo(cx + bodyHalf + 8f, tail)
    path.lineTo(cx + bodyHalf + 6f, tail + 3f)
    path.lineTo(cx, tail - 4f)
    path.lineTo(cx - bodyHalf - 6f, tail + 3f)
    path.lineTo(cx - bodyHalf - 8f, tail)
    path.lineTo(cx - bodyHalf, tail - 14f)
    path.lineTo(cx - bodyHalf, nose + 16f)
    path.cubicTo(cx - bodyHalf, nose + 10f, cx - bodyHalf, nose, cx, nose)
    path.close()
    val wingY = if (rear) tail - 22f else 34f
    path.moveTo(cx - bodyHalf, wingY - 2f)
    path.lineTo(cx - wing, wingY + sweep)
    path.lineTo(cx - wing + 2f, wingY + sweep + 4f)
    path.lineTo(cx - bodyHalf, wingY + 6f)
    path.close()
    path.moveTo(cx + bodyHalf, wingY - 2f)
    path.lineTo(cx + wing, wingY + sweep)
    path.lineTo(cx + wing - 2f, wingY + sweep + 4f)
    path.lineTo(cx + bodyHalf, wingY + 6f)
    path.close()
    draw(canvas, path, fill, line)
    val engine = Path()
    if (prop && engines == 1) {
        canvas.drawCircle(cx, nose + 1f, 5.5f, line)
    } else if (prop) {
        canvas.drawCircle(cx - wing * 0.45f, wingY + 2f, 5f, line)
        canvas.drawCircle(cx + wing * 0.45f, wingY + 2f, 5f, line)
    } else if (engines == 4) {
        blob(engine, cx - wing * 0.55f, wingY + 1f)
        blob(engine, cx - wing * 0.28f, wingY + 2f)
        blob(engine, cx + wing * 0.28f, wingY + 2f)
        blob(engine, cx + wing * 0.55f, wingY + 1f)
        draw(canvas, engine, fill, line)
    } else if (rear) {
        blob(engine, cx - bodyHalf - 1.5f, tail - 16f)
        blob(engine, cx + bodyHalf + 1.5f, tail - 16f)
        draw(canvas, engine, fill, line)
    } else {
        blob(engine, cx - wing * 0.38f, wingY + 2f)
        blob(engine, cx + wing * 0.38f, wingY + 2f)
        draw(canvas, engine, fill, line)
    }
}

private fun blob(path: Path, x: Float, y: Float) {
    path.addRoundRect(x - 2.2f, y - 1.2f, x + 2.2f, y + 4.2f, 1.2f, 1.2f, Path.Direction.CW)
}

private fun heli(
    canvas: Canvas,
    fill: Paint,
    line: Paint,
    blades: Int,
    radius: Float,
    tandem: Boolean,
    body: Float,
) {
    val path = Path()
    if (tandem) {
        path.addRoundRect(32f - body, 18f, 32f + body, 50f, 4f, 4f, Path.Direction.CW)
        path.addRect(24f, 30f, 40f, 36f, Path.Direction.CW)
    } else {
        path.addRoundRect(32f - body * 0.7f, 24f, 32f + body * 0.7f, 40f, 4f, 4f, Path.Direction.CW)
        path.addRect(30.2f, 38f, 33.8f, 54f, Path.Direction.CW)
        path.moveTo(26f, 52f)
        path.lineTo(38f, 52f)
        path.lineTo(36f, 56f)
        path.lineTo(28f, 56f)
        path.close()
    }
    draw(canvas, path, fill, line)
    if (tandem) {
        rotor(canvas, 32f, 22f, radius, blades, line, fill)
        rotor(canvas, 32f, 46f, radius, blades, line, fill)
    } else {
        rotor(canvas, 32f, 30f, radius, blades, line, fill)
        canvas.drawCircle(40f, 53f, 3.2f, line)
    }
}

private fun rotor(canvas: Canvas, cx: Float, cy: Float, radius: Float, blades: Int, line: Paint, fill: Paint) {
    for (i in 0 until blades) {
        val angle = Math.toRadians(-90.0 + i * (360.0 / blades))
        canvas.drawLine(
            cx,
            cy,
            cx + (cos(angle) * radius).toFloat(),
            cy + (sin(angle) * radius).toFloat(),
            line,
        )
    }
    canvas.drawCircle(cx, cy, 2.4f, fill)
    canvas.drawCircle(cx, cy, 2.4f, line)
}

private fun draw(canvas: Canvas, path: Path, fill: Paint, line: Paint) {
    canvas.drawPath(path, fill)
    canvas.drawPath(path, line)
}
