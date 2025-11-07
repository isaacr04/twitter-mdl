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
    val mediaUrls: String, // JSON array of URLs
    val mediaTypes: String, // JSON array of types
    val localFilePaths: String // JSON array of local paths
)
