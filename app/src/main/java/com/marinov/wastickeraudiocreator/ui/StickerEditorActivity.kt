package com.marinov.wastickeraudiocreator.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.MediaUtils
import java.io.File

class StickerEditorActivity : AppCompatActivity() {

    private lateinit var canvas: StickerCanvasView
    private lateinit var btnMove: MaterialButton
    private lateinit var btnDraw: MaterialButton
    private lateinit var btnText: MaterialButton
    private lateinit var btnColor: MaterialButton
    private lateinit var btnAudioToggle: MaterialButton

    private lateinit var sourceBytes: ByteArray
    private lateinit var info: MediaUtils.WebpInfo
    private var opusFile: File? = null
    private var audioEnabled = false
    private var colorIndex = 0

    private val palette = intArrayOf(
        Color.WHITE, Color.BLACK, 0xFFFF5252.toInt(), 0xFFFFAB40.toInt(),
        0xFFFFFF00.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(), 0xFFE040FB.toInt()
    )

    private val pickOpus = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val file = runCatching { MediaUtils.copyUriToCache(this, uri, "opus") }.getOrNull()
            val bytes = file?.readBytes()
            when {
                bytes == null || !MediaUtils.isOggOpus(bytes) -> {
                    toast(R.string.only_opus); file?.delete()
                }
                bytes.size > MediaUtils.MAX_AUDIO_BYTES -> {
                    toast(R.string.file_too_big); file?.delete()
                }
                else -> {
                    opusFile = file
                    audioEnabled = true
                    updateAudioIcon()
                    toast(R.string.audio_attached)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sticker_editor)

        canvas = findViewById(R.id.canvas)
        btnMove = findViewById(R.id.btn_move)
        btnDraw = findViewById(R.id.btn_draw)
        btnText = findViewById(R.id.btn_text)
        btnColor = findViewById(R.id.btn_color)
        btnAudioToggle = findViewById(R.id.btn_audio_toggle)
        val btnUndo = findViewById<MaterialButton>(R.id.btn_undo)
        val btnAudioAttach = findViewById<MaterialButton>(R.id.btn_audio_attach)
        val btnPrimary = findViewById<MaterialButton>(R.id.btn_primary)

        val webpPath = intent.getStringExtra(EXTRA_WEBP)
        if (webpPath == null) { finish(); return }
        sourceBytes = runCatching { File(webpPath).readBytes() }.getOrElse { finish(); return }
        info = MediaUtils.parseWebp(sourceBytes)
        if (!info.valid) { toast(R.string.only_webp); finish(); return }

        intent.getStringExtra(EXTRA_OPUS)?.let {
            val f = File(it)
            if (f.exists()) { opusFile = f; audioEnabled = true }
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.edit_sticker)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationContentDescription(R.string.cd_back)
        toolbar.setNavigationOnClickListener { finish() }

        val bmp = MediaUtils.decodeFirstFrame(sourceBytes)
        canvas.setBitmap(bmp)
        if (bmp == null) {
            if (info.animated) {
                // Animated WebP preview needs Android 9+; keep original animation untouched.
                toast(R.string.preview_unavailable)
                btnDraw.isEnabled = false
                btnText.isEnabled = false
                btnMove.isEnabled = false
            } else {
                toast(R.string.invalid_image); finish(); return
            }
        }

        canvas.textPlacementListener = { x, y ->
            inputDialog(getString(R.string.add_text), getString(R.string.text_hint), "") { txt ->
                canvas.addText(txt, x, y)
            }
        }

        btnMove.setOnClickListener { canvas.mode = StickerCanvasView.Mode.MOVE; selectMode(btnMove) }
        btnDraw.setOnClickListener { canvas.mode = StickerCanvasView.Mode.DRAW; selectMode(btnDraw) }
        btnText.setOnClickListener { canvas.mode = StickerCanvasView.Mode.TEXT; selectMode(btnText) }
        btnUndo.setOnClickListener { canvas.undo() }
        btnColor.setOnClickListener {
            colorIndex = (colorIndex + 1) % palette.size
            canvas.currentColor = palette[colorIndex]
            btnColor.iconTint = ColorStateList.valueOf(palette[colorIndex])
        }
        btnAudioToggle.setOnClickListener {
            if (opusFile == null) {
                toast(R.string.no_audio_attached)
            } else {
                audioEnabled = !audioEnabled
                updateAudioIcon()
                toast(if (audioEnabled) R.string.audio_on else R.string.audio_off)
            }
        }
        btnAudioAttach.setOnClickListener {
            pickOpus.launch(arrayOf("audio/ogg", "audio/opus", "application/ogg", "application/octet-stream"))
        }

        selectMode(btnMove)
        updateAudioIcon()

        btnPrimary.setText(R.string.create_sticker)
        btnPrimary.setOnClickListener { createSticker() }
    }

    private fun selectMode(selected: MaterialButton) {
        for (b in listOf(btnMove, btnDraw, btnText)) {
            val sel = b == selected
            val bgAttr = if (sel) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorSecondaryContainer
            val fgAttr = if (sel) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSecondaryContainer
            b.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(b, bgAttr))
            b.iconTint = ColorStateList.valueOf(MaterialColors.getColor(b, fgAttr))
        }
    }

    private fun updateAudioIcon() {
        btnAudioToggle.setIconResource(
            if (audioEnabled && opusFile != null) R.drawable.ic_audio else R.drawable.ic_audio_off
        )
    }

    private fun createSticker() {
        // Animated + untouched image -> pass the ORIGINAL bytes through (keeps animation).
        val passthrough = info.animated && !canvas.imageEdited()
        val bytes = if (passthrough) sourceBytes else MediaUtils.exportWebp(canvas.export())
        val st = MediaUtils.finalizeSticker(
            this,
            webpBytes = bytes,
            animated = passthrough,
            opus = opusFile?.takeIf { audioEnabled }
        )
        setResult(RESULT_OK, Intent().putExtra(EXTRA_STICKER, st.toJson().toString()))
        finish()
    }

    companion object {
        private const val EXTRA_WEBP = "webp_path"
        private const val EXTRA_OPUS = "opus_path"
        const val EXTRA_STICKER = "sticker"

        fun intent(ctx: Context, webpPath: String, opusPath: String?): Intent =
            Intent(ctx, StickerEditorActivity::class.java)
                .putExtra(EXTRA_WEBP, webpPath)
                .putExtra(EXTRA_OPUS, opusPath)
    }
}