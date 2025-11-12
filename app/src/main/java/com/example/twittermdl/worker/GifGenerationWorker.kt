package com.example.twittermdl.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.Data
import com.example.twittermdl.data.AppDatabase
import com.example.twittermdl.network.MediaDownloader
import com.example.twittermdl.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker that generates GIF thumbnails from videos in the background.
 * This worker survives process death and respects system constraints,
 * ensuring GIF generation completes even when the app is in the background.
 */
class GifGenerationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_HISTORY_ID = "history_id"
        const val KEY_VIDEO_PATH = "video_path"
        const val KEY_TWEET_ID = "tweet_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR_MESSAGE = "error_message"

        const val TAG = "GifGenerationWorker"
    }

    private val mediaDownloader = MediaDownloader(context)
    private val database = AppDatabase.getDatabase(context)
    private val repository = DownloadRepository(database.downloadHistoryDao())

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val historyId = inputData.getLong(KEY_HISTORY_ID, -1L)
        val videoPath = inputData.getString(KEY_VIDEO_PATH)
        val tweetId = inputData.getString(KEY_TWEET_ID)

        // Validate inputs
        if (historyId == -1L || videoPath == null || tweetId == null) {
            Log.e(TAG, "Invalid input data: historyId=$historyId, videoPath=$videoPath, tweetId=$tweetId")
            return@withContext Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "Invalid worker input data")
            )
        }

        Log.d(TAG, "Starting GIF generation for history ID: $historyId")

        try {
            // Set initial progress
            setProgressAsync(workDataOf(KEY_PROGRESS to 0))

            // Generate GIF thumbnail with progress updates
            val gifResult = mediaDownloader.generateVideoGifThumbnail(
                videoPath,
                tweetId
            ) { progress ->
                // Update progress asynchronously
                setProgressAsync(workDataOf(KEY_PROGRESS to progress))
            }

            gifResult.onSuccess { gifPath ->
                Log.d(TAG, "GIF generated successfully: $gifPath")

                // Update the history entry with the new GIF thumbnail
                val history = repository.getDownloadById(historyId)
                if (history != null) {
                    val updatedHistory = history.copy(thumbnailPath = gifPath)
                    repository.update(updatedHistory)
                    Log.d(TAG, "Updated history entry with GIF thumbnail")

                    // Set final progress to 100%
                    setProgressAsync(workDataOf(KEY_PROGRESS to 100))

                    return@withContext Result.success(
                        workDataOf(KEY_PROGRESS to 100)
                    )
                } else {
                    Log.w(TAG, "Could not find history entry $historyId to update")
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR_MESSAGE to "History entry not found")
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "GIF generation failed", error)
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to (error.message ?: "GIF generation failed"))
                )
            }

            // Should not reach here, but return failure as fallback
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Unknown error"))

        } catch (e: Exception) {
            Log.e(TAG, "Error in GIF generation worker", e)
            Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Worker exception"))
            )
        }
    }
}
