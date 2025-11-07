package com.example.twittermdl.repository

import com.example.twittermdl.data.DownloadHistory
import com.example.twittermdl.data.DownloadHistoryDao
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val downloadHistoryDao: DownloadHistoryDao) {

    val allDownloads: Flow<List<DownloadHistory>> = downloadHistoryDao.getAllDownloads()

    suspend fun insert(downloadHistory: DownloadHistory): Long {
        return downloadHistoryDao.insert(downloadHistory)
    }

    suspend fun update(downloadHistory: DownloadHistory) {
        downloadHistoryDao.update(downloadHistory)
    }

    suspend fun delete(downloadHistory: DownloadHistory) {
        downloadHistoryDao.delete(downloadHistory)
    }

    suspend fun deleteById(id: Long) {
        downloadHistoryDao.deleteById(id)
    }

    suspend fun getDownloadById(id: Long): DownloadHistory? {
        return downloadHistoryDao.getDownloadById(id)
    }

    suspend fun getDownloadByTweetId(tweetId: String): DownloadHistory? {
        return downloadHistoryDao.getDownloadByTweetId(tweetId)
    }
}
