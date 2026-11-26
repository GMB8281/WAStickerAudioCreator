package com.marinov.wastickeraudiocreator.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable

object MediaUtils {

    data class WebpInfo(val valid: Boolean, val animated: Boolean, val width: Int, val height: Int)

    const val MAX_IMAGE_BYTES = 500_000
    const val MAX_AUDIO_BYTES = 1_000_000

    // ---------------- WebP ----------------

    fun parseWebp(b: ByteArray): WebpInfo {
        if (b.size < 16 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(b, 8, 4, Charsets.US_ASCII) != "WEBP"
        ) return WebpInfo(false, false, 0, 0)

        fun u16(o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        fun u32(o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or
                ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

        var pos = 12; var animated = false; var w = 0; var h = 0
        while (pos + 8 <= b.size) {
            val fourcc = String(b, pos, 4, Charsets.US_ASCII)
            val size = u32(pos + 4)
            val payload = pos + 8
            when (fourcc) {
                "ANIM" -> animated = true
                "VP8X" -> if (payload + 10 <= b.size) {
                    w = (u32(payload + 4) and 0xFFFFFF) + 1
                    h = (u32(payload + 7) and 0xFFFFFF) + 1
                    if ((b[payload].toInt() and 2) != 0) animated = true
                }
                "VP8L" -> if (payload + 5 <= b.size && w == 0) {
                    val bits = u32(payload + 1)
                    w = (bits and 0x3FFF) + 1
                    h = ((bits ushr 14) and 0x3FFF) + 1
                }
                "VP8 " -> if (payload + 10 <= b.size && w == 0) {
                    w = u16(payload + 6) and 0x3FFF
                    h = u16(payload + 8) and 0x3FFF
                }
            }
            if (size < 0) break
            pos += 8 + size + (size and 1)
        }
        return WebpInfo(true, animated, w, h)
    }

    // ---------------- Opus ----------------

    fun isOggOpus(b: ByteArray): Boolean {
        if (b.size < 38 || String(b, 0, 4, Charsets.US_ASCII) != "OggS") return false
        return String(b, 0, min(8192, b.size), Charsets.ISO_8859_1).contains("OpusHead")
    }

    /** Duration from Ogg granule positions (48 kHz) minus OpusHead pre-skip. */
    fun opusDurationSeconds(b: ByteArray): Int {
        fun longLE(o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 255)
            return v
        }
        var pos = 0; var lastGranule = -1L; var preSkip = 0
        while (pos + 27 <= b.size &&
            b[pos] == 0x4F.toByte() && b[pos + 1] == 0x67.toByte() &&
            b[pos + 2] == 0x67.toByte() && b[pos + 3] == 0x53.toByte()
        ) {
            val granule = longLE(pos + 6)
            if (granule >= 0) lastGranule = granule
            val segs = b[pos + 26].toInt() and 255
            if (pos + 27 + segs > b.size) break
            var bodyLen = 0
            for (i in 0 until segs) bodyLen += b[pos + 27 + i].toInt() and 255
            val bodyStart = pos + 27 + segs
            if (preSkip == 0 && bodyStart + 12 <= b.size &&
                String(b, bodyStart, 8, Charsets.US_ASCII) == "OpusHead"
            ) {
                preSkip = (b[bodyStart + 10].toInt() and 255) or ((b[bodyStart + 11].toInt() and 255) shl 8)
            }
            pos = bodyStart + bodyLen
        }
        val dur = if (lastGranule > 0) (lastGranule - preSkip).toDouble() / 48000.0 else 5.0
        return max(1, dur.roundToInt())
    }

    // ---------------- IO / decode / export ----------------

    fun copyUriToCache(context: Context, uri: Uri, ext: String): File {
        val out = File(context.cacheDir, "pick_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("cannot open $uri")
        return out
    }

    fun decodeFirstFrame(bytes: ByteArray): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (e: Exception) {
        null
    }

    fun decodeThumb(file: File, req: Int): Bitmap? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val bmp = decodeFirstFrame(bytes) ?: return null
        val scale = max(1, min(bmp.width, bmp.height) / req)
        val w = max(1, bmp.width / scale); val h = max(1, bmp.height / scale)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    /** 512×512 WebP under MAX_IMAGE_BYTES (quality loop). */
    fun exportWebp(bmp: Bitmap): ByteArray {
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        var quality = 92
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            bmp.compress(fmt, quality, bos)
            out = bos.toByteArray()
            quality -= 8
        } while (out.size > MAX_IMAGE_BYTES && quality >= 30)
        return out
    }

    fun makeTrayPng(context: Context, pack: StickerPack): File {
        val out = File(context.cacheDir, "tray_${pack.id}.png")
        val first = pack.stickers.firstOrNull()
        val bmp = first?.let { runCatching { decodeFirstFrame(PackRepository.mediaFile(it.webpFile).readBytes()) }.getOrNull() }
        val tray = if (bmp != null) Bitmap.createScaledBitmap(bmp, 96, 96, true)
        else Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF146C2E.toInt()) }
        out.outputStream().use { tray.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    /** Persist the final sticker into app media storage and return the model. */
    fun finalizeSticker(context: Context, webpBytes: ByteArray, animated: Boolean, opus: File?): Sticker {
        PackRepository.init(context)
        val id = UUID.randomUUID().toString()
        val webpName = "$id.webp"
        File(PackRepository.mediaDir, webpName).writeBytes(webpBytes)
        var opusName: String? = null
        var dur = 0
        if (opus != null) {
            val ob = opus.readBytes()
            opusName = "$id.opus"
            File(PackRepository.mediaDir, opusName).writeBytes(ob)
            dur = opusDurationSeconds(ob)
        }
        return Sticker(id, webpName, opusName, animated, dur)
    }

    /** Animated preview (API 28+). Returns null for static images or older APIs. */
    fun decodeAnimatedDrawable(bytes: ByteArray): Drawable? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            val d = ImageDecoder.decodeDrawable(src)
            if (d is AnimatedImageDrawable) d else null
        } catch (e: Exception) {
            null
        }
    }
}