package com.example.twittermdl.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadHistoryDao {
    @Query("SELECT * FROM download_history ORDER BY downloadDate DESC")
    fun getAllDownloads(): Flow<List<DownloadHistory>>

    @Query("SELECT * FROM download_history WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadHistory?

    @Query("SELECT * FROM download_history WHERE tweetId = :tweetId")
    suspend fun getDownloadByTweetId(tweetId: String): DownloadHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(downloadHistory: DownloadHistory): Long

    @Update
    suspend fun update(downloadHistory: DownloadHistory)

    @Delete
    suspend fun delete(downloadHistory: DownloadHistory)

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_history")
    suspend fun deleteAll()
}
