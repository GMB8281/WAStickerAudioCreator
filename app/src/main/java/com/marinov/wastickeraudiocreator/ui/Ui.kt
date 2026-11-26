package com.marinov.wastickeraudiocreator.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.whatsapp.WhatsAppManager
import kotlinx.coroutines.launch

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun Context.toast(resId: Int) {
    Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}

fun View.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun AppCompatActivity.inputDialog(
    title: String,
    hint: String,
    initial: String,
    onOk: (String) -> Unit
) {
    val view = layoutInflater.inflate(R.layout.dialog_input, null)
    val til = view.findViewById<TextInputLayout>(R.id.til_input)
    val et = view.findViewById<TextInputEditText>(R.id.et_input)
    til.hint = hint
    et.setText(initial)
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(view)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.save) { _, _ ->
            val v = et.text?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) onOk(v)
        }
        .show()
}

fun AppCompatActivity.confirm(title: String, message: String, onYes: () -> Unit) {
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.yes) { _, _ -> onYes() }
        .show()
}

/** Runs a WhatsApp native task with a progress dialog and a toast on error. */
fun AppCompatActivity.runWhatsAppTask(
    message: String,
    block: suspend () -> WhatsAppManager.Result,
    onSuccess: (WhatsAppManager.Result) -> Unit = {}
) {
    val ctx = this
    val layout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(ProgressBar(ctx))
        addView(TextView(ctx).apply {
            text = message
            setPadding(dp(20), 0, 0, 0)
        })
    }
    val dialog: AlertDialog = MaterialAlertDialogBuilder(ctx)
        .setView(FrameLayout(ctx).apply { addView(layout) })
        .setCancelable(false)
        .show()
    lifecycleScope.launch {
        val r = try {
            block()
        } catch (e: Exception) {
            WhatsAppManager.Result(false, e.message ?: "error")
        }
        dialog.dismiss()
        if (r.ok) onSuccess(r) else ctx.toast(r.error ?: "Failed")
    }
}