package com.example.twittermdl.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.twittermdl.data.*
import com.example.twittermdl.network.MediaDownloader
import com.example.twittermdl.network.TwitterApiService
import com.example.twittermdl.repository.DownloadRepository
import com.example.twittermdl.utils.JsonUtils
import com.example.twittermdl.utils.PreferencesManager
import com.example.twittermdl.worker.GifGenerationWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = DownloadRepository(database.downloadHistoryDao())
    private val twitterApiService = TwitterApiService()
    private val mediaDownloader = MediaDownloader(application)
    private val preferencesManager = PreferencesManager(application)
    private val workManager = WorkManager.getInstance(application)

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

    // Track WorkManager work IDs for each history ID
    private val gifWorkIds = mutableMapOf<Long, UUID>()

    // Track WorkManager LiveData instances for cleanup
    private val workInfoObservers = mutableMapOf<UUID, androidx.lifecycle.Observer<WorkInfo>>()

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
                val totalMediaCount = selectedMedia.size
                val createdHistoryIds = mutableListOf<Long>()

                selectedMedia.forEachIndexed { index, media ->
                    Log.d("MainViewModel", "Downloading media ${index + 1}/$totalMediaCount: ${media.type}")

                    val result = mediaDownloader.downloadMedia(
                        media,
                        tweetData.tweetId
                    ) { progress ->
                        _downloadProgress.postValue(
                            ((index.toFloat() / totalMediaCount) * 100 +
                            (progress.toFloat() / totalMediaCount)).toInt()
                        )
                    }

                    result.onSuccess { path ->
                        Log.d("MainViewModel", "Downloaded successfully: $path")

                        // Generate thumbnail for this specific media item
                        var thumbnailPath: String? = null
                        var videoPath: String? = null

                        try {
                            if (media.type == MediaType.VIDEO) {
                                // Save video path for async GIF generation
                                videoPath = path

                                // Extract static thumbnail from the video
                                Log.d("MainViewModel", "Extracting static thumbnail from video (media $index)")
                                val thumbResult = mediaDownloader.extractStaticThumbnail(path, "${tweetData.tweetId}_$index")
                                thumbResult.onSuccess {
                                    thumbnailPath = it
                                    Log.d("MainViewModel", "Static thumbnail extracted: $it")
                                }.onFailure { error ->
                                    Log.e("MainViewModel", "Static thumbnail extraction failed: ${error.message}", error)
                                }
                            } else {
                                // For images and GIFs, use the downloaded file as thumbnail
                                val thumbUrl = media.thumbnailUrl ?: media.url
                                Log.d("MainViewModel", "Downloading thumbnail for ${media.type} (media $index): $thumbUrl")

                                val thumbResult = mediaDownloader.downloadThumbnail(thumbUrl, "${tweetData.tweetId}_$index")
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

                        // Create individual history entry for this media item
                        val downloadHistory = DownloadHistory(
                            tweetId = tweetData.tweetId,
                            tweetUrl = tweetData.tweetUrl,
                            authorName = tweetData.authorName,
                            authorUsername = tweetData.authorUsername,
                            text = tweetData.text,
                            downloadDate = System.currentTimeMillis(),
                            thumbnailPath = thumbnailPath,
                            mediaUrl = media.url,
                            mediaType = media.type.name,
                            localFilePath = path,
                            mediaIndex = index,
                            totalMediaCount = totalMediaCount
                        )

                        val historyId = repository.insert(downloadHistory)
                        createdHistoryIds.add(historyId)
                        Log.d("MainViewModel", "Created history entry ID: $historyId for media $index")

                        // Generate GIF asynchronously if this is a video and GIF generation is enabled
                        if (videoPath != null) {
                            val shouldGenerateGifs = preferencesManager.generateGifsForThumbnails.firstOrNull() ?: true
                            if (shouldGenerateGifs) {
                                Log.d("MainViewModel", "Launching async GIF generation for ID: $historyId")
                                // Initialize progress state immediately
                                val currentMap = _gifGenerationProgress.value ?: emptyMap()
                                _gifGenerationProgress.value = currentMap + (historyId to GifGenerationProgress.InProgress(0))
                                launchAsyncGifGeneration(historyId, videoPath, "${tweetData.tweetId}_$index")
                            } else {
                                Log.d("MainViewModel", "GIF generation disabled, keeping static thumbnail")
                            }
                        }
                    }.onFailure { error ->
                        Log.e("MainViewModel", "Download failed: ${error.message}", error)
                        _errorMessage.postValue("Failed to download media ${index + 1}: ${error.message}")
                    }
                }

                _loadingState.value = LoadingState.Success
                _downloadProgress.value = 100

                Log.d("MainViewModel", "Created ${createdHistoryIds.size} individual history entries")

            } catch (e: Exception) {
                _errorMessage.value = e.message
                _loadingState.value = LoadingState.Error(e.message ?: "Download failed")
            }
        }
    }

    /**
     * Generates GIF thumbnail asynchronously using WorkManager for guaranteed background execution.
     * This ensures GIF generation continues even when the app is in the background.
     */
    private fun launchAsyncGifGeneration(historyId: Long, videoPath: String, tweetId: String) {
        Log.d("MainViewModel", "Scheduling GIF generation work for history ID: $historyId")

        // Create input data for the worker
        val inputData = Data.Builder()
            .putLong(GifGenerationWorker.KEY_HISTORY_ID, historyId)
            .putString(GifGenerationWorker.KEY_VIDEO_PATH, videoPath)
            .putString(GifGenerationWorker.KEY_TWEET_ID, tweetId)
            .build()

        // Create work request with constraints
        val workRequest = OneTimeWorkRequestBuilder<GifGenerationWorker>()
            .setInputData(inputData)
            .addTag("gif_generation_$historyId")
            .build()

        // Store the work ID for this history entry
        gifWorkIds[historyId] = workRequest.id

        // Enqueue the work
        workManager.enqueue(workRequest)

        // Create and store observer for cleanup
        val observer = androidx.lifecycle.Observer<WorkInfo> { workInfo ->
            if (workInfo != null) {
                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING -> {
                        // Extract progress from work data
                        val progress = workInfo.progress.getInt(GifGenerationWorker.KEY_PROGRESS, 0)
                        updateGifProgress(historyId, GifGenerationProgress.InProgress(progress))
                        Log.d("MainViewModel", "GIF generation progress for $historyId: $progress%")
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        Log.d("MainViewModel", "GIF generation completed for history ID: $historyId")
                        updateGifProgress(historyId, GifGenerationProgress.Complete)
                        // Remove from progress map and cleanup after short delay
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(1000)
                            removeGifProgress(historyId)
                            gifWorkIds.remove(historyId)
                            // Remove observer
                            workInfoObservers.remove(workRequest.id)?.let { obs ->
                                workManager.getWorkInfoByIdLiveData(workRequest.id).removeObserver(obs)
                            }
                        }
                    }
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED -> {
                        val errorMessage = workInfo.outputData.getString(GifGenerationWorker.KEY_ERROR_MESSAGE)
                        Log.w("MainViewModel", "GIF generation failed for history ID $historyId: $errorMessage")
                        updateGifProgress(historyId, GifGenerationProgress.Failed)
                        gifWorkIds.remove(historyId)
                        // Remove observer
                        workInfoObservers.remove(workRequest.id)?.let { obs ->
                            workManager.getWorkInfoByIdLiveData(workRequest.id).removeObserver(obs)
                        }
                    }
                    WorkInfo.State.BLOCKED -> {
                        Log.d("MainViewModel", "GIF generation blocked for history ID: $historyId")
                    }
                }
            }
        }

        // Store observer and start observing
        workInfoObservers[workRequest.id] = observer
        workManager.getWorkInfoByIdLiveData(workRequest.id).observeForever(observer)

        // Set initial progress immediately
        updateGifProgress(historyId, GifGenerationProgress.InProgress(0))
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
                _loadingState.value = LoadingState.Loading
                Log.d("MainViewModel", "Redownloading media for history ID: ${downloadHistory.id}")

                // Create media item from the single entry
                val mediaItem = MediaItem(
                    url = downloadHistory.mediaUrl,
                    type = MediaType.valueOf(downloadHistory.mediaType),
                    thumbnailUrl = null
                )

                // Download the media
                val result = mediaDownloader.downloadMedia(
                    mediaItem,
                    downloadHistory.tweetId
                ) { progress ->
                    _downloadProgress.postValue(progress)
                }

                result.onSuccess { path ->
                    Log.d("MainViewModel", "Redownloaded successfully: $path")

                    // Generate new thumbnail
                    var thumbnailPath: String? = null
                    var videoPath: String? = null

                    try {
                        if (mediaItem.type == MediaType.VIDEO) {
                            videoPath = path
                            // Extract static thumbnail
                            val thumbResult = mediaDownloader.extractStaticThumbnail(
                                path,
                                "${downloadHistory.tweetId}_${downloadHistory.mediaIndex}"
                            )
                            thumbResult.onSuccess {
                                thumbnailPath = it
                                Log.d("MainViewModel", "Static thumbnail extracted: $it")
                            }.onFailure { error ->
                                Log.e("MainViewModel", "Static thumbnail extraction failed: ${error.message}", error)
                            }
                        } else {
                            // For images and GIFs, download thumbnail
                            val thumbUrl = mediaItem.thumbnailUrl ?: mediaItem.url
                            val thumbResult = mediaDownloader.downloadThumbnail(
                                thumbUrl,
                                "${downloadHistory.tweetId}_${downloadHistory.mediaIndex}"
                            )
                            thumbResult.onSuccess {
                                thumbnailPath = it
                                Log.d("MainViewModel", "Thumbnail downloaded: $it")
                            }.onFailure { error ->
                                Log.e("MainViewModel", "Thumbnail download failed: ${error.message}", error)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Thumbnail generation failed", e)
                    }

                    // Update the existing history entry
                    val updatedHistory = downloadHistory.copy(
                        downloadDate = System.currentTimeMillis(),
                        thumbnailPath = thumbnailPath,
                        localFilePath = path
                    )
                    repository.update(updatedHistory)
                    Log.d("MainViewModel", "Updated history entry ID: ${downloadHistory.id}")

                    // Generate GIF if this is a video
                    if (videoPath != null) {
                        val shouldGenerateGifs = preferencesManager.generateGifsForThumbnails.firstOrNull() ?: true
                        if (shouldGenerateGifs) {
                            val currentMap = _gifGenerationProgress.value ?: emptyMap()
                            _gifGenerationProgress.value = currentMap + (downloadHistory.id to GifGenerationProgress.InProgress(0))
                            launchAsyncGifGeneration(
                                downloadHistory.id,
                                videoPath,
                                "${downloadHistory.tweetId}_${downloadHistory.mediaIndex}"
                            )
                        }
                    }

                    _loadingState.value = LoadingState.Success
                    _downloadProgress.value = 100
                }.onFailure { error ->
                    Log.e("MainViewModel", "Redownload failed: ${error.message}", error)
                    _errorMessage.value = "Redownload failed: ${error.message}"
                    _loadingState.value = LoadingState.Error(error.message ?: "Redownload failed")
                }
            } catch (e: Exception) {
                _errorMessage.value = "Redownload failed: ${e.message}"
                _loadingState.value = LoadingState.Error(e.message ?: "Redownload failed")
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

            // Delete downloaded media file
            val path = downloadHistory.localFilePath
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
                history.mediaType == "VIDEO"
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
                // Only refresh thumbnails for videos
                if (history.mediaType == "VIDEO") {
                    Log.d("MainViewModel", "Refreshing thumbnail for history ${history.id}")

                    val oldThumbnailPath = history.thumbnailPath

                    if (shouldGenerateGifs) {
                        // Generate GIF thumbnail asynchronously using WorkManager
                        // (progress state already initialized above)
                        Log.d("MainViewModel", "Starting async GIF refresh for history ${history.id}")

                        // Delete old thumbnail after new one is generated (handled by worker)
                        // Note: Old thumbnail cleanup is now handled separately since WorkManager completes asynchronously
                        oldThumbnailPath?.let { oldPath ->
                            viewModelScope.launch {
                                // Wait a bit to ensure new thumbnail is generated, then clean up old one
                                kotlinx.coroutines.delay(2000)
                                val oldFile = java.io.File(oldPath)
                                if (oldFile.exists()) {
                                    // Check if it's still the current thumbnail (user might have changed it)
                                    val currentHistory = repository.getDownloadById(history.id)
                                    if (currentHistory?.thumbnailPath != oldPath) {
                                        oldFile.delete()
                                        Log.d("MainViewModel", "Deleted old thumbnail: $oldPath")
                                    }
                                }
                            }
                        }

                        // Use WorkManager for guaranteed background execution
                        launchAsyncGifGeneration(history.id, history.localFilePath, "${history.tweetId}_${history.mediaIndex}")
                    } else {
                        // Generate static thumbnail synchronously (it's fast)
                        val staticResult = mediaDownloader.extractStaticThumbnail(
                            history.localFilePath,
                            "${history.tweetId}_${history.mediaIndex}"
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
                put("mediaUrl", history.mediaUrl)
                put("mediaType", history.mediaType)
                put("localFilePath", history.localFilePath)
                put("mediaIndex", history.mediaIndex)
                put("totalMediaCount", history.totalMediaCount)
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

            // Detect format: check if new format fields exist
            val isNewFormat = entryJson.has("mediaUrl")

            if (isNewFormat) {
                // New format: single media per entry
                val tweetId = entryJson.getString("tweetId")
                val mediaIndex = entryJson.optInt("mediaIndex", 0)

                // Check if entry already exists by tweetId AND mediaIndex
                // (with new format, multiple entries can have same tweetId but different mediaIndex)
                val allDownloads = repository.allDownloads.firstOrNull() ?: emptyList()
                val existingEntry = allDownloads.find {
                    it.tweetId == tweetId && it.mediaIndex == mediaIndex
                }

                if (existingEntry != null) {
                    Log.d("MainViewModel", "Skipping duplicate entry: $tweetId (index $mediaIndex)")
                    continue
                }

                val history = DownloadHistory(
                    tweetId = tweetId,
                    tweetUrl = entryJson.getString("tweetUrl"),
                    authorName = entryJson.getString("authorName"),
                    authorUsername = entryJson.getString("authorUsername"),
                    text = entryJson.getString("text"),
                    downloadDate = entryJson.getLong("downloadDate"),
                    thumbnailPath = entryJson.getString("thumbnailPath").ifEmpty { null },
                    mediaUrl = entryJson.getString("mediaUrl"),
                    mediaType = entryJson.getString("mediaType"),
                    localFilePath = entryJson.getString("localFilePath"),
                    mediaIndex = mediaIndex,
                    totalMediaCount = entryJson.optInt("totalMediaCount", 1)
                )

                // Insert into database
                val historyId = repository.insert(history)
                restoredCount++

                // Check if thumbnail is missing or invalid
                val needsNewThumbnail = history.thumbnailPath == null ||
                        !java.io.File(history.thumbnailPath).exists()

                if (needsNewThumbnail) {
                    Log.d("MainViewModel", "Generating missing thumbnail for restored entry $tweetId (index $mediaIndex)")
                    generateMissingThumbnail(historyId, history, shouldGenerateGifs)
                }
            } else {
                // Old format: multiple media in JSON arrays - convert to individual entries
                val tweetId = entryJson.getString("tweetId")
                val existingEntry = repository.getDownloadByTweetId(tweetId)
                if (existingEntry != null) {
                    Log.d("MainViewModel", "Skipping duplicate tweet: $tweetId")
                    continue
                }

                try {
                    val mediaUrls = JsonUtils.jsonToList(entryJson.getString("mediaUrls"))
                    val mediaTypes = JsonUtils.jsonToList(entryJson.getString("mediaTypes"))
                    val localPaths = JsonUtils.jsonToList(entryJson.getString("localFilePaths"))
                    val totalMediaCount = mediaUrls.size

                    // Create individual entry for each media item
                    mediaUrls.forEachIndexed { index, mediaUrl ->
                        if (index < mediaTypes.size && index < localPaths.size) {
                            val history = DownloadHistory(
                                tweetId = tweetId,
                                tweetUrl = entryJson.getString("tweetUrl"),
                                authorName = entryJson.getString("authorName"),
                                authorUsername = entryJson.getString("authorUsername"),
                                text = entryJson.getString("text"),
                                downloadDate = entryJson.getLong("downloadDate"),
                                thumbnailPath = if (index == 0) entryJson.getString("thumbnailPath").ifEmpty { null } else null,
                                mediaUrl = mediaUrl,
                                mediaType = mediaTypes[index],
                                localFilePath = localPaths[index],
                                mediaIndex = index,
                                totalMediaCount = totalMediaCount
                            )

                            val historyId = repository.insert(history)
                            restoredCount++

                            // Generate thumbnail if missing (only for first media or if thumbnail is null)
                            val needsNewThumbnail = history.thumbnailPath == null ||
                                    !java.io.File(history.thumbnailPath ?: "").exists()

                            if (needsNewThumbnail) {
                                Log.d("MainViewModel", "Generating missing thumbnail for restored entry $tweetId (index $index)")
                                generateMissingThumbnail(historyId, history, shouldGenerateGifs)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to parse old format entry: $tweetId", e)
                }
            }
        }

        Log.d("MainViewModel", "Restored $restoredCount entries")
        return restoredCount
    }

    private fun generateMissingThumbnail(historyId: Long, history: DownloadHistory, shouldGenerateGifs: Boolean) {
        viewModelScope.launch {
            try {
                // Check if local file exists
                val localPath = history.localFilePath
                val fileExists = if (localPath.startsWith("content://")) {
                    try {
                        getApplication<Application>().contentResolver.openInputStream(Uri.parse(localPath))?.use { true } ?: false
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    java.io.File(localPath).exists()
                }

                if (!fileExists) {
                    Log.w("MainViewModel", "Local file not found for history $historyId: $localPath")
                    return@launch
                }

                // Generate thumbnail based on media type
                if (history.mediaType == "VIDEO") {
                    if (shouldGenerateGifs) {
                        // Generate GIF asynchronously using WorkManager for guaranteed background execution
                        Log.d("MainViewModel", "Launching WorkManager GIF generation for restored entry $historyId")
                        launchAsyncGifGeneration(historyId, localPath, "${history.tweetId}_${history.mediaIndex}")
                    } else {
                        // Generate static thumbnail
                        val staticResult = mediaDownloader.extractStaticThumbnail(
                            localPath,
                            "${history.tweetId}_${history.mediaIndex}"
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
                    if (history.mediaUrl.isNotEmpty()) {
                        val thumbnailResult = mediaDownloader.downloadThumbnail(
                            history.mediaUrl,
                            "${history.tweetId}_${history.mediaIndex}"
                        )
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

    override fun onCleared() {
        super.onCleared()
        // Clean up all WorkManager observers to prevent memory leaks
        workInfoObservers.forEach { (workId, observer) ->
            workManager.getWorkInfoByIdLiveData(workId).removeObserver(observer)
        }
        workInfoObservers.clear()
        gifWorkIds.clear()
        Log.d("MainViewModel", "Cleaned up WorkManager observers")
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
