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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.openlawsvpn.android.R
import com.openlawsvpn.android.databinding.FragmentConnectionBinding
import com.openlawsvpn.android.model.ConnectionState
import kotlinx.coroutines.launch

class ConnectionFragment : Fragment() {

    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConnectionViewModel by activityViewModels()

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
            // Resolve the human-readable display name via OpenableColumns — avoids
            // the raw encoded path that uri.lastPathSegment returns for content URIs.
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

        binding.btnImport.setOnClickListener { filePicker.launch("*/*") }
        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { viewModel.disconnect() }

        binding.spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val p = viewModel.profiles.value
                if (position < p.size) viewModel.selectProfile(p[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.connectionState.collect { updateUi(it) } }
                launch { viewModel.profiles.collect { updateProfileList(it) } }
                launch { viewModel.samlUrlEvent.collect { openCustomTab(it) } }
                // Re-evaluate Connect button when selected profile changes (e.g. after import).
                launch { viewModel.selectedProfile.collect { updateConnectButton() } }
            }
        }
    }

    private fun onConnectClicked() {
        val profile = viewModel.selectedProfile.value
        if (profile == null) {
            Snackbar.make(binding.root, "Import a VPN profile first.", Snackbar.LENGTH_SHORT).show()
            return
        }
        // Ask Android for VPN permission — shows the system "Allow VPN?" dialog if needed.
        // VpnService.prepare() can throw SecurityException on some Android 15 builds when
        // AppOps records are stale (e.g. right after reinstall). Treat it as needing permission.
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

    private fun updateConnectButton() {
        val state   = viewModel.connectionState.value
        val isIdle  = state is ConnectionState.Idle || state is ConnectionState.Error
        binding.btnConnect.isEnabled = isIdle && viewModel.selectedProfile.value != null
    }

    private fun updateUi(state: ConnectionState) {
        val isIdle        = state is ConnectionState.Idle || state is ConnectionState.Error
        val isConnected   = state is ConnectionState.Connected
        val isTransitioning = state is ConnectionState.Connecting ||
                              state is ConnectionState.WaitingSaml ||
                              state is ConnectionState.Disconnecting

        binding.btnConnect.isEnabled    = isIdle && viewModel.selectedProfile.value != null
        binding.btnDisconnect.isEnabled = isConnected || isTransitioning
        binding.progressBar.isVisible   = isTransitioning

        binding.tvStatus.text = when (state) {
            is ConnectionState.Idle          -> "Disconnected"
            is ConnectionState.Connecting    -> "Connecting to ${state.profileName}…"
            is ConnectionState.WaitingSaml   -> "Waiting for SAML login…"
            is ConnectionState.Connected     -> "Connected — ${state.profileName}"
            is ConnectionState.Disconnecting -> "Disconnecting…"
            is ConnectionState.NeedReauth    -> state.reason.ifEmpty { "Session expired — tap Connect to re-authenticate." }
            is ConnectionState.Error         -> "Error: ${state.message}"
        }

        val colorRes = when (state) {
            is ConnectionState.Connected -> com.google.android.material.R.color.design_default_color_secondary
            is ConnectionState.Error     -> com.google.android.material.R.color.design_default_color_error
            else                         -> android.R.color.darker_gray
        }
        binding.tvStatus.setTextColor(requireContext().getColor(colorRes))
    }

    private fun updateProfileList(profiles: List<com.openlawsvpn.android.model.VpnProfile>) {
        val names = profiles.map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProfile.adapter = adapter

        // Restore spinner to the currently selected profile; replacing the adapter
        // always resets the position to 0, which would silently switch to the first profile.
        val currentId = viewModel.selectedProfile.value?.id
        val idx = profiles.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        binding.spinnerProfile.setSelection(idx)
        // onItemSelectedListener fires from setSelection and keeps viewModel in sync.
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
