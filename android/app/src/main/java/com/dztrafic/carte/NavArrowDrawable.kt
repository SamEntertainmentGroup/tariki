package com.dztrafic.carte

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Flèche de navigation (pointe vers le haut), dessinée à la taille de la zone disponible. */
class NavArrowDrawable(fillColor: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val w = area.width().toFloat()
        val h = area.height().toFloat()
        val path = Path().apply {
            moveTo(area.left + w * 0.50f, area.top + h * 0.06f)
            lineTo(area.left + w * 0.90f, area.top + h * 0.94f)
            lineTo(area.left + w * 0.50f, area.top + h * 0.72f)
            lineTo(area.left + w * 0.10f, area.top + h * 0.94f)
            close()
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
