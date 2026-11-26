package com.marinov.wastickeraudiocreator.stickers

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Animated WebP re-encode (native libwebp).
 * Keeps animation and bakes the overlay using a full 2D affine transform.
 */
object AnimatedWebp {

    init {
        System.loadLibrary("wa_bridge")
    }

    external fun nativeOverlayAnimated(requestJson: String): String

    /**
     * @param overlay 512x512 transparent bitmap with strokes/text (may be empty)
     * @param matrix source-media -> 512 sticker space affine transform:
     *               [a, b, c, d, e, f]
     *
     * dstX = a*srcX + b*srcY + c
     * dstY = d*srcX + e*srcY + f
     */
    suspend fun export(
        context: Context,
        source: File,
        overlay: Bitmap,
        matrix: FloatArray,
        size: Int = 512
    ): ByteArray = withContext(Dispatchers.IO) {
        require(matrix.size == 6) { "matrix must have 6 values" }

        val id = UUID.randomUUID().toString()
        val ovFile = File(context.cacheDir, "ov_$id.rgba")
        val outFile = File(context.cacheDir, "anim_$id.webp")

        try {
            val buf = ByteBuffer.allocate(size * size * 4)
            overlay.copyPixelsToBuffer(buf)
            ovFile.writeBytes(buf.array())

            val req = JSONObject()
                .put("src_webp", source.absolutePath)
                .put("overlay_rgba", ovFile.absolutePath)
                .put("out_webp", outFile.absolutePath)
                .put("size", size)
                .put(
                    "matrix",
                    JSONArray().apply {
                        matrix.forEach { put(it.toDouble()) }
                    }
                )
                .put("max_bytes", MediaUtils.MAX_IMAGE_BYTES)

            val res = JSONObject(nativeOverlayAnimated(req.toString()))

            if (!res.optBoolean("ok")) {
                throw IllegalStateException(
                    res.optString("error").ifEmpty { "animated export failed" }
                )
            }

            outFile.readBytes()
        } finally {
            ovFile.delete()
            outFile.delete()
        }
    }
}