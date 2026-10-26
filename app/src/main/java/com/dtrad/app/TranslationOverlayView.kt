package com.dtrad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View

/** Une bulle à recouvrir : zone d'origine, texte français, couleurs. */
data class OverlayItem(
    val rect: RectF,
    val text: String,
    val background: Int,
    val foreground: Int
)

/** Dessine les textes traduits par-dessus la page. Un tap retire la traduction. */
class TranslationOverlayView(
    context: Context,
    private val items: List<OverlayItem>,
    private val onTap: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val pad = 6f * density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun sp(v: Float) = v * scaledDensity

    private fun build(text: String, width: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        for (item in items) {
            val box = RectF(item.rect).apply { inset(-pad, -pad) }
            val width = (box.width() - 2 * pad).toInt().coerceAtLeast(40)
            val maxHeight = box.height() - 2 * pad
            val minSize = sp(11f)

            var size = sp(22f)
            var layout = build(item.text, width, size)
            while (layout.height > maxHeight && size > minSize) {
                size -= sp(1f)
                layout = build(item.text, width, size)
            }

            // Si le français ne tient pas, on agrandit la zone autour du centre.
            val neededHeight = layout.height + 2 * pad
            if (neededHeight > box.height()) {
                val cy = box.centerY()
                box.top = cy - neededHeight / 2f
                box.bottom = cy + neededHeight / 2f
            }

            bgPaint.color = item.background
            canvas.drawRoundRect(box, 14f * density, 14f * density, bgPaint)

            textPaint.color = item.foreground
            canvas.save()
            canvas.translate(box.left + pad, box.top + (box.height() - layout.height) / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            onTap()
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
