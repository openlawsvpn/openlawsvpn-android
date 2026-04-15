// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android

import android.content.Context
import com.openlawsvpn.android.model.VpnProfile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages VPN profiles (.ovpn) stored in the app's private internal storage.
 *
 * Storage layout:
 *   filesDir/profiles/<uuid>/config.ovpn   — .ovpn content
 *   filesDir/profiles/<uuid>/meta.json     — name + createdAt
 *
 * Protected by the Android sandbox (mode 0700 on the app data dir).
 */
class ProfileManager(private val context: Context) {

    private val root get() = File(context.filesDir, "profiles").also { it.mkdirs() }

    fun listProfiles(): List<VpnProfile> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> load(dir) }
            ?.sortedBy { it.createdAt }
            ?: emptyList()

    fun getProfile(id: String): VpnProfile? = load(File(root, id))

    fun importProfile(name: String, ovpn: String): VpnProfile {
        val id  = UUID.randomUUID().toString()
        val dir = File(root, id).also { it.mkdirs() }
        File(dir, "config.ovpn").writeText(ovpn)
        val meta = JSONObject().apply {
            put("name", name)
            put("createdAt", System.currentTimeMillis())
        }
        File(dir, "meta.json").writeText(meta.toString())
        return VpnProfile(id = id, name = name, configContent = ovpn)
    }

    fun deleteProfile(id: String) {
        File(root, id).deleteRecursively()
    }

    /**
     * Write the profile config to a temp file and return its path.
     * The caller is responsible for deleting the file when done.
     */
    fun writeTempConfig(profile: VpnProfile): File {
        val tmp = File(context.cacheDir, "vpn_active.ovpn")
        tmp.writeText(profile.configContent)
        return tmp
    }

    private fun load(dir: File): VpnProfile? {
        if (!dir.isDirectory) return null
        val configFile = File(dir, "config.ovpn")
        val metaFile   = File(dir, "meta.json")
        if (!configFile.exists() || !metaFile.exists()) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            VpnProfile(
                id            = dir.name,
                name          = meta.getString("name"),
                configContent = configFile.readText(),
                createdAt     = meta.getLong("createdAt"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
