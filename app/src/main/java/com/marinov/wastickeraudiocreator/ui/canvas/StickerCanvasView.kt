package com.marinov.wastickeraudiocreator.ui.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sticker canvas with WhatsApp-like behavior:
 * - Touch target detection: moves/scaling applies to whatever is under the fingers.
 * - one finger move (media or text)
 * - two finger pinch zoom & rotation (media or text)
 * - drawing mode (consumes single finger touches)
 * - overlays live in 512-unit sticker space
 */
class StickerCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    var mode: CanvasMode = CanvasMode.INTERACT
        set(value) {
            field = value
            invalidate()
        }

    var currentColor: Int = Color.WHITE
    var onEditTextListener: ((CanvasOp.Text) -> Unit)? = null
    var onBackgroundTapped: (() -> Unit)? = null

    var isAnimatedSource: Boolean = false
        private set

    val rect = RectF()

    // ------------------------------------------------------------------
    // Internal state (used by CanvasPainter / CanvasTouchHandler)
    // ------------------------------------------------------------------

    internal var bitmap: Bitmap? = null
    internal var animDrawable: Drawable? = null
    internal var srcW = 0
    internal var srcH = 0

    internal val baseMatrix = Matrix()
    internal val fitMatrix = Matrix()

    internal val ops = mutableListOf<CanvasOp>()
    internal var activeStroke: CanvasOp.Stroke? = null
    internal var selectedText: CanvasOp.Text? = null
    internal var textBeingEdited: CanvasOp.Text? = null

    internal var touchTarget: CanvasOp.Text? = null
    internal val lastTouch = PointF()

    internal var isMultiTouch = false
    internal var pointerId1 = -1
    internal var pointerId2 = -1
    internal var lastDistance = 0f
    internal var lastAngle = 0f
    internal val lastFocus = PointF()

    internal val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    internal val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    internal val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x99FFFFFF.toInt()
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    internal val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    internal val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFFFFFF.toInt()
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    internal val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isMultiTouch) return false
                if (rect.width() <= 0) return false

                val p = toSticker(e.x, e.y)
                val hit = hitTest(p)
                if (hit != null) {
                    selectedText = hit
                    onEditTextListener?.invoke(hit) // Open keyboard with single tap
                    return true
                } else {
                    selectedText = null
                    onBackgroundTapped?.invoke() // Close keyboard if tapping outside text
                    return true
                }
            }
        }
    )

    private val painter = CanvasPainter(this)
    private val touchHandler = CanvasTouchHandler(this)

    // ------------------------------------------------------------------
    // Source
    // ------------------------------------------------------------------

    internal fun hasSource(): Boolean = bitmap != null || animDrawable != null

    fun setStaticSource(b: Bitmap) {
        stopAnim()
        bitmap = b
        animDrawable = null
        isAnimatedSource = false
        srcW = b.width
        srcH = b.height
        post { resetFit() }
    }

    /** [d] may be null on API < 28: export still works, only preview is missing. */
    fun setAnimatedSource(d: Drawable?, w: Int, h: Int) {
        stopAnim()
        bitmap = null
        animDrawable = d
        isAnimatedSource = true
        srcW = max(1, w)
        srcH = max(1, h)

        d?.let {
            it.setBounds(0, 0, srcW, srcH)
            it.callback = this
            if (Build.VERSION.SDK_INT >= 28 && it is AnimatedImageDrawable) it.start()
        }

        post { resetFit() }
    }

    private fun stopAnim() {
        animDrawable?.let {
            if (Build.VERSION.SDK_INT >= 28 && it is AnimatedImageDrawable) it.stop()
            it.callback = null
        }
    }

    override fun invalidateDrawable(d: Drawable) = invalidate()

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // Public text API
    // ------------------------------------------------------------------

    fun addTextCentered(text: String) {
        val t = CanvasOp.Text(
            x = 256f,
            y = 256f,
            text = text,
            color = currentColor,
            size = 48f,
            rotation = 0f
        )
        ops.add(t)
        selectedText = t
        invalidate()
    }

    fun removeText(op: CanvasOp.Text) {
        ops.remove(op)
        if (selectedText === op) selectedText = null
        if (textBeingEdited === op) textBeingEdited = null
        invalidate()
    }

    fun selectText(op: CanvasOp.Text?) {
        selectedText = op
        invalidate()
    }

    fun getSelectedText(): CanvasOp.Text? = selectedText

    /**
     * Applies a color to the selected text if there is one;
     * always updates currentColor for new text/drawing.
     */
    fun setColor(color: Int) {
        currentColor = color
        selectedText?.color = color
        textBeingEdited?.color = color
        invalidate()
    }

    fun undo() {
        if (ops.isNotEmpty()) {
            val last = ops.removeAt(ops.lastIndex)
            if (last === selectedText) selectedText = null
            if (last === textBeingEdited) textBeingEdited = null
        }
        activeStroke = null
        invalidate()
    }

    // ------------------------------------------------------------------
    // Text editing visibility
    // ------------------------------------------------------------------

    /**
     * Hides the given text (and its selection box) from the canvas
     * while the keyboard is open. Pass null for new text creation.
     */
    fun startEditingText(op: CanvasOp.Text?) {
        textBeingEdited = op
        invalidate()
    }

    /**
     * Shows the text again after editing is committed/cancelled.
     */
    fun stopEditingText() {
        textBeingEdited = null
        invalidate()
    }

    // ------------------------------------------------------------------
    // Geometry / state
    // ------------------------------------------------------------------

    fun imageEdited(): Boolean {
        if (ops.isNotEmpty()) return true
        val a = FloatArray(9)
        val b = FloatArray(9)
        baseMatrix.getValues(a)
        fitMatrix.getValues(b)
        return a.indices.any { abs(a[it] - b[it]) > 0.5f }
    }

    private fun computeRect() {
        val pad = 24f * resources.displayMetrics.density
        val s = max(1f, min(width.toFloat(), height.toFloat()) - 2 * pad)
        rect.set(
            (width - s) / 2f,
            (height - s) / 2f,
            (width + s) / 2f,
            (height + s) / 2f
        )
    }

    private fun resetFit() {
        computeRect()
        if (srcW <= 0 || srcH <= 0 || rect.width() <= 0) return

        // contain: whole media centered; smaller ones scaled up.
        val sc = min(rect.width() / srcW, rect.height() / srcH)
        fitMatrix.reset()
        fitMatrix.postScale(sc, sc)
        fitMatrix.postTranslate(
            rect.centerX() - srcW * sc / 2f,
            rect.centerY() - srcH * sc / 2f
        )
        baseMatrix.set(fitMatrix)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFit()
    }

    internal fun toSticker(x: Float, y: Float): PointF = PointF(
        (x - rect.left) * 512f / rect.width(),
        (y - rect.top) * 512f / rect.height()
    )

    internal fun hitTest(p: PointF): CanvasOp.Text? {
        for (op in ops.asReversed()) {
            if (op is CanvasOp.Text) {
                // Do not hit-test the text that is currently being edited
                if (op === textBeingEdited) continue

                textPaint.textSize = op.size
                val w = textPaint.measureText(op.text)

                val dx = p.x - op.x
                val dy = p.y - op.y

                val rad = Math.toRadians(op.rotation.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()

                // Inverse rotate point into text-local space.
                val lx = dx * c + dy * s
                val ly = -dx * s + dy * c

                val hitPad = 8f
                if (lx >= -hitPad && lx <= w + hitPad &&
                    ly >= -op.size && ly <= op.size * 0.35f
                ) {
                    return op
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Touch handling (delegated to CanvasTouchHandler)
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return touchHandler.onTouchEvent(event)
    }

    // ------------------------------------------------------------------
    // Drawing (delegated to CanvasPainter)
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (rect.width() <= 0) computeRect()

        canvas.save()
        canvas.clipRect(rect)

        val bmp = bitmap
        val anim = animDrawable

        when {
            bmp != null -> canvas.drawBitmap(bmp, baseMatrix, null)

            anim != null -> {
                canvas.save()
                canvas.concat(baseMatrix)
                anim.draw(canvas)
                canvas.restore()
            }

            else -> canvas.drawText(
                "512 × 512",
                rect.centerX(),
                rect.centerY(),
                placeholderPaint
            )
        }

        canvas.restore()

        canvas.drawRect(rect, borderPaint)

        val k = rect.width() / 512f
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left, rect.top)
        canvas.scale(k, k)

        painter.drawOps(canvas)
        painter.drawSelection(canvas)

        canvas.restore()
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /** Static export: base + overlays at 512x512. */
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

        painter.drawOps(c)
        return out
    }

    /** Overlay-only 512x512 RGBA (fed to the native animated encoder). */
    fun exportOverlay(): Bitmap {
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        painter.drawOps(Canvas(out))
        return out
    }

    /**
     * Source-media -> 512 sticker-space affine matrix:
     * [a, b, c, d, e, f]
     *
     * dstX = a*srcX + b*srcY + c
     * dstY = d*srcX + e*srcY + f
     */
    fun baseTransform512(): FloatArray {
        if (rect.width() <= 0) {
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        }

        val v = FloatArray(9)
        baseMatrix.getValues(v)

        val k = 512f / rect.width()

        return floatArrayOf(
            v[Matrix.MSCALE_X] * k,
            v[Matrix.MSKEW_X] * k,
            (v[Matrix.MTRANS_X] - rect.left) * k,
            v[Matrix.MSKEW_Y] * k,
            v[Matrix.MSCALE_Y] * k,
            (v[Matrix.MTRANS_Y] - rect.top) * k
        )
    }
}