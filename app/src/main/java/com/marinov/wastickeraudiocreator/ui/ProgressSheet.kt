package com.marinov.wastickeraudiocreator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.marinov.wastickeraudiocreator.R

class ProgressSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_progress, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        isCancelable = false

        val title = arguments?.getString(ARG_TITLE).orEmpty()
        val subtitle = arguments?.getString(ARG_SUBTITLE)

        view.findViewById<TextView>(R.id.tv_title).text = title

        val tvSubtitle = view.findViewById<TextView>(R.id.tv_subtitle)
        if (subtitle.isNullOrEmpty()) {
            tvSubtitle.visibility = View.GONE
        } else {
            tvSubtitle.text = subtitle
        }
    }

    companion object {
        const val TAG = "progress_sheet"

        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"

        fun newInstance(title: String, subtitle: String? = null): ProgressSheet {
            return ProgressSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    subtitle?.let { putString(ARG_SUBTITLE, it) }
                }
            }
        }
    }
}