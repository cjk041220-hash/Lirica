package com.lirica.android.automation.gesture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.automation.grounding.ElementBounds
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

    /**
     * node=null 失败语义：接地失败兜底 bounds 时（HelixGuiCoordinator 旧实现曾硬编码 node=null），
     * ACTION_SET_TEXT 与 ACTION_PASTE 两条注入路径均不可用，必须显式返回 false，
     * 且不冲掉用户原有剪贴板内容。
     */
    @Test
    fun testInjectText_nullNode_failsAndRestoresOriginalClip() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "用户剪贴板里的原文"))

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(null, bounds, "没有节点的注入")

        assertEquals(false, success)
        // 剪贴板快照还原铁律：即使失败也不能冲掉用户内容
        assertEquals("用户剪贴板里的原文", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun testInjectText_passwordField_physicallyBlocked() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "剪贴板敏感数据"))

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isPassword } returns true
        every { node.packageName } returns "com.example.app"

        val bounds = ElementBounds(100, 100, 300, 200)
        val result = textInputExecutor.executeInject(node, bounds, "mypassword123")

        assertTrue(result is TextInputResult.Rejected)
        val rejected = result as TextInputResult.Rejected
        assertEquals("该操作属于受限范围，已停止", rejected.honestReason)
        assertEquals("RULE_ZERO_TOLERANCE_PASSWORD", rejected.rule)
        assertEquals("该操作属于受限范围，已停止", textInputExecutor.lastHonestReason)

        // 剪贴板完全未被污染
        assertEquals("剪贴板敏感数据", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
        coVerify(exactly = 0) { gestureExecutor.execute(any()) }
    }

    @Test
    fun testInjectText_bankAppPayment_physicallyBlocked() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "安全内容"))

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.packageName } returns "com.icbc"
        every { node.text } returns "转账金额"

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "5000", "com.icbc")

        assertEquals(false, success)
        assertEquals("该操作属于受限范围，已停止", textInputExecutor.lastHonestReason)
        assertEquals("安全内容", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
        coVerify(exactly = 0) { gestureExecutor.execute(any()) }
    }

    @Test
    fun testInjectText_wechatApp_physicallyBlocked_doesNotTouchClipboard() = runTest {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("user", "我的原始消息"))

        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.packageName } returns "com.tencent.mm"
        every { node.isEditable } returns true

        val bounds = ElementBounds(100, 100, 300, 200)
        val result = textInputExecutor.executeInject(node, bounds, "代发的消息", "com.tencent.mm")

        assertTrue(result is TextInputResult.Rejected)
        val rejected = result as TextInputResult.Rejected
        assertEquals("RULE_COMMUNICATION_TEXT_INPUT_BLOCKED", rejected.rule)
        assertEquals("该操作属于受限范围，已停止", rejected.honestReason)
        assertEquals("该操作属于受限范围，已停止", textInputExecutor.lastHonestReason)

        // 剪贴板完全未被污染，手势与无障碍注入完全未调用
        assertEquals("我的原始消息", clipboardManager.primaryClip?.getItemAt(0)?.text?.toString())
        coVerify(exactly = 0) { gestureExecutor.execute(any()) }
        io.mockk.verify(exactly = 0) { node.performAction(any()) }
    }

    @Test
    fun testInjectText_qqApp_physicallyBlocked() = runTest {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.packageName } returns "com.tencent.mobileqq"

        val bounds = ElementBounds(100, 100, 300, 200)
        val success = textInputExecutor.injectText(node, bounds, "QQ代发消息", "com.tencent.mobileqq")

        assertEquals(false, success)
        assertEquals("该操作属于受限范围，已停止", textInputExecutor.lastHonestReason)
    }
}
