package com.example.twittermdl.ui

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
import com.example.twittermdl.R
import com.example.twittermdl.data.DownloadHistory
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class DownloadHistoryAdapter(
    private val onRedownload: (DownloadHistory) -> Unit,
    private val onDelete: (DownloadHistory) -> Unit
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

            history.thumbnailPath?.let { path ->
                if (File(path).exists()) {
                    thumbnail.load(File(path)) {
                        crossfade(true)
                        placeholder(R.drawable.ic_placeholder)
                        error(R.drawable.ic_error)
                    }
                }
            }

            redownloadButton.setOnClickListener {
                onRedownload(history)
            }

            deleteButton.setOnClickListener {
                onDelete(history)
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
