package com.example.twittermdl.utils

import com.example.twittermdl.data.MediaItem
import com.example.twittermdl.data.MediaType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object JsonUtils {
    private val gson = Gson()

    fun mediaItemsToJson(items: List<MediaItem>): String {
        return gson.toJson(items.map { mapOf(
            "url" to it.url,
            "type" to it.type.name,
            "thumbnailUrl" to it.thumbnailUrl
        )})
    }

    fun jsonToMediaItems(json: String): List<MediaItem> {
        val type = object : TypeToken<List<Map<String, String>>>() {}.type
        val list: List<Map<String, String>> = gson.fromJson(json, type)
        return list.map {
            MediaItem(
                url = it["url"] ?: "",
                type = MediaType.valueOf(it["type"] ?: "IMAGE"),
                thumbnailUrl = it["thumbnailUrl"]
            )
        }
    }

    fun listToJson(list: List<String>): String {
        return gson.toJson(list)
    }

    fun jsonToList(json: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type)
    }
}
