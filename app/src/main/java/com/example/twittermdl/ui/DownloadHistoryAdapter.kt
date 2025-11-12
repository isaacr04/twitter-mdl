package com.example.twittermdl.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
import com.example.twittermdl.R
import com.example.twittermdl.data.DownloadHistory
import com.example.twittermdl.utils.JsonUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DownloadHistoryAdapter(
    private val onRedownload: (DownloadHistory) -> Unit,
    private val onDelete: (DownloadHistory) -> Unit,
    private val onItemClick: (DownloadHistory) -> Unit,
    private val onOpenMedia: (DownloadHistory) -> Unit
) : ListAdapter<DownloadHistory, DownloadHistoryAdapter.HistoryViewHolder>(HistoryDiffCallback()) {

    private var gifProgressMap: Map<Long, GifGenerationProgress> = emptyMap()

    companion object {
        private const val PAYLOAD_PROGRESS_UPDATE = "progress_update"
    }

    fun updateGifProgress(progressMap: Map<Long, GifGenerationProgress>) {
        Log.d("HistoryAdapter", "updateGifProgress called with ${progressMap.size} entries")
        val oldMap = gifProgressMap
        gifProgressMap = progressMap

        // Only update items that have changed progress
        currentList.forEachIndexed { index, history ->
            val oldProgress = oldMap[history.id]
            val newProgress = progressMap[history.id]

            if (oldProgress != newProgress) {
                Log.d("HistoryAdapter", "Progress changed for history ${history.id}: $oldProgress -> $newProgress")
                // Use payload to only update progress, not entire item
                notifyItemChanged(index, PAYLOAD_PROGRESS_UPDATE)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            // No payload, do full bind
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Payload present, only update specific parts
            val history = getItem(position)
            payloads.forEach { payload ->
                when (payload) {
                    PAYLOAD_PROGRESS_UPDATE -> {
                        // Only update progress bar
                        holder.updateProgressOnly(history)
                    }
                    "thumbnail_changed" -> {
                        // Only reload thumbnail when thumbnailPath changes
                        holder.reloadThumbnail(history)
                    }
                }
            }
        }
    }

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val authorName: TextView = itemView.findViewById(R.id.authorNameText)
        private val authorUsername: TextView = itemView.findViewById(R.id.authorUsernameText)
        private val downloadDate: TextView = itemView.findViewById(R.id.downloadDateText)
        private val tweetText: TextView = itemView.findViewById(R.id.tweetTextView)
        private val openButton: Button = itemView.findViewById(R.id.openButton)
        private val redownloadButton: Button = itemView.findViewById(R.id.redownloadButton)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
        private val gifProgressLayout: View = itemView.findViewById(R.id.gifProgressLayout)
        private val gifProgressBar: android.widget.ProgressBar = itemView.findViewById(R.id.gifProgressBar)
        private val gifProgressText: TextView = itemView.findViewById(R.id.gifProgressText)

        fun bind(history: DownloadHistory) {
            try {
                authorName.text = history.authorName
                authorUsername.text = history.authorUsername
                tweetText.text = history.text

                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                downloadDate.text = "Downloaded: ${dateFormat.format(Date(history.downloadDate))}"

                // Load animated thumbnails for videos/GIFs
                loadAnimatedThumbnail(history)

                // Update GIF generation progress
                updateGifProgress(history)

                // Check if media files exist and show appropriate button
                // This is critical - always set button visibility explicitly
                val mediaExists = try {
                    checkMediaFilesExist(history)
                } catch (e: Exception) {
                    Log.e("HistoryAdapter", "Error checking media files for history ${history.id}: ${e.message}", e)
                    false  // If we can't check, assume files don't exist
                }

                Log.d("HistoryAdapter", "History ${history.id}: mediaExists=$mediaExists, localFilePaths=${history.localFilePaths}")

                // Always set button visibility - ensure one is visible and one is gone
                if (mediaExists) {
                    Log.d("HistoryAdapter", "Showing Open button for history ${history.id}")
                    openButton.visibility = View.VISIBLE
                    redownloadButton.visibility = View.GONE
                } else {
                    Log.d("HistoryAdapter", "Showing Redownload button for history ${history.id}")
                    openButton.visibility = View.GONE
                    redownloadButton.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e("HistoryAdapter", "Error in bind() for history ${history.id}: ${e.message}", e)
                // On error, default to showing redownload button
                openButton.visibility = View.GONE
                redownloadButton.visibility = View.VISIBLE
            }

            // Make the entire item clickable to open the tweet URL
            itemView.setOnClickListener {
                onItemClick(history)
            }

            openButton.setOnClickListener {
                onOpenMedia(history)
            }

            redownloadButton.setOnClickListener {
                onRedownload(history)
            }

            deleteButton.setOnClickListener {
                onDelete(history)
            }
        }

        private fun checkMediaFilesExist(history: DownloadHistory): Boolean {
            // Handle null or empty localFilePaths
            val localFilePathsJson = history.localFilePaths
            if (localFilePathsJson.isNullOrBlank() || localFilePathsJson == "[]") {
                Log.d("HistoryAdapter", "No local paths in JSON for history ${history.id}: '$localFilePathsJson'")
                return false
            }

            val localPaths = try {
                JsonUtils.jsonToList(localFilePathsJson)
            } catch (e: Exception) {
                Log.e("HistoryAdapter", "Error parsing localFilePaths JSON for history ${history.id}: ${e.message}")
                return false
            }

            Log.d("HistoryAdapter", "Checking files for history ${history.id}: ${localPaths.size} paths from JSON: $localFilePathsJson")

            if (localPaths.isEmpty()) {
                Log.d("HistoryAdapter", "Empty paths list for history ${history.id}")
                return false
            }

            // Check if at least one media file exists
            val result = localPaths.any { path ->
                val exists = if (path.startsWith("content://")) {
                    // For content URIs, query MediaStore to verify the file exists and is not trashed
                    try {
                        val uri = Uri.parse(path)
                        var fileExists = false

                        // Build projection - include IS_TRASHED for Android 10+ (API 29+)
                        val projection = mutableListOf(
                            android.provider.MediaStore.MediaColumns._ID,
                            android.provider.MediaStore.MediaColumns.SIZE,
                            android.provider.MediaStore.MediaColumns.DATA
                        )

                        // Add IS_TRASHED column for Android 10+ to detect trashed files
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            projection.add(android.provider.MediaStore.MediaColumns.IS_TRASHED)
                        }

                        // Query the MediaStore to check if the file exists and has valid data
                        itemView.context.contentResolver.query(
                            uri,
                            projection.toTypedArray(),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0

                                // Check if file is trashed (Android 10+)
                                val isTrashed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    val trashedIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.IS_TRASHED)
                                    if (trashedIndex >= 0) cursor.getInt(trashedIndex) == 1 else false
                                } else {
                                    false
                                }

                                // File exists if we got a result, size > 0, and NOT trashed
                                fileExists = size > 0 && !isTrashed
                                Log.d("HistoryAdapter", "Content URI $path: exists=$fileExists, size=$size bytes, trashed=$isTrashed")
                            } else {
                                Log.d("HistoryAdapter", "Content URI $path: cursor empty (file deleted)")
                            }
                        } ?: run {
                            Log.d("HistoryAdapter", "Content URI $path: query returned null")
                        }

                        fileExists
                    } catch (e: Exception) {
                        Log.d("HistoryAdapter", "Content URI $path: query error=${e.message}")
                        false
                    }
                } else {
                    // For file paths, check if file exists
                    val fileExists = File(path).exists()
                    Log.d("HistoryAdapter", "File path $path: exists=$fileExists")
                    fileExists
                }
                exists
            }

            Log.d("HistoryAdapter", "Final result for history ${history.id}: mediaExists=$result")
            return result
        }

        /**
         * Updates only the progress bar without reloading the thumbnail
         */
        fun updateProgressOnly(history: DownloadHistory) {
            updateGifProgress(history)
        }

        /**
         * Reloads only the thumbnail when thumbnailPath changes
         */
        fun reloadThumbnail(history: DownloadHistory) {
            loadAnimatedThumbnail(history)
        }

        private fun updateGifProgress(history: DownloadHistory) {
            val progress = gifProgressMap[history.id]
            Log.d("HistoryAdapter", "updateGifProgress for history ${history.id}: progress=$progress")

            when (progress) {
                is GifGenerationProgress.InProgress -> {
                    Log.d("HistoryAdapter", "Showing progress bar: ${progress.progress}%")
                    gifProgressLayout.visibility = View.VISIBLE
                    gifProgressBar.progress = progress.progress
                    gifProgressText.text = "Generating GIF... ${progress.progress}%"
                }
                is GifGenerationProgress.Complete -> {
                    Log.d("HistoryAdapter", "Showing complete state")
                    gifProgressLayout.visibility = View.VISIBLE
                    gifProgressBar.progress = 100
                    gifProgressText.text = "GIF Complete!"
                }
                is GifGenerationProgress.Failed -> {
                    Log.d("HistoryAdapter", "Hiding progress bar (failed)")
                    gifProgressLayout.visibility = View.GONE
                }
                null -> {
                    Log.d("HistoryAdapter", "Hiding progress bar (null)")
                    gifProgressLayout.visibility = View.GONE
                }
            }
        }

        private fun loadAnimatedThumbnail(history: DownloadHistory) {
            try {
                // First, try to load the thumbnail path (which may be a GIF for videos)
                if (history.thumbnailPath != null) {
                    val path = history.thumbnailPath
                    Log.d("HistoryAdapter", "Attempting to load thumbnail from path: $path")
                    val thumbFile = File(path)

                    if (thumbFile.exists()) {
                        Log.d("HistoryAdapter", "Thumbnail file exists, size: ${thumbFile.length()} bytes")
                        // Check if the thumbnail is a GIF
                        val isGif = path.endsWith(".gif", ignoreCase = true)
                        thumbnail.load(thumbFile) {
                            if (isGif) {
                                // Load animated GIF thumbnail
                                crossfade(false)  // Disable crossfade for animated images
                                allowHardware(false)  // Force software rendering to support animation
                            } else {
                                crossfade(true)
                            }
                            placeholder(R.drawable.ic_placeholder)
                            error(R.drawable.ic_error)
                        }
                        return  // Successfully loaded thumbnail, exit
                    } else {
                        Log.w("HistoryAdapter", "Thumbnail file does NOT exist: $path")
                    }
                } else {
                    Log.d("HistoryAdapter", "No thumbnailPath for history ID ${history.id}, will try fallback")
                }

                // Fallback: Parse media types and local file paths
                val mediaTypes = JsonUtils.jsonToList(history.mediaTypes)
                val localPaths = JsonUtils.jsonToList(history.localFilePaths)

                Log.d("HistoryAdapter", "Fallback: mediaTypes=${mediaTypes.size}, localPaths=${localPaths.size}")

                if (mediaTypes.isNotEmpty() && localPaths.isNotEmpty()) {
                    val firstMediaType = mediaTypes[0]
                    val firstLocalPath = localPaths[0]

                    Log.d("HistoryAdapter", "Loading fallback for type: $firstMediaType, path: $firstLocalPath")

                    // Handle both file paths and content URIs
                    val imageSource = if (firstLocalPath.startsWith("content://")) {
                        Uri.parse(firstLocalPath)
                    } else {
                        File(firstLocalPath)
                    }

                    when (firstMediaType) {
                        "GIF" -> {
                            // Load GIF with animation - use software rendering for compatibility
                            Log.d("HistoryAdapter", "Loading GIF from media file")
                            thumbnail.load(imageSource) {
                                crossfade(false)  // Disable crossfade for animated images
                                allowHardware(false)  // Force software rendering to support animation
                                placeholder(R.drawable.ic_placeholder)
                                error(R.drawable.ic_error)
                                listener(
                                    onError = { _, result ->
                                        Log.e("HistoryAdapter", "Failed to load GIF: ${result.throwable.message}", result.throwable)
                                    }
                                )
                            }
                        }
                        "VIDEO" -> {
                            // For videos without GIF thumbnail, extract frame from video file
                            Log.d("HistoryAdapter", "Extracting video frame from: $firstLocalPath")
                            thumbnail.load(imageSource) {
                                crossfade(true)
                                videoFrameMillis(0)  // Extract frame from start instead of 15s (more reliable)
                                placeholder(R.drawable.ic_placeholder)
                                error(R.drawable.ic_error)
                                listener(
                                    onError = { _, result ->
                                        Log.e("HistoryAdapter", "Failed to extract video frame: ${result.throwable.message}", result.throwable)
                                    },
                                    onSuccess = { _, _ ->
                                        Log.d("HistoryAdapter", "Successfully extracted video frame")
                                    }
                                )
                            }
                        }
                        else -> {
                            // For images, load normally
                            Log.d("HistoryAdapter", "Loading image from media file")
                            thumbnail.load(imageSource) {
                                crossfade(true)
                                placeholder(R.drawable.ic_placeholder)
                                error(R.drawable.ic_error)
                                listener(
                                    onError = { _, result ->
                                        Log.e("HistoryAdapter", "Failed to load image: ${result.throwable.message}", result.throwable)
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HistoryAdapter", "Error loading thumbnail: ${e.message}", e)
                // Fallback to static thumbnail
                history.thumbnailPath?.let { path ->
                    val thumbFile = File(path)
                    if (thumbFile.exists()) {
                        thumbnail.load(thumbFile) {
                            crossfade(true)
                            placeholder(R.drawable.ic_placeholder)
                            error(R.drawable.ic_error)
                        }
                    }
                }
            }
        }
    }

    class HistoryDiffCallback : DiffUtil.ItemCallback<DownloadHistory>() {
        override fun areItemsTheSame(oldItem: DownloadHistory, newItem: DownloadHistory): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadHistory, newItem: DownloadHistory): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: DownloadHistory, newItem: DownloadHistory): Any? {
            // If only thumbnailPath changed, return a payload to trigger thumbnail reload
            return if (oldItem.copy(thumbnailPath = newItem.thumbnailPath) == newItem &&
                       oldItem.thumbnailPath != newItem.thumbnailPath) {
                "thumbnail_changed"
            } else {
                null
            }
        }
    }
}
