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
 * 敏感数据的对称加密：密钥存放在 Android Keystore（不可导出，有 TEE 时受硬件保护），
 * 数据用 AES-256-GCM 加密。用于私钥文件与数据库里的密码/API Key 等字段。
 *
 * 密钥与设备绑定：清除应用数据、换机直拷数据目录后密文不可解，属预期代价；
 * 跨设备迁移走备份（备份导出为明文、导入时按新设备密钥重新加密）。
 *
 * 明文兼容：解密入口对非密文输入原样返回，便于旧明文数据的平滑迁移。
 */
object KeystoreCipher {
    private const val TAG = "KeystoreCipher"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "xencode_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** 字符串密文前缀（写入用）；不含此前缀的值按旧明文处理。 */
    private const val STRING_PREFIX = "XENCODEENC1:"

    /** 旧版（AiCode 时代）字符串密文前缀：读取时兼容，保证升级不丢数据。 */
    private const val LEGACY_STRING_PREFIX = "AICODEENC1:"

    /** 字节密文文件头（写入用）；用于区分加密文件与旧明文文件。 */
    private val BYTE_MAGIC = "XENCODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    /** 旧版字节密文文件头：读取时兼容。 */
    private val LEGACY_BYTE_MAGIC = "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    /** 命中写入头或旧版头；返回命中的 magic（决定 IV 偏移）。 */
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
            // 密钥失效（清除应用数据 / 换机直拷 / Keystore 重置 / 跨包迁移）时密文不可解。
            // 必须降级为空而不是抛出——调用方多在 Room Flow 的 map 里，一个异常会让
            // 整条 Flow 崩掉并落到未捕获处理器，直接进程闪退（2026-10-04 线上复现）。
            com.xencode.core.util.FileLogger.e(
                TAG, "解密字节失败（密钥不可用），降级为空: ${e.javaClass.simpleName}: ${e.message}"
            )
            ByteArray(0)
        }
    }

    fun isEncryptedString(value: String?): Boolean =
        value != null && (value.startsWith(STRING_PREFIX) || value.startsWith(LEGACY_STRING_PREFIX))

    /** 加密字符串；空串原样返回，避免把「未填写」变成非空密文。 */
    fun encryptString(plain: String): String {
        if (plain.isEmpty()) return plain
        return STRING_PREFIX + Base64.getEncoder().encodeToString(encryptBytes(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 解密字符串；非密文（旧明文）原样返回。密钥失效时降级为空串并记录（见 [decryptBytes]）。 */
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
                TAG, "解密字符串失败（密钥不可用），降级为空串: ${e.javaClass.simpleName}: ${e.message}"
            )
            ""
        }
    }
}
