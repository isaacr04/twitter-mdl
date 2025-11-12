package com.example.twittermdl.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.twittermdl.data.*
import com.example.twittermdl.network.MediaDownloader
import com.example.twittermdl.network.TwitterApiService
import com.example.twittermdl.repository.DownloadRepository
import com.example.twittermdl.utils.JsonUtils
import com.example.twittermdl.utils.PreferencesManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = DownloadRepository(database.downloadHistoryDao())
    private val twitterApiService = TwitterApiService()
    private val mediaDownloader = MediaDownloader(application)
    private val preferencesManager = PreferencesManager(application)

    val allDownloads: Flow<List<DownloadHistory>> = repository.allDownloads
    val userCredentials: Flow<UserCredentials?> = preferencesManager.userCredentials
    val isLoggedIn: Flow<Boolean> = preferencesManager.isLoggedIn
    val generateGifsForThumbnails: Flow<Boolean> = preferencesManager.generateGifsForThumbnails
    val deleteLocalFilesWithHistory: Flow<Boolean> = preferencesManager.deleteLocalFilesWithHistory

    private val _tweetData = MutableLiveData<TweetData?>()
    val tweetData: LiveData<TweetData?> = _tweetData

    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> = _loadingState

    private val _downloadProgress = MutableLiveData<Int>()
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Track GIF generation progress for each history ID
    private val _gifGenerationProgress = MutableLiveData<Map<Long, GifGenerationProgress>>()
    val gifGenerationProgress: LiveData<Map<Long, GifGenerationProgress>> = _gifGenerationProgress

    fun fetchTweetData(url: String) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading
            _errorMessage.value = null

            val credentials = getCurrentCredentials()
            val result = twitterApiService.getTweetDataFromUrl(url, credentials)

            result.onSuccess { data ->
                _tweetData.value = data
                _loadingState.value = LoadingState.Success
            }.onFailure { error ->
                _errorMessage.value = error.message
                _loadingState.value = LoadingState.Error(error.message ?: "Unknown error")
            }
        }
    }

    fun downloadSelectedMedia(
        tweetData: TweetData,
        selectedMedia: List<MediaItem>,
        existingHistoryId: Long? = null
    ) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading
            Log.d("MainViewModel", "Starting download for ${selectedMedia.size} media items" +
                    if (existingHistoryId != null) " (redownload for history ID: $existingHistoryId)" else "")

            try {
                val downloadedPaths = mutableListOf<String>()
                val mediaUrls = mutableListOf<String>()
                val mediaTypes = mutableListOf<String>()

                var thumbnailPath: String? = null
                var firstVideoPath: String? = null
                var firstVideoTweetId: String? = null

                selectedMedia.forEachIndexed { index, media ->
                    Log.d("MainViewModel", "Downloading media ${index + 1}/${selectedMedia.size}: ${media.type}")

                    val result = mediaDownloader.downloadMedia(
                        media,
                        tweetData.tweetId
                    ) { progress ->
                        _downloadProgress.postValue(
                            ((index.toFloat() / selectedMedia.size) * 100 +
                            (progress.toFloat() / selectedMedia.size)).toInt()
                        )
                    }

                    result.onSuccess { path ->
                        Log.d("MainViewModel", "Downloaded successfully: $path")
                        downloadedPaths.add(path)
                        mediaUrls.add(media.url)
                        mediaTypes.add(media.type.name)

                        // Use first media as thumbnail
                        if (thumbnailPath == null) {
                            try {
                                if (media.type == MediaType.VIDEO) {
                                    // Save video path for async GIF generation
                                    firstVideoPath = path
                                    firstVideoTweetId = tweetData.tweetId

                                    // Extract static thumbnail from the video
                                    Log.d("MainViewModel", "Extracting static thumbnail from video")
                                    val thumbResult = mediaDownloader.extractStaticThumbnail(path, tweetData.tweetId)
                                    thumbResult.onSuccess {
                                        thumbnailPath = it
                                        Log.d("MainViewModel", "Static thumbnail extracted: $it")
                                    }.onFailure { error ->
                                        Log.e("MainViewModel", "Static thumbnail extraction failed: ${error.message}", error)
                                    }
                                } else {
                                    // For images and GIFs, use the downloaded file as thumbnail
                                    val thumbUrl = media.thumbnailUrl ?: media.url
                                    Log.d("MainViewModel", "Downloading thumbnail for ${media.type}: $thumbUrl")

                                    val thumbResult = mediaDownloader.downloadThumbnail(thumbUrl, tweetData.tweetId)
                                    thumbResult.onSuccess {
                                        thumbnailPath = it
                                        Log.d("MainViewModel", "Thumbnail downloaded: $it")
                                    }.onFailure { error ->
                                        Log.e("MainViewModel", "Thumbnail download failed: ${error.message}", error)
                                    }
                                }
                            } catch (e: Exception) {
                                // Don't let thumbnail generation failures break downloads
                                Log.e("MainViewModel", "Thumbnail generation failed", e)
                            }
                        }
                    }.onFailure { error ->
                        Log.e("MainViewModel", "Download failed: ${error.message}", error)
                        _errorMessage.postValue("Failed to download media: ${error.message}")
                    }
                }

                // Save or update history entry
                val historyId = if (existingHistoryId != null) {
                    // Update existing history entry for redownload
                    val existingHistory = repository.getDownloadById(existingHistoryId)
                    if (existingHistory != null) {
                        val updatedHistory = existingHistory.copy(
                            downloadDate = System.currentTimeMillis(),
                            thumbnailPath = thumbnailPath,
                            localFilePaths = JsonUtils.listToJson(downloadedPaths)
                        )
                        repository.update(updatedHistory)
                        Log.d("MainViewModel", "Updated existing history entry ID: $existingHistoryId")
                        existingHistoryId
                    } else {
                        // Fallback: create new entry if existing not found
                        Log.w("MainViewModel", "Existing history not found, creating new entry")
                        val downloadHistory = DownloadHistory(
                            tweetId = tweetData.tweetId,
                            tweetUrl = tweetData.tweetUrl,
                            authorName = tweetData.authorName,
                            authorUsername = tweetData.authorUsername,
                            text = tweetData.text,
                            downloadDate = System.currentTimeMillis(),
                            thumbnailPath = thumbnailPath,
                            mediaUrls = JsonUtils.listToJson(mediaUrls),
                            mediaTypes = JsonUtils.listToJson(mediaTypes),
                            localFilePaths = JsonUtils.listToJson(downloadedPaths)
                        )
                        repository.insert(downloadHistory)
                    }
                } else {
                    // Create new history entry for fresh download
                    val downloadHistory = DownloadHistory(
                        tweetId = tweetData.tweetId,
                        tweetUrl = tweetData.tweetUrl,
                        authorName = tweetData.authorName,
                        authorUsername = tweetData.authorUsername,
                        text = tweetData.text,
                        downloadDate = System.currentTimeMillis(),
                        thumbnailPath = thumbnailPath,
                        mediaUrls = JsonUtils.listToJson(mediaUrls),
                        mediaTypes = JsonUtils.listToJson(mediaTypes),
                        localFilePaths = JsonUtils.listToJson(downloadedPaths)
                    )
                    val newId = repository.insert(downloadHistory)
                    Log.d("MainViewModel", "Created new history entry ID: $newId")
                    newId
                }

                // IMPORTANT: Set progress state BEFORE GIF generation to avoid race condition
                // This ensures the progress bar shows immediately when the item is bound
                val willGenerateGif = firstVideoPath != null && firstVideoTweetId != null

                _loadingState.value = LoadingState.Success
                _downloadProgress.value = 100

                // Generate GIF asynchronously if we have a video and GIF generation is enabled
                if (willGenerateGif) {
                    val shouldGenerateGifs = preferencesManager.generateGifsForThumbnails.firstOrNull() ?: true
                    if (shouldGenerateGifs) {
                        Log.d("MainViewModel", "Launching async GIF generation for ID: $historyId")
                        // Initialize progress state immediately (synchronously on main thread)
                        val currentMap = _gifGenerationProgress.value ?: emptyMap()
                        _gifGenerationProgress.value = currentMap + (historyId to GifGenerationProgress.InProgress(0))
                        launchAsyncGifGeneration(historyId, firstVideoPath!!, firstVideoTweetId!!)
                    } else {
                        Log.d("MainViewModel", "GIF generation disabled, keeping static thumbnail")
                    }
                }

            } catch (e: Exception) {
                _errorMessage.value = e.message
                _loadingState.value = LoadingState.Error(e.message ?: "Download failed")
            }
        }
    }

    /**
     * Generates GIF thumbnail asynchronously and updates the history entry when complete
     */
    private fun launchAsyncGifGeneration(historyId: Long, videoPath: String, tweetId: String) {
        viewModelScope.launch {
            try {
                Log.d("MainViewModel", "Starting async GIF generation for history ID: $historyId")

                // Update progress map to show generation starting
                updateGifProgress(historyId, GifGenerationProgress.InProgress(0))

                val gifResult = mediaDownloader.generateVideoGifThumbnail(
                    videoPath,
                    tweetId
                ) { progress ->
                    // Update progress as frames are extracted/encoded
                    updateGifProgress(historyId, GifGenerationProgress.InProgress(progress))
                }

                gifResult.onSuccess { gifPath ->
                    Log.d("MainViewModel", "GIF generated successfully: $gifPath")

                    // Update the history entry with the new GIF thumbnail
                    val history = repository.getDownloadById(historyId)
                    if (history != null) {
                        val updatedHistory = history.copy(thumbnailPath = gifPath)
                        repository.update(updatedHistory)
                        Log.d("MainViewModel", "Updated history entry with GIF thumbnail")

                        // Mark as complete and remove from progress map after short delay
                        updateGifProgress(historyId, GifGenerationProgress.Complete)
                        kotlinx.coroutines.delay(1000)
                        removeGifProgress(historyId)
                    } else {
                        Log.w("MainViewModel", "Could not find history entry $historyId to update")
                        updateGifProgress(historyId, GifGenerationProgress.Failed)
                    }
                }.onFailure { error ->
                    Log.w("MainViewModel", "Async GIF generation failed (static thumbnail will remain)", error)
                    updateGifProgress(historyId, GifGenerationProgress.Failed)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error in async GIF generation", e)
                updateGifProgress(historyId, GifGenerationProgress.Failed)
            }
        }
    }

    private fun updateGifProgress(historyId: Long, progress: GifGenerationProgress) {
        val currentMap = _gifGenerationProgress.value ?: emptyMap()
        _gifGenerationProgress.postValue(currentMap + (historyId to progress))
    }

    private fun removeGifProgress(historyId: Long) {
        val currentMap = _gifGenerationProgress.value ?: emptyMap()
        _gifGenerationProgress.postValue(currentMap - historyId)
    }

    /**
     * Fetch fresh tweet data for redownloading (to get quality variants)
     * Returns the tweet data with video variants if available
     */
    suspend fun fetchTweetDataForRedownload(tweetUrl: String): Result<TweetData> {
        return try {
            val credentials = getCurrentCredentials()
            twitterApiService.getTweetDataFromUrl(tweetUrl, credentials)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun redownloadMedia(downloadHistory: DownloadHistory) {
        viewModelScope.launch {
            try {
                val mediaUrls = JsonUtils.jsonToList(downloadHistory.mediaUrls)
                val mediaTypes = JsonUtils.jsonToList(downloadHistory.mediaTypes)

                val mediaItems = mediaUrls.mapIndexed { index, url ->
                    MediaItem(
                        url = url,
                        type = MediaType.valueOf(mediaTypes[index]),
                        thumbnailUrl = null
                    )
                }

                val tweetData = TweetData(
                    tweetId = downloadHistory.tweetId,
                    tweetUrl = downloadHistory.tweetUrl,
                    authorName = downloadHistory.authorName,
                    authorUsername = downloadHistory.authorUsername,
                    text = downloadHistory.text,
                    mediaItems = mediaItems
                )

                // Pass the existing history ID to update instead of creating new entry
                downloadSelectedMedia(tweetData, mediaItems, existingHistoryId = downloadHistory.id)
            } catch (e: Exception) {
                _errorMessage.value = "Redownload failed: ${e.message}"
            }
        }
    }

    fun saveCredentials(username: String, password: String) {
        viewModelScope.launch {
            val credentials = UserCredentials(username, password)
            preferencesManager.saveCredentials(credentials)
        }
    }

    fun logout() {
        viewModelScope.launch {
            preferencesManager.clearCredentials()
        }
    }

    private suspend fun getCurrentCredentials(): UserCredentials? {
        return try {
            userCredentials.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    fun deleteDownload(downloadHistory: DownloadHistory) {
        viewModelScope.launch {
            // Check if we should delete local files
            val shouldDeleteFiles = preferencesManager.deleteLocalFilesWithHistory.firstOrNull() ?: false

            if (shouldDeleteFiles) {
                Log.d("MainViewModel", "Deleting local files for history ID: ${downloadHistory.id}")
                deleteLocalFiles(downloadHistory)
            }

            repository.delete(downloadHistory)
        }
    }

    private fun deleteLocalFiles(downloadHistory: DownloadHistory) {
        try {
            // Delete thumbnail file
            downloadHistory.thumbnailPath?.let { path ->
                val thumbFile = java.io.File(path)
                if (thumbFile.exists()) {
                    val deleted = thumbFile.delete()
                    Log.d("MainViewModel", "Deleted thumbnail: $deleted ($path)")
                }
            }

            // Delete downloaded media files
            val localPaths = JsonUtils.jsonToList(downloadHistory.localFilePaths)
            localPaths.forEach { path ->
                if (path.startsWith("content://")) {
                    // For content URIs, try to delete from MediaStore
                    try {
                        val uri = android.net.Uri.parse(path)
                        val deleted = getApplication<Application>().contentResolver.delete(uri, null, null)
                        Log.d("MainViewModel", "Deleted media from MediaStore: $deleted ($path)")
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Failed to delete from MediaStore: $path", e)
                    }
                } else {
                    // For file paths, delete directly
                    val file = java.io.File(path)
                    if (file.exists()) {
                        val deleted = file.delete()
                        Log.d("MainViewModel", "Deleted media file: $deleted ($path)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error deleting local files", e)
        }
    }

    fun setGenerateGifsForThumbnails(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setGenerateGifsForThumbnails(enabled)
        }
    }

    fun setDeleteLocalFilesWithHistory(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDeleteLocalFilesWithHistory(enabled)
        }
    }

    suspend fun refreshAllThumbnails() {
        val downloads = repository.allDownloads.firstOrNull() ?: return
        val shouldGenerateGifs = preferencesManager.generateGifsForThumbnails.firstOrNull() ?: true

        Log.d("MainViewModel", "Refreshing ${downloads.size} thumbnails (GIFs: $shouldGenerateGifs)")

        // Collect all video items that need GIF generation and set initial progress
        if (shouldGenerateGifs) {
            val videoItems = downloads.filter { history ->
                val mediaTypes = JsonUtils.jsonToList(history.mediaTypes)
                mediaTypes.isNotEmpty() && mediaTypes[0] == "VIDEO"
            }

            if (videoItems.isNotEmpty()) {
                // Set initial progress for all videos at once
                val progressMap = videoItems.associate { it.id to GifGenerationProgress.InProgress(0) }
                val currentMap = _gifGenerationProgress.value ?: emptyMap()
                _gifGenerationProgress.value = currentMap + progressMap
                Log.d("MainViewModel", "Set initial progress for ${videoItems.size} videos")

                // Small delay to ensure UI updates
                kotlinx.coroutines.delay(50)
            }
        }

        downloads.forEach { history ->
            try {
                // Get the first media item
                val mediaTypes = JsonUtils.jsonToList(history.mediaTypes)
                val localPaths = JsonUtils.jsonToList(history.localFilePaths)

                if (mediaTypes.isEmpty() || localPaths.isEmpty()) {
                    Log.w("MainViewModel", "Skipping history ${history.id}: no media")
                    return@forEach
                }

                val firstMediaType = mediaTypes[0]
                val firstLocalPath = localPaths[0]

                // Only refresh thumbnails for videos
                if (firstMediaType == "VIDEO") {
                    Log.d("MainViewModel", "Refreshing thumbnail for history ${history.id}")

                    val oldThumbnailPath = history.thumbnailPath

                    if (shouldGenerateGifs) {
                        // Generate GIF thumbnail asynchronously with progress tracking
                        // (progress state already initialized above)
                        Log.d("MainViewModel", "Starting async GIF refresh for history ${history.id}")

                        // Launch async generation (don't wait - process next item)
                        viewModelScope.launch {
                            try {
                                val gifResult = mediaDownloader.generateVideoGifThumbnail(
                                    firstLocalPath,
                                    history.tweetId
                                ) { progress ->
                                    updateGifProgress(history.id, GifGenerationProgress.InProgress(progress))
                                }

                                gifResult.onSuccess { newThumbnailPath ->
                                    Log.d("MainViewModel", "GIF refresh complete for history ${history.id}")

                                    // Delete old thumbnail AFTER new one is generated
                                    oldThumbnailPath?.let { oldPath ->
                                        val oldFile = java.io.File(oldPath)
                                        if (oldFile.exists() && oldPath != newThumbnailPath) {
                                            oldFile.delete()
                                            Log.d("MainViewModel", "Deleted old thumbnail: $oldPath")
                                        }
                                    }

                                    // Update history with new thumbnail
                                    val updatedHistory = history.copy(thumbnailPath = newThumbnailPath)
                                    repository.update(updatedHistory)
                                    Log.d("MainViewModel", "Updated thumbnail for history ${history.id}: $newThumbnailPath")

                                    // Mark as complete and remove from progress map after short delay
                                    updateGifProgress(history.id, GifGenerationProgress.Complete)
                                    kotlinx.coroutines.delay(1000)
                                    removeGifProgress(history.id)
                                }.onFailure { error ->
                                    Log.e("MainViewModel", "GIF refresh failed for history ${history.id}", error)
                                    updateGifProgress(history.id, GifGenerationProgress.Failed)
                                }
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error in async GIF refresh for history ${history.id}", e)
                                updateGifProgress(history.id, GifGenerationProgress.Failed)
                            }
                        }
                    } else {
                        // Generate static thumbnail synchronously (it's fast)
                        val staticResult = mediaDownloader.extractStaticThumbnail(
                            firstLocalPath,
                            history.tweetId
                        )

                        staticResult.onSuccess { newThumbnailPath ->
                            // Delete old thumbnail AFTER new one is generated
                            oldThumbnailPath?.let { oldPath ->
                                val oldFile = java.io.File(oldPath)
                                if (oldFile.exists() && oldPath != newThumbnailPath) {
                                    oldFile.delete()
                                    Log.d("MainViewModel", "Deleted old thumbnail: $oldPath")
                                }
                            }

                            // Update history with new thumbnail
                            val updatedHistory = history.copy(thumbnailPath = newThumbnailPath)
                            repository.update(updatedHistory)
                            Log.d("MainViewModel", "Updated static thumbnail for history ${history.id}: $newThumbnailPath")
                        }.onFailure { error ->
                            Log.e("MainViewModel", "Static thumbnail refresh failed for history ${history.id}", error)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error refreshing thumbnail for history ${history.id}", e)
            }
        }
    }

    suspend fun backupHistory(context: Context, uri: Uri) {
        val downloads = repository.allDownloads.firstOrNull() ?: emptyList()
        Log.d("MainViewModel", "Backing up ${downloads.size} history entries")

        val backupJson = JSONArray()
        downloads.forEach { history ->
            val entryJson = JSONObject().apply {
                put("tweetId", history.tweetId)
                put("tweetUrl", history.tweetUrl)
                put("authorName", history.authorName)
                put("authorUsername", history.authorUsername)
                put("text", history.text)
                put("downloadDate", history.downloadDate)
                put("thumbnailPath", history.thumbnailPath ?: "")
                put("mediaUrls", history.mediaUrls)
                put("mediaTypes", history.mediaTypes)
                put("localFilePaths", history.localFilePaths)
            }
            backupJson.put(entryJson)
        }

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(backupJson.toString().toByteArray())
        }

        Log.d("MainViewModel", "Backup completed successfully")
    }

    suspend fun restoreHistory(context: Context, uri: Uri): Int {
        val shouldGenerateGifs = preferencesManager.generateGifsForThumbnails.firstOrNull() ?: true
        Log.d("MainViewModel", "Restoring history from backup (Generate GIFs: $shouldGenerateGifs)")

        val backupContent = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            inputStream.readBytes().toString(Charsets.UTF_8)
        } ?: throw Exception("Failed to read backup file")

        val backupJson = JSONArray(backupContent)
        var restoredCount = 0

        for (i in 0 until backupJson.length()) {
            val entryJson = backupJson.getJSONObject(i)

            // Check if entry already exists by tweetId
            val existingEntry = repository.getDownloadByTweetId(entryJson.getString("tweetId"))
            if (existingEntry != null) {
                Log.d("MainViewModel", "Skipping duplicate entry: ${entryJson.getString("tweetId")}")
                continue
            }

            val history = DownloadHistory(
                tweetId = entryJson.getString("tweetId"),
                tweetUrl = entryJson.getString("tweetUrl"),
                authorName = entryJson.getString("authorName"),
                authorUsername = entryJson.getString("authorUsername"),
                text = entryJson.getString("text"),
                downloadDate = entryJson.getLong("downloadDate"),
                thumbnailPath = entryJson.getString("thumbnailPath").ifEmpty { null },
                mediaUrls = entryJson.getString("mediaUrls"),
                mediaTypes = entryJson.getString("mediaTypes"),
                localFilePaths = entryJson.getString("localFilePaths")
            )

            // Insert into database
            val historyId = repository.insert(history)
            restoredCount++

            // Check if thumbnail is missing or invalid
            val needsNewThumbnail = history.thumbnailPath == null ||
                    !java.io.File(history.thumbnailPath).exists()

            if (needsNewThumbnail) {
                Log.d("MainViewModel", "Generating missing thumbnail for restored entry ${history.tweetId}")
                generateMissingThumbnail(historyId, history, shouldGenerateGifs)
            }
        }

        Log.d("MainViewModel", "Restored $restoredCount entries")
        return restoredCount
    }

    private fun generateMissingThumbnail(historyId: Long, history: DownloadHistory, shouldGenerateGifs: Boolean) {
        viewModelScope.launch {
            try {
                val mediaTypes = JsonUtils.jsonToList(history.mediaTypes)
                val localPaths = JsonUtils.jsonToList(history.localFilePaths)

                if (mediaTypes.isEmpty() || localPaths.isEmpty()) {
                    Log.w("MainViewModel", "No media for history $historyId, skipping thumbnail generation")
                    return@launch
                }

                val firstMediaType = mediaTypes[0]
                val firstLocalPath = localPaths[0]

                // Check if local file exists
                val fileExists = if (firstLocalPath.startsWith("content://")) {
                    try {
                        getApplication<Application>().contentResolver.openInputStream(Uri.parse(firstLocalPath))?.use { true } ?: false
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    java.io.File(firstLocalPath).exists()
                }

                if (!fileExists) {
                    Log.w("MainViewModel", "Local file not found for history $historyId: $firstLocalPath")
                    return@launch
                }

                // Generate thumbnail based on media type
                if (firstMediaType == "VIDEO") {
                    if (shouldGenerateGifs) {
                        // Generate GIF asynchronously with progress
                        updateGifProgress(historyId, GifGenerationProgress.InProgress(0))

                        val gifResult = mediaDownloader.generateVideoGifThumbnail(
                            firstLocalPath,
                            history.tweetId
                        ) { progress ->
                            updateGifProgress(historyId, GifGenerationProgress.InProgress(progress))
                        }

                        gifResult.onSuccess { thumbnailPath ->
                            val updatedHistory = history.copy(thumbnailPath = thumbnailPath, id = historyId)
                            repository.update(updatedHistory)
                            Log.d("MainViewModel", "Generated GIF thumbnail for restored entry: $thumbnailPath")

                            updateGifProgress(historyId, GifGenerationProgress.Complete)
                            kotlinx.coroutines.delay(1000)
                            removeGifProgress(historyId)
                        }.onFailure { error ->
                            Log.e("MainViewModel", "Failed to generate GIF for restored entry", error)
                            updateGifProgress(historyId, GifGenerationProgress.Failed)
                        }
                    } else {
                        // Generate static thumbnail
                        val staticResult = mediaDownloader.extractStaticThumbnail(
                            firstLocalPath,
                            history.tweetId
                        )

                        staticResult.onSuccess { thumbnailPath ->
                            val updatedHistory = history.copy(thumbnailPath = thumbnailPath, id = historyId)
                            repository.update(updatedHistory)
                            Log.d("MainViewModel", "Generated static thumbnail for restored entry: $thumbnailPath")
                        }.onFailure { error ->
                            Log.e("MainViewModel", "Failed to generate static thumbnail for restored entry", error)
                        }
                    }
                } else {
                    // For images/GIFs, download thumbnail from URL if we have it
                    val mediaUrls = JsonUtils.jsonToList(history.mediaUrls)
                    if (mediaUrls.isNotEmpty()) {
                        val thumbnailResult = mediaDownloader.downloadThumbnail(mediaUrls[0], history.tweetId)
                        thumbnailResult.onSuccess { thumbnailPath ->
                            val updatedHistory = history.copy(thumbnailPath = thumbnailPath, id = historyId)
                            repository.update(updatedHistory)
                            Log.d("MainViewModel", "Downloaded thumbnail for restored entry: $thumbnailPath")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error generating thumbnail for restored entry", e)
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

sealed class LoadingState {
    object Idle : LoadingState()
    object Loading : LoadingState()
    object Success : LoadingState()
    data class Error(val message: String) : LoadingState()
}

sealed class GifGenerationProgress {
    data class InProgress(val progress: Int) : GifGenerationProgress()
    object Complete : GifGenerationProgress()
    object Failed : GifGenerationProgress()
}
