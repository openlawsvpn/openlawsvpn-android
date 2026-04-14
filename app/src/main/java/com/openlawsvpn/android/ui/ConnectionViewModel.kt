// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openlawsvpn.android.ProfileManager
import com.openlawsvpn.android.VpnConnectionService
import com.openlawsvpn.android.model.ConnectionState
import com.openlawsvpn.android.model.VpnProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {

    // Application context is safe to hold statically — it lives as long as the process.
    @Suppress("StaticFieldLeak")
    private val ctx = app.applicationContext

    // ── Observe service state (shared across entire process) ──────────────────
    val connectionState: StateFlow<ConnectionState> = VpnConnectionService.connectionState
    val logOutput                                   = VpnConnectionService.logOutput
    val samlUrlEvent                                = VpnConnectionService.samlUrlEvent

    // ── Profile list ──────────────────────────────────────────────────────────
    private val _profiles = MutableStateFlow<List<VpnProfile>>(emptyList())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _selectedProfile = MutableStateFlow<VpnProfile?>(null)
    val selectedProfile: StateFlow<VpnProfile?> = _selectedProfile.asStateFlow()

    init { refreshProfiles() }

    fun refreshProfiles() {
        viewModelScope.launch {
            _profiles.value = ProfileManager(ctx).listProfiles()
            if (_selectedProfile.value == null)
                _selectedProfile.value = _profiles.value.firstOrNull()
        }
    }

    fun selectProfile(profile: VpnProfile) {
        _selectedProfile.value = profile
    }

    fun importProfile(name: String, ovpnContent: String) {
        viewModelScope.launch {
            ProfileManager(ctx).importProfile(name, ovpnContent)
            refreshProfiles()
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            ProfileManager(ctx).deleteProfile(id)
            refreshProfiles()
            if (_selectedProfile.value?.id == id)
                _selectedProfile.value = _profiles.value.firstOrNull()
        }
    }

    // ── Connect / disconnect ──────────────────────────────────────────────────

    /** Start VPN connection. Activity must have already called VpnService.prepare(). */
    fun connect(profileId: String) {
        val intent = Intent(ctx, VpnConnectionService::class.java).apply {
            action = VpnConnectionService.ACTION_CONNECT
            putExtra(VpnConnectionService.EXTRA_PROFILE_ID, profileId)
        }
        ctx.startService(intent)
    }

    fun disconnect() {
        ctx.startService(Intent(ctx, VpnConnectionService::class.java).apply {
            action = VpnConnectionService.ACTION_DISCONNECT
        })
    }
}
