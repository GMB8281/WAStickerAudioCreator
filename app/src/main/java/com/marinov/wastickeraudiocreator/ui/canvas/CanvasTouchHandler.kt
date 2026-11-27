package com.marinov.wastickeraudiocreator.ui.canvas

import android.graphics.Path
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Handles all touch/gesture logic for the sticker canvas.
 */
internal class CanvasTouchHandler(private val view: StickerCanvasView) {

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (view.rect.width() <= 0) return true

        // Intercept ALL touch events for DRAW mode immediately
        if (view.mode == CanvasMode.DRAW) {
            handleDraw(event)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isMultiTouch = false
                view.pointerId1 = event.getPointerId(0)
                view.pointerId2 = -1

                // Detect what is under the first finger
                val p = view.toSticker(event.x, event.y)
                view.touchTarget = view.hitTest(p)
                view.touchTarget?.let { view.selectedText = it }

                view.lastTouch.set(event.x, event.y)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    startMultiTouch(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (view.isMultiTouch) {
                    updateMultiTouch(event)
                } else {
                    handleSingleTouchMove(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIdx = event.actionIndex
                val upId = event.getPointerId(upIdx)
                if (upId == view.pointerId1 || upId == view.pointerId2) {
                    // Find the index of the remaining finger to prevent jump
                    val remainingIdx = if (upIdx == 0) 1 else 0
                    if (remainingIdx < event.pointerCount) {
                        view.lastTouch.set(
                            event.getX(remainingIdx),
                            event.getY(remainingIdx)
                        )
                    }
                    endMultiTouch()
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                endMultiTouch()
                view.touchTarget = null
            }
        }

        // Let gesture detector handle taps
        view.gestureDetector.onTouchEvent(event)

        return true
    }

    private fun handleSingleTouchMove(event: MotionEvent) {
        val dx = event.x - view.lastTouch.x
        val dy = event.y - view.lastTouch.y

        val t = view.touchTarget
        if (t != null) {
            // Dragging text
            val k = 512f / view.rect.width()
            t.x += dx * k
            t.y += dy * k
        } else {
            // Dragging media
            if (view.hasSource()) {
                view.baseMatrix.postTranslate(dx, dy)
            }
        }

        view.lastTouch.set(event.x, event.y)
        view.invalidate()
    }

    private fun startMultiTouch(event: MotionEvent) {
        if (event.pointerCount < 2) return

        view.pointerId1 = event.getPointerId(0)
        view.pointerId2 = event.getPointerId(1)

        val x1 = event.getX(0)
        val y1 = event.getY(0)
        val x2 = event.getX(1)
        val y2 = event.getY(1)

        view.lastDistance = distance(x1, y1, x2, y2)
        view.lastAngle = angle(x1, y1, x2, y2)
        view.lastFocus.set((x1 + x2) / 2f, (y1 + y2) / 2f)

        view.isMultiTouch = true
        view.invalidate()
    }

    private fun updateMultiTouch(event: MotionEvent) {
        if (!view.isMultiTouch) return

        val idx1 = event.findPointerIndex(view.pointerId1)
        val idx2 = event.findPointerIndex(view.pointerId2)
        if (idx1 < 0 || idx2 < 0) {
            endMultiTouch()
            return
        }

        val x1 = event.getX(idx1)
        val y1 = event.getY(idx1)
        val x2 = event.getX(idx2)
        val y2 = event.getY(idx2)

        val dist = distance(x1, y1, x2, y2)
        val ang = angle(x1, y1, x2, y2)
        val focus = android.graphics.PointF((x1 + x2) / 2f, (y1 + y2) / 2f)

        if (view.lastDistance > 0f && dist > 0f) {
            val scaleFactor = dist / view.lastDistance

            var rotationDelta = ang - view.lastAngle
            if (rotationDelta > 180f) rotationDelta -= 360f
            if (rotationDelta < -180f) rotationDelta += 360f

            val dx = focus.x - view.lastFocus.x
            val dy = focus.y - view.lastFocus.y

            val t = view.touchTarget
            if (t != null) {
                // Transform text
                val k = 512f / view.rect.width()

                // Translate with fingers.
                t.x += dx * k
                t.y += dy * k

                // Rotate/scale around current focus.
                val f = view.toSticker(focus.x, focus.y)
                val vx = t.x - f.x
                val vy = t.y - f.y

                val rad = Math.toRadians(rotationDelta.toDouble())
                val c = kotlin.math.cos(rad).toFloat()
                val s = kotlin.math.sin(rad).toFloat()

                val nvx = (vx * c - vy * s) * scaleFactor
                val nvy = (vx * s + vy * c) * scaleFactor

                t.x = f.x + nvx
                t.y = f.y + nvy

                t.size = (t.size * scaleFactor).coerceIn(8f, 400f)
                t.rotation += rotationDelta
            } else {
                // Transform media
                if (view.hasSource()) {
                    view.baseMatrix.postTranslate(dx, dy)
                    view.baseMatrix.postScale(scaleFactor, scaleFactor, focus.x, focus.y)
                    view.baseMatrix.postRotate(rotationDelta, focus.x, focus.y)
                }
            }
        }

        view.lastDistance = dist
        view.lastAngle = ang
        view.lastFocus.set(focus.x, focus.y)

        view.invalidate()
    }

    private fun endMultiTouch() {
        view.isMultiTouch = false
        view.pointerId1 = -1
        view.pointerId2 = -1
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    private fun angle(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
    }

    private fun handleDraw(event: MotionEvent) {
        val p = view.toSticker(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path().apply { moveTo(p.x, p.y) }
                view.activeStroke = CanvasOp.Stroke(path, view.currentColor, 12f)
            }

            MotionEvent.ACTION_MOVE -> {
                view.activeStroke?.path?.lineTo(p.x, p.y)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                view.activeStroke?.let { view.ops.add(it) }
                view.activeStroke = null
            }
        }

        view.invalidate()
    }
}