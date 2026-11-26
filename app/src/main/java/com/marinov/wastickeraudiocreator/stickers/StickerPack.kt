package com.marinov.wastickeraudiocreator.stickers

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Sticker(
    val id: String = UUID.randomUUID().toString(),
    val webpFile: String,
    val opusFile: String? = null,
    val animated: Boolean = false,
    val durationSeconds: Int = 0,
    val emoji: String = ""
) {
    val hasAudio: Boolean get() = opusFile != null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("webp", webpFile)
        .put("opus", opusFile ?: JSONObject.NULL)
        .put("animated", animated)
        .put("duration", durationSeconds)
        .put("emoji", emoji)

    companion object {
        fun fromJson(o: JSONObject): Sticker = Sticker(
            id = o.getString("id"),
            webpFile = o.getString("webp"),
            opusFile = o.optString("opus").takeIf { it.isNotEmpty() && it != "null" },
            animated = o.optBoolean("animated"),
            durationSeconds = o.optInt("duration"),
            emoji = o.optString("emoji")
        )
    }
}

data class StickerPack(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var publisher: String,
    val createdAt: Long = System.currentTimeMillis(),
    val stickers: MutableList<Sticker> = mutableListOf(),
    var addedToWhatsApp: Boolean = false,
    var addedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("publisher", publisher)
        .put("createdAt", createdAt)
        .put("added", addedToWhatsApp)
        .put("addedAt", addedAt)
        .put("stickers", JSONArray().apply { stickers.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): StickerPack {
            val arr = o.optJSONArray("stickers") ?: JSONArray()
            val list = mutableListOf<Sticker>()
            for (i in 0 until arr.length()) list.add(Sticker.fromJson(arr.getJSONObject(i)))
            return StickerPack(
                id = o.getString("id"),
                name = o.getString("name"),
                publisher = o.optString("publisher"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                stickers = list,
                addedToWhatsApp = o.optBoolean("added"),
                addedAt = o.optLong("addedAt")
            )
        }
    }
}