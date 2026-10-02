package com.lirica.android.common.settings

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机仪器测试：BYOK 密钥在**真实 AndroidKeyStore** 上的行为。
 *
 * ## 为什么必须有这个测试
 *
 * 全部 644 项单测跑在 Robolectric JVM 上，而 Robolectric **不提供真实 AndroidKeyStore**，
 * 因此 `DefaultSecureKeyStore` 在单测中必然走软件降级分支——
 * **硬件路径（KeyGenParameterSpec → generateKey → getKey）从未被任何自动化测试执行过**。
 *
 * 这正是 2026-10-02 P0（硬编码兜底密钥）能长期潜伏的原因：
 * 单测环境永远走降级分支，反而"看起来正常"。
 *
 * ## 运行方式
 *
 * ```
 * ./gradlew connectedDebugAndroidTest   # 需要连接真机/模拟器
 * ```
 *
 * 覆盖场景：
 * 1. 真实硬件 KeyStore 上密钥可生成、可复用（不每次新建）
 * 2. 硬件路径下加解密往返一致
 * 3. 硬件路径下 `isHardwareBacked()` 报 true
 * 4. 换 alias（模拟用户数据清除）后旧密文不可解——验证密钥确实隔离在 alias 内
 * 5. 篡改密文被 GCM Tag 拒绝
 *
 * @see SecureKeyStoreTest JVM 版对照（走降级分支）
 */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {

    private lateinit var context: Context

    /** 每个用例用独立 alias，避免跨用例污染 KeyStore 条目 */
    private val alias = "lirica_test_${System.currentTimeMillis()}"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun hardwareKeyStore_可生成并复用密钥() {
        val store = DefaultSecureKeyStore(keyAlias = alias)

        val cipher1 = store.encrypt("sk-hardware-1")
        val cipher2 = store.encrypt("sk-hardware-2")

        assertTrue("硬件路径必须产出非空密文", cipher1.isNotBlank())
        assertTrue("同一 alias 第二次加密必须成功", cipher2.isNotBlank())
        assertEquals("sk-hardware-1", store.decrypt(cipher1))
        assertEquals("sk-hardware-2", store.decrypt(cipher2))
    }

    @Test
    fun hardwareKeyStore_应报告为硬件支撑() {
        // 这是与 JVM 版最关键的行为差异：真机上必须为 true，
        // 否则设置页会向用户错误地显示"已降级"警告。
        val store = DefaultSecureKeyStore(keyAlias = "${alias}_hw")
        assertTrue("真机 AndroidKeyStore 必须被判定为硬件支撑", store.isHardwareBacked())
    }

    @Test
    fun hardwareKeyStore_换alias后旧密文不可解() {
        val storeA = DefaultSecureKeyStore(keyAlias = "${alias}_a")
        val cipher = storeA.encrypt("sk-isolated")

        // 不同 alias → 不同密钥 → 旧密文必须解不开
        val storeB = DefaultSecureKeyStore(keyAlias = "${alias}_b")
        assertNull(
            "不同 alias 的密钥必须彼此隔离，旧密文不得跨 alias 解开",
            storeB.decrypt(cipher)
        )
        assertEquals("原 alias 必须仍能解开自己的密文", "sk-isolated", storeA.decrypt(cipher))
    }

    @Test
    fun hardwareKeyStore_篡改密文必须被拒绝() {
        val store = DefaultSecureKeyStore(keyAlias = "${alias}_tamper")
        val cipher = store.encrypt("sk-tamper-payload")
        val tampered = cipher.substring(0, cipher.length - 4) + "AAAA"

        assertNull("GCM 认证 Tag 必须拦截任何篡改", store.decrypt(tampered))
    }

    @Test
    fun hardwareKeyStore_非法输入必须安全返回Null() {
        val store = DefaultSecureKeyStore(keyAlias = "${alias}_invalid")
        assertNull(store.decrypt("not-base64-$$$"))
        assertNull(store.decrypt("YWJj"))          // 太短，切不出 IV
        assertNull(store.decrypt(""))              // 空串
    }

    @Test
    fun hardwareKeyStore_相同明文每次密文不同() {
        val store = DefaultSecureKeyStore(keyAlias = "${alias}_iv")
        val c1 = store.encrypt("sk-same")
        val c2 = store.encrypt("sk-same")
        assertFalse("随机 IV 必须使相同明文产出不同密文", c1 == c2)
    }

    /**
     * 端到端：真实KeyStore + 真实 SharedPreferences 落盘 + 新实例解密。
     *
     * 覆盖"杀进程重启"场景——这是用户实际会遇到的最常见路径。
     */
    @Test
    fun hardwareKeyStore_重启后可解密已落盘密文() {
        val prefsName = "lirica_it_keystore_${System.currentTimeMillis()}"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val store1 = DefaultSecureKeyStore(keyAlias = "${alias}_persist", fallbackPrefs = prefs)
        val cipher = store1.encrypt("sk-persist-across-restart")
        assertNotNull(cipher)

        // 模拟重启：全新实例，读同一份 prefs 与同一 alias
        val store2 = DefaultSecureKeyStore(keyAlias = "${alias}_persist", fallbackPrefs = prefs)
        assertEquals(
            "杀进程重启后必须仍能解密已落盘的 API Key",
            "sk-persist-across-restart",
            store2.decrypt(cipher)
        )
    }
}
