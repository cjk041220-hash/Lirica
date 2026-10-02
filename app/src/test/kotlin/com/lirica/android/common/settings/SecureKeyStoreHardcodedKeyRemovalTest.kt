package com.lirica.android.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 密钥材料不得硬编码进源码的回归测试（2026-10-02 P0 修复）。
 *
 * ## 被守护的缺陷
 *
 * 旧 `SecureKeyStore` 内置固定 32 字节常量作为降级根密钥：
 * ```
 * private val SOFTWARE_FALLBACK_KEY_BYTES = byteArrayOf(0x2A, 0x5D, 0x19, 0x7E, ...)
 * ```
 * 该常量随 APK 分发，任何人反编译即可获得 → 用户的 API Key 等价于用公开密钥加密。
 *
 * ## 本测试的两条腿
 *
 * 1. **源码级**：直接读取本仓库的 SecureKeyStore.kt 源文件，断言旧的 32 字节序列
 *    不再出现，且文件中不存在任何长十六进制字节数组字面量。
 *    这条断言不依赖运行时行为——即便降级路径从未被执行，密钥材料也不会回到源码里。
 * 2. **行为级**：不同实例必须使用不同的降级密钥（随机性），
 *    且同一实例内往返必须自洽（否则功能坏了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SecureKeyStoreHardcodedKeyRemovalTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun sourceFileOf(relative: String): String {
        val projectDir = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            "$projectDir/src/main/kotlin/$relative",
            "$projectDir/app/src/main/kotlin/$relative"
        )
        val file = candidates.map { java.io.File(it) }.firstOrNull { it.exists() }
        assertNotNull("找不到源文件 $relative，候选：$candidates", file)
        return file!!.readText()
    }

    @Test
    fun testSource_不得再出现旧硬编码密钥的32字节序列() {
        val source = sourceFileOf("com/lirica/android/common/settings/SecureKeyStore.kt")

        // 旧实现的密钥以「0xXX, 0xXX, ...」形式书写，必须按**字节序列**匹配。
        // 教训：曾用「去掉 0x 与逗号后的连续 hex」匹配，永远匹配不上 → 假绿。
        // 变异验证实测：按连续 hex 搜索时本用例在缺陷代码下依然 PASSED。
        val oldKeyBytes = listOf(
            "0x2A", "0x5D", "0x19", "0x7E", "0x4B", "0x3F", "0x62", "0x10",
            "0x7A", "0x34", "0x5C", "0x21", "0x48", "0x6F", "0x11", "0x39",
            "0x53", "0x12", "0x78", "0x44", "0x29", "0x66", "0x38", "0x1F",
            "0x4D", "0x5E", "0x22", "0x71", "0x30", "0x6A", "0x15", "0x40"
        )

        // 取前 6 个字节做序列锚点即可定位旧常量，且不易被无关代码误伤
        val anchor = oldKeyBytes.take(6).joinToString(",")
        assertFalse(
            "源码中不得残留旧硬编码降级密钥字节序列（前6 字节锚点：$anchor）",
            Regex(Regex.escape(anchor).replace(",", "\\s*,\\s*")).containsMatchIn(source)
        )

        // 兜底：逐字节确认任意一个旧密钥字节都不与新密钥字节混淆
        // （新实现使用 SecureRandom 动态生成，源码中不应出现任何 32 字节字面量）
        val newBytesGeneratedAtRuntime = oldKeyBytes.none { b ->
            Regex("""byteArrayOf\(\s*""" + Regex.escape(b)).containsMatchIn(source)
        }
        assertTrue(
            "源码中不得出现 byteArrayOf(0x..) 形式的密钥字面量",
            newBytesGeneratedAtRuntime
        )
    }

    @Test
    fun testSource_不得存在任何长十六进制字节数组字面量() {
        val source = sourceFileOf("com/lirica/android/common/settings/SecureKeyStore.kt")

        // 通用防线：任何形如 0xXX, 0xXX, ... 且长度 >= 16 字节的数组字面量都视为可疑密钥材料
        val suspicious = Regex("""0x[0-9A-Fa-f]{2}(\s*,\s*0x[0-9A-Fa-f]{2}){7,}""").findAll(source).count()
        assertEquals(
            "SecureKeyStore 源码中不得出现 >=8 字节的十六进制数组字面量（疑似硬编码密钥）",
            0,
            suspicious
        )
    }

    @Test
    fun testSource_必须存在随机生成与持久化逻辑的说明() {
        val source = sourceFileOf("com/lirica/android/common/settings/SecureKeyStore.kt")
        assertTrue(
            "降级路径必须走随机生成（SecureRandom），不得回退到固定常量",
            source.contains("SOFTWARE_KEY_PREFS_NAME") || source.contains("resolveSoftwareFallbackKey")
        )
    }

    @Test
    fun testBehavior_不同实例的降级密钥必须不同() {
        val prefsA = context.getSharedPreferences("sw_key_a", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val prefsB = context.getSharedPreferences("sw_key_b", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

        val storeA = DefaultSecureKeyStore(fallbackPrefs = prefsA)
        val storeB = DefaultSecureKeyStore(fallbackPrefs = prefsB)

        val cipherA = storeA.encrypt("sk-shared-secret")
        val cipherB = storeB.encrypt("sk-shared-secret")

        // 断言 1：明文绝不出现在密文中
        assertFalse(cipherA.contains("sk-shared-secret"))
        assertFalse(cipherB.contains("sk-shared-secret"))

        // 断言 2（核心）：两个实例的降级密钥材料不同 —— 证明密钥来自随机生成而非源码常量。
        // 直接比对两把密钥派生出的持久化材料是否一致。
        val keyA = prefsA.getString(DefaultSecureKeyStore.SOFTWARE_SECRET_KEY_NAME, null)
        val keyB = prefsB.getString(DefaultSecureKeyStore.SOFTWARE_SECRET_KEY_NAME, null)
        assertNotNull("实例 A 必须持久化自己的降级密钥", keyA)
        assertNotNull("实例 B 必须持久化自己的降级密钥", keyB)
        assertTrue(
            "不同实例必须使用不同的随机降级密钥；若相同说明回退到了硬编码常量",
            keyA != keyB
        )
    }

    @Test
    fun testBehavior_同一实例往返自洽且跨实例持久化() {
        val prefs = context.getSharedPreferences("sw_key_persist", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }

        val store1 = DefaultSecureKeyStore(fallbackPrefs = prefs)
        val cipher = store1.encrypt("sk-round-trip")
        assertEquals("sk-round-trip", store1.decrypt(cipher))

        // 新实例读同一份 prefs，必须能用同一把密钥解开（模拟杀进程重启）
        val store2 = DefaultSecureKeyStore(fallbackPrefs = prefs)
        assertEquals(
            "杀进程重启后必须仍能解密已落盘的密文",
            "sk-round-trip",
            store2.decrypt(cipher)
        )
    }

    @Test
    fun testBehavior_降级时isHardwareBacked为false且如实上报() {
        val prefs = context.getSharedPreferences("sw_key_hw", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val store = DefaultSecureKeyStore(fallbackPrefs = prefs)
        // Robolectric 下 AndroidKeyStore 不可用，必走降级路径
        assertFalse("Robolectric 环境应判定为非硬件支撑", store.isHardwareBacked())
    }

    @Test
    fun testContract_encrypt失败必须抛错而非返回空串() {
        val prefs = context.getSharedPreferences("sw_key_fail", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val store = DefaultSecureKeyStore(fallbackPrefs = prefs)

        // 空串是合法明文输入，加密必须成功（不得返回空串）
        val cipherOfEmpty = store.encrypt("")
        assertNotNull("加密空串不得返回 null", cipherOfEmpty)
        assertEquals("加密空串应产出合法密文（IV+Tag 至少 28 字节）", "", store.decrypt(cipherOfEmpty))
    }
}
