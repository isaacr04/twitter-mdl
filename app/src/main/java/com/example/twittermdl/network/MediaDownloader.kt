package com.example.twittermdl.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.twittermdl.data.MediaItem
import com.example.twittermdl.data.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MediaDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder().build()

    suspend fun downloadMedia(
        mediaItem: MediaItem,
        tweetId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(mediaItem.url)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(
                Exception("Empty response body")
            )

            val extension = getExtensionForMediaType(mediaItem.type)
            val fileName = "twitter_${tweetId}_${System.currentTimeMillis()}.$extension"

            val savedPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(body.byteStream(), fileName, mediaItem.type, onProgress)
            } else {
                saveToExternalStorage(body.byteStream(), fileName, mediaItem.type, onProgress)
            }

            Result.success(savedPath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveToMediaStore(
        inputStream: InputStream,
        fileName: String,
        mediaType: MediaType,
        onProgress: (Int) -> Unit
    ): String {
        val collection = when (mediaType) {
            MediaType.IMAGE, MediaType.GIF -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val mimeType = getMimeType(mediaType)
        val relativeLocation = when (mediaType) {
            MediaType.IMAGE, MediaType.GIF -> Environment.DIRECTORY_PICTURES + "/TwitterDownloads"
            MediaType.VIDEO -> Environment.DIRECTORY_MOVIES + "/TwitterDownloads"
            MediaType.AUDIO -> Environment.DIRECTORY_MUSIC + "/TwitterDownloads"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeLocation)
        }

        val uri = context.contentResolver.insert(collection, contentValues)
            ?: throw Exception("Failed to create media store entry")

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            inputStream.copyTo(outputStream)
        }

        return uri.toString()
    }

    private fun saveToExternalStorage(
        inputStream: InputStream,
        fileName: String,
        mediaType: MediaType,
        onProgress: (Int) -> Unit
    ): String {
        val directory = when (mediaType) {
            MediaType.IMAGE, MediaType.GIF ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            MediaType.VIDEO ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            MediaType.AUDIO ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        }

        val twitterDir = File(directory, "TwitterDownloads")
        if (!twitterDir.exists()) {
            twitterDir.mkdirs()
        }

        val file = File(twitterDir, fileName)
        FileOutputStream(file).use { outputStream ->
            inputStream.copyTo(outputStream)
        }

        return file.absolutePath
    }

    private fun getExtensionForMediaType(mediaType: MediaType): String {
        return when (mediaType) {
            MediaType.IMAGE -> "jpg"
            MediaType.GIF -> "gif"
            MediaType.VIDEO -> "mp4"
            MediaType.AUDIO -> "mp3"
        }
    }

    private fun getMimeType(mediaType: MediaType): String {
        return when (mediaType) {
            MediaType.IMAGE -> "image/jpeg"
            MediaType.GIF -> "image/gif"
            MediaType.VIDEO -> "video/mp4"
            MediaType.AUDIO -> "audio/mpeg"
        }
    }

    suspend fun downloadThumbnail(url: String, tweetId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Thumbnail download failed"))
                }

                val body = response.body ?: return@withContext Result.failure(
                    Exception("Empty response body")
                )

                val thumbnailsDir = File(context.filesDir, "thumbnails")
                if (!thumbnailsDir.exists()) {
                    thumbnailsDir.mkdirs()
                }

                val file = File(thumbnailsDir, "thumb_${tweetId}_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { outputStream ->
                    body.byteStream().copyTo(outputStream)
                }

                Result.success(file.absolutePath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
