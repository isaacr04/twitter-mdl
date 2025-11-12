package com.example.twittermdl.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tweetId: String,
    val tweetUrl: String,
    val authorName: String,
    val authorUsername: String,
    val text: String,
    val downloadDate: Long,
    val thumbnailPath: String?,
    val mediaUrl: String, // Single media URL
    val mediaType: String, // Single media type
    val localFilePath: String, // Single local file path
    val mediaIndex: Int = 0, // Index of this media in the tweet (0-based)
    val totalMediaCount: Int = 1 // Total number of media items in the tweet
)
