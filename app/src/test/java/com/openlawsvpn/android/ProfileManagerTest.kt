// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileManagerTest {

    private lateinit var ctx: Context
    private lateinit var mgr: ProfileManager

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        mgr = ProfileManager(ctx)
        ctx.filesDir.resolve("profiles").deleteRecursively()
    }

    @Test fun listProfiles_emptyWhenNoneImported() { assertTrue(mgr.listProfiles().isEmpty()) }

    @Test fun importProfile_returnsProfileWithCorrectName() {
        val p = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        assertEquals("Corp VPN", p.name)
        assertEquals(SAMPLE_OVPN, p.configContent)
    }

    @Test fun listProfiles_containsImportedProfile() {
        mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        val list = mgr.listProfiles()
        assertEquals(1, list.size)
        assertEquals("Corp VPN", list[0].name)
    }

    @Test fun importMultipleProfiles_allListed() {
        mgr.importProfile("VPN A", SAMPLE_OVPN)
        mgr.importProfile("VPN B", SAMPLE_OVPN)
        assertEquals(2, mgr.listProfiles().size)
    }

    @Test fun getProfile_returnsCorrectProfile() {
        val imported = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        val fetched  = mgr.getProfile(imported.id)
        assertNotNull(fetched)
        assertEquals(imported.id, fetched!!.id)
    }

    @Test fun getProfile_returnsNullForUnknownId() { assertNull(mgr.getProfile("nonexistent")) }

    @Test fun deleteProfile_removesIt() {
        val p = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        mgr.deleteProfile(p.id)
        assertTrue(mgr.listProfiles().isEmpty())
    }

    @Test fun writeTempConfig_createsReadableFile() {
        val p   = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        val tmp = mgr.writeTempConfig(p)
        assertTrue(tmp.exists())
        assertEquals(SAMPLE_OVPN, tmp.readText())
        tmp.delete()
    }

    companion object {
        private val SAMPLE_OVPN = """
client
dev tun
proto udp
remote vpn.example.com 443
""".trimIndent()
    }
}
