package com.albahadly.lookouttv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.albahadly.lookouttv.AppLog
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts camera passwords with an AES key kept in the Android Keystore.
 * The key never leaves the TV's secure storage; the settings file only holds ciphertext.
 * Nothing here throws: callers get null and keep the old value, so a passing Keystore
 * glitch can never wipe a saved password.
 */
object SecretBox {
    private const val ALIAS = "lookouttv_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile private var cachedKey: SecretKey? = null

    /** Set while the Keystore refuses to work (shown in Settings); null when it works. */
    @Volatile var problem: String? = null
        private set

    /** Ciphertext for [plain], "" for "", or null if the Keystore failed (keep the old value). */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            problem = null
            Base64.encodeToString(sealed, Base64.NO_WRAP)
        } catch (e: Exception) {
            AppLog.w("could not encrypt a secret (Keystore problem)", e)
            problem = "The TV's secure storage isn't working, so passwords can't be saved right now. Restart the TV and try again."
            null
        }
    }

    /** The plain text, "" for "", or null if it can't be decrypted right now (keep the ciphertext). */
    fun decrypt(sealed: String): String? {
        if (sealed.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            val permanent = e is AEADBadTagException || e is KeyPermanentlyInvalidatedException
            AppLog.w("could not decrypt a secret" + if (permanent) " (key changed; re-enter it)" else " (will retry)", e)
            if (!permanent) problem = "The TV's secure storage isn't answering, so saved passwords can't be read right now. Restart the TV."
            null
        }
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // An UnrecoverableKeyException here is left to the caller (it returns null and keeps
        // the old ciphertext). The Keystore can refuse briefly after boot or an update; deleting
        // the key on that would make every saved password unreadable for good.
        val existing = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
        cachedKey = key
        return key
    }
}
