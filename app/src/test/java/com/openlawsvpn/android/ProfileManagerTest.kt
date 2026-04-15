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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * ProfileManager tests — use Robolectric so Context / filesystem work on the JVM.
 * Requires: testImplementation("org.robolectric:robolectric:4.13")
 */
@RunWith(RobolectricTestRunner::class)
class ProfileManagerTest {

    private lateinit var ctx: Context
    private lateinit var mgr: ProfileManager

    // Plain JVM AES key — bypasses AndroidKeyStore which is unavailable on the JVM.
    private val testKeyProvider = object : ProfileManager.KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES")
            .also { it.init(256) }.generateKey()
        override fun getKey(): SecretKey = key
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        mgr = ProfileManager(ctx, testKeyProvider)
        // Start each test with an empty profile store.
        ctx.filesDir.resolve("profiles").deleteRecursively()
    }

    @Test
    fun listProfiles_emptyWhenNoneImported() {
        assertTrue(mgr.listProfiles().isEmpty())
    }

    @Test
    fun importProfile_returnsProfileWithCorrectName() {
        val profile = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        assertEquals("Corp VPN", profile.name)
        assertEquals(SAMPLE_OVPN, profile.configContent)
    }

    @Test
    fun listProfiles_containsImportedProfile() {
        mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        val list = mgr.listProfiles()
        assertEquals(1, list.size)
        assertEquals("Corp VPN", list[0].name)
    }

    @Test
    fun importMultipleProfiles_allListed() {
        mgr.importProfile("VPN A", SAMPLE_OVPN)
        mgr.importProfile("VPN B", SAMPLE_OVPN)
        assertEquals(2, mgr.listProfiles().size)
    }

    @Test
    fun getProfile_returnsCorrectProfile() {
        val imported = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        val fetched = mgr.getProfile(imported.id)
        assertNotNull(fetched)
        assertEquals(imported.id, fetched!!.id)
        assertEquals("Corp VPN", fetched.name)
    }

    @Test
    fun getProfile_returnsNullForUnknownId() {
        assertNull(mgr.getProfile("nonexistent-uuid"))
    }

    @Test
    fun deleteProfile_removesIt() {
        val profile = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        mgr.deleteProfile(profile.id)
        assertTrue(mgr.listProfiles().isEmpty())
        assertNull(mgr.getProfile(profile.id))
    }

    @Test
    fun writeTempConfig_createsReadableFile() {
        val profile = mgr.importProfile("Corp VPN", SAMPLE_OVPN)
        val tmp = mgr.writeTempConfig(profile)
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
