package com.xencode.feature.agent.domain.container

import android.content.Context
import com.xencode.core.security.KeystoreCipher
import com.xencode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.SSHClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted read/write of SSH private key files: keys are stored on disk as Keystore-encrypted
 * ciphertext and decrypted in memory when read, then handed to sshj.
 *
 * Plaintext exists only in memory; even if a process inside the container reads the key file it only sees ciphertext. Legacy plaintext files are lazily encrypted and rewritten on first read.
 */
@Singleton
class SshPrivateKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "SshPrivateKeyStore"
    }

    /** Write the private key file encrypted (overwrites existing content). */
    fun write(path: String, plain: ByteArray) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(KeystoreCipher.encryptBytes(plain))
    }

    /** Read the private key plaintext PEM: decrypts ciphertext; legacy plaintext is returned as-is and lazily re-encrypted. */
    fun readPem(path: String): String {
        val file = File(path)
        val bytes = file.readBytes()
        if (!KeystoreCipher.isEncryptedBytes(bytes)) {
            // Legacy plaintext file: use it as plaintext this time and try to encrypt it in place (failure does not block the connection).
            runCatching { file.writeBytes(KeystoreCipher.encryptBytes(bytes)) }
                .onFailure { FileLogger.w(TAG, "Failed to migrate plaintext private key to encrypted: ${file.name}", it) }
            return String(bytes, Charsets.UTF_8)
        }
        return String(KeystoreCipher.decryptBytes(bytes), Charsets.UTF_8)
    }

    /** Compute the SHA-256 fingerprint of the public key matching this private key; null when an encrypted key (needing a passphrase) cannot be parsed. */
    fun fingerprint(pem: String): String? = try {
        val keyProvider = SSHClient().loadKeys(pem, null, null)
        sshHostKeyFingerprint(keyProvider.getPublic())
    } catch (e: Exception) {
        null
    }
}
