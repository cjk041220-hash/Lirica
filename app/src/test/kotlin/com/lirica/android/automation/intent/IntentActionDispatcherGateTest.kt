package com.lirica.android.automation.intent

import android.content.Context
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.policy.AutomationPermission
import com.lirica.android.automation.policy.AutomationPermissionProvider
import com.lirica.android.automation.policy.SafetyGrant
import com.lirica.android.automation.policy.SafetyPolicyEngineImpl
import com.lirica.android.automation.policy.SystemActionCatalog
import com.lirica.android.automation.policy.SystemActionCatalog.ID_SEND_SMS
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Intent 通道安全收口回归（M5.1 P0-1：闸门必须装在 dispatch() 入口）。
 *
 * 这些用例是"Intent 路径不再是未授权通信漏洞"的可执行证据：
 * 任何绕过闸门的重构都会让它们变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentActionDispatcherGateTest {

    private class FakeSystemActionExecutor : SystemActionExecutor {
        val calls = mutableListOf<String>()
        var nextResult: SystemActionResult = SystemActionResult.Success("ok")

        override fun toggleFlashlight(enabled: Boolean): SystemActionResult {
            calls.add("flashlight:" + enabled)
            return nextResult
        }

        override fun adjustVolume(stream: VolumeStream, deltaSteps: Int): SystemActionResult {
            calls.add("volume:" + stream.name + ":" + deltaSteps)
            return nextResult
        }

        override fun adjustBrightness(percent: Int): SystemActionResult {
            calls.add("brightness:" + percent)
            return nextResult
        }

        override fun insertCalendarEvent(action: IntentAction.AddCalendarEvent): SystemActionResult {
            calls.add("calendar:" + action.title)
            return nextResult
        }
    }

    private val context: Context = mockk(relaxed = true)
    private val systemExecutor = FakeSystemActionExecutor()

    private fun dispatcher(
        mode: ActionExecutionMode = ActionExecutionMode.SHARED,
        permissions: AutomationPermissionProvider = AutomationPermissionProvider.ALL_GRANTED
    ): Pair<IntentActionDispatcherImpl, SafetyPolicyEngineImpl> {
        val policy = SafetyPolicyEngineImpl(
            permissionProvider = permissions,
            initialMode = mode
        )
        return IntentActionDispatcherImpl(
            context = context,
            safetyPolicy = policy,
            systemActionExecutor = systemExecutor
        ) to policy
    }

    @Test
    fun testSendSmsRequiresConfirmationAtDispatchEntry() {
        val (dispatcher, _) = dispatcher()
        val result = dispatcher.dispatch(IntentAction.SendSms("10086", "晚点回家"))
        assertTrue("未确认的短信派发必须被拦在闸门处", result is DispatchResult.ConfirmationRequired)
        val required = result as DispatchResult.ConfirmationRequired
        assertTrue(required.promptMessage.contains("晚点回家"))
    }

    @Test
    fun testSendSmsProceedsAfterGrantConsumed() {
        val (dispatcher, policy) = dispatcher()
        val first = dispatcher.dispatch(IntentAction.SendSms("10086", "晚点回家")) as DispatchResult.ConfirmationRequired
        assertTrue(policy.confirm(first.token))
        val second = dispatcher.dispatch(
            IntentAction.SendSms("10086", "晚点回家"),
            grant = SafetyGrant(first.token, ID_SEND_SMS)
        )
        assertTrue(
            "确认后必须放行到真正的派发环节（而非仍被闸门卡住）",
            second !is DispatchResult.ConfirmationRequired
        )
    }

    @Test
    fun testGrantCannotBeReusedForAnotherAction() {
        val (dispatcher, policy) = dispatcher()
        val required = dispatcher.dispatch(IntentAction.ShareText("hello")) as DispatchResult.ConfirmationRequired
        policy.confirm(required.token)
        // 用分享的凭证去发短信：动作不匹配，必须仍然要求确认
        val reused = dispatcher.dispatch(
            IntentAction.SendSms("10086", "hi"),
            grant = SafetyGrant(required.token, ID_SEND_SMS)
        )
        assertTrue(reused is DispatchResult.ConfirmationRequired)
    }

    @Test
    fun testGrantIsSingleUse() {
        val (dispatcher, policy) = dispatcher()
        val required = dispatcher.dispatch(IntentAction.SendEmail("a@b.com")) as DispatchResult.ConfirmationRequired
        policy.confirm(required.token)
        val actionId = SystemActionCatalog.ID_SEND_EMAIL
        dispatcher.dispatch(IntentAction.SendEmail("a@b.com"), grant = SafetyGrant(required.token, actionId))
        val replay = dispatcher.dispatch(IntentAction.SendEmail("a@b.com"), grant = SafetyGrant(required.token, actionId))
        assertTrue("凭证一次性，重放必须重新确认", replay is DispatchResult.ConfirmationRequired)
    }

    @Test
    fun testCalendarWithoutPermissionGuidesInsteadOfFailing() {
        val (dispatcher, _) = dispatcher(permissions = AutomationPermissionProvider.ALL_DENIED)
        val result = dispatcher.dispatch(IntentAction.AddCalendarEvent("会议", 1000L, 2000L))
        assertTrue(result is DispatchResult.PermissionRequired)
        assertEquals(
            AutomationPermission.WRITE_CALENDAR.manifestPermission,
            (result as DispatchResult.PermissionRequired).permissionName
        )
        assertTrue("缺权限时不得调用系统 API", systemExecutor.calls.isEmpty())
    }

    @Test
    fun testBrightnessWithoutSpecialPermissionGuidesToSettingsPage() {
        val (dispatcher, _) = dispatcher(permissions = AutomationPermissionProvider.ALL_DENIED)
        val result = dispatcher.dispatch(IntentAction.AdjustBrightness(60))
        assertTrue(result is DispatchResult.PermissionRequired)
        assertEquals(
            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
            (result as DispatchResult.PermissionRequired).settingsAction
        )
    }

    @Test
    fun testSystemActionsRouteToSystemExecutor() {
        val (dispatcher, _) = dispatcher()
        assertTrue(dispatcher.dispatch(IntentAction.ToggleFlashlight(true)) is DispatchResult.Success)
        assertTrue(dispatcher.dispatch(IntentAction.AdjustVolume(VolumeStream.MEDIA, 2)) is DispatchResult.Success)
        assertTrue(dispatcher.dispatch(IntentAction.AdjustBrightness(70)) is DispatchResult.Success)
        assertTrue(dispatcher.dispatch(IntentAction.AddCalendarEvent("会议", 1000L, 2000L)) is DispatchResult.Success)
        assertEquals(
            listOf("flashlight:true", "volume:MEDIA:2", "brightness:70", "calendar:会议"),
            systemExecutor.calls
        )
    }

    @Test
    fun testCreateIntentReturnsNullForSystemServiceActions() {
        val (dispatcher, _) = dispatcher()
        assertNull(dispatcher.createIntent(IntentAction.ToggleFlashlight(true)))
        assertNull(dispatcher.createIntent(IntentAction.AdjustVolume(VolumeStream.MEDIA, 1)))
        assertNull(dispatcher.createIntent(IntentAction.AdjustBrightness(50)))
        assertNull(dispatcher.createIntent(IntentAction.AddCalendarEvent("会议", 1L, 2L)))
    }

    @Test
    fun testRoamingModeBlocksOutboundCommunication() {
        val (dispatcher, _) = dispatcher(mode = ActionExecutionMode.ROAMING)
        val result = dispatcher.dispatch(IntentAction.ShareText("hello"))
        assertTrue(result is DispatchResult.SecurityRejected)
        assertTrue((result as DispatchResult.SecurityRejected).reason.contains("漫游"))
    }

    @Test
    fun testRoamingModeStillAllowsLowRiskDeviceActions() {
        val (dispatcher, _) = dispatcher(mode = ActionExecutionMode.ROAMING)
        // 开手电筒是设备级协助，不在被浏览应用里留痕，漫游下照常放行
        assertTrue(dispatcher.dispatch(IntentAction.ToggleFlashlight(true)) is DispatchResult.Success)
        assertTrue(dispatcher.dispatch(IntentAction.SetAlarm(7, 0)) !is DispatchResult.SecurityRejected)
    }

    @Test
    fun testSystemExecutorFailureSurfacesHonestly() {
        val (dispatcher, _) = dispatcher()
        systemExecutor.nextResult = SystemActionResult.Failed("相机被占用")
        val result = dispatcher.dispatch(IntentAction.ToggleFlashlight(true))
        assertTrue(result is DispatchResult.SecurityRejected)
        assertTrue((result as DispatchResult.SecurityRejected).reason.contains("相机被占用"))
    }

    @Test
    fun testSystemExecutorExecutionErrorPropagatesAsThrowable() {
        val (dispatcher, _) = dispatcher()
        systemExecutor.nextResult = SystemActionResult.ExecutionError(IllegalStateException("boom"))
        assertTrue(dispatcher.dispatch(IntentAction.ToggleFlashlight(true)) is DispatchResult.ExecutionError)
    }

    @Test
    fun testRealEngineIsInjectedIntoDispatcher() {
        val (dispatcher, policy) = dispatcher()
        assertEquals(policy, dispatcher.safetyEngineForTest())
        assertEquals(
            SystemActionCatalog.ID_SEND_SMS,
            dispatcher.descriptorOf(IntentAction.SendSms("10086", "x"))?.actionId
        )
    }
}
