package com.openlawsvpn.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
            val name = uri.lastPathSegment?.removeSuffix(".ovpn") ?: "profile"
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

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.connectionState.collect { updateUi(it) } }
                launch { viewModel.profiles.collect { updateProfileList(it) } }
                launch { viewModel.samlUrlEvent.collect { openCustomTab(it) } }
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
            is ConnectionState.NeedReauth    -> "Session expired — tap Connect to re-authenticate."
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

        if (profiles.isNotEmpty())
            viewModel.selectProfile(profiles[binding.spinnerProfile.selectedItemPosition.coerceAtMost(profiles.lastIndex)])
    }

    private fun openCustomTab(samlUrl: String) {
        CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(requireContext(), Uri.parse(samlUrl))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
