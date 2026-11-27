package com.marinov.wastickeraudiocreator.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.AnimatedWebp
import com.marinov.wastickeraudiocreator.stickers.MediaUtils
import com.marinov.wastickeraudiocreator.ui.canvas.CanvasMode
import com.marinov.wastickeraudiocreator.ui.canvas.CanvasOp
import com.marinov.wastickeraudiocreator.ui.canvas.StickerCanvasView
import kotlinx.coroutines.launch
import java.io.File

class StickerEditorActivity : AppCompatActivity(), AudioTrimSheet.Listener {

    private lateinit var canvas: StickerCanvasView
    private lateinit var btnMove: MaterialButton
    private lateinit var btnDraw: MaterialButton
    private lateinit var btnText: MaterialButton
    private lateinit var btnColor: MaterialButton
    private lateinit var btnAudioToggle: MaterialButton
    private lateinit var btnAudioTrim: MaterialButton
    private lateinit var btnPrimary: MaterialButton
    private lateinit var etTextEntry: EditText

    private lateinit var sourceFile: File
    private lateinit var sourceBytes: ByteArray
    private lateinit var info: MediaUtils.WebpInfo

    private var opusFile: File? = null
    private var audioEnabled = false
    private var audioDurationMs: Long? = null   // user-chosen trim target
    private var colorIndex = 0
    private var exporting = false

    private var textEntryVisible = false
    private var editingText: CanvasOp.Text? = null
    private var progressSheet: ProgressSheet? = null

    private val palette = intArrayOf(
        Color.WHITE,
        Color.BLACK,
        0xFFFF5252.toInt(),
        0xFFFFAB40.toInt(),
        0xFFFFFF00.toInt(),
        0xFF69F0AE.toInt(),
        0xFF40C4FF.toInt(),
        0xFFE040FB.toInt()
    )

    private val pickOpus =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val file = runCatching {
                    MediaUtils.copyUriToCache(this, uri, "opus")
                }.getOrNull()

                val bytes = file?.readBytes()

                when {
                    bytes == null || !MediaUtils.isOggOpus(bytes) -> {
                        toast(R.string.only_opus)
                        file?.delete()
                    }

                    bytes.size > MediaUtils.MAX_AUDIO_BYTES -> {
                        toast(R.string.file_too_big)
                        file?.delete()
                    }

                    else -> {
                        opusFile = file
                        audioEnabled = true
                        audioDurationMs = null   // reset previous trim
                        updateAudioIcon()
                        toast(R.string.audio_attached)
                        // Always open the trim UI when a new audio is attached.
                        openAudioTrim()
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
        btnAudioTrim = findViewById(R.id.btn_audio_trim)
        btnPrimary = findViewById(R.id.btn_primary)
        etTextEntry = findViewById(R.id.et_text_entry)

        val btnUndo = findViewById<MaterialButton>(R.id.btn_undo)
        val btnAudioAttach = findViewById<MaterialButton>(R.id.btn_audio_attach)

        val webpPath = intent.getStringExtra(EXTRA_WEBP)
        if (webpPath == null) {
            finish()
            return
        }

        sourceFile = File(webpPath)
        sourceBytes = runCatching { sourceFile.readBytes() }.getOrElse {
            finish()
            return
        }

        info = MediaUtils.parseWebp(sourceBytes)
        if (!info.valid) {
            toast(R.string.only_webp)
            finish()
            return
        }

        intent.getStringExtra(EXTRA_OPUS)?.let { path ->
            val f = File(path)
            if (f.exists()) {
                opusFile = f
                audioEnabled = true
            }
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.edit_sticker)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationContentDescription(R.string.cd_back)
        toolbar.setNavigationOnClickListener {
            if (textEntryVisible) {
                commitTextEntry()
            } else {
                finish()
            }
        }

        if (info.animated) {
            val anim = MediaUtils.decodeAnimatedDrawable(sourceBytes)
            canvas.setAnimatedSource(anim, info.width, info.height)
            if (anim == null) toast(R.string.preview_unavailable)
        } else {
            val bmp = MediaUtils.decodeFirstFrame(sourceBytes)
            if (bmp == null) {
                toast(R.string.invalid_image)
                finish()
                return
            }
            canvas.setStaticSource(bmp)
        }

        canvas.onEditTextListener = { op ->
            openTextEntry(op)
        }

        canvas.onBackgroundTapped = {
            if (textEntryVisible) {
                commitTextEntry()
            }
        }

        etTextEntry.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitTextEntry()
                true
            } else {
                false
            }
        }

        btnMove.setOnClickListener {
            if (textEntryVisible) commitTextEntry()
            canvas.mode = CanvasMode.INTERACT
            selectMode(btnMove)
        }

        btnDraw.setOnClickListener {
            if (textEntryVisible) commitTextEntry()
            canvas.mode = CanvasMode.DRAW
            selectMode(btnDraw)
        }

        btnText.setOnClickListener {
            if (textEntryVisible) {
                commitTextEntry()
            } else {
                val selected = canvas.getSelectedText()
                openTextEntry(selected)
            }
        }

        btnUndo.setOnClickListener {
            if (textEntryVisible) commitTextEntry()
            canvas.undo()
        }

        btnColor.setOnClickListener {
            colorIndex = (colorIndex + 1) % palette.size
            val color = palette[colorIndex]

            canvas.setColor(color)
            editingText?.color = color

            if (textEntryVisible) {
                etTextEntry.setTextColor(color)
            }

            btnColor.iconTint = ColorStateList.valueOf(color)
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
            pickOpus.launch(
                arrayOf(
                    "audio/ogg",
                    "audio/opus",
                    "application/ogg",
                    "application/octet-stream"
                )
            )
        }

        btnAudioTrim.setOnClickListener {
            openAudioTrim()
        }

        selectMode(btnMove)
        updateAudioIcon()

        btnPrimary.setText(R.string.create_sticker)
        btnPrimary.setOnClickListener { createSticker() }
    }

    private fun selectMode(selected: MaterialButton) {
        for (b in listOf(btnMove, btnDraw, btnText)) {
            val sel = b == selected

            val bgAttr = if (sel) {
                androidx.appcompat.R.attr.colorPrimary
            } else {
                com.google.android.material.R.attr.colorSecondaryContainer
            }

            val fgAttr = if (sel) {
                com.google.android.material.R.attr.colorOnPrimary
            } else {
                com.google.android.material.R.attr.colorOnSecondaryContainer
            }

            b.backgroundTintList =
                ColorStateList.valueOf(MaterialColors.getColor(b, bgAttr))

            b.iconTint =
                ColorStateList.valueOf(MaterialColors.getColor(b, fgAttr))
        }
    }

    private fun updateAudioIcon() {
        btnAudioToggle.setIconResource(
            if (audioEnabled && opusFile != null) {
                R.drawable.ic_audio
            } else {
                R.drawable.ic_audio_off
            }
        )
        // Show trim button whenever an audio file is attached.
        btnAudioTrim.visibility = if (opusFile != null) View.VISIBLE else View.GONE
    }

    private var audioStartMs: Long? = null
    private var audioEndMs: Long? = null

    private fun openAudioTrim() {
        val file = opusFile ?: return
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        val durationMs = MediaUtils.opusDurationMs(bytes)
        if (durationMs <= 0) {
            toast(R.string.invalid_image)
            return
        }
        AudioTrimSheet.newInstance(durationMs, file.absolutePath)
            .show(supportFragmentManager, AudioTrimSheet.TAG)
    }

    override fun onAudioTrimSelected(startMs: Long, endMs: Long) {
        audioStartMs = startMs
        audioEndMs = endMs
    }

    // ------------------------------------------------------------------
    // Inline text entry
    // ------------------------------------------------------------------

    private fun openTextEntry(op: CanvasOp.Text?) {
        if (textEntryVisible && editingText === op) {
            etTextEntry.requestFocus()
            etTextEntry.showKeyboard()
            return
        }

        if (textEntryVisible) {
            commitTextEntry()
        }

        editingText = op

        etTextEntry.setText(op?.text ?: "")
        etTextEntry.setTextColor(op?.color ?: canvas.currentColor)
        etTextEntry.visibility = View.VISIBLE
        textEntryVisible = true

        canvas.mode = CanvasMode.INTERACT
        selectMode(btnText)

        canvas.startEditingText(op)

        etTextEntry.post {
            updateTextEntrySize()
            etTextEntry.requestFocus()
            etTextEntry.showKeyboard()
        }
    }

    private fun commitTextEntry() {
        if (!textEntryVisible) return

        val text = etTextEntry.text.toString().trim()

        etTextEntry.hideKeyboard()
        etTextEntry.visibility = View.GONE
        textEntryVisible = false

        val editing = editingText
        editingText = null

        if (editing != null) {
            if (text.isEmpty()) {
                canvas.removeText(editing)
            } else {
                editing.text = text
                canvas.selectText(editing)
            }
        } else {
            if (text.isNotEmpty()) {
                canvas.addTextCentered(text)
            }
        }

        canvas.stopEditingText()

        canvas.mode = CanvasMode.INTERACT
        selectMode(btnMove)
        canvas.invalidate()
    }

    private fun updateTextEntrySize() {
        val op = editingText
        if (op != null && canvas.width > 0) {
            val k = canvas.width / 512f
            etTextEntry.setTextSize(TypedValue.COMPLEX_UNIT_PX, op.size * k)
        } else {
            etTextEntry.textSize = 32f
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (textEntryVisible) {
            commitTextEntry()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private fun createSticker() {
        if (exporting) return

        exporting = true
        btnPrimary.isEnabled = false

        progressSheet = ProgressSheet.newInstance(
            title = getString(R.string.creating_sticker),
            subtitle = getString(R.string.exporting_media)
        )
        progressSheet?.show(supportFragmentManager, ProgressSheet.TAG)

        lifecycleScope.launch {
            try {
                val animated = info.animated

                var finalWebpBytes: ByteArray
                var finalOpusFile: File? = opusFile?.takeIf { audioEnabled }

                if (animated) {
                    val pristine = !canvas.imageEdited() &&
                            info.width == 512 &&
                            info.height == 512 &&
                            finalOpusFile == null

                    if (pristine) {
                        finalWebpBytes = sourceBytes
                    } else {
                        val matrix = canvas.baseTransform512()
                        val result = AnimatedWebp.export(
                            context = this@StickerEditorActivity,
                            source = sourceFile,
                            overlay = canvas.exportOverlay(),
                            matrix = matrix,
                            opusFile = finalOpusFile,
                            audioStartMs = audioStartMs,
                            audioEndMs = audioEndMs
                        )

                        finalWebpBytes = result.webpBytes

                        if (result.opusBytes != null && finalOpusFile != null) {
                            val trimmed = File(
                                cacheDir,
                                "final_opus_${System.currentTimeMillis()}.opus"
                            )
                            trimmed.writeBytes(result.opusBytes)
                            finalOpusFile = trimmed
                        }
                    }
                } else {
                    finalWebpBytes = MediaUtils.exportWebp(canvas.export())
                }

                val st = MediaUtils.finalizeSticker(
                    context = this@StickerEditorActivity,
                    webpBytes = finalWebpBytes,
                    animated = animated,
                    opus = finalOpusFile
                )

                setResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_STICKER, st.toJson().toString())
                )
                finish()
            } catch (e: Exception) {
                toast(e.message ?: "export failed")
                exporting = false
                btnPrimary.isEnabled = true
            } finally {
                progressSheet?.dismissAllowingStateLoss()
                progressSheet = null
            }
        }
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