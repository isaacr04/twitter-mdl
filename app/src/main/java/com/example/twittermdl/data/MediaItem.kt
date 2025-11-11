package com.example.twittermdl.data

data class MediaItem(
    val url: String,
    val type: MediaType,
    val thumbnailUrl: String? = null,
    val duration: Long? = null,
    var isSelected: Boolean = true
)
