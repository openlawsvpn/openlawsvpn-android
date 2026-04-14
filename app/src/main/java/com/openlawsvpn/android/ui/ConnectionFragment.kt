// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.OpenableColumns
import androidx.core.net.toUri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.openlawsvpn.android.databinding.FragmentConnectionBinding
import com.openlawsvpn.android.model.VpnProfile
import kotlinx.coroutines.launch

class ConnectionFragment : Fragment() {

    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConnectionViewModel by activityViewModels()
    private lateinit var adapter: VpnProfileAdapter

    // ── VPN permission result ─────────────────────────────────────────────────
    private var pendingProfileId: String? = null
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingProfileId?.let { viewModel.connect(it) }
        }
        pendingProfileId = null
    }

    // ── Config file picker ────────────────────────────────────────────────────
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val content = requireContext().contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val displayName = requireContext().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val name = (displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "profile")
                .removeSuffix(".ovpn").ifEmpty { "profile" }
            viewModel.importProfile(name, content)
            Snackbar.make(binding.root, "Profile '$name' imported.", Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Import failed: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = VpnProfileAdapter(
            onConnect    = { profile -> requestConnectWithPermission(profile) },
            onDisconnect = { viewModel.disconnect() },
            onDelete     = { profile -> confirmDelete(profile) },
        )
        binding.rvProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProfiles.adapter = adapter

        binding.btnImport.setOnClickListener { filePicker.launch("*/*") }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.samlUrlEvent.collect { openCustomTab(it) } }
                launch { viewModel.profiles.collect { updateAll() } }
                launch { viewModel.connectionState.collect { updateAll() } }
                launch { viewModel.activeProfileId.collect { updateAll() } }
            }
        }
    }

    private fun updateAll() {
        val profiles = viewModel.profiles.value
        val state    = viewModel.connectionState.value
        val activeId = viewModel.activeProfileId.value
        adapter.update(profiles, state, activeId)
        binding.tvEmpty.isVisible    = profiles.isEmpty()
        binding.rvProfiles.isVisible = profiles.isNotEmpty()
    }

    private fun requestConnectWithPermission(profile: VpnProfile) {
        // VpnService.prepare() can throw SecurityException on some Android 15 builds
        // when AppOps records are stale (e.g. right after reinstall).
        val intent = try {
            VpnService.prepare(requireContext())
        } catch (e: SecurityException) {
            Snackbar.make(binding.root, "VPN permission error: ${e.message}", Snackbar.LENGTH_LONG).show()
            return
        }
        if (intent != null) {
            pendingProfileId = profile.id
            vpnPermissionLauncher.launch(intent)
        } else {
            viewModel.connect(profile.id)
        }
    }

    private fun confirmDelete(profile: VpnProfile) {
        Snackbar.make(binding.root, "Delete '${profile.name}'?", Snackbar.LENGTH_LONG)
            .setAction("Delete") { viewModel.deleteProfile(profile.id) }
            .show()
    }

    private fun openCustomTab(samlUrl: String) {
        CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(requireContext(), samlUrl.toUri())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
