package com.ailamp.app

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout

class VoiceControlDock(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val preferences = VoiceSettings.preferences(context)
    private var horizontal = preferences.getFloat("voice_dock_x", 1f).coerceIn(0f, 1f)
    private var vertical = preferences.getFloat("voice_dock_y", 1f).coerceIn(0f, 1f)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val container get() = parent as? View
    private val containerLayout = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    private var startX = 0f
    private var startY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onFinishInflate() {
        super.onFinishInflate()
        findViewById<View>(R.id.voice_chat_drag_handle).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = translationX
                    initialY = translationY
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (dragging || dx * dx + dy * dy >= touchSlop * touchSlop) {
                        dragging = true
                        val (maxX, maxY) = travel()
                        translationX = (initialX + dx).coerceIn(0f, maxX)
                        translationY = (initialY + dy).coerceIn(0f, maxY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) rememberPosition()
                    dragging = false
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        container?.addOnLayoutChangeListener(containerLayout)
        post { place() }
    }

    override fun onDetachedFromWindow() {
        container?.removeOnLayoutChangeListener(containerLayout)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        place()
    }

    private fun travel(): Pair<Float, Float> {
        val area = container ?: return 0f to 0f
        val maxX = (area.width - area.paddingLeft - area.paddingRight - width).coerceAtLeast(0)
        val maxY = (area.height - area.paddingTop - area.paddingBottom - height).coerceAtLeast(0)
        return maxX.toFloat() to maxY.toFloat()
    }

    private fun place() {
        if (dragging) return
        val (maxX, maxY) = travel()
        translationX = horizontal * maxX
        translationY = vertical * maxY
    }

    private fun rememberPosition() {
        val (maxX, maxY) = travel()
        if (maxX > 0) horizontal = (translationX / maxX).coerceIn(0f, 1f)
        if (maxY > 0) vertical = (translationY / maxY).coerceIn(0f, 1f)
        preferences.edit().putFloat("voice_dock_x", horizontal).putFloat("voice_dock_y", vertical).apply()
    }
}
