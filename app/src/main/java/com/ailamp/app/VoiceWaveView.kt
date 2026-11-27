package com.ailamp.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sin

class VoiceWaveView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.shell_accent)
    }
    private var level = 0f
    private var animating = false

    fun showLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun animatePlayback(active: Boolean) {
        if (animating == active) return
        animating = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bars = 15
        val step = width.toFloat() / bars
        val radius = step * 0.24f
        val time = SystemClock.uptimeMillis() / 170.0
        val energy = if (animating) 0.35f + 0.5f * abs(sin(time)).toFloat() else level
        for (index in 0 until bars) {
            val x = step * (index + 0.5f)
            val shape = 0.35f + 0.65f * abs(sin(index * 0.75 + if (animating) time else 0.0)).toFloat()
            val halfHeight = radius + height * 0.36f * energy * shape
            val center = height * 0.5f
            canvas.drawRoundRect(x - radius, center - halfHeight, x + radius, center + halfHeight, radius, radius, paint)
        }
        if (animating) postInvalidateOnAnimation()
    }
}
