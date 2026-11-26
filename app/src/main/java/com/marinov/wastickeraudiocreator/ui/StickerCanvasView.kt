package com.marinov.wastickeraudiocreator.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Free-move / pinch-zoom canvas with a dashed 512×512 sticker boundary,
 * scribble strokes and draggable text. Overlays live in 512-unit sticker space.
 */
class StickerCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { MOVE, DRAW, TEXT }

    sealed class Op {
        class Stroke(val path: Path, val color: Int, val width: Float) : Op()
        class Text(var x: Float, var y: Float, val text: String, val color: Int, val size: Float) : Op()
    }

    var mode: Mode = Mode.MOVE
    var currentColor: Int = Color.WHITE
    /** Called when the user taps empty space in TEXT mode (sticker-space coords). */
    var textPlacementListener: ((Float, Float) -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val baseMatrix = Matrix()
    private val fitMatrix = Matrix()
    private val ops = mutableListOf<Op>()
    private var activeStroke: Op.Stroke? = null
    private var dragText: Op.Text? = null
    private val lastTouch = PointF()
    private val rect = RectF()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x99FFFFFF.toInt(); strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt(); textSize = 36f; textAlign = Paint.Align.CENTER
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (mode == Mode.MOVE && bitmap != null) {
                baseMatrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
                invalidate()
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode == Mode.MOVE && bitmap != null) {
                baseMatrix.postTranslate(-dx, -dy)
                invalidate()
            }
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode == Mode.TEXT && rect.width() > 0) {
                val p = toSticker(e.x, e.y)
                if (hitTest(p) == null) textPlacementListener?.invoke(p.x, p.y)
            }
            return true
        }
    })

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        post { resetFit() }
    }

    fun imageEdited(): Boolean {
        if (ops.isNotEmpty()) return true
        val a = FloatArray(9); val b = FloatArray(9)
        baseMatrix.getValues(a); fitMatrix.getValues(b)
        return a.indices.any { abs(a[it] - b[it]) > 0.5f }
    }

    fun undo() {
        if (ops.isNotEmpty()) ops.removeAt(ops.lastIndex)
        activeStroke = null
        invalidate()
    }

    fun addText(text: String, x: Float, y: Float) {
        ops.add(Op.Text(x, y, text, currentColor, 48f))
        invalidate()
    }

    private fun computeRect() {
        val pad = 24f * resources.displayMetrics.density
        val s = max(1f, min(width.toFloat(), height.toFloat()) - 2 * pad)
        rect.set((width - s) / 2f, (height - s) / 2f, (width + s) / 2f, (height + s) / 2f)
    }

    private fun resetFit() {
        computeRect()
        val bmp = bitmap ?: return
        if (rect.width() <= 0) return
        // "contain": whole image fits inside the square, centered. Smaller images
        // are scaled up; rectangular ones get transparent margins and the user
        // can pinch/drag to fill the 512x512 area.
        val sc = min(rect.width() / bmp.width, rect.height() / bmp.height)
        fitMatrix.reset()
        fitMatrix.postScale(sc, sc)
        fitMatrix.postTranslate(
            rect.centerX() - bmp.width * sc / 2f,
            rect.centerY() - bmp.height * sc / 2f
        )
        baseMatrix.set(fitMatrix)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFit()
    }

    private fun toSticker(x: Float, y: Float): PointF =
        PointF((x - rect.left) * 512f / rect.width(), (y - rect.top) * 512f / rect.height())

    private fun hitTest(p: PointF): Op.Text? {
        for (op in ops.asReversed()) {
            if (op is Op.Text) {
                textPaint.textSize = op.size
                val w = textPaint.measureText(op.text)
                if (p.x >= op.x && p.x <= op.x + w && p.y >= op.y - op.size && p.y <= op.y + op.size * 0.3f) {
                    return op
                }
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rect.width() <= 0) return true
        when (mode) {
            Mode.MOVE -> {
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
            }
            Mode.DRAW -> handleDraw(event)
            Mode.TEXT -> {
                gestureDetector.onTouchEvent(event)
                handleTextDrag(event)
            }
        }
        return true
    }

    private fun handleDraw(event: MotionEvent) {
        val p = toSticker(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path().apply { moveTo(p.x, p.y) }
                activeStroke = Op.Stroke(path, currentColor, 12f)
            }
            MotionEvent.ACTION_MOVE -> activeStroke?.path?.lineTo(p.x, p.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeStroke?.let { ops.add(it) }
                activeStroke = null
            }
        }
        invalidate()
    }

    private fun handleTextDrag(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragText = hitTest(toSticker(event.x, event.y))
                lastTouch.set(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                dragText?.let {
                    val k = 512f / rect.width()
                    it.x += (event.x - lastTouch.x) * k
                    it.y += (event.y - lastTouch.y) * k
                    lastTouch.set(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragText = null
        }
        invalidate()
    }

    private fun drawOps(canvas: Canvas) {
        for (op in ops) drawOp(canvas, op)
        activeStroke?.let { drawOp(canvas, it) }
    }

    private fun drawOp(canvas: Canvas, op: Op) {
        when (op) {
            is Op.Stroke -> {
                strokePaint.color = op.color
                strokePaint.strokeWidth = op.width
                canvas.drawPath(op.path, strokePaint)
            }
            is Op.Text -> {
                textPaint.color = op.color
                textPaint.textSize = op.size
                canvas.drawText(op.text, op.x, op.y, textPaint)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.width() <= 0) computeRect()
        val bmp = bitmap
        if (bmp != null) {
            canvas.save()
            canvas.clipRect(rect)
            canvas.drawBitmap(bmp, baseMatrix, null)
            canvas.restore()
        } else {
            canvas.drawText("512 × 512", rect.centerX(), rect.centerY(), placeholderPaint)
        }
        canvas.drawRect(rect, borderPaint)
        // overlays: sticker space -> view space
        val k = rect.width() / 512f
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left, rect.top)
        canvas.scale(k, k)
        drawOps(canvas)
        canvas.restore()
    }

    /** Render the final 512×512 sticker (base + overlays). */
    fun export(): Bitmap {
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bmp = bitmap
        if (bmp != null && rect.width() > 0) {
            val k = 512f / rect.width()
            c.save()
            c.scale(k, k)
            c.translate(-rect.left, -rect.top)
            c.drawBitmap(bmp, baseMatrix, null)
            c.restore()
        }
        drawOps(c) // already in 512-unit sticker space
        return out
    }
}