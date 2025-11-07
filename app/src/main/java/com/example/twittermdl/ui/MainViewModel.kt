package com.example.twittermdl.ui

import android.app.Application
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = DownloadRepository(database.downloadHistoryDao())
    private val twitterApiService = TwitterApiService()
    private val mediaDownloader = MediaDownloader(application)
    private val preferencesManager = PreferencesManager(application)

    val allDownloads: Flow<List<DownloadHistory>> = repository.allDownloads
    val userCredentials: Flow<UserCredentials?> = preferencesManager.userCredentials
    val isLoggedIn: Flow<Boolean> = preferencesManager.isLoggedIn

    private val _tweetData = MutableLiveData<TweetData?>()
    val tweetData: LiveData<TweetData?> = _tweetData

    private val _loadingState = MutableLiveData<LoadingState>()
    val loadingState: LiveData<LoadingState> = _loadingState

    private val _downloadProgress = MutableLiveData<Int>()
    val downloadProgress: LiveData<Int> = _downloadProgress

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

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

    fun downloadSelectedMedia(tweetData: TweetData, selectedMedia: List<MediaItem>) {
        viewModelScope.launch {
            _loadingState.value = LoadingState.Loading

            try {
                val downloadedPaths = mutableListOf<String>()
                val mediaUrls = mutableListOf<String>()
                val mediaTypes = mutableListOf<String>()

                var thumbnailPath: String? = null

                selectedMedia.forEachIndexed { index, media ->
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
                        downloadedPaths.add(path)
                        mediaUrls.add(media.url)
                        mediaTypes.add(media.type.name)

                        // Use first media as thumbnail
                        if (thumbnailPath == null) {
                            val thumbUrl = media.thumbnailUrl ?: media.url
                            mediaDownloader.downloadThumbnail(thumbUrl, tweetData.tweetId)
                                .onSuccess { thumbnailPath = it }
                        }
                    }.onFailure { error ->
                        _errorMessage.postValue("Failed to download media: ${error.message}")
                    }
                }

                // Save to history
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

                _loadingState.value = LoadingState.Success
                _downloadProgress.value = 100
            } catch (e: Exception) {
                _errorMessage.value = e.message
                _loadingState.value = LoadingState.Error(e.message ?: "Download failed")
            }
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

                downloadSelectedMedia(tweetData, mediaItems)
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
            repository.delete(downloadHistory)
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
