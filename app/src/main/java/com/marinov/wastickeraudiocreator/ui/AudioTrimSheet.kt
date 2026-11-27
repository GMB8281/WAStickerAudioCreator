package com.marinov.wastickeraudiocreator.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.RangeSlider
import com.marinov.wastickeraudiocreator.R
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class AudioTrimSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onAudioTrimSelected(startMs: Long, endMs: Long)
    }

    private var originalDurationMs: Long = 0L
    private var opusPath: String? = null

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_audio_trim, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        originalDurationMs = arguments?.getLong(ARG_DURATION_MS, 0L) ?: 0L
        opusPath = arguments?.getString(ARG_OPUS_PATH)

        val originalSec = originalDurationMs / 1000f

        view.findViewById<TextView>(R.id.tv_original_duration).text =
            getString(R.string.original_duration, formatSeconds(originalSec))

        val slider = view.findViewById<RangeSlider>(R.id.slider_trim)
        val tvInterval = view.findViewById<TextView>(R.id.tv_selected_interval)
        val btnPlay = view.findViewById<MaterialButton>(R.id.btn_play)

        // Snap valueTo down to a valid step (avoid RangeSlider IllegalStateException).
        val from = 0.0f
        val step = 0.1f
        val rawMax = max(from, minOf(originalSec, MAX_TRIM_SECONDS))
        val snappedMax = floorToStep(rawMax, from, step).coerceAtLeast(from + step)

        slider.valueFrom = from
        slider.stepSize = step
        slider.valueTo = snappedMax
        slider.setValues(from, snappedMax)

        updateIntervalLabel(slider, tvInterval)

        slider.addOnChangeListener { _, _, _ ->
            updateIntervalLabel(slider, tvInterval)
            if (isPlaying) {
                val values = slider.values
                if (values.isNotEmpty() && values.size >= 2) {
                    restartPlayback(values[0], values[1])
                }
            }
        }

        btnPlay.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                val values = slider.values
                if (values.isNotEmpty() && values.size >= 2) {
                    startPlayback(values[0], values[1])
                }
            }
            updatePlayButton(btnPlay)
        }

        view.findViewById<MaterialButton>(R.id.btn_apply).setOnClickListener {
            val values = slider.values
            if (values.isNotEmpty() && values.size >= 2) {
                val startMs = (values[0] * 1000).toLong()
                val endMs = (values[1] * 1000).toLong()
                (requireActivity() as? Listener)?.onAudioTrimSelected(startMs, endMs)
            }
            dismissAllowingStateLoss()
        }
    }

    private fun updateIntervalLabel(slider: RangeSlider, tv: TextView) {
        val values = slider.values
        if (values.isNotEmpty() && values.size >= 2) {
            tv.text = "${formatSeconds(values[0])}  →  ${formatSeconds(values[1])}"
        }
    }

    /**
     * Updates only the icon of the play/pause button (icon-only, no text).
     */
    private fun updatePlayButton(btn: MaterialButton) {
        if (isPlaying) {
            btn.setIconResource(R.drawable.ic_pause)
            btn.contentDescription = "Pause"
        } else {
            btn.setIconResource(R.drawable.ic_play)
            btn.contentDescription = "Preview"
        }
    }

    private fun startPlayback(startSec: Float, endSec: Float) {
        val path = opusPath ?: return
        releasePlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                seekTo((startSec * 1000).toInt())
                start()
            }
            isPlaying = true
            scheduleCheckEnd(endSec)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        isPlaying = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun restartPlayback(startSec: Float, endSec: Float) {
        pausePlayback()
        startPlayback(startSec, endSec)
        updatePlayButton(view?.findViewById(R.id.btn_play) ?: return)
    }

    private fun scheduleCheckEnd(endSec: Float) {
        handler.postDelayed({
            val mp = mediaPlayer ?: return@postDelayed
            if (mp.isPlaying && mp.currentPosition >= (endSec * 1000).toInt()) {
                mp.pause()
                isPlaying = false
                updatePlayButton(view?.findViewById(R.id.btn_play) ?: return@postDelayed)
            } else if (isPlaying) {
                scheduleCheckEnd(endSec)
            }
        }, 50)
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        releasePlayer()
    }

    private fun formatSeconds(sec: Float): String =
        String.format(Locale.getDefault(), "%.1fs", sec)

    /**
     * Rounds [value] DOWN to the nearest valid slider step so that
     *   result == from + n * step
     * Required by Material Slider/RangeSlider, which throws IllegalStateException
     * otherwise.
     */
    private fun floorToStep(value: Float, from: Float, step: Float): Float {
        if (step <= 0f) return value
        val n = floor(((value - from) / step).toDouble()).toFloat()
        return from + n * step
    }

    companion object {
        const val TAG = "audio_trim_sheet"
        private const val ARG_DURATION_MS = "duration_ms"
        private const val ARG_OPUS_PATH = "opus_path"
        private const val MAX_TRIM_SECONDS = 10f

        fun newInstance(durationMs: Long, opusPath: String): AudioTrimSheet {
            return AudioTrimSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_DURATION_MS, durationMs)
                    putString(ARG_OPUS_PATH, opusPath)
                }
            }
        }
    }
}