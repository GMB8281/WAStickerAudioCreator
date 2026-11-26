package com.marinov.wastickeraudiocreator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.marinov.wastickeraudiocreator.R
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

class AudioTrimSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onAudioTrimSelected(durationMs: Long)
    }

    private var originalDurationMs: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_audio_trim, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        originalDurationMs = arguments?.getLong(ARG_DURATION_MS, 0L) ?: 0L

        val originalSec = originalDurationMs / 1000f

        view.findViewById<TextView>(R.id.tv_original_duration).text =
            getString(R.string.original_duration, formatSeconds(originalSec))

        val slider = view.findViewById<Slider>(R.id.slider_trim)
        val tvSelected = view.findViewById<TextView>(R.id.tv_selected_duration)

        val from = 0.5f
        val step = 0.1f
        val rawMax = max(from, minOf(originalSec, MAX_TRIM_SECONDS))

        // Snap valueTo DOWN to the nearest valid step so it never exceeds the real duration
        // and always satisfies: valueTo == valueFrom + n * stepSize.
        val snappedMax = floorToStep(rawMax, from, step).coerceAtLeast(from)

        slider.valueFrom = from
        slider.stepSize = step
        slider.valueTo = snappedMax
        slider.value = snappedMax

        tvSelected.text = getString(R.string.selected_duration, formatSeconds(slider.value))

        slider.addOnChangeListener { _, value, _ ->
            tvSelected.text = getString(R.string.selected_duration, formatSeconds(value))
        }

        view.findViewById<MaterialButton>(R.id.btn_apply).setOnClickListener {
            val selectedMs = (slider.value * 1000).toLong()
            (requireActivity() as? Listener)?.onAudioTrimSelected(selectedMs)
            dismissAllowingStateLoss()
        }
    }

    private fun formatSeconds(sec: Float): String =
        String.format(Locale.getDefault(), "%.1fs", sec)

    /**
     * Rounds [value] DOWN to the nearest valid slider step:
     *   result == from + n * step   (n >= 0)
     * This is required by Material Slider, which throws IllegalStateException
     * if valueFrom/valueTo/value do not sit on the step grid.
     */
    private fun floorToStep(value: Float, from: Float, step: Float): Float {
        if (step <= 0f) return value
        val n = floor(((value - from) / step).toDouble()).toFloat()
        return from + n * step
    }

    companion object {
        const val TAG = "audio_trim_sheet"
        private const val ARG_DURATION_MS = "duration_ms"
        private const val MAX_TRIM_SECONDS = 10f

        fun newInstance(durationMs: Long): AudioTrimSheet {
            return AudioTrimSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_DURATION_MS, durationMs)
                }
            }
        }
    }
}