package com.marinov.wastickeraudiocreator.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.PackRepository
import com.marinov.wastickeraudiocreator.stickers.Sticker
import com.marinov.wastickeraudiocreator.stickers.StickerPack

class CreatePackActivity : AppCompatActivity(), CreateStickerSheet.StickerReceiver {

    private lateinit var adapter: StickerGridAdapter
    private lateinit var tilName: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var tilPublisher: TextInputLayout
    private lateinit var etPublisher: TextInputEditText
    private val pending = mutableListOf<Sticker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PackRepository.init(this)
        setContentView(R.layout.activity_create_pack)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.create_pack)
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationContentDescription(R.string.cd_back)
        toolbar.setNavigationOnClickListener { finish() }

        tilName = findViewById(R.id.til_name)
        etName = findViewById(R.id.et_name)
        tilPublisher = findViewById(R.id.til_publisher)
        etPublisher = findViewById(R.id.et_publisher)

        adapter = StickerGridAdapter(
            onStickerClick = { st ->
                confirm(getString(R.string.remove_sticker), st.id) {
                    pending.remove(st)
                    adapter.submit(pending)
                }
            },
            onAddClick = { CreateStickerSheet().show(supportFragmentManager, "create_sticker") }
        )
        val rv = findViewById<RecyclerView>(R.id.rv_stickers)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter
        adapter.submit(pending)

        val btnPrimary = findViewById<MaterialButton>(R.id.btn_primary)
        btnPrimary.setText(R.string.create_pack)
        btnPrimary.setOnClickListener { savePack() }
    }

    private fun savePack() {
        val name = etName.text?.toString()?.trim().orEmpty()
        val publisher = etPublisher.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) { tilName.error = getString(R.string.name_required); return }
        tilName.error = null
        if (publisher.isEmpty()) { tilPublisher.error = getString(R.string.publisher_required); return }
        tilPublisher.error = null
        if (pending.isEmpty()) { toast(R.string.stickers_required); return }

        val pack = StickerPack(name = name, publisher = publisher, stickers = pending.toMutableList())
        PackRepository.upsert(pack)
        toast(R.string.pack_saved)
        finish()
    }

    override fun onStickerCreated(sticker: Sticker) {
        pending.add(sticker)
        adapter.submit(pending)
        toast(R.string.sticker_created)
    }
}