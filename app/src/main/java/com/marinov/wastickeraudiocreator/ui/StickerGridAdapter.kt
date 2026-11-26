package com.marinov.wastickeraudiocreator.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.marinov.wastickeraudiocreator.R
import com.marinov.wastickeraudiocreator.stickers.MediaUtils
import com.marinov.wastickeraudiocreator.stickers.PackRepository
import com.marinov.wastickeraudiocreator.stickers.Sticker

/** Shared grid: sticker cells + a trailing "Add sticker" cell. */
class StickerGridAdapter(
    private val onStickerClick: (Sticker) -> Unit,
    private val onAddClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Sticker>()

    fun submit(list: List<Sticker>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position < items.size) TYPE_STICKER else TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_STICKER) {
            StickerVH(inflater.inflate(R.layout.item_sticker_cell, parent, false))
        } else {
            AddVH(inflater.inflate(R.layout.item_add_sticker, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is StickerVH -> {
                val st = items[position]
                val thumb = MediaUtils.decodeThumb(PackRepository.mediaFile(st.webpFile), 144)
                if (thumb != null) holder.image.setImageBitmap(thumb)
                else holder.image.setImageResource(R.drawable.ic_sticker_packs)
                holder.audioBadge.visibility = if (st.hasAudio) View.VISIBLE else View.GONE
                holder.itemView.setOnClickListener { onStickerClick(st) }
            }
            is AddVH -> holder.itemView.setOnClickListener { onAddClick() }
        }
    }

    private class StickerVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.iv_sticker)
        val audioBadge: ImageView = itemView.findViewById(R.id.iv_audio)
    }

    private class AddVH(itemView: View) : RecyclerView.ViewHolder(itemView)

    companion object {
        private const val TYPE_STICKER = 0
        private const val TYPE_ADD = 1
    }
}