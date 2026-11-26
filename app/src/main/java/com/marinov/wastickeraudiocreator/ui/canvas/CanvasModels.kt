package com.marinov.wastickeraudiocreator.ui.canvas

import android.graphics.Path

/**
 * Operating mode of the sticker canvas.
 *
 * INTERACT: touch/drag/pinch on media or text.
 * DRAW: single finger draws strokes.
 */
enum class CanvasMode {
    INTERACT,
    DRAW
}

/**
 * Overlay operations rendered on the 512x512 sticker space.
 */
sealed class CanvasOp {

    class Stroke(
        val path: Path,
        val color: Int,
        val width: Float
    ) : CanvasOp()

    class Text(
        var x: Float,
        var y: Float,
        var text: String,
        var color: Int,
        var size: Float,
        var rotation: Float = 0f
    ) : CanvasOp()
}