package com.marinov.wastickeraudiocreator.stickers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object PackRepository {

    private lateinit var repoDir: File
    lateinit var mediaDir: File
        private set
    private val packs = mutableListOf<StickerPack>()

    @Synchronized
    fun init(context: Context) {
        if (::repoDir.isInitialized) return
        repoDir = File(context.filesDir, "repo").apply { mkdirs() }
        mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        load()
    }

    fun all(): List<StickerPack> = synchronized(this) { packs.toList() }

    fun byId(id: String): StickerPack? = synchronized(this) { packs.find { it.id == id } }

    @Synchronized
    fun upsert(pack: StickerPack) {
        val idx = packs.indexOfFirst { it.id == pack.id }
        if (idx >= 0) packs[idx] = pack else packs.add(pack)
        save()
    }

    @Synchronized
    fun removeSticker(packId: String, stickerId: String) {
        val pack = packs.find { it.id == packId } ?: return
        val st = pack.stickers.find { it.id == stickerId } ?: return
        pack.stickers.remove(st)
        runCatching { File(mediaDir, st.webpFile).delete() }
        st.opusFile?.let { runCatching { File(mediaDir, it).delete() } }
        save()
    }

    fun mediaFile(name: String): File = File(mediaDir, name)

    private fun load() {
        val f = File(repoDir, "packs.json")
        if (!f.exists()) return
        runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("packs") ?: JSONArray()
            for (i in 0 until arr.length()) packs.add(StickerPack.fromJson(arr.getJSONObject(i)))
        }
    }

    private fun save() {
        val arr = JSONArray()
        packs.forEach { arr.put(it.toJson()) }
        File(repoDir, "packs.json").writeText(JSONObject().put("packs", arr).toString())
    }
}