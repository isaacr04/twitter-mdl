package com.example.twittermdl.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.twittermdl.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    // File picker for backup (create file)
    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                try {
                    viewModel.backupHistory(requireContext(), it)
                    Toast.makeText(requireContext(), "Backup saved successfully", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // File picker for restore (open file)
    private val openRestoreFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            showRestoreConfirmationDialog(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        // Generate GIFs switch
        binding.generateGifsSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setGenerateGifsForThumbnails(isChecked)
        }

        // Delete local files switch
        binding.deleteLocalFilesSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDeleteLocalFilesWithHistory(isChecked)
        }

        // Refresh thumbnails button
        binding.refreshThumbnailsButton.setOnClickListener {
            showRefreshThumbnailsDialog()
        }

        // Backup history button
        binding.backupHistoryButton.setOnClickListener {
            startBackup()
        }

        // Restore history button
        binding.restoreHistoryButton.setOnClickListener {
            startRestore()
        }
    }

    private fun observeViewModel() {
        // Observe generate GIFs setting
        lifecycleScope.launch {
            viewModel.generateGifsForThumbnails.collect { enabled ->
                binding.generateGifsSwitch.isChecked = enabled
            }
        }

        // Observe delete local files setting
        lifecycleScope.launch {
            viewModel.deleteLocalFilesWithHistory.collect { enabled ->
                binding.deleteLocalFilesSwitch.isChecked = enabled
            }
        }
    }

    private fun showRefreshThumbnailsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Refresh Thumbnails")
            .setMessage("This will permanently delete any replaced thumbnails. Continue?")
            .setPositiveButton("Continue") { _, _ ->
                refreshThumbnails()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshThumbnails() {
        lifecycleScope.launch {
            try {
                binding.refreshThumbnailsButton.isEnabled = false
                binding.refreshThumbnailsButton.text = "Refreshing..."

                viewModel.refreshAllThumbnails()

                Toast.makeText(
                    requireContext(),
                    "Thumbnails refreshed successfully",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to refresh thumbnails: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.refreshThumbnailsButton.isEnabled = true
                binding.refreshThumbnailsButton.text = "Refresh Thumbnails"
            }
        }
    }

    private fun startBackup() {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val fileName = "twitter_mdl_backup_$timestamp.bak"

        createBackupFile.launch(fileName)
    }

    private fun startRestore() {
        openRestoreFile.launch(arrayOf("application/octet-stream", "*/*"))
    }

    private fun showRestoreConfirmationDialog(uri: android.net.Uri) {
        AlertDialog.Builder(requireContext())
            .setTitle("Restore History")
            .setMessage("This will add entries from the backup to your current history. Missing thumbnails will be regenerated. Continue?")
            .setPositiveButton("Continue") { _, _ ->
                restoreHistory(uri)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreHistory(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                binding.restoreHistoryButton.isEnabled = false
                binding.restoreHistoryButton.text = "Restoring..."

                val restoredCount = viewModel.restoreHistory(requireContext(), uri)

                Toast.makeText(
                    requireContext(),
                    "Restored $restoredCount entries successfully",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Restore failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.restoreHistoryButton.isEnabled = true
                binding.restoreHistoryButton.text = "Restore History"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
