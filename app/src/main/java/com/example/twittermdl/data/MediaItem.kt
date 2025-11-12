package com.example.twittermdl.data

data class MediaItem(
    val url: String,
    val type: MediaType,
    val thumbnailUrl: String? = null,
    val duration: Long? = null,
    var isSelected: Boolean = true,
    val videoVariants: List<VideoVariant>? = null // Available quality options for videos
)

data class VideoVariant(
    val url: String,
    val quality: String, // e.g., "1080p", "720p", "480p", "360p", "Audio only"
    val bitrate: Int, // in kbps
    val contentType: String // e.g., "video/mp4", "audio/aac"
)
