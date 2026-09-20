package com.xiaoxi.android.automation.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.automation.grounding.ElementBounds
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TextInputExecutorTest {

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var gestureExecutor: GestureExecutor
    private lateinit var textInputExecutor: TextInputExecutor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        gestureExecutor = mockk(relaxed = true)
        textInputExecutor = TextInputExecutor(clipboardManager, gestureExecutor)
    }

    @Test
    fun testInjectText_standardSetText_doesNotTouchClipboard() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "用户原本复制的内容"))

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns true
        every { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) } returns true

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "自动化输入的文字")

        assertTrue(success)
        assertEquals("用户原本复制的内容", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
        coVerify(exactly = 0) { gestureExecutor.execute(any()) }
    }

    @Test
    fun testInjectText_fallbackClipboard_restoresOriginalClip() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "关键验证码123456"))

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns false // 不支持 ACTION_SET_TEXT
        every { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) } returns true

        coEvery { gestureExecutor.execute(any()) } returns GestureResult.Completed(200)

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "通过剪贴板粘贴的文本")

        assertTrue(success)
        // 验证执行后剪贴板 100% 还原为原有验证码
        assertEquals("关键验证码123456", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun testInjectText_fallbackClipboard_withEmptyOriginalClip() = runTest {
        clipboardManager.clearPrimaryClip()

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns false
        every { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) } returns true

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "临时文本")

        assertTrue(success)
        // 还原后仍为 null 或空
        assertTrue(clipboardManager.primaryClip == null || clipboardManager.primaryClip?.itemCount == 0)
    }
}
