package com.example.modelviewer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var runtime: RenderRuntime
    private lateinit var canvas: FrameLayout
    private val cards = ArrayList<ModelCardView>(5)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = RenderRuntime(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff10131a.toInt())
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            // Keep controls clear of status/navigation bars on edge-to-edge Android versions.
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val add = Button(this).apply {
            text = "Add model"
            setOnClickListener { button ->
                val popup = PopupMenu(this@MainActivity, button)
                ModelCatalog.models.forEachIndexed { i, spec -> popup.menu.add(0, i, i, spec.title) }
                popup.setOnMenuItemClickListener { item -> addModel(ModelCatalog.models[item.itemId]); true }
                popup.show()
            }
        }
        root.addView(add, LinearLayout.LayoutParams(-1, dp(52)))
        canvas = FrameLayout(this).apply { clipChildren = true }
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        canvas.post {
            val restored = savedInstanceState?.getIntegerArrayList("models")
            if (restored == null) addModel(ModelCatalog.models[0])
            else restored.forEach { if (it in ModelCatalog.models.indices) addModel(ModelCatalog.models[it]) }
        }
    }

    private fun addModel(spec: ModelSpec) {
        if (isFinishing || cards.size == 5 || canvas.width == 0 || canvas.height == 0) {
            if (cards.size == 5) Toast.makeText(this, "Maximum 5 models", Toast.LENGTH_SHORT).show()
            return
        }
        val size = minOf(dp(250), canvas.width, canvas.height)
        val card = ModelCardView(this, runtime, spec) { closed ->
            closed.release() // Cancel and destroy native resources before detaching the UI.
            cards.remove(closed)
            canvas.removeView(closed)
        }
        val offset = cards.size * dp(22)
        cards.add(card)
        canvas.addView(card, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT))
        card.x = offset.coerceAtMost(canvas.width - size).toFloat()
        card.y = offset.coerceAtMost(canvas.height - size).toFloat()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putIntegerArrayList("models", ArrayList(cards.map { ModelCatalog.models.indexOf(it.spec) }))
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); runtime.resume() }
    override fun onPause() { runtime.pause(); super.onPause() }
    override fun onDestroy() {
        for (card in cards) card.release()
        cards.clear()
        runtime.close()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
