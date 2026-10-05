package com.example.modelviewer

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class CardGestureView(
    context: Context,
    private val card: ModelCardView,
    private val target: RenderTarget
) : View(context) {
    private var interaction = false
    private var active = false
    private var rebase = true
    private var lastX = 0f
    private var lastY = 0f
    private var resizeScale = 1f
    private val detector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector) = active
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!active) return false
                if (interaction) target.zoom(detector.scaleFactor)
                else {
                    val root = card.parent as? View ?: return false
                    val maxSide = minOf(dp(520), root.width, root.height).coerceAtLeast(1)
                    val minSide = minOf(dp(150), maxSide)
                    val base = card.layoutParams.width.coerceAtLeast(1)
                    val side = (base * resizeScale * detector.scaleFactor)
                        .coerceIn(minSide.toFloat(), maxSide.toFloat())
                    resizeScale = side / base
                    // GPU/UI composition transform during pinch: no repeated requestLayout().
                    card.scaleX = resizeScale; card.scaleY = resizeScale
                    card.clampPosition()
                }
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (!interaction) commitResize()
                rebase = true
            }
        })

    init { isClickable = true; detector.isQuickScaleEnabled = false }

    fun setInteractionMode(enabled: Boolean) {
        cancel() // Mode changes invalidate the old stream; require a new ACTION_DOWN.
        interaction = enabled
    }

    fun cancel() {
        if (!interaction) commitResize()
        active = false; rebase = true
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            active = true; rebase = false
            lastX = event.rawX; lastY = event.rawY
            card.bringToFront()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        // Feed even cancelled streams so the detector receives its terminal event.
        detector.onTouchEvent(event)
        if (!active) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> rebase = true
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1 || detector.isInProgress) { rebase = true; return true }
                val rawX = event.rawX; val rawY = event.rawY
                if (!rebase) {
                    val dx = rawX - lastX; val dy = rawY - lastY
                    if (interaction) target.rotate(dx, dy)
                    else {
                        // View translations avoid layout traversal for every drag event.
                        card.x += dx; card.y += dy
                        card.clampPosition()
                    }
                }
                lastX = rawX; lastY = rawY; rebase = false
            }
            MotionEvent.ACTION_UP -> { cancel(); performClick() }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun commitResize() {
        if (resizeScale == 1f) return
        val params = card.layoutParams ?: return
        val side = (params.width * resizeScale).toInt().coerceAtLeast(1)
        params.width = side; params.height = side
        card.scaleX = 1f; card.scaleY = 1f; resizeScale = 1f
        // Commit once when pinch ends. Fixed-size render buffers survive this layout pass.
        card.layoutParams = params
        card.clampPosition()
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
