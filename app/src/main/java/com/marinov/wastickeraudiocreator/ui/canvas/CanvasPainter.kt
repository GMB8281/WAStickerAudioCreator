package com.marinov.wastickeraudiocreator.ui.canvas

import android.graphics.Canvas

/**
 * Handles all drawing of overlay operations and selection indicators.
 */
internal class CanvasPainter(private val view: StickerCanvasView) {

    /**
     * Draws all committed ops plus the active stroke, skipping the text
     * that is currently being edited.
     */
    fun drawOps(canvas: Canvas) {
        for (op in view.ops) {
            if (op === view.textBeingEdited) continue
            drawOp(canvas, op)
        }
        view.activeStroke?.let { drawOp(canvas, it) }
    }

    fun drawOp(canvas: Canvas, op: CanvasOp) {
        when (op) {
            is CanvasOp.Stroke -> {
                view.strokePaint.color = op.color
                view.strokePaint.strokeWidth = op.width
                canvas.drawPath(op.path, view.strokePaint)
            }

            is CanvasOp.Text -> {
                canvas.save()
                canvas.translate(op.x, op.y)
                canvas.rotate(op.rotation)

                view.textPaint.color = op.color
                view.textPaint.textSize = op.size
                canvas.drawText(op.text, 0f, 0f, view.textPaint)

                canvas.restore()
            }
        }
    }

    /**
     * Draws the selection box around the selected text, unless it is
     * currently being edited (in which case it is hidden).
     */
    fun drawSelection(canvas: Canvas) {
        view.selectedText?.let { t ->
            if (t !== view.textBeingEdited) {
                view.textPaint.textSize = t.size
                val w = view.textPaint.measureText(t.text)

                canvas.save()
                canvas.translate(t.x, t.y)
                canvas.rotate(t.rotation)
                canvas.drawRect(
                    -8f,
                    -t.size,
                    w + 8f,
                    t.size * 0.35f,
                    view.selPaint
                )
                canvas.restore()
            }
        }
    }
}