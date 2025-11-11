package com.example.twittermdl.ui

import android.content.Intent
import android.net.Uri
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
    private val onItemClick: (DownloadHistory) -> Unit
) : ListAdapter<DownloadHistory, DownloadHistoryAdapter.HistoryViewHolder>(HistoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val authorName: TextView = itemView.findViewById(R.id.authorNameText)
        private val authorUsername: TextView = itemView.findViewById(R.id.authorUsernameText)
        private val downloadDate: TextView = itemView.findViewById(R.id.downloadDateText)
        private val tweetText: TextView = itemView.findViewById(R.id.tweetTextView)
        private val redownloadButton: Button = itemView.findViewById(R.id.redownloadButton)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteButton)

        fun bind(history: DownloadHistory) {
            authorName.text = history.authorName
            authorUsername.text = history.authorUsername
            tweetText.text = history.text

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            downloadDate.text = "Downloaded: ${dateFormat.format(Date(history.downloadDate))}"

            // Load animated thumbnails for videos/GIFs
            loadAnimatedThumbnail(history)

            // Make the entire item clickable to open the tweet URL
            itemView.setOnClickListener {
                onItemClick(history)
            }

            redownloadButton.setOnClickListener {
                onRedownload(history)
            }

            deleteButton.setOnClickListener {
                onDelete(history)
            }
        }

        private fun loadAnimatedThumbnail(history: DownloadHistory) {
            try {
                // Parse media types and local file paths
                val mediaTypes = JsonUtils.jsonToList(history.mediaTypes)
                val localPaths = JsonUtils.jsonToList(history.localFilePaths)

                if (mediaTypes.isNotEmpty() && localPaths.isNotEmpty()) {
                    val firstMediaType = mediaTypes[0]
                    val firstLocalPath = localPaths[0]

                    // Handle both file paths and content URIs
                    val imageSource = if (firstLocalPath.startsWith("content://")) {
                        Uri.parse(firstLocalPath)
                    } else {
                        File(firstLocalPath)
                    }

                    when (firstMediaType) {
                        "GIF" -> {
                            // Load GIF with animation - use software rendering for compatibility
                            thumbnail.load(imageSource) {
                                crossfade(false)  // Disable crossfade for animated images
                                allowHardware(false)  // Force software rendering to support animation
                                placeholder(R.drawable.ic_placeholder)
                                error(R.drawable.ic_error)
                            }
                        }
                        "VIDEO" -> {
                            // For videos, extract frame from middle (15 seconds in)
                            thumbnail.load(imageSource) {
                                crossfade(true)
                                videoFrameMillis(15000)  // Extract frame at 15 seconds
                                placeholder(R.drawable.ic_placeholder)
                                error(R.drawable.ic_error)
                            }
                        }
                        else -> {
                            // For images, load normally
                            thumbnail.load(imageSource) {
                                crossfade(true)
                                placeholder(R.drawable.ic_placeholder)
                                error(R.drawable.ic_error)
                            }
                        }
                    }
                } else {
                    // Fallback to thumbnail path if available
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
    }
}
