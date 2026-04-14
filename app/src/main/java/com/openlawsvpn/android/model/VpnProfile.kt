// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.model

/**
 * A stored VPN profile (.ovpn content + metadata).
 * Persisted to app-private storage by ProfileManager.
 */
data class VpnProfile(
    val id: String,            // UUID
    val name: String,          // human-readable name (defaults to filename)
    val configContent: String, // raw .ovpn text (filtered, stored in app private dir)
    val createdAt: Long = System.currentTimeMillis(),
)
