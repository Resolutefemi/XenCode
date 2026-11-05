package com.xencode.feature.backup.domain

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric encryption of backup files: PBKDF2WithHmacSHA256 key derivation + chunked AES/GCM/NoPadding.
 *
 * AES/GCM on Android (Conscrypt's OpenSSLAeadCipher) doesn't support streaming output: `Cipher.update` always returns an empty array and
 * buffers everything fed to it internally, producing output in one shot at `doFinal`. So large files can't be written via "multiple updates" —
 * the whole plaintext would pile up inside the cipher. Instead, the plaintext is cut into fixed-size chunks, each getting its own independent AES-GCM (STREAM construction):
 *
 * - One key is derived for the whole file (random salt); each chunk's nonce = random 8-byte base || chunk index (4 bytes), guaranteeing no nonce reuse under the same key;
 * - Each chunk writes "is this the last chunk" into the AAD; the reader must see the last chunk, preventing truncated ciphertext; the chunk index is bound to the nonce, so reordering/substituting chunks fails the tag check;
 * - Each chunk's doFinal output is bounded, so peak memory is independent of the total backup size.
 *
 * The file header is [MAGIC] + version + salt + nonce base, followed by the chunk sequence. Files not starting with MAGIC are decrypted
 * in the legacy "salt + IV + single GCM" format ([decryptLegacy]), so old encrypted backups remain importable.
 *
 * The passphrase is never persisted or remembered. GCM provides integrity checks; a wrong passphrase or a tampered file makes decryption throw [BackupDecryptionException].
 */
class BackupDecryptionException(cause: Throwable? = null) : IllegalArgumentException(
    "Wrong backup passphrase, or the encrypted backup file is corrupted; leave the passphrase empty if the backup is not encrypted",
    cause
)

object BackupCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_LEN_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val TAG_BYTES = GCM_TAG_BITS / 8

    /** Plaintext size per chunk; each chunk gets its own GCM, so a single doFinal's output is bounded. */
    private const val CHUNK_SIZE = 256 * 1024

    private val MAGIC = "XENCODEBK".toByteArray(Charsets.US_ASCII)

    /** Legacy (AiCode-era) file header: kept for read compatibility, so encrypted backups exported by old versions remain importable. */
    private val LEGACY_MAGIC = "AICODEBK".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private const val FORMAT_VERSION = 0x02
    private const val NONCE_BASE_LEN = 8

    const val SALT_LEN = 16

    /** IV length of the legacy single-shot GCM; used only by [decryptLegacy]. */
    const val IV_LEN = 12

    private val random = SecureRandom()

    internal fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LEN_BITS)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /** Chunked nonce: random base (8 bytes) + big-endian chunk index (4 bytes), 96 bits total. */
    private fun chunkNonce(nonceBase: ByteArray, index: Long): ByteArray {
        val nonce = ByteArray(nonceBase.size + 4)
        System.arraycopy(nonceBase, 0, nonce, 0, nonceBase.size)
        val o = nonceBase.size
        nonce[o] = (index ushr 24).toByte()
        nonce[o + 1] = (index ushr 16).toByte()
        nonce[o + 2] = (index ushr 8).toByte()
        nonce[o + 3] = index.toByte()
        return nonce
    }

    private fun aad(isLast: Boolean): ByteArray = byteArrayOf((if (isLast) 1 else 0).toByte())

    /** Streaming encryption: writes the v2 header first, then encrypts [input] chunk by chunk into [output]. */
    fun encryptStream(input: InputStream, output: OutputStream, password: CharArray) {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val nonceBase = ByteArray(NONCE_BASE_LEN).also { random.nextBytes(it) }
        output.write(MAGIC)
        output.write(FORMAT_VERSION)
        output.write(salt)
        output.write(nonceBase)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val buffer = ByteArray(CHUNK_SIZE)
        var pending = -1
        var index = 0L
        while (true) {
            var n = 0
            if (pending >= 0) {
                buffer[0] = pending.toByte()
                pending = -1
                n = 1
            }
            n += readUpTo(input, buffer, n, CHUNK_SIZE - n)

            // When a full chunk is read, 1 extra byte must be peeked to decide whether it's the last chunk; the extra byte carries over to the start of the next chunk
            val isLast: Boolean
            if (n < CHUNK_SIZE) {
                isLast = true
            } else {
                val b = input.read()
                if (b < 0) isLast = true else { pending = b; isLast = false }
            }

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce(nonceBase, index)))
            cipher.updateAAD(aad(isLast))
            val chunk = cipher.doFinal(buffer, 0, n)
            output.write(if (isLast) 1 else 0)
            writeInt(output, chunk.size)
            output.write(chunk)

            if (isLast) break
            index++
            check(index <= 0xFFFFFFFFL) { "Backup file is too large for the encryption format" }
        }
        output.flush()
    }

    /** Streaming decryption: v2 files decrypt chunk by chunk (both the new header and the legacy header are accepted); the rest decrypt in the legacy format (single GCM) for compatibility. */
    fun decryptStream(input: InputStream, output: OutputStream, password: CharArray) {
        val header = readFully(input, MAGIC.size)
        val isCurrent = header.hasPrefix(MAGIC)
        // The legacy header is 1 byte shorter than the new one: on a legacy-header hit, the version is exactly the last byte of header
        val isLegacyV2 = header.hasPrefix(LEGACY_MAGIC)
        if (!isCurrent && !isLegacyV2) {
            decryptLegacy(input, output, password, header)
            return
        }
        val version = if (isCurrent) readFully(input, 1)[0] else header[LEGACY_MAGIC.size]
        if (version.toInt() != FORMAT_VERSION) {
            throw IllegalArgumentException("Unsupported encrypted backup file version")
        }

        val salt = readFully(input, SALT_LEN)
        val nonceBase = readFully(input, NONCE_BASE_LEN)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var index = 0L
        while (true) {
            val flag = input.read()
            if (flag < 0) throw IllegalArgumentException("Encrypted backup file is truncated")
            val len = readInt(input)
            if (len < TAG_BYTES || len > CHUNK_SIZE + TAG_BYTES) {
                throw IllegalArgumentException("Encrypted backup file is corrupted")
            }
            val chunk = readFully(input, len)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce(nonceBase, index)))
            cipher.updateAAD(aad(flag == 1))
            val plain = try {
                cipher.doFinal(chunk)
            } catch (e: BadPaddingException) {
                throw BackupDecryptionException(e)
            }
            output.write(plain)
            if (flag == 1) break
            index++
            check(index <= 0xFFFFFFFFL) { "Encrypted backup file is corrupted" }
        }
        output.flush()
    }

    /** Legacy format: salt + IV + single AES-GCM. Used only for compatibility with encrypted backups exported by old versions. */
    private fun decryptLegacy(input: InputStream, output: OutputStream, password: CharArray, header: ByteArray) {
        val salt = ByteArray(SALT_LEN)
        val fromHeader = minOf(header.size, SALT_LEN)
        System.arraycopy(header, 0, salt, 0, fromHeader)
        if (fromHeader < SALT_LEN) readFully(input, salt, fromHeader, SALT_LEN - fromHeader)
        val iv = readFully(input, IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) output.write(cipher.update(buffer, 0, n))
        }
        try {
            output.write(cipher.doFinal())
        } catch (e: BadPaddingException) {
            throw BackupDecryptionException(e)
        }
        output.flush()
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private fun readInt(input: InputStream): Int {
        val b0 = input.read()
        val b1 = input.read()
        val b2 = input.read()
        val b3 = input.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw IllegalArgumentException("Encrypted backup file is truncated")
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /** Reads up to [len] bytes; only returns fewer than [len] at end of stream. */
    private fun readUpTo(input: InputStream, buffer: ByteArray, offset: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buffer, offset + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val result = ByteArray(len)
        readFully(input, result, 0, len)
        return result
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buffer, offset + read, len - read)
            if (n < 0) throw IllegalArgumentException("Not a valid encrypted XenCode backup file")
            read += n
        }
    }
}
