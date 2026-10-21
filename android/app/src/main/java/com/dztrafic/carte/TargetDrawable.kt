package com.dztrafic.carte

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Icône « cible » (anneau, point central et 4 repères) comme le bouton « ma position » des applis de cartes. */
class TargetDrawable(tint: Int) : Drawable() {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val size = minOf(area.width(), area.height()).toFloat()
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val stroke = size * 0.09f
        ring.strokeWidth = stroke
        ring.strokeCap = Paint.Cap.BUTT
        canvas.drawCircle(cx, cy, size * 0.27f, ring)
        canvas.drawCircle(cx, cy, size * 0.11f, dot)
        val inner = size * 0.35f
        val outer = size * 0.50f
        canvas.drawLine(cx, cy - outer, cx, cy - inner, ring)
        canvas.drawLine(cx, cy + inner, cx, cy + outer, ring)
        canvas.drawLine(cx - outer, cy, cx - inner, cy, ring)
        canvas.drawLine(cx + inner, cy, cx + outer, cy, ring)
    }

    override fun setAlpha(alpha: Int) {
        ring.alpha = alpha
        dot.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ring.colorFilter = colorFilter
        dot.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
