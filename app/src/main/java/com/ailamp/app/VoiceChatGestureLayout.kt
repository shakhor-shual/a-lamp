package com.ailamp.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs

internal enum class VoiceChatSwipe { UP, LEFT }

internal fun voiceChatSwipe(dx: Float, dy: Float, threshold: Float): VoiceChatSwipe? = when {
    dy <= -threshold && -dy > abs(dx) * 1.5f -> VoiceChatSwipe.UP
    dx <= -threshold && -dx > abs(dy) * 1.5f -> VoiceChatSwipe.LEFT
    else -> null
}

class VoiceChatGestureLayout(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val threshold = 64 * resources.displayMetrics.density
    private val screenPosition = IntArray(2)
    private var trackingSwipe = false
    private var startX = 0f
    private var startY = 0f
    private var recording: View? = null
    private var dock: View? = null
    private var autoSend: View? = null
    private var canSwipe: (VoiceChatSwipe) -> Boolean = { false }
    private var onSwipe: (VoiceChatSwipe) -> Unit = {}

    override fun onFinishInflate() {
        super.onFinishInflate()
        recording = findViewById(R.id.voice_chat_recording)
        dock = findViewById(R.id.voice_chat_dock)
        autoSend = findViewById(R.id.voice_chat_autosend)
    }

    internal fun setSwipeActions(canSwipe: (VoiceChatSwipe) -> Boolean, onSwipe: (VoiceChatSwipe) -> Unit) {
        this.canSwipe = canSwipe
        this.onSwipe = onSwipe
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                trackingSwipe = recording?.isShown == true &&
                    !contains(dock, startX, startY) && !contains(autoSend, startX, startY)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> trackingSwipe = false
            MotionEvent.ACTION_UP -> {
                val swipe = if (trackingSwipe && recording?.isShown == true) {
                    voiceChatSwipe(event.rawX - startX, event.rawY - startY, threshold)
                } else null
                trackingSwipe = false
                if (swipe != null && canSwipe(swipe)) {
                    // The child receives cancellation instead of an up event that might click a button.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    onSwipe(swipe)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun contains(view: View?, x: Float, y: Float): Boolean {
        if (view == null || !view.isShown) return false
        view.getLocationOnScreen(screenPosition)
        return x >= screenPosition[0] && x < screenPosition[0] + view.width &&
            y >= screenPosition[1] && y < screenPosition[1] + view.height
    }
}
