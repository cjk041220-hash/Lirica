package com.lirica.android.common.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SecureKeyStoreTest {

    private lateinit var keyStore: SecureKeyStore

    @Before
    fun setUp() {
        keyStore = DefaultSecureKeyStore()
    }

    @Test
    fun testEncryptAndDecrypt_roundTripSuccess() {
        val originalKey = "sk-deepseek-1234567890abcdef"
        val cipherText = keyStore.encrypt(originalKey)

        assertNotNull(cipherText)
        assertTrue(cipherText.isNotBlank())
        assertNotEquals(originalKey, cipherText)

        val decrypted = keyStore.decrypt(cipherText)
        assertEquals(originalKey, decrypted)
    }

    @Test
    fun testEncrypt_differentIvEachTime() {
        val key = "sk-test-same-key"
        val cipher1 = keyStore.encrypt(key)
        val cipher2 = keyStore.encrypt(key)

        assertNotEquals("相同明文每次加密由于随机 IV 产物必须不同", cipher1, cipher2)
        assertEquals(key, keyStore.decrypt(cipher1))
        assertEquals(key, keyStore.decrypt(cipher2))
    }

    @Test
    fun testDecrypt_invalidCipherText_returnsNull() {
        assertNull("非法 Base64 应安全返回 null", keyStore.decrypt("not-a-valid-base64-$$$"))
        assertNull("太短的密文无法切出 IV 应返回 null", keyStore.decrypt("YWJj")) // "abc"
    }

    @Test
    fun testDecrypt_tamperedCipher_returnsNull() {
        val original = "sk-secret-payload"
        val cipher = keyStore.encrypt(original)
        // 篡改密文最后几个字符
        val tampered = cipher.substring(0, cipher.length - 4) + "AAAA"
        val decrypted = keyStore.decrypt(tampered)
        assertNull("被篡改密文由于 GCM 认证 Tag 失败应安全返回 null", decrypted)
    }
}
