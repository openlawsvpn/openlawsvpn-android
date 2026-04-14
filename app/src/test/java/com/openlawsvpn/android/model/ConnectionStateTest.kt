// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {

    @Test
    fun idle_isDistinctSingleton() {
        // Object singletons should be reference-equal
        assertTrue(ConnectionState.Idle === ConnectionState.Idle)
    }

    @Test
    fun connecting_holdsProfileName() {
        val state = ConnectionState.Connecting("Corp VPN")
        assertEquals("Corp VPN", state.profileName)
    }

    @Test
    fun waitingSaml_holdsProfileName() {
        val state = ConnectionState.WaitingSaml("Corp VPN")
        assertEquals("Corp VPN", state.profileName)
    }

    @Test
    fun connected_holdsAllFields() {
        val state = ConnectionState.Connected("Corp VPN", "10.0.0.1", "172.16.0.5")
        assertEquals("Corp VPN", state.profileName)
        assertEquals("10.0.0.1", state.serverIp)
        assertEquals("172.16.0.5", state.assignedIp)
    }

    @Test
    fun error_holdsMessage() {
        val state = ConnectionState.Error("Phase 2 failed")
        assertEquals("Phase 2 failed", state.message)
    }

    @Test
    fun needReauth_holdsProfileName() {
        val state = ConnectionState.NeedReauth("Corp VPN")
        assertEquals("Corp VPN", state.profileName)
    }

    @Test
    fun disconnecting_isDistinctSingleton() {
        assertTrue(ConnectionState.Disconnecting === ConnectionState.Disconnecting)
    }

    @Test
    fun dataClassEquality() {
        assertEquals(
            ConnectionState.Connecting("VPN A"),
            ConnectionState.Connecting("VPN A"),
        )
        assertFalse(
            ConnectionState.Connecting("VPN A") == ConnectionState.Connecting("VPN B")
        )
    }

    @Test
    fun sealedHierarchyCoversAllStates() {
        // Exhaustive when-expression compiles only if all branches are covered.
        val states: List<ConnectionState> = listOf(
            ConnectionState.Idle,
            ConnectionState.Connecting("x"),
            ConnectionState.WaitingSaml("x"),
            ConnectionState.Connected("x", "1.2.3.4", ""),
            ConnectionState.Disconnecting,
            ConnectionState.NeedReauth("x"),
            ConnectionState.Error("e"),
        )
        val labels = states.map { state ->
            when (state) {
                is ConnectionState.Idle          -> "idle"
                is ConnectionState.Connecting    -> "connecting"
                is ConnectionState.WaitingSaml   -> "waiting_saml"
                is ConnectionState.Connected     -> "connected"
                is ConnectionState.Disconnecting -> "disconnecting"
                is ConnectionState.NeedReauth    -> "need_reauth"
                is ConnectionState.Error         -> "error"
            }
        }
        assertEquals(7, labels.distinct().size)
    }
}
