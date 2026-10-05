package com.example.modelviewer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class ModelCardView(
    context: Context,
    runtime: RenderRuntime,
    val spec: ModelSpec,
    private val onClose: (ModelCardView) -> Unit
) : FrameLayout(context) {
    private var closed = false
    private var interacting = false
    private var labels = false
    private val texture = TextureView(context).apply { isOpaque = true }
    private val overlay = LabelOverlayView(context)
    private val message = TextView(context).apply {
        text = "Loading ${spec.title}…"; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER; setPadding(dp(10), dp(48), dp(10), dp(10))
        isClickable = false
    }
    private val target: RenderTarget
    private val gestures: CardGestureView

    init {
        setBackgroundColor(0xff1c212b.toInt())
        pivotX = 0f; pivotY = 0f
        clipChildren = true
        addView(texture, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1))
        addView(message, LayoutParams(-1, -1))
        target = RenderTarget(runtime, texture, spec, overlay) { error ->
            message.text = error ?: ""
            message.visibility = if (error == null) GONE else VISIBLE
        }
        gestures = CardGestureView(context, this, target)
        addView(gestures, LayoutParams(-1, -1))
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val mode = button("Normal", "Toggle container/model interaction")
        val label = button("Labels", "Toggle part labels").apply { alpha = 0.5f }
        val close = button("×", "Close ${spec.title}")
        bar.addView(mode, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(label, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        mode.setOnClickListener {
            interacting = !interacting
            gestures.setInteractionMode(interacting)
            mode.text = if (interacting) "Interaction" else "Normal"
            mode.isSelected = interacting
        }
        label.setOnClickListener {
            labels = !labels; target.setLabels(labels)
            label.alpha = if (labels) 1f else 0.5f
            label.isSelected = labels
        }
        close.setOnClickListener { if (!closed) onClose(this) }
        addView(bar, LayoutParams(-1, dp(44)))
    }

    fun release() {
        if (closed) return
        closed = true
        gestures.cancel()
        target.close()
        texture.setOnTouchListener(null)
        removeAllViews()
    }

    override fun onDetachedFromWindow() {
        release() // This component treats detach as permanent disposal.
        super.onDetachedFromWindow()
    }

    fun clampPosition() {
        val root = parent as? android.view.View ?: return
        x = x.coerceIn(0f, (root.width - width * scaleX).coerceAtLeast(0f))
        y = y.coerceIn(0f, (root.height - height * scaleY).coerceAtLeast(0f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPosition()
    }

    private fun button(text: String, description: String) = TextView(context).apply {
        this.text = text; textSize = 11f
        contentDescription = description
        gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setBackgroundColor(0xee263246.toInt())
        isClickable = true; isFocusable = true
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
