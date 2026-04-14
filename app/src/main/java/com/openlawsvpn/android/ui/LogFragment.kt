// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openlawsvpn.android.databinding.FragmentLogBinding
import kotlinx.coroutines.launch

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConnectionViewModel by activityViewModels()
    private val lines = ArrayDeque<String>(200)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCopy.setOnClickListener { copyLogs() }
        binding.btnClear.setOnClickListener { lines.clear(); binding.tvLog.text = "" }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.logOutput.collect { line ->
                    if (lines.size >= 200) lines.removeFirst()
                    lines.addLast(line)
                    binding.tvLog.text = lines.joinToString("\n")
                    binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun copyLogs() {
        if (lines.isEmpty()) {
            Toast.makeText(requireContext(), "No logs to copy.", Toast.LENGTH_SHORT).show()
            return
        }
        val clip = ClipData.newPlainText("openlawsvpn log", lines.joinToString("\n"))
        requireContext().getSystemService(Context.CLIPBOARD_SERVICE as String)
            .let { it as ClipboardManager }
            .setPrimaryClip(clip)
        // Android 13+ shows its own clipboard confirmation toast; show our own on older versions.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(requireContext(), "Logs copied to clipboard.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
