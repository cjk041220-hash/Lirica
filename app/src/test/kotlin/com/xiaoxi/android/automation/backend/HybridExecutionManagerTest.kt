package com.xiaoxi.android.automation.backend

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridExecutionManagerTest {

    private val accessibilityBackend = mockk<ExecutionBackend>(relaxed = true)
    private val shizukuBackend = mockk<ExecutionBackend>(relaxed = true)

    @Test
    fun testContainsCjkOrSpecial_accuracy() {
        assertFalse(HybridExecutionManager.containsCjkOrSpecial("hello123_abc"))
        assertFalse(HybridExecutionManager.containsCjkOrSpecial("test-query.com"))
        assertTrue(HybridExecutionManager.containsCjkOrSpecial("搜索商品"))
        assertTrue(HybridExecutionManager.containsCjkOrSpecial("hello你好"))
        assertTrue(HybridExecutionManager.containsCjkOrSpecial("测试，标点符号"))
        assertTrue(HybridExecutionManager.containsCjkOrSpecial("emoji 🚀 test"))
    }

    @Test
    fun testInputText_cjkOrEmoji_100PercentRoutesToAccessibility() = runTest {
        coEvery { shizukuBackend.isAvailable() } returns true
        coEvery { accessibilityBackend.inputText(any()) } returns true

        val manager = HybridExecutionManager(
            accessibilityBackend = accessibilityBackend,
            shizukuBackend = shizukuBackend
        )

        // 包含中文内容：即使 Shizuku 处于就绪状态，也必须 100% 走无障碍通道
        val result = manager.inputText("帮我搜索海阔天空")
        assertTrue(result)

        coVerify(exactly = 1) { accessibilityBackend.inputText("帮我搜索海阔天空") }
        coVerify(exactly = 0) { shizukuBackend.inputText(any()) }
    }

    @Test
    fun testInputText_pureAscii_prefersShizukuWhenAvailable() = runTest {
        coEvery { shizukuBackend.isAvailable() } returns true
        coEvery { shizukuBackend.inputText("xiaoxi") } returns true

        val manager = HybridExecutionManager(
            accessibilityBackend = accessibilityBackend,
            shizukuBackend = shizukuBackend
        )

        val result = manager.inputText("xiaoxi")
        assertTrue(result)

        coVerify(exactly = 1) { shizukuBackend.inputText("xiaoxi") }
        coVerify(exactly = 0) { accessibilityBackend.inputText(any()) }
    }

    @Test
    fun testInputText_pureAscii_whenShizukuFails_fallsBackToAccessibility() = runTest {
        coEvery { shizukuBackend.isAvailable() } returns true
        coEvery { shizukuBackend.inputText("xiaoxi") } returns false
        coEvery { accessibilityBackend.inputText("xiaoxi") } returns true

        val manager = HybridExecutionManager(
            accessibilityBackend = accessibilityBackend,
            shizukuBackend = shizukuBackend
        )

        val result = manager.inputText("xiaoxi")
        assertTrue(result)

        coVerify(exactly = 1) { shizukuBackend.inputText("xiaoxi") }
        coVerify(exactly = 1) { accessibilityBackend.inputText("xiaoxi") }
    }

    @Test
    fun testClick_prefersShizuku_andFallsBackToAccessibility() = runTest {
        coEvery { shizukuBackend.isAvailable() } returns true
        coEvery { shizukuBackend.click(100f, 200f) } returns false
        coEvery { accessibilityBackend.click(100f, 200f) } returns true

        val manager = HybridExecutionManager(
            accessibilityBackend = accessibilityBackend,
            shizukuBackend = shizukuBackend
        )

        val result = manager.click(100f, 200f)
        assertTrue(result)

        coVerify(exactly = 1) { shizukuBackend.click(100f, 200f) }
        coVerify(exactly = 1) { accessibilityBackend.click(100f, 200f) }
    }
}
