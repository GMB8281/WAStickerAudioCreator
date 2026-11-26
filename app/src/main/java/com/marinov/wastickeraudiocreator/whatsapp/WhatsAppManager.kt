package com.marinov.wastickeraudiocreator.whatsapp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.marinov.wastickeraudiocreator.stickers.MediaUtils
import com.marinov.wastickeraudiocreator.stickers.PackRepository
import com.marinov.wastickeraudiocreator.stickers.Sticker
import com.marinov.wastickeraudiocreator.stickers.StickerPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object WhatsAppManager {

    const val WA_PACKAGE = "com.whatsapp"

    data class Result(val ok: Boolean, val error: String? = null, val raw: JSONObject? = null)

    private fun parse(s: String): Result = try {
        val o = JSONObject(s)
        val ok = o.optBoolean("ok")
        var err = o.optString("error").takeIf { it.isNotEmpty() }
        if (!ok && err != null && err.contains("staging stickers.db")) {
            err = "WhatsApp stickers.db not found or unreadable. Open WhatsApp and sign in at least once before injecting. [$err]"
        }
        Result(ok, err, o)
    } catch (e: Exception) {
        Result(false, "bad native response")
    }

    private fun common(ctx: Context): JSONObject = JSONObject()
        .put("app_uid", Process.myUid())
        .put("package", WA_PACKAGE)
        .put("stage_dir", File(ctx.cacheDir, "wa_stage").absolutePath)
        .put("relaunch", true)

    fun whatsappInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(WA_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** First call may block on the Magisk grant prompt (120 s timeout in Rust). */
    suspend fun requestRoot(): Result = withContext(Dispatchers.IO) {
        parse(WhatsAppBridge.nativeRootStatus())
    }

    suspend fun checkEnvironment(ctx: Context): Result = withContext(Dispatchers.IO) {
        parse(WhatsAppBridge.nativeEnvCheck(common(ctx).put("relaunch", false).toString()))
    }

    /** Add (replace=false) or update (replace=true, same UUID) a pack in WhatsApp. */
    suspend fun addPack(ctx: Context, pack: StickerPack, replace: Boolean): Result =
        withContext(Dispatchers.IO) {
            if (pack.stickers.isEmpty()) return@withContext Result(false, "pack has no stickers")
            val tray = MediaUtils.makeTrayPng(ctx, pack)
            val arr = JSONArray()
            for (st in pack.stickers) {
                arr.put(
                    JSONObject()
                        .put("webp_path", PackRepository.mediaFile(st.webpFile).absolutePath)
                        .put("opus_path", st.opusFile?.let { PackRepository.mediaFile(it).absolutePath } ?: JSONObject.NULL)
                        .put("emoji", st.emoji)
                        .put("duration", st.durationSeconds)
                )
            }
            val req = common(ctx)
                .put("pack_id", pack.id)
                .put("name", pack.name)
                .put("publisher", pack.publisher)
                .put("tray_path", tray.absolutePath)
                .put("replace", replace)
                .put("stickers", arr)
            parse(WhatsAppBridge.nativeInjectPack(req.toString()))
        }

    /** Inject one sticker into WhatsApp favorites (always hash-rotated in Rust). */
    suspend fun addFavorite(ctx: Context, st: Sticker): Result = withContext(Dispatchers.IO) {
        val req = common(ctx)
            .put("webp_path", PackRepository.mediaFile(st.webpFile).absolutePath)
            .put("opus_path", st.opusFile?.let { PackRepository.mediaFile(it).absolutePath } ?: JSONObject.NULL)
            .put("emoji", st.emoji)
            .put("duration", st.durationSeconds)
        parse(WhatsAppBridge.nativeInjectFavorite(req.toString()))
    }

    suspend fun removePack(ctx: Context, packId: String): Result = withContext(Dispatchers.IO) {
        parse(WhatsAppBridge.nativeRemovePack(common(ctx).put("pack_id", packId).toString()))
    }
}