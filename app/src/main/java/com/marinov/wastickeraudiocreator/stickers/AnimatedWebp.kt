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
 * Animated WebP backend.
 *
 * Guarantees:
 *  - output is resampled to TARGET_FPS (10 fps)
 *  - if audio is longer than the animation, the animation is looped to match it
 *  - final duration is capped at MAX_DURATION_MS (10 s)
 *  - audio is trimmed to the chosen/limited duration when needed
 */
object AnimatedWebp {

    init {
        System.loadLibrary("wa_bridge")
    }

    external fun nativeOverlayAnimated(requestJson: String): String

    const val TARGET_FPS = 10
    const val MAX_DURATION_MS = 10_000

    data class Result(
        val webpBytes: ByteArray,
        val opusBytes: ByteArray?,
        val audioTrimmed: Boolean
    )

    /**
     * @param overlay 512x512 transparent bitmap with strokes/text (may be empty)
     * @param matrix  source-media -> 512 sticker space affine transform [a,b,c,d,e,f]
     * @param opusFile optional original Ogg Opus audio
     * @param audioDurationMs optional user-chosen audio duration (trim target).
     *                        If null, the full audio duration is used (still capped).
     */
    suspend fun export(
        context: Context,
        source: File,
        overlay: Bitmap,
        matrix: FloatArray,
        opusFile: File?,
        audioDurationMs: Long? = null,
        size: Int = 512
    ): Result = withContext(Dispatchers.IO) {
        require(matrix.size == 6) { "matrix must have 6 values" }

        val id = UUID.randomUUID().toString()
        val ovFile = File(context.cacheDir, "ov_$id.rgba")
        val outFile = File(context.cacheDir, "anim_$id.webp")
        val outOpus = File(context.cacheDir, "opus_$id.opus")

        try {
            val buf = ByteBuffer.allocate(size * size * 4)
            overlay.copyPixelsToBuffer(buf)
            ovFile.writeBytes(buf.array())

            val req = JSONObject()
                .put("src_webp", source.absolutePath)
                .put("overlay_rgba", ovFile.absolutePath)
                .put("out_webp", outFile.absolutePath)
                .put("size", size)
                .put("matrix", JSONArray().apply { matrix.forEach { put(it.toDouble()) } })
                .put("max_bytes", MediaUtils.MAX_IMAGE_BYTES)
                .put("target_fps", TARGET_FPS)
                .put("max_duration_ms", MAX_DURATION_MS)

            if (opusFile != null && opusFile.exists()) {
                req.put("opus_path", opusFile.absolutePath)
                req.put("out_opus", outOpus.absolutePath)
                audioDurationMs?.let { req.put("audio_duration_ms", it) }
            }

            val res = JSONObject(nativeOverlayAnimated(req.toString()))

            if (!res.optBoolean("ok")) {
                throw IllegalStateException(
                    res.optString("error").ifEmpty { "animated export failed" }
                )
            }

            val webpBytes = outFile.readBytes()

            val opusBytes = if (opusFile != null && outOpus.exists()) {
                outOpus.readBytes()
            } else {
                null
            }

            Result(
                webpBytes = webpBytes,
                opusBytes = opusBytes,
                audioTrimmed = res.optBoolean("audio_trimmed")
            )
        } finally {
            ovFile.delete()
            outFile.delete()
            outOpus.delete()
        }
    }
}