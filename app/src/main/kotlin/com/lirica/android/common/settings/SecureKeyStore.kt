package com.lirica.android.common.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.lirica.android.common.logging.LiricaLog
import java.security.InvalidAlgorithmParameterException
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
     *
     * @throws SecureKeyStoreUnavailableException 加密能力不可用或加密过程失败。
     *         **绝不返回空串**——空串会被调用方误判为"加密成功但结果为空"，
     *         导致用户已配置的 API Key 被静默清空（2026-09-27 审计已修过一次该症状，
     *         此处从契约层根除）。
     */
    fun encrypt(plainText: String): String

    /**
     * 解密 Base64 密文，解密或验签失败时安全返回 null。
     */
    fun decrypt(cipherText: String): String?

    /**
     * 当前是否由硬件 AndroidKeyStore 支撑。
     *
     * 供设置页向用户诚实披露加密强度：false 表示运行在软件降级密钥上，
     * 密文能挡住"随手翻 prefs 文件"，但挡不住有 root/调试权限的攻击者。
     */
    fun isHardwareBacked(): Boolean
}

/**
 * 密钥存储能力不可用。
 *
 * 与"加密失败"区分开：前者是环境问题（无KeyStore 且无法建立软件密钥），
 * 调用方应视为"本机不具备安全存储条件"，引导用户而非静默重试。
 */
class SecureKeyStoreUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * 基于 AndroidKeyStore + AES/GCM/NoPadding 的密钥安全存储实现。
 *
 * 安全设计：
 * 1. **首选**硬件隔离的 AndroidKeyStore（密钥不可导出）；
 * 2. 每次加密均使用 SecureRandom 动态生成 12 字节独占 IV，杜绝重放与密文统计攻击；
 * 3. 采用 GCM 128 位认证标签（Tag），任何篡改均触发 AEADBadTagException 并安全静默拒绝；
 * 4. 当 AndroidKeyStore 在单元测试环境（纯 JVM/Robolectric）或极少数硬件故障 OEM 机型上不可用时，
 *    降级为**首次访问时随机生成并持久化**的软件密钥。
 *
 * ## 2026-10-02 安全修复：移除硬编码兜底密钥
 *
 * 旧实现内置了一段固定 32 字节常量（`SOFTWARE_FALLBACK_KEY_BYTES`）作为降级根密钥。
 * 该常量随APK 分发，**任何人反编译即可获得**，等价于用户的 API Key 以公开密钥加密——
 * 密钥保护形同虚设。
 *
 * 现降级路径改为：`SecureRandom` 生成 32 字节随机密钥 → 以 Base64 存入
 * [SOFTWARE_KEY_PREFS_NAME] 指定的 SharedPreferences。攻击者即便拿到本机 prefs
 * 也需要先突破沙箱；而源码中不再存在任何可提取的密钥材料。
 *
 * 残余风险（诚实声明）：软件降级密钥无法防住 root / 物理接触 / 调试器附加攻击。
 * 这是"无硬件保护时的能力天花板"，不是可修复的缺陷。
 * 此类设备上应通过 [isHardwareBacked] 向用户披露强度。
 */
class DefaultSecureKeyStore(
    private val keyAlias: String = KEY_ALIAS_DEFAULT,
    /**
     * 软件降级密钥的持久化容器。为 null 时（纯 JVM 单测）退化为进程内生命周期密钥，
     * 仅保证同一实例内加解密自洽，不跨进程存活。
     */
    private val fallbackPrefs: SharedPreferences? = null
) : SecureKeyStore {

    companion object {
        private const val TAG = "SecureKeyStore"
        const val KEY_ALIAS_DEFAULT = "lirica_byok_aes_master_key"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AES_256_KEY_LENGTH_BYTES = 32

        /**
         * 软件降级密钥的持久化文件名。
         * 独立于 BYOK 配置本身（[ByokSettingsRepository.PREFS_NAME]），
         * 便于备份规则按需单独排除。
         */
        const val SOFTWARE_KEY_PREFS_NAME = "lirica_keystore_software_key"

        /**
         * 软件降级密钥在 SharedPreferences 中的键名。
         * 公开仅为供测试断言"不同实例密钥不同"，生产代码请勿直接引用。
         */
        const val SOFTWARE_SECRET_KEY_NAME = "sw_aes_key_v1"

        /**
         * 纯 JVM 环境（fallbackPrefs 为 null）的进程内降级密钥。
         *
         * 必须缓存：否则每次调用 `resolveSoftwareFallbackKey()` 都会生成新密钥，
         * 同一实例内 encrypt→decrypt 往返会因密钥不匹配而失败。
         */
        private val inProcessFallbackKey: SecretKey by lazy {
            val bytes = ByteArray(AES_256_KEY_LENGTH_BYTES)
            SecureRandom().nextBytes(bytes)
            SecretKeySpec(bytes, "AES")
        }
    }

    private val secureRandom = SecureRandom()

    private val secretKey: SecretKey by lazy {
        getOrCreateSecretKey()
    }

    /**
     * 硬件密钥是否可用。null 表示尚未尝试（惰性求值前）。
     */
    private var hardwareBackedResolved: Boolean? = null

    private fun getOrCreateSecretKey(): SecretKey {
        val hardwareKey = runCatching {
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
        }

        return hardwareKey.fold(
            onSuccess = { key ->
                hardwareBackedResolved = true
                LiricaLog.i(TAG, "AndroidKeyStore 硬件密钥就绪: $keyAlias")
                key
            },
            onFailure = { e ->
                hardwareBackedResolved = false
                LiricaLog.w(
                    TAG,
                    "AndroidKeyStore 不可用(${e::class.java.simpleName}: ${e.message})，" +
                        "降级为设备绑定的软件随机密钥。加密强度降级，root/物理接触不可防。"
                )
                resolveSoftwareFallbackKey()
            }
        )
    }

    /**
     * 取软件降级密钥：优先读持久化值，缺失或损坏则重新随机生成。
     *
     * **绝不返回源码常量**——这是本次修复的核心。
     */
    private fun resolveSoftwareFallbackKey(): SecretKey {
        val prefs = fallbackPrefs
        if (prefs == null) {
            // 纯 JVM 环境：进程内固定一份即可满足单测的往返契约
            return inProcessFallbackKey
        }
        val existing = prefs.getString(SOFTWARE_SECRET_KEY_NAME, null)
        if (!existing.isNullOrBlank()) {
            runCatching { Base64.getDecoder().decode(existing) }
                .getOrNull()
                ?.takeIf { it.size == AES_256_KEY_LENGTH_BYTES }
                ?.let { return SecretKeySpec(it, "AES") }
            LiricaLog.w(TAG, "软件降级密钥长度异常或已损坏，重新生成")
        }
        val fresh = generateRandomKey()
        prefs.edit().putString(SOFTWARE_SECRET_KEY_NAME, Base64.getEncoder().encodeToString(fresh.encoded)).apply()
        return fresh
    }

    private fun generateRandomKey(): SecretKey {
        val bytes = ByteArray(AES_256_KEY_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
        return SecretKeySpec(bytes, "AES")
    }

    override fun isHardwareBacked(): Boolean {
        //触发一次惰性密钥解析以确定实际路径
        val unused = secretKey
        return hardwareBackedResolved ?: false
    }

    override fun encrypt(plainText: String): String {
        val key = secretKey // 解析失败会直接抛出，不可继续
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // 关键真机修复（华为 Mate 50 / Android 12+ KeyStore 2.0 兼容）：
        // 当使用硬件 AndroidKeyStore 时，系统强制要求随机加密，并严禁调用方提供自定义 IV（否则报
        // InvalidAlgorithmParameterException: Caller-provided IV not permitted）。
        // 因此先以无参数方式执行 cipher.init(Cipher.ENCRYPT_MODE, key)，让底层硬件安全单元自动生成随机 IV，
        // 再从 cipher.iv 读取；若遇到某些非 AndroidKeyStore 极端环境拒绝无参 init，则回退为带参初始化。
        val iv: ByteArray = try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv ?: throw IllegalStateException("Cipher 未能生成有效 IV")
        } catch (e: InvalidAlgorithmParameterException) {
            val fallbackIv = ByteArray(GCM_IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, fallbackIv))
            fallbackIv
        }
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // 拼装：[12 字节 IV] + [密文 + Tag]
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    override fun decrypt(cipherText: String): String? {
        if (cipherText.isBlank()) return null
        val combined = runCatching { Base64.getDecoder().decode(cipherText) }.getOrNull() ?: return null
        if (combined.size <= GCM_IV_LENGTH_BYTES) return null

        val key = runCatching { secretKey }.getOrElse { return null }

        return runCatching {
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)

            val cipherBytesLength = combined.size - GCM_IV_LENGTH_BYTES
            val cipherBytes = ByteArray(cipherBytesLength)
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherBytes, 0, cipherBytesLength)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        }.getOrElse { e ->
            LiricaLog.w(TAG, "密文解密失败或已被篡改: ${e.message}")
            null
        }
    }
}

