package com.lirica.android.common.settings

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lirica.android.common.logging.LiricaLog
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface SecureKeyStore {
    /**
     * 加密明文字符串，返回 Base64 编码的密文（包含 12 字节随机 IV + GCM 密文与认证 Tag）。
     */
    fun encrypt(plainText: String): String

    /**
     * 解密 Base64 密文，解密或验签失败时安全返回 null。
     */
    fun decrypt(cipherText: String): String?
}

/**
 * 基于 AndroidKeyStore + AES/GCM/NoPadding 的密钥安全存储实现。
 *
 * 安全设计：
 * 1. 密钥存放于硬件隔离的 AndroidKeyStore（不支持导出的硬件私钥/AES 根钥）；
 * 2. 每次加密均使用 SecureRandom 动态生成 12 字节独占 IV，杜绝重放与密文统计攻击；
 * 3. 采用 GCM 128 位认证标签（Tag），任何篡改均触发 AEADBadTagException 并安全静默拒绝；
 * 4. 当 AndroidKeyStore 在单元测试环境（纯 JVM/Robolectric）或极少数硬件故障 OEM 机型上不可用时，
 *    提供确定性的软实现降级，确保应用永不崩溃。
 */
class DefaultSecureKeyStore(
    private val keyAlias: String = KEY_ALIAS_DEFAULT
) : SecureKeyStore {

    companion object {
        private const val TAG = "SecureKeyStore"
        const val KEY_ALIAS_DEFAULT = "lirica_byok_aes_master_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        // 软件兜底静态根种子（32 字节 AES-256，仅在 KeyStore 硬件彻底不可用时兜底，防止崩溃）
        private val SOFTWARE_FALLBACK_KEY_BYTES = byteArrayOf(
            0x2A, 0x5D, 0x19, 0x7E, 0x4B, 0x3F, 0x62, 0x10,
            0x7A, 0x34, 0x5C, 0x21, 0x48, 0x6F, 0x11, 0x39,
            0x53, 0x12, 0x78, 0x44, 0x29, 0x66, 0x38, 0x1F,
            0x4D, 0x5E, 0x22, 0x71, 0x30, 0x6A, 0x15, 0x40
        )
    }

    private val secureRandom = SecureRandom()

    private val secretKey: SecretKey by lazy {
        getOrCreateSecretKey()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEY_STORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            keyStore.getKey(keyAlias, null) as SecretKey
        }.getOrElse { e ->
            LiricaLog.w(TAG, "AndroidKeyStore 不可用，安全降级为软件 AES 秘钥: ${e.message}")
            SecretKeySpec(SOFTWARE_FALLBACK_KEY_BYTES, "AES")
        }
    }

    override fun encrypt(plainText: String): String {
        return runCatching {
            val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // 拼装：[12 字节 IV] + [密文 + Tag]
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

            Base64.getEncoder().encodeToString(combined)
        }.getOrElse { e ->
            LiricaLog.e(TAG, "密钥加密失败: ${e.message}", e)
            ""
        }
    }

    override fun decrypt(cipherText: String): String? {
        if (cipherText.isBlank()) return null
        return runCatching {
            val combined = Base64.getDecoder().decode(cipherText)
            if (combined.size <= GCM_IV_LENGTH_BYTES) return null

            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)

            val cipherBytesLength = combined.size - GCM_IV_LENGTH_BYTES
            val cipherBytes = ByteArray(cipherBytesLength)
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherBytes, 0, cipherBytesLength)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        }.getOrElse { e ->
            LiricaLog.w(TAG, "密文解密失败或已被篡改: ${e.message}")
            null
        }
    }
}
