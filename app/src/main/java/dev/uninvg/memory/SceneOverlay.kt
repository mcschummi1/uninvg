package dev.uninvg.memory

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import kotlin.math.max

class SceneOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lines = emptyList<ImageLine>()
    private var lens: ViewLens? = null
    private var label = ""
    private var split = false
    private var color = Color.GREEN

    fun show(lines: List<ImageLine>, lens: ViewLens?, label: String, split: Boolean, color: Int) {
        this.lines = lines
        this.lens = lens
        this.label = label
        this.split = split
        this.color = color
        visibility = if (label.isEmpty()) GONE else VISIBLE
        contentDescription = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val projection = lens
        val density = resources.displayMetrics.density
        paint.color = color
        paint.strokeWidth = density
        paint.style = Paint.Style.STROKE
        paint.alpha = 110
        paint.pathEffect = DashPathEffect(floatArrayOf(density * 5, density * 4), 0f)
        fun drawEye(left: Float, eyeWidth: Float) {
            if (projection == null) return
            canvas.save()
            canvas.clipRect(left, 0f, left + eyeWidth, height.toFloat())
            val scaledWidth = max(eyeWidth.toDouble(), height * projection.aspect).toFloat()
            val scaledHeight = (scaledWidth / projection.aspect).toFloat()
            val offsetX = left + (eyeWidth - scaledWidth) / 2f
            val offsetY = (height - scaledHeight) / 2f
            for (line in lines) canvas.drawLine(
                offsetX + line.a.x.toFloat() * scaledWidth, offsetY + (1 - line.a.y).toFloat() * scaledHeight,
                offsetX + line.b.x.toFloat() * scaledWidth, offsetY + (1 - line.b.y).toFloat() * scaledHeight, paint
            )
            canvas.restore()
        }
        if (split) {
            val divider = max(2, width / 300)
            val eyeWidth = (width - divider) / 2f
            drawEye(0f, eyeWidth)
            drawEye(eyeWidth + divider, eyeWidth)
        } else drawEye(0f, width.toFloat())
        paint.pathEffect = null
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.textSize = 11f * density
        val x = 12f * density
        val y = height - 76f * density
        paint.setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
        val fitted = android.text.TextUtils.ellipsize(label, android.text.TextPaint(paint), width - 24f * density,
            android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(fitted, x, y, paint)
        paint.clearShadowLayer()
    }
}
