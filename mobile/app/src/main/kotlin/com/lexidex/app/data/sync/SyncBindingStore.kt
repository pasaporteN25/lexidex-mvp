package com.lexidex.app.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Where the hub binding lives. Behind an interface because the Keystore-backed implementation only
 * exists on a device, and the sync logic has to be testable off one.
 */
interface SyncBindingStore {
    fun read(): SyncHubBinding?
    fun write(binding: SyncHubBinding)
    fun clear()
}

/**
 * The binding sealed with a key that never leaves the Android Keystore.
 *
 * The credential is a bearer token for this device's slice of the personal catalog, so it gets the
 * same treatment as any other secret on the phone: the key material is generated inside the
 * Keystore and is not extractable, and only the ciphertext is written to preferences. A backup or
 * an off-device copy of the preferences file therefore restores nothing usable.
 *
 * User authentication is deliberately **not** required to unlock the key. Sync has to be able to
 * run in the background (9.11) and behind a lock screen; requiring a fingerprint per exchange
 * would trade a real feature for protection against an attacker who already holds an unlocked
 * phone - which is the case where the app's own data is readable anyway.
 */
class KeystoreSyncBindingStore(context: Context) : SyncBindingStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): SyncHubBinding? {
        val stored = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val plaintext = try {
            decrypt(stored)
        } catch (error: Exception) {
            // La clave del Keystore puede desaparecer -restaurar el telefono, cambiar el bloqueo
            // de pantalla en algunas versiones-, y entonces el texto cifrado ya no significa nada.
            // Se descarta y se pide emparejar de nuevo, que es lo unico honesto que se puede hacer.
            clear()
            return null
        }
        return runCatching {
            val document = JSONObject(plaintext)
            SyncHubBinding(
                hubId = document.getString("hub_id"),
                exchangeUrl = document.getString("exchange_url"),
                certificateSha256 = document.optString("certificate_sha256").takeIf { it.isNotBlank() },
                deviceId = document.getString("device_id"),
                credential = document.getString("credential"),
            )
        }.getOrNull()
    }

    override fun write(binding: SyncHubBinding) {
        val document = JSONObject()
            .put("hub_id", binding.hubId)
            .put("exchange_url", binding.exchangeUrl)
            .put("certificate_sha256", binding.certificateSha256 ?: "")
            .put("device_id", binding.deviceId)
            .put("credential", binding.credential)
            .toString()
        preferences.edit().putString(KEY_PAYLOAD, encrypt(document)).commit()
    }

    override fun clear() {
        preferences.edit().remove(KEY_PAYLOAD).commit()
        runCatching { keystore().deleteEntry(KEY_ALIAS) }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // El IV se guarda pegado al texto cifrado: es aleatorio por escritura y no es secreto,
        // pero sin el no hay forma de descifrar.
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
        )
        return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val existing = keystore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        existing?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val PREFERENCES = "lexidex.sync"
        const val KEY_ALIAS = "lexidex.sync.binding"
        const val KEY_PAYLOAD = "binding"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** In-memory binding store for tests and previews. */
class InMemorySyncBindingStore(private var binding: SyncHubBinding? = null) : SyncBindingStore {
    override fun read(): SyncHubBinding? = binding
    override fun write(binding: SyncHubBinding) { this.binding = binding }
    override fun clear() { binding = null }
}
