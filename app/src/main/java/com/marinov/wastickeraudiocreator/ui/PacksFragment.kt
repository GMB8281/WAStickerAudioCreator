package com.marinov.wastickeraudiocreator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.MediaUtils
import com.marinov.wastickeraudiocreator.stickers.PackRepository
import com.marinov.wastickeraudiocreator.stickers.StickerPack
import com.marinov.wastickeraudiocreator.whatsapp.WhatsAppManager

class PacksFragment : Fragment() {

    private var rvPacks: RecyclerView? = null
    private var tvEmpty: TextView? = null
    private lateinit var adapter: PackListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_packs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rvPacks = view.findViewById(R.id.rv_packs)
        tvEmpty = view.findViewById(R.id.tv_empty)
        adapter = PackListAdapter(
            onOpen = { pack -> PackDetailsActivity.start(requireContext(), pack.id) },
            onAdd = { pack -> addPack(pack) }
        )
        rvPacks?.layoutManager = LinearLayoutManager(requireContext())
        rvPacks?.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val packs = PackRepository.all().sortedByDescending { it.createdAt }
        adapter.submit(packs)
        tvEmpty?.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addPack(pack: StickerPack) {
        val activity = requireActivity() as AppCompatActivity
        val wasAdded = pack.addedToWhatsApp
        activity.runWhatsAppTask(
            getString(R.string.adding_pack),
            { WhatsAppManager.addPack(requireContext(), pack, replace = wasAdded) }
        ) {
            pack.addedToWhatsApp = true
            pack.addedAt = System.currentTimeMillis()
            PackRepository.upsert(pack)
            requireContext().toast(if (wasAdded) R.string.pack_updated else R.string.pack_added)
            refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rvPacks = null
        tvEmpty = null
    }
}

private class PackListAdapter(
    private val onOpen: (StickerPack) -> Unit,
    private val onAdd: (StickerPack) -> Unit
) : RecyclerView.Adapter<PackListAdapter.VH>() {

    private val items = mutableListOf<StickerPack>()

    fun submit(list: List<StickerPack>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.tv_pack_name)
        val publisher: TextView = itemView.findViewById(R.id.tv_publisher)
        val previews: LinearLayout = itemView.findViewById(R.id.ll_previews)
        val addBtn: MaterialButton = itemView.findViewById(R.id.btn_add_pack)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pack, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pack = items[position]
        val ctx = holder.itemView.context
        holder.name.text = pack.name
        holder.publisher.text = pack.publisher

        // Preview count depends on screen width: (screen - card chrome - add button) / cell.
        val cell = holder.itemView.dp(56)
        val chrome = holder.itemView.dp(96)
        val avail = ctx.resources.displayMetrics.widthPixels - chrome
        val count = maxOf(1, avail / cell)
        holder.previews.removeAllViews()
        for (st in pack.stickers.take(count)) {
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(holder.itemView.dp(48), holder.itemView.dp(48))
                    .apply { marginEnd = holder.itemView.dp(8) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                val thumb = MediaUtils.decodeThumb(PackRepository.mediaFile(st.webpFile), 96)
                if (thumb != null) setImageBitmap(thumb) else setImageResource(R.drawable.ic_sticker_packs)
            }
            holder.previews.addView(iv)
        }

        holder.addBtn.setOnClickListener { onAdd(pack) }
        holder.itemView.setOnClickListener { onOpen(pack) }
    }
}