package com.example.twittermdl.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.twittermdl.data.VideoVariant

class VideoQualityDialog : DialogFragment() {

    private var variants: List<VideoVariant> = emptyList()
    private var onQualitySelected: ((VideoVariant) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val qualities = variants.map { it.quality }.toTypedArray()

        return AlertDialog.Builder(requireContext())
            .setTitle("Select Video Quality")
            .setItems(qualities) { _, which ->
                onQualitySelected?.invoke(variants[which])
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        fun newInstance(
            variants: List<VideoVariant>,
            onQualitySelected: (VideoVariant) -> Unit
        ): VideoQualityDialog {
            return VideoQualityDialog().apply {
                this.variants = variants
                this.onQualitySelected = onQualitySelected
            }
        }
    }
}
