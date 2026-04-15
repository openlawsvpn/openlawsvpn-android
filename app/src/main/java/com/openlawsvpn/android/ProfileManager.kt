// Copyright (C) 2026 openlawsvpn contributors. All rights reserved.
package com.openlawsvpn.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.openlawsvpn.android.model.VpnProfile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages VPN profiles (.ovpn) stored in the app's private internal storage.
 *
 * Storage layout:
 *   filesDir/profiles/<uuid>/config.ovpn.enc  — AES-256-GCM encrypted .ovpn content
 *   filesDir/profiles/<uuid>/meta.json        — name + createdAt (not sensitive)
 *
 * Encrypted file format: [12-byte GCM IV][ciphertext + 16-byte GCM tag]
 *
 * The encryption key lives in the Android Keystore (never leaves secure hardware
 * on devices that support it). On first access the key is generated automatically.
 *
 * Migration: plaintext config.ovpn files from earlier installs are encrypted in
 * place on first load and the plaintext file is then deleted.
 */
class ProfileManager(
    private val context: Context,
    private val keyProvider: KeyProvider = AndroidKeystoreKeyProvider(),
) {

    /** Abstracts key acquisition so tests can inject a plain JVM key. */
    interface KeyProvider {
        fun getKey(): SecretKey
    }

    /** Production: key lives in the Android Keystore. */
    class AndroidKeystoreKeyProvider : KeyProvider {
        private val keyStore: KeyStore =
            KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

        override fun getKey(): SecretKey {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply { init(spec) }
                    .generateKey()
            }
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
    }

    private val root get() = File(context.filesDir, "profiles").also { it.mkdirs() }

    // ── Crypto ────────────────────────────────────────────────────────────────

    private fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getKey())
        val iv         = cipher.iv                                  // random 12-byte IV
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ciphertext                                      // IV prepended to blob
    }

    private fun decrypt(data: ByteArray): String {
        val iv         = data.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = data.copyOfRange(GCM_IV_BYTES, data.size)
        val cipher     = Cipher.getInstance(CIPHER_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    // ── Public API ────────────────────────────────────────────────────────────

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
        File(dir, ENC_FILE).writeBytes(encrypt(ovpn))
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
     * Write the (decrypted) profile config to a temp file and return its path.
     * The file lives in cacheDir (app-private). Caller must delete it when done.
     */
    fun writeTempConfig(profile: VpnProfile): File {
        val tmp = File(context.cacheDir, "vpn_active.ovpn")
        tmp.writeText(profile.configContent)
        return tmp
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun load(dir: File): VpnProfile? {
        if (!dir.isDirectory) return null
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val meta      = JSONObject(metaFile.readText())
            val encFile   = File(dir, ENC_FILE)
            val plainFile = File(dir, "config.ovpn")
            val content   = when {
                encFile.exists()   -> decrypt(encFile.readBytes())
                plainFile.exists() -> {
                    // Migrate legacy plaintext profile → encrypted in place.
                    val text = plainFile.readText()
                    encFile.writeBytes(encrypt(text))
                    plainFile.delete()
                    text
                }
                else -> return null
            }
            VpnProfile(
                id            = dir.name,
                name          = meta.getString("name"),
                configContent = content,
                createdAt     = meta.getLong("createdAt"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val KEY_ALIAS        = "openlawsvpn_profile_key"
        const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
        const val ENC_FILE         = "config.ovpn.enc"
        const val GCM_IV_BYTES     = 12
        const val GCM_TAG_BITS     = 128
    }
}
