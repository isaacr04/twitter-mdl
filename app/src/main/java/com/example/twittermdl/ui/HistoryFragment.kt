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
        observeViewModel()
    }

    private fun setupRecyclerView() {
        historyAdapter = DownloadHistoryAdapter(
            onRedownload = { history ->
                viewModel.redownloadMedia(history)
                Toast.makeText(
                    requireContext(),
                    "Redownloading media...",
                    Toast.LENGTH_SHORT
                ).show()
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
            }
        )

        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.allDownloads.collect { downloads ->
                if (downloads.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.historyRecyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.historyRecyclerView.visibility = View.VISIBLE
                    historyAdapter.submitList(downloads)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
