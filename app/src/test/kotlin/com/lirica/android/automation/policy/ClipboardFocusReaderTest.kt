package com.lirica.android.automation.policy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 剪贴板前台焦点读取回归（M5.0 §4.1 Android 10+ 现实边界 · P2-6）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardFocusReaderTest {

    private val context: Context = mockk(relaxed = true)
    private val clipboardManager: ClipboardManager = mockk(relaxed = true)
    private val policy = SafetyPolicyEngineImpl(timeProvider = { 1_000L })
    private val reader = ClipboardFocusReaderImpl(context, policy)

    @Test
    fun testBackgroundReadIsBlocked() {
        val result = reader.read(
            SafetyRequest.ClipboardRead(hasWindowFocus = false, isForeground = false, isUserInitiatedShare = false)
        )
        assertTrue(result is ClipboardReadResult.Blocked)
        assertEquals(
            SafetyRuleIds.CLIPBOARD_FOREGROUND_ONLY,
            (result as ClipboardReadResult.Blocked).rule
        )
    }

    @Test
    fun testForegroundFocusedReadSucceeds() {
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        every { clipboardManager.hasPrimaryClip() } returns true
        every { clipboardManager.primaryClip } returns ClipData.newPlainText("label", "从剪贴板来的文本")

        val result = reader.read(
            SafetyRequest.ClipboardRead(hasWindowFocus = true, isForeground = true, isUserInitiatedShare = false)
        )
        assertTrue(result is ClipboardReadResult.Success)
        assertEquals("从剪贴板来的文本", (result as ClipboardReadResult.Success).text)
    }

    @Test
    fun testUserInitiatedShareAllowsRead() {
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        every { clipboardManager.hasPrimaryClip() } returns true
        every { clipboardManager.primaryClip } returns ClipData.newPlainText("label", "分享内容")

        val result = reader.read(
            SafetyRequest.ClipboardRead(hasWindowFocus = false, isForeground = false, isUserInitiatedShare = true)
        )
        assertTrue(result is ClipboardReadResult.Success)
    }

    @Test
    fun testEmptyClipboardReturnsEmpty() {
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        every { clipboardManager.hasPrimaryClip() } returns false

        val result = reader.read(
            SafetyRequest.ClipboardRead(hasWindowFocus = true, isForeground = true, isUserInitiatedShare = false)
        )
        assertEquals(ClipboardReadResult.Empty, result)
    }
}
