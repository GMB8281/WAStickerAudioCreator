package com.marinov.wastickeraudiocreator.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.PackRepository
import com.marinov.wastickeraudiocreator.stickers.Sticker
import com.marinov.wastickeraudiocreator.stickers.StickerPack
import com.marinov.wastickeraudiocreator.whatsapp.WhatsAppManager

class PackDetailsActivity : AppCompatActivity(), CreateStickerSheet.StickerReceiver {

    private lateinit var pack: StickerPack
    private lateinit var adapter: StickerGridAdapter
    private lateinit var tvName: TextView
    private lateinit var tvPublisher: TextView
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnRemove: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PackRepository.init(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val p = id?.let { PackRepository.byId(it) }
        if (p == null) { finish(); return }
        pack = p

        setContentView(R.layout.activity_pack_details)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.pack_details)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationContentDescription(R.string.cd_back)
        toolbar.setNavigationOnClickListener { finish() }

        tvName = findViewById(R.id.tv_pack_name)
        tvPublisher = findViewById(R.id.tv_publisher)
        btnPrimary = findViewById(R.id.btn_primary)
        btnRemove = findViewById(R.id.btn_remove)

        adapter = StickerGridAdapter(
            onStickerClick = { st -> showStickerOptions(st) },
            onAddClick = { CreateStickerSheet().show(supportFragmentManager, "create_sticker") }
        )
        val rv = findViewById<RecyclerView>(R.id.rv_stickers)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btn_edit_name).setOnClickListener {
            inputDialog(getString(R.string.edit_name), getString(R.string.pack_name), pack.name) {
                pack.name = it
                PackRepository.upsert(pack)
                refresh()
            }
        }
        findViewById<MaterialButton>(R.id.btn_edit_publisher).setOnClickListener {
            inputDialog(getString(R.string.edit_publisher), getString(R.string.publisher), pack.publisher) {
                pack.publisher = it
                PackRepository.upsert(pack)
                refresh()
            }
        }

        btnPrimary.setOnClickListener { addToWhatsApp() }
        btnRemove.setOnClickListener {
            confirm(getString(R.string.confirm_remove_pack_title), getString(R.string.confirm_remove_pack_msg)) {
                removeFromWhatsApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::pack.isInitialized) refresh()
    }

    private fun refresh() {
        tvName.text = pack.name
        tvPublisher.text = pack.publisher
        adapter.submit(pack.stickers)
        btnPrimary.setText(if (pack.addedToWhatsApp) R.string.update_pack else R.string.add_pack)
        btnRemove.visibility = if (pack.addedToWhatsApp) View.VISIBLE else View.GONE
    }

    private fun showStickerOptions(st: Sticker) {
        val options = arrayOf(getString(R.string.remove_sticker), getString(R.string.add_sticker))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sticker_options)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        PackRepository.removeSticker(pack.id, st.id)
                        toast(R.string.sticker_removed)
                        refresh()
                    }
                    1 -> runWhatsAppTask(
                        getString(R.string.adding_sticker),
                        { WhatsAppManager.addFavorite(this, st) }
                    ) { toast(R.string.sticker_added) }
                }
            }
            .show()
    }

    private fun addToWhatsApp() {
        if (pack.stickers.isEmpty()) { toast(R.string.stickers_required); return }
        val wasAdded = pack.addedToWhatsApp
        runWhatsAppTask(
            getString(R.string.adding_pack),
            { WhatsAppManager.addPack(this, pack, replace = wasAdded) }
        ) {
            pack.addedToWhatsApp = true
            pack.addedAt = System.currentTimeMillis()
            PackRepository.upsert(pack)
            toast(if (wasAdded) R.string.pack_updated else R.string.pack_added)
            refresh()
        }
    }

    private fun removeFromWhatsApp() {
        runWhatsAppTask(
            getString(R.string.removing_pack),
            { WhatsAppManager.removePack(this, pack.id) }
        ) {
            pack.addedToWhatsApp = false
            PackRepository.upsert(pack)
            toast(R.string.pack_removed)
            refresh()
        }
    }

    override fun onStickerCreated(sticker: Sticker) {
        pack.stickers.add(sticker)
        PackRepository.upsert(pack)
        toast(R.string.sticker_created)
        refresh()
    }

    companion object {
        private const val EXTRA_ID = "pack_id"
        fun start(ctx: Context, packId: String) {
            ctx.startActivity(Intent(ctx, PackDetailsActivity::class.java).putExtra(EXTRA_ID, packId))
        }
    }
}