package com.example.twittermdl.network

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.twittermdl.data.MediaItem
import com.example.twittermdl.data.MediaType
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

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
        @Suppress("UNUSED_PARAMETER") onProgress: (Int) -> Unit
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
        @Suppress("UNUSED_PARAMETER") onProgress: (Int) -> Unit
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
                Log.d("MediaDownloader", "Downloading thumbnail from: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e("MediaDownloader", "Thumbnail download failed with code: ${response.code}")
                    return@withContext Result.failure(Exception("Thumbnail download failed: ${response.code}"))
                }

                val body = response.body ?: run {
                    Log.e("MediaDownloader", "Empty response body for thumbnail")
                    return@withContext Result.failure(Exception("Empty response body"))
                }

                val thumbnailsDir = File(context.filesDir, "thumbnails")
                if (!thumbnailsDir.exists()) {
                    thumbnailsDir.mkdirs()
                    Log.d("MediaDownloader", "Created thumbnails directory: ${thumbnailsDir.absolutePath}")
                }

                val file = File(thumbnailsDir, "thumb_${tweetId}_${System.currentTimeMillis()}.jpg")
                Log.d("MediaDownloader", "Saving thumbnail to: ${file.absolutePath}")

                FileOutputStream(file).use { outputStream ->
                    val bytes = body.byteStream().copyTo(outputStream)
                    Log.d("MediaDownloader", "Wrote $bytes bytes to thumbnail file")
                }

                if (file.exists() && file.length() > 0) {
                    Log.d("MediaDownloader", "Thumbnail saved successfully: ${file.absolutePath} (${file.length()} bytes)")
                    Result.success(file.absolutePath)
                } else {
                    Log.e("MediaDownloader", "Thumbnail file not created or empty")
                    Result.failure(Exception("Thumbnail file not created"))
                }
            } catch (e: Exception) {
                Log.e("MediaDownloader", "Error downloading thumbnail", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Extracts a single frame from a video file to use as a static thumbnail.
     * This is more efficient than downloading the full video as a thumbnail.
     */
    suspend fun extractStaticThumbnail(
        videoPath: String,
        tweetId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        try {
            Log.d("MediaDownloader", "Extracting static thumbnail from video: $videoPath")

            // Get video file
            val videoFile = getFileFromPath(videoPath)
            if (videoFile == null) {
                Log.e("MediaDownloader", "Could not resolve video file from path: $videoPath")
                return@withContext Result.failure(Exception("Could not resolve video file"))
            }

            // Initialize retriever
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)

            // Extract a single frame from the beginning (0 microseconds)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)

            if (frame == null) {
                Log.e("MediaDownloader", "Failed to extract frame from video")
                return@withContext Result.failure(Exception("Failed to extract frame"))
            }

            Log.d("MediaDownloader", "Extracted frame: ${frame.width}x${frame.height}")

            // Scale frame to reasonable thumbnail size (320px width)
            val scaledFrame = scaleBitmap(frame, 320)
            frame.recycle()

            // Create thumbnails directory
            val thumbnailsDir = File(context.filesDir, "thumbnails")
            if (!thumbnailsDir.exists()) {
                thumbnailsDir.mkdirs()
            }

            val outputFile = File(
                thumbnailsDir,
                "thumb_${tweetId}_${System.currentTimeMillis()}.jpg"
            )

            // Save as JPEG
            FileOutputStream(outputFile).use { outputStream ->
                scaledFrame.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            }
            scaledFrame.recycle()

            if (!outputFile.exists() || outputFile.length() == 0L) {
                Log.e("MediaDownloader", "Thumbnail file was not created")
                return@withContext Result.failure(Exception("Thumbnail file not created"))
            }

            val fileSize = outputFile.length()
            Log.d("MediaDownloader", "Static thumbnail extracted successfully: ${outputFile.absolutePath} (${fileSize / 1024}KB)")
            Result.success(outputFile.absolutePath)

        } catch (e: Exception) {
            Log.e("MediaDownloader", "Error extracting static thumbnail", e)
            Result.failure(e)
        } finally {
            retriever?.release()
        }
    }

    /**
     * Generates a GIF thumbnail from a video file using MediaMetadataRetriever.
     * If the video is shorter than 15 seconds, converts the entire video to GIF.
     * Otherwise, extracts 15 seconds from the middle of the video.
     * @param onProgress Callback to report progress (0-100)
     */
    suspend fun generateVideoGifThumbnail(
        videoPath: String,
        tweetId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        try {
            // Get video file
            val videoFile = getFileFromPath(videoPath)
            if (videoFile == null) {
                Log.e("MediaDownloader", "Could not resolve video file from path: $videoPath")
                return@withContext Result.failure(Exception("Could not resolve video file"))
            }

            // Initialize retriever
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(videoFile.absolutePath)

            // Get video duration
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val durationSec = durationMs / 1000.0

            if (durationMs <= 0) {
                Log.e("MediaDownloader", "Invalid video duration: $durationMs")
                return@withContext Result.failure(Exception("Invalid video duration"))
            }

            // Calculate which portion of the video to extract
            val (startTimeSec, endTimeSec) = if (durationSec <= 15) {
                // Video is 15 seconds or shorter - use entire video
                Pair(0.0, durationSec)
            } else {
                // Video is longer than 15 seconds - extract 15 seconds from middle
                val startTime = (durationSec - 15) / 2
                Pair(startTime, startTime + 15)
            }

            // Extract frames (10 fps for smooth animation)
            val fps = 10
            val frameInterval = 1000.0 / fps // milliseconds between frames
            val frames = mutableListOf<Bitmap>()

            var currentTime = startTimeSec * 1000 * 1000 // Convert to microseconds
            val endTime = endTimeSec * 1000 * 1000

            Log.d("MediaDownloader", "Extracting frames from ${startTimeSec}s to ${endTimeSec}s at ${fps}fps")

            val expectedFrameCount = ((endTimeSec - startTimeSec) * fps).toInt().coerceAtMost(150)
            onProgress(5) // Started extraction

            while (currentTime <= endTime && frames.size < 150) { // Limit to 150 frames max
                try {
                    val frame = retriever.getFrameAtTime(
                        currentTime.toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST // Use OPTION_CLOSEST instead of SYNC
                    )

                    if (frame != null) {
                        // Scale frame to 320px width while maintaining aspect ratio
                        val scaledFrame = scaleBitmap(frame, 320)
                        frames.add(scaledFrame)
                        frame.recycle()

                        // Report progress: extraction is 5-50% of total
                        val extractionProgress = 5 + ((frames.size.toFloat() / expectedFrameCount) * 45).toInt()
                        onProgress(extractionProgress.coerceIn(5, 50))

                        if (frames.size % 10 == 0) {
                            Log.d("MediaDownloader", "Extracted ${frames.size} frames so far... (${extractionProgress}%)")
                        }
                    } else {
                        Log.w("MediaDownloader", "Frame at $currentTime was null")
                    }

                    currentTime += frameInterval * 1000 // Add interval in microseconds
                } catch (e: Exception) {
                    Log.w("MediaDownloader", "Failed to extract frame at $currentTime: ${e.message}", e)
                    // Continue trying to extract more frames instead of breaking
                    currentTime += frameInterval * 1000
                }
            }

            retriever.release()
            retriever = null

            if (frames.isEmpty()) {
                Log.e("MediaDownloader", "No frames extracted from video")
                return@withContext Result.failure(Exception("No frames extracted"))
            }

            Log.d("MediaDownloader", "Extracted ${frames.size} frames total, creating GIF...")

            // Create thumbnails directory
            val thumbnailsDir = File(context.filesDir, "thumbnails")
            if (!thumbnailsDir.exists()) {
                thumbnailsDir.mkdirs()
            }

            val outputFile = File(
                thumbnailsDir,
                "thumb_${tweetId}_${System.currentTimeMillis()}.gif"
            )

            // Create GIF from frames
            val imageOptions = ImageOptions().setDelay(100, TimeUnit.MILLISECONDS) // 100ms per frame = 10fps

            onProgress(55) // Starting encoding

            FileOutputStream(outputFile).use { outputStream ->
                Log.d("MediaDownloader", "Creating GIF encoder (${frames[0].width}x${frames[0].height})")
                val encoder = GifEncoder(outputStream, frames[0].width, frames[0].height, 0)

                val totalFrames = frames.size

                frames.forEachIndexed { index, frame ->
                    try {
                        encoder.addImage(frame.to2DIntArray(), imageOptions)

                        // Report progress: encoding is 55-95% of total
                        val encodingProgress = 55 + ((index.toFloat() / totalFrames) * 40).toInt()
                        onProgress(encodingProgress.coerceIn(55, 95))

                        if (index % 20 == 0) {
                            Log.d("MediaDownloader", "Encoded frame ${index + 1}/${totalFrames} (${encodingProgress}%)")
                        }
                    } catch (e: Exception) {
                        Log.e("MediaDownloader", "Failed to encode frame $index", e)
                        throw e
                    } finally {
                        frame.recycle()
                    }
                }

                encoder.finishEncoding()
                Log.d("MediaDownloader", "GIF encoding finished")
                onProgress(100) // Complete
            }

            if (!outputFile.exists()) {
                Log.e("MediaDownloader", "GIF file was not created")
                return@withContext Result.failure(Exception("GIF file not created"))
            }

            val fileSize = outputFile.length()
            Log.d("MediaDownloader", "GIF thumbnail generated successfully: ${outputFile.absolutePath} (${fileSize / 1024}KB)")
            Result.success(outputFile.absolutePath)

        } catch (e: Exception) {
            Log.e("MediaDownloader", "Error generating GIF thumbnail", e)
            Result.failure(e)
        } finally {
            retriever?.release()
        }
    }

    /**
     * Scales a bitmap to the specified width while maintaining aspect ratio
     */
    private fun scaleBitmap(bitmap: Bitmap, targetWidth: Int): Bitmap {
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val targetHeight = (targetWidth * aspectRatio).toInt()
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * Converts a bitmap to a 2D int array for GIF encoding
     */
    private fun Bitmap.to2DIntArray(): Array<IntArray> {
        val width = this.width
        val height = this.height
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert 1D array to 2D array (row by row)
        return Array(height) { row ->
            IntArray(width) { col ->
                pixels[row * width + col]
            }
        }
    }

    /**
     * Converts a content URI or file path to a File object
     */
    private fun getFileFromPath(path: String): File? {
        return try {
            Log.d("MediaDownloader", "Resolving video path: $path")

            val file = if (path.startsWith("content://")) {
                // For content URIs, we need to create a temporary file
                Log.d("MediaDownloader", "Path is content URI, creating temp file")
                val uri = Uri.parse(path)
                val tempFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val copied = input.copyTo(output)
                        Log.d("MediaDownloader", "Copied $copied bytes to temp file")
                    }
                }
                tempFile
            } else {
                // For regular file paths
                Log.d("MediaDownloader", "Path is regular file path")
                File(path)
            }

            if (file.exists()) {
                Log.d("MediaDownloader", "Video file exists: ${file.absolutePath}, size: ${file.length()} bytes")
            } else {
                Log.e("MediaDownloader", "Video file does NOT exist: ${file.absolutePath}")
            }

            file
        } catch (e: Exception) {
            Log.e("MediaDownloader", "Error getting file from path: $path", e)
            null
        }
    }
}
