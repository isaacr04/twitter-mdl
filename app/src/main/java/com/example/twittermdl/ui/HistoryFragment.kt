package com.example.twittermdl.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twittermdl.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var historyAdapter: DownloadHistoryAdapter
    private var currentDownloads: List<com.example.twittermdl.data.DownloadHistory> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        historyAdapter = DownloadHistoryAdapter(
            onRedownload = { history ->
                // Fetch fresh tweet data to get video quality variants
                lifecycleScope.launch {
                    try {
                        val result = viewModel.fetchTweetDataForRedownload(history.tweetUrl)
                        result.onSuccess { tweetData ->
                            // Check if there are videos with quality variants
                            val videoWithVariants = tweetData.mediaItems.find {
                                it.type == com.example.twittermdl.data.MediaType.VIDEO &&
                                !it.videoVariants.isNullOrEmpty()
                            }

                            if (videoWithVariants != null && !videoWithVariants.videoVariants.isNullOrEmpty()) {
                                // Show quality selection dialog
                                showRedownloadQualityDialog(history, tweetData, videoWithVariants)
                            } else {
                                // No quality options, proceed with redownload
                                viewModel.redownloadMedia(history)
                                Toast.makeText(
                                    requireContext(),
                                    "Redownloading media...",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }.onFailure { _ ->
                            // Fallback to old redownload method if fetching fails
                            Toast.makeText(
                                requireContext(),
                                "Could not fetch quality options, using default",
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.redownloadMedia(history)
                        }
                    } catch (e: Exception) {
                        viewModel.redownloadMedia(history)
                    }
                }
            },
            onDelete = { history ->
                viewModel.deleteDownload(history)
                Toast.makeText(
                    requireContext(),
                    "Download deleted",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onItemClick = { history ->
                // Open the tweet URL in browser
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(history.tweetUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        "Unable to open tweet URL",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onOpenMedia = { history ->
                openMediaFiles(history)
            }
        )

        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            // Force rebind of all items to recheck file existence
            refreshList()
        }
    }

    private fun refreshList() {
        // Resubmit the current list to force all items to rebind
        // This will trigger checkMediaFilesExist() for each item
        if (currentDownloads.isNotEmpty()) {
            historyAdapter.submitList(null) // Clear first
            historyAdapter.submitList(currentDownloads) // Then resubmit
        }

        // Hide the refresh spinner
        binding.swipeRefreshLayout.isRefreshing = false

        Toast.makeText(
            requireContext(),
            "Refreshed history",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showRedownloadQualityDialog(
        history: com.example.twittermdl.data.DownloadHistory,
        tweetData: com.example.twittermdl.data.TweetData,
        videoItem: com.example.twittermdl.data.MediaItem
    ) {
        val dialog = VideoQualityDialog.newInstance(videoItem.videoVariants!!) { selectedVariant ->
            // Create updated media items with selected quality
            val updatedMediaItems = tweetData.mediaItems.map { media ->
                if (media == videoItem) {
                    media.copy(url = selectedVariant.url)
                } else {
                    media
                }
            }

            // Update tweet data with new media items
            val updatedTweetData = tweetData.copy(mediaItems = updatedMediaItems)

            // Download with the selected quality
            viewModel.downloadSelectedMedia(
                updatedTweetData,
                updatedMediaItems,
                existingHistoryId = history.id
            )

            Toast.makeText(
                requireContext(),
                "Redownloading with selected quality...",
                Toast.LENGTH_SHORT
            ).show()
        }

        dialog.show(childFragmentManager, "VideoQualityDialog")
    }

    private fun openMediaFiles(history: com.example.twittermdl.data.DownloadHistory) {
        val localPath = history.localFilePath
        val mediaType = history.mediaType

        if (localPath.isBlank()) {
            Toast.makeText(requireContext(), "No media file found", Toast.LENGTH_SHORT).show()
            return
        }

        // Open the media file
        val mimeType = when (mediaType) {
            "VIDEO" -> "video/*"
            "GIF" -> "image/gif"
            "IMAGE" -> "image/*"
            "AUDIO" -> "audio/*"
            else -> "*/*"
        }

        try {
            val uri = if (localPath.startsWith("content://")) {
                Uri.parse(localPath)
            } else {
                Uri.parse("file://$localPath")
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Unable to open media: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.allDownloads.collect { downloads ->
                // Store current downloads for refresh functionality
                currentDownloads = downloads

                if (downloads.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.historyRecyclerView.visibility = View.GONE
                    binding.swipeRefreshLayout.isEnabled = false // Disable pull-to-refresh when empty
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.historyRecyclerView.visibility = View.VISIBLE
                    binding.swipeRefreshLayout.isEnabled = true // Enable pull-to-refresh
                    historyAdapter.submitList(downloads)
                }
            }
        }

        // Observe GIF generation progress
        viewModel.gifGenerationProgress.observe(viewLifecycleOwner) { progressMap ->
            historyAdapter.updateGifProgress(progressMap)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
