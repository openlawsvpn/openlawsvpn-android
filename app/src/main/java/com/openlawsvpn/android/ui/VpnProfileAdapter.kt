// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.openlawsvpn.android.R
import com.openlawsvpn.android.databinding.ItemVpnProfileBinding
import com.openlawsvpn.android.model.ConnectionState
import com.openlawsvpn.android.model.VpnProfile

class VpnProfileAdapter(
    private val onConnect: (VpnProfile) -> Unit,
    private val onDisconnect: () -> Unit,
    private val onDelete: (VpnProfile) -> Unit,
) : RecyclerView.Adapter<VpnProfileAdapter.ViewHolder>() {

    private var profiles: List<VpnProfile> = emptyList()
    private var state: ConnectionState = ConnectionState.Idle
    private var activeId: String? = null

    fun update(profiles: List<VpnProfile>, state: ConnectionState, activeId: String?) {
        this.profiles = profiles
        this.state = state
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVpnProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(profiles[position], state, activeId)

    override fun getItemCount() = profiles.size

    inner class ViewHolder(private val b: ItemVpnProfileBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(profile: VpnProfile, state: ConnectionState, activeId: String?) {
            val isThis = profile.id == activeId
            // True if any profile is busy (blocks Connect on other profiles).
            val anyBusy = state !is ConnectionState.Idle &&
                          state !is ConnectionState.Error &&
                          state !is ConnectionState.NeedReauth

            b.tvName.text = profile.name

            val statusText = when {
                isThis && state is ConnectionState.Connecting   -> "Connecting…"
                isThis && state is ConnectionState.WaitingSaml  -> "Waiting for SAML login…"
                isThis && state is ConnectionState.Connected    -> "● Connected"
                isThis && state is ConnectionState.Disconnecting -> "Disconnecting…"
                isThis && state is ConnectionState.NeedReauth   ->
                    (state as ConnectionState.NeedReauth).reason
                        .ifEmpty { "Session expired — tap Connect to re-authenticate" }
                isThis && state is ConnectionState.Error        ->
                    "Error: ${(state as ConnectionState.Error).message}"
                else -> ""
            }
            b.tvStatus.text = statusText
            b.tvStatus.isVisible = statusText.isNotEmpty()

            val isTransitioning = isThis && (state is ConnectionState.Connecting ||
                                             state is ConnectionState.WaitingSaml ||
                                             state is ConnectionState.Disconnecting)
            b.progressBar.isVisible = isTransitioning

            val showDisconnect = isThis &&
                (state is ConnectionState.Connected || state is ConnectionState.Disconnecting)
            if (showDisconnect) {
                b.btnAction.setText(R.string.btn_disconnect)
                b.btnAction.isEnabled = state is ConnectionState.Connected
                b.btnAction.setOnClickListener { onDisconnect() }
            } else {
                b.btnAction.setText(R.string.btn_connect)
                b.btnAction.isEnabled = !anyBusy
                b.btnAction.setOnClickListener { onConnect(profile) }
            }

            // Prevent deletion while this profile's tunnel is active.
            b.btnDelete.isEnabled = !(isThis && anyBusy)
            b.btnDelete.setOnClickListener { onDelete(profile) }
        }
    }
}
