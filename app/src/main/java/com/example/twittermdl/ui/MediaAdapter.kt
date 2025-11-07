package com.example.twittermdl.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.twittermdl.R
import com.example.twittermdl.data.MediaItem

class MediaAdapter : ListAdapter<MediaItem, MediaAdapter.MediaViewHolder>(MediaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun getSelectedMedia(): List<MediaItem> {
        return currentList.filter { it.isSelected }
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.mediaThumbnail)
        private val typeText: TextView = itemView.findViewById(R.id.mediaTypeText)
        private val urlText: TextView = itemView.findViewById(R.id.mediaUrlText)
        private val checkbox: CheckBox = itemView.findViewById(R.id.mediaCheckbox)

        fun bind(mediaItem: MediaItem) {
            typeText.text = mediaItem.type.name
            urlText.text = mediaItem.url

            val thumbnailUrl = mediaItem.thumbnailUrl ?: mediaItem.url
            thumbnail.load(thumbnailUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_error)
            }

            checkbox.isChecked = mediaItem.isSelected
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                mediaItem.isSelected = isChecked
            }

            itemView.setOnClickListener {
                checkbox.isChecked = !checkbox.isChecked
            }
        }
    }

    class MediaDiffCallback : DiffUtil.ItemCallback<MediaItem>() {
        override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem.url == newItem.url
        }

        override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
            return oldItem == newItem
        }
    }
}
