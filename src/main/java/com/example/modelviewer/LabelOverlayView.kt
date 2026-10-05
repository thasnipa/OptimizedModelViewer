package com.example.modelviewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.min

class LabelOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val baseTextSize = 10f * resources.displayMetrics.scaledDensity
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffffffff.toInt(); textSize = baseTextSize
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xe6191f29.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff91b9de.toInt(); strokeWidth = density
    }
    private val pad = 4f * density
    private val edge = 2f * density
    private val topInset = 48f * density
    private val font = textPaint.fontMetrics // Cached metrics; no TextViews or font-metric allocations per frame.
    private val fontHeight = font.descent - font.ascent
    private var anchors = emptyArray<GlbMetadataParser.Anchor>()
    private var order = IntArray(0)
    private var boxY = FloatArray(0)

    init { visibility = INVISIBLE; isClickable = false }

    fun install(values: Array<GlbMetadataParser.Anchor>) {
        anchors = values
        // Storage is reused for every subsequent projection/draw of this model.
        order = IntArray(values.size)
        boxY = FloatArray(values.size)
        textPaint.textSize = baseTextSize
        for (anchor in anchors) anchor.textWidth = textPaint.measureText(anchor.text)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableHeight = height - topInset - edge
        val maximumWidth = width * 0.44f - edge
        if (availableHeight <= 0 || maximumWidth <= pad * 2) return
        var count = 0
        for (i in anchors.indices) if (anchors[i].visible) order[count++] = i
        if (count == 0) return
        // Sort by screen X, divide evenly, then order each column by screen Y.
        // Insertion sort is cheap for these assets (5..12 labels), and allocates nothing.
        sort(0, count, true)
        val split = (count + 1) / 2
        sort(0, split, false); sort(split, count, false)
        val slot = availableHeight / split
        val heightScale = ((slot - 2f * density - pad * 2) / fontHeight).coerceIn(0.1f, 1f)
        val boxHeight = min(fontHeight * heightScale + pad * 2, slot)
        val spacing = min(boxHeight + 2f * density, slot)
        placeColumn(0, split, spacing, boxHeight)
        placeColumn(split, count, spacing, boxHeight)
        for (rank in 0 until count) {
            val anchor = anchors[order[rank]]
            val widthScale = ((maximumWidth - pad * 2) / anchor.textWidth.coerceAtLeast(1f)).coerceAtMost(1f)
            val textScale = min(heightScale, widthScale)
            val boxWidth = min(anchor.textWidth * textScale + pad * 2, maximumWidth)
            val left = if (rank < split) edge else width - edge - boxWidth
            val top = boxY[rank]
            val connectorX = if (rank < split) left + boxWidth else left
            val connectorY = anchor.y.coerceIn(top, top + boxHeight)
            canvas.drawLine(anchor.x, anchor.y, connectorX, connectorY, line)
            canvas.drawCircle(anchor.x, anchor.y, 2f * density, line)
            canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, pad, pad, fill)
            // Fit the COMPLETE label string; no runtime substring or silent text truncation.
            textPaint.textSize = baseTextSize * textScale
            val baseline = top + (boxHeight - fontHeight * textScale) * 0.5f - font.ascent * textScale
            canvas.drawText(anchor.text, left + pad, baseline, textPaint)
        }
    }

    private fun sort(start: Int, end: Int, byX: Boolean) {
        for (i in start + 1 until end) {
            val index = order[i]
            val value = if (byX) anchors[index].x else anchors[index].y
            var j = i - 1
            while (j >= start && (if (byX) anchors[order[j]].x else anchors[order[j]].y) > value) {
                order[j + 1] = order[j]; j--
            }
            order[j + 1] = index
        }
    }

    private fun placeColumn(start: Int, end: Int, spacing: Float, boxHeight: Float) {
        if (start == end) return
        var lower = topInset
        val bottom = height - edge - boxHeight
        for (rank in start until end) {
            val desired = (anchors[order[rank]].y - boxHeight * 0.5f).coerceIn(topInset, bottom)
            boxY[rank] = maxOf(desired, lower)
            lower = boxY[rank] + spacing
        }
        var upper = bottom
        for (rank in end - 1 downTo start) {
            boxY[rank] = minOf(boxY[rank], upper)
            upper = boxY[rank] - spacing
        }
    }
}
