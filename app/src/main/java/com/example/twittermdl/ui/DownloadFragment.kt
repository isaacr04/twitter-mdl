package com.example.twittermdl.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twittermdl.databinding.FragmentDownloadBinding
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    private var _binding: FragmentDownloadBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var mediaAdapter: MediaAdapter
    private var downloadInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        checkClipboardForTwitterUrl()
    }

    override fun onResume() {
        super.onResume()
        // Check clipboard again when fragment becomes visible
        checkClipboardForTwitterUrl()
    }

    private fun checkClipboardForTwitterUrl() {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip

            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString() ?: return

                // Check if it's a Twitter/X URL and auto-populate (always replace existing text)
                if (isTwitterUrl(text)) {
                    binding.urlInput.setText(text)
                    Toast.makeText(
                        requireContext(),
                        "Twitter URL detected from clipboard",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            // Silently fail - clipboard access is optional
            android.util.Log.d("DownloadFragment", "Failed to read clipboard: ${e.message}")
        }
    }

    private fun isTwitterUrl(url: String): Boolean {
        val regex = """(?:https?://)?(?:www\.)?(?:twitter\.com|x\.com)/\w+/status/\d+""".toRegex()
        return regex.containsMatchIn(url)
    }

    private fun setupRecyclerView() {
        mediaAdapter = MediaAdapter()
        binding.mediaRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mediaAdapter
        }
    }

    private fun setupListeners() {
        binding.fetchButton.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                viewModel.fetchTweetData(url)
            } else {
                Toast.makeText(requireContext(), "Please enter a URL", Toast.LENGTH_SHORT).show()
            }
        }

        binding.downloadButton.setOnClickListener {
            val tweetData = viewModel.tweetData.value
            if (tweetData != null) {
                val selectedMedia = mediaAdapter.getSelectedMedia()
                if (selectedMedia.isNotEmpty()) {
                    // Check if any selected media is a video with quality variants
                    val videoWithVariants = selectedMedia.find {
                        it.type == com.example.twittermdl.data.MediaType.VIDEO &&
                        !it.videoVariants.isNullOrEmpty()
                    }

                    if (videoWithVariants != null && !videoWithVariants.videoVariants.isNullOrEmpty()) {
                        // Show quality selection dialog
                        showQualitySelectionDialog(tweetData, selectedMedia, videoWithVariants)
                    } else {
                        // No quality options available, download directly
                        downloadInProgress = true
                        viewModel.downloadSelectedMedia(tweetData, selectedMedia)
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Please select at least one media item",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showQualitySelectionDialog(
        tweetData: com.example.twittermdl.data.TweetData,
        selectedMedia: List<com.example.twittermdl.data.MediaItem>,
        videoItem: com.example.twittermdl.data.MediaItem
    ) {
        val dialog = VideoQualityDialog.newInstance(videoItem.videoVariants!!) { selectedVariant ->
            // Replace the video item's URL with the selected variant's URL
            val updatedMedia = selectedMedia.map { media ->
                if (media == videoItem) {
                    media.copy(url = selectedVariant.url)
                } else {
                    media
                }
            }

            downloadInProgress = true
            viewModel.downloadSelectedMedia(tweetData, updatedMedia)
        }

        dialog.show(childFragmentManager, "VideoQualityDialog")
    }

    private fun observeViewModel() {
        viewModel.tweetData.observe(viewLifecycleOwner) { tweetData ->
            if (tweetData != null) {
                binding.tweetDataContainer.visibility = View.VISIBLE
                binding.authorText.text = "${tweetData.authorName} (@${tweetData.authorUsername})"
                binding.tweetText.text = tweetData.text

                if (tweetData.mediaItems.isNotEmpty()) {
                    mediaAdapter.submitList(tweetData.mediaItems)
                    binding.selectMediaLabel.visibility = View.VISIBLE
                    binding.mediaRecyclerView.visibility = View.VISIBLE
                } else {
                    Toast.makeText(
                        requireContext(),
                        "No media found in this tweet",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        viewModel.loadingState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is LoadingState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.statusText.visibility = View.VISIBLE
                    binding.statusText.text = "Loading..."
                    binding.fetchButton.isEnabled = false
                }
                is LoadingState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.visibility = View.GONE
                    binding.fetchButton.isEnabled = true

                    // Clear URL field after successful download
                    if (downloadInProgress) {
                        binding.urlInput.text?.clear()
                        downloadInProgress = false
                        Toast.makeText(requireContext(), "Download complete!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Success!", Toast.LENGTH_SHORT).show()
                    }
                }
                is LoadingState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.visibility = View.VISIBLE
                    binding.statusText.text = "Error: ${state.message}"
                    binding.fetchButton.isEnabled = true
                    downloadInProgress = false
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                    binding.statusText.visibility = View.GONE
                    binding.fetchButton.isEnabled = true
                }
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
