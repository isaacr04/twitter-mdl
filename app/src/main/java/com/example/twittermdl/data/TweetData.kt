package com.example.twittermdl.data

data class TweetData(
    val tweetId: String,
    val tweetUrl: String,
    val authorName: String,
    val authorUsername: String,
    val text: String,
    val mediaItems: List<MediaItem>,
    val isSensitive: Boolean = false
)
