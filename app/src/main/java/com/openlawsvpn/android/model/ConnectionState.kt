// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.model

sealed class ConnectionState {
    object Idle : ConnectionState()
    data class Connecting(val profileName: String) : ConnectionState()
    /** SAML URL obtained — Chrome Custom Tab is open, waiting for SAMLResponse on :35001. */
    data class WaitingSaml(val profileName: String) : ConnectionState()
    data class Connected(
        val profileName: String,
        val serverIp: String,
        val assignedIp: String,
    ) : ConnectionState()
    object Disconnecting : ConnectionState()
    /** SAML session expired — re-auth needed (NEED_CREDS event after CONNECTED). */
    data class NeedReauth(val profileName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
