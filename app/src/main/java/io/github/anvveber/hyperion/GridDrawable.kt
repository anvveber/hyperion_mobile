package io.github.anvveber.hyperion

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** The faint 32px grid the webapp draws behind every page (see body in hyperion's app/globals.css). */
class GridDrawable(
    private val background: Int,
    lineColor: Int,
    private val step: Float,
    lineWidth: Float,
) : Drawable() {
    private val paint = Paint().apply {
        color = lineColor
        strokeWidth = lineWidth
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(background)
        var x = b.left.toFloat()
        while (x < b.right) {
            canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), paint)
            x += step
        }
        var y = b.top.toFloat()
        while (y < b.bottom) {
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, paint)
            y += step
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
