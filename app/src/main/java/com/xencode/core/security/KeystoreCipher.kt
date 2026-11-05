package com.xencode.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric encryption for sensitive data: the key lives in the Android Keystore
 * (non-exportable, hardware-protected when a TEE is present), and data is encrypted with
 * AES-256-GCM. Used for private key files and password/API key fields in the database.
 *
 * The key is bound to the device: after clearing app data or copying the data directory
 * straight to another device, ciphertexts become undecryptable — an accepted cost;
 * cross-device migration goes through backup (exported as plaintext, re-encrypted with the
 * new device's key on import).
 *
 * Plaintext compatibility: decryption entry points return non-ciphertext input as-is,
 * allowing smooth migration of legacy plaintext data.
 */
object KeystoreCipher {
    private const val TAG = "KeystoreCipher"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "xencode_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** String ciphertext prefix (used on write); values without this prefix are treated as legacy plaintext. */
    private const val STRING_PREFIX = "XENCODEENC1:"

    /** Legacy (AiCode-era) string ciphertext prefix: accepted on read so upgrades don't lose data. */
    private const val LEGACY_STRING_PREFIX = "AICODEENC1:"

    /** Byte ciphertext file header (used on write); distinguishes encrypted files from legacy plaintext ones. */
    private val BYTE_MAGIC = "XENCODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    /** Legacy byte ciphertext file header: accepted on read. */
    private val LEGACY_BYTE_MAGIC = "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    /** Matches the current or legacy header; returns the matched magic (which decides the IV offset). */
    private fun matchedByteMagic(bytes: ByteArray): ByteArray? = when {
        BYTE_MAGIC.indices.all { bytes.size > it && bytes[it] == BYTE_MAGIC[it] } -> BYTE_MAGIC
        LEGACY_BYTE_MAGIC.indices.all { bytes.size > it && bytes[it] == LEGACY_BYTE_MAGIC[it] } -> LEGACY_BYTE_MAGIC
        else -> null
    }

    private val lock = Any()

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
    }

    fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return BYTE_MAGIC + iv + ciphertext
    }

    fun isEncryptedBytes(bytes: ByteArray): Boolean {
        val magic = matchedByteMagic(bytes) ?: return false
        return bytes.size >= magic.size + IV_LEN
    }

    fun decryptBytes(encrypted: ByteArray): ByteArray {
        val magic = matchedByteMagic(encrypted)
        require(magic != null && encrypted.size >= magic.size + IV_LEN) { "Not encrypted data" }
        return runCatching {
            val iv = encrypted.copyOfRange(magic.size, magic.size + IV_LEN)
            val ciphertext = encrypted.copyOfRange(magic.size + IV_LEN, encrypted.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrElse { e ->
            // Ciphertext is undecryptable when the key is gone (app data cleared / data
            // directory copied to another device / Keystore reset / cross-package migration).
            // Must degrade to empty rather than throw — callers mostly sit inside Room Flow
            // map blocks, where one exception kills the whole Flow and lands in the uncaught
            // handler, crashing the process (reproduced in production 2026-10-04).
            com.xencode.core.util.FileLogger.e(
                TAG, "Byte decryption failed (key unavailable), degraded to empty: ${e.javaClass.simpleName}: ${e.message}"
            )
            ByteArray(0)
        }
    }

    fun isEncryptedString(value: String?): Boolean =
        value != null && (value.startsWith(STRING_PREFIX) || value.startsWith(LEGACY_STRING_PREFIX))

    /** Encrypts a string; an empty string is returned as-is so an unfilled value doesn't become non-empty ciphertext. */
    fun encryptString(plain: String): String {
        if (plain.isEmpty()) return plain
        return STRING_PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Decrypts a string; non-ciphertext (legacy plaintext) is returned as-is. Degrades to an empty string and logs when the key is gone (see [decryptBytes]). */
    fun decryptString(value: String): String {
        if (!isEncryptedString(value)) return value
        return runCatching {
            val body = Base64.getDecoder().decode(
                if (value.startsWith(STRING_PREFIX)) value.removePrefix(STRING_PREFIX)
                else value.removePrefix(LEGACY_STRING_PREFIX)
            )
            String(decryptBytes(body), Charsets.UTF_8)
        }.getOrElse { e ->
            com.xencode.core.util.FileLogger.e(
                TAG, "String decryption failed (key unavailable), degraded to empty string: ${e.javaClass.simpleName}: ${e.message}"
            )
            ""
        }
    }
}
