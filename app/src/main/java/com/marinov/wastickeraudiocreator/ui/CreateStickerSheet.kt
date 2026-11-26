package com.marinov.wastickeraudiocreator.ui

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.MediaUtils
import com.marinov.wastickeraudiocreator.stickers.Sticker
import org.json.JSONObject
import java.io.File

class CreateStickerSheet : BottomSheetDialogFragment() {

    interface StickerReceiver {
        fun onStickerCreated(sticker: Sticker)
    }

    private var pendingWebp: File? = null

    private val pickWebp = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleWebp(it) }
    }

    private val pickOpus = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleOpus(it) }
    }

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val json = result.data?.getStringExtra(StickerEditorActivity.EXTRA_STICKER)
                if (json != null) {
                    val st = Sticker.fromJson(JSONObject(json))
                    (requireActivity() as? StickerReceiver)?.onStickerCreated(st)
                    dismissAllowingStateLoss()
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.sheet_create_sticker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.btn_choose_media).setOnClickListener {
            // octet-stream fallback: many providers report unknown MIME for .webp;
            // we strictly validate the RIFF/WEBP magic afterwards.
            pickWebp.launch(arrayOf("image/webp", "application/octet-stream"))
        }
    }

    private fun handleWebp(uri: Uri) {
        val ctx = requireContext()
        val file = runCatching { MediaUtils.copyUriToCache(ctx, uri, "webp") }.getOrNull()
        if (file == null) { ctx.toast(R.string.invalid_image); return }
        val bytes = file.readBytes()
        val info = MediaUtils.parseWebp(bytes)
        if (!info.valid) { ctx.toast(R.string.only_webp); file.delete(); return }
        // Accept ANY dimensions (static or animated). The canvas fits/centers the
        // image; the export normalizes static stickers to 512x512.
        pendingWebp = file
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.will_have_audio)
            .setNegativeButton(R.string.no) { _, _ -> openEditor(null) }
            .setPositiveButton(R.string.yes) { _, _ ->
                pickOpus.launch(arrayOf("audio/ogg", "audio/opus", "application/ogg", "application/octet-stream"))
            }
            .setOnCancelListener { openEditor(null) }
            .show()
    }

    private fun handleOpus(uri: Uri) {
        val ctx = requireContext()
        val file = runCatching { MediaUtils.copyUriToCache(ctx, uri, "opus") }.getOrNull()
        if (file == null) { ctx.toast(R.string.only_opus); openEditor(null); return }
        val bytes = file.readBytes()
        if (!MediaUtils.isOggOpus(bytes)) {
            ctx.toast(R.string.only_opus); file.delete(); openEditor(null); return
        }
        if (bytes.size > MediaUtils.MAX_AUDIO_BYTES) {
            ctx.toast(R.string.file_too_big); file.delete(); openEditor(null); return
        }
        openEditor(file)
    }

    private fun openEditor(opus: File?) {
        val webp = pendingWebp ?: return
        editorLauncher.launch(
            StickerEditorActivity.intent(requireContext(), webp.absolutePath, opus?.absolutePath)
        )
    }
}