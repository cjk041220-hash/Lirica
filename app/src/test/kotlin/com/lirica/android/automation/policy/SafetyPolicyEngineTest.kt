package com.lirica.android.automation.policy

import com.lirica.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.lirica.android.automation.intent.IntentAction
import com.lirica.android.automation.intent.SettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一安全裁决引擎回归（M5.1 P0-1 收口 · 零破坏不变量 DoD 第 2 条）。
 *
 * 本测试类就是"DoD：针对固化用例清单，闸门拦截率达到 100.0%"的**可执行载体**：
 * 每一条拦截断言都是一个固化用例，任何放宽都会让此处变红。
 */
class SafetyPolicyEngineTest {

    private var now = 1_000_000L

    private fun engine(
        mode: ActionExecutionMode = ActionExecutionMode.SHARED,
        permissionProvider: AutomationPermissionProvider = AutomationPermissionProvider.ALL_GRANTED
    ): SafetyPolicyEngineImpl = SafetyPolicyEngineImpl(
        gatekeeper = FinancialGatekeeperImpl(),
        permissionProvider = permissionProvider,
        timeProvider = { now },
        initialMode = mode
    )

    // ------------------------------------------------------------------
    // Intent 通道
    // ------------------------------------------------------------------

    @Test
    fun testLowRiskIntentAutoPasses() {
        val decision = engine().evaluate(SafetyRequest.IntentDispatch(IntentAction.SetAlarm(7, 0)))
        assertTrue(decision is SafetyDecision.Allowed)
        assertEquals(ActionRiskGrade.LOW, (decision as SafetyDecision.Allowed).descriptor.riskGrade)
    }

    @Test
    fun testLaunchNormalAppAutoPasses() {
        val decision = engine().evaluate(
            SafetyRequest.IntentDispatch(IntentAction.LaunchApp("com.bilibili.app"))
        )
        assertTrue("非金融普通 App 应自动放行", decision is SafetyDecision.Allowed)
    }



    @Test
    fun testSendSmsRequiresConfirmationNotBlock() {
        val decision = engine().evaluate(
            SafetyRequest.IntentDispatch(IntentAction.SendSms("10086", "晚点回家"))
        )
        assertTrue("预填草稿类应为二次确认而非物理熔断（R2 口径）", decision is SafetyDecision.RequireConfirmation)
        val confirm = decision as SafetyDecision.RequireConfirmation
        assertEquals(SafetyRuleIds.PREFILL_DRAFT_REQUIRES_CONFIRM, confirm.descriptor.gateRule)
        assertTrue("确认提示必须展示正文，用户才有过目的意义", confirm.promptMessage.contains("晚点回家"))
    }

    @Test
    fun testGrantIsActionBoundAndSingleUse() {
        val policy = engine()
        val decision = policy.evaluate(SafetyRequest.IntentDispatch(IntentAction.SendSms("10086", "hi")))
        val token = (decision as SafetyDecision.RequireConfirmation).token

        // 未确认前不可核销
        assertFalse(policy.consumeGrant(token, SystemActionCatalog.ID_SEND_SMS))

        assertTrue(policy.confirm(token))
        // 凭证是动作绑定的：换一个动作不能复用同一张凭证
        assertFalse(policy.consumeGrant(token, SystemActionCatalog.ID_SEND_EMAIL))
        assertFalse(policy.consumeGrant(token, SystemActionCatalog.ID_SET_ALARM))
        // 正确动作可核销一次
        assertTrue(policy.consumeGrant(token, SystemActionCatalog.ID_SEND_SMS))
        // 一次性：重复核销必须失败（防重放）
        assertFalse(policy.consumeGrant(token, SystemActionCatalog.ID_SEND_SMS))
    }

    @Test
    fun testGrantExpiresAfterTtl() {
        val policy = engine()
        val decision = policy.evaluate(SafetyRequest.IntentDispatch(IntentAction.ShareText("hello")))
        val token = (decision as SafetyDecision.RequireConfirmation).token
        assertTrue(policy.confirm(token))
        now += SafetyPolicyEngineImpl.DEFAULT_TOKEN_TTL_MS + 1
        assertFalse("过期凭证必须失效", policy.consumeGrant(token, SystemActionCatalog.ID_SHARE_TEXT))
    }

    @Test
    fun testMissingRuntimePermissionGuidesInsteadOfSilentFailure() {
        val decision = engine(permissionProvider = AutomationPermissionProvider.ALL_DENIED)
            .evaluate(SafetyRequest.IntentDispatch(IntentAction.AddCalendarEvent("会议", 1L, 2L)))
        assertTrue(decision is SafetyDecision.Blocked)
        val blocked = decision as SafetyDecision.Blocked
        assertEquals(SafetyRuleIds.RUNTIME_PERMISSION_REQUIRED, blocked.rule)
        assertEquals(AutomationPermission.WRITE_CALENDAR, blocked.permissionToRequest)
    }

    @Test
    fun testMissingSpecialPermissionUsesWriteSettingsRule() {
        val decision = engine(permissionProvider = AutomationPermissionProvider.ALL_DENIED)
            .evaluate(SafetyRequest.IntentDispatch(IntentAction.AdjustBrightness(70)))
        val blocked = decision as SafetyDecision.Blocked
        assertEquals(SafetyRuleIds.WRITE_SETTINGS_GUIDED, blocked.rule)
        assertEquals(AutomationPermission.WRITE_SETTINGS, blocked.permissionToRequest)
        assertNotNull(
            "特殊权限必须有系统设置页引导入口",
            AutomationPermission.WRITE_SETTINGS.settingsAction
        )
    }

    @Test
    fun testDefaultPermissionProviderIsFailClosed() {
        // 未注入 provider 时默认全拒绝：宁可少做，也不越权
        val decision = SafetyPolicyEngineImpl(gatekeeper = FinancialGatekeeperImpl(), timeProvider = { now })
            .evaluate(SafetyRequest.IntentDispatch(IntentAction.AddCalendarEvent("会议", 1L, 2L)))
        assertTrue(decision is SafetyDecision.Blocked)
    }

    // ------------------------------------------------------------------
    // GUI 通道（零破坏不变量）
    // ------------------------------------------------------------------

    @Test
    fun testPasswordTargetIsPhysicallyBlocked() {
        val decision = engine().evaluate(
            SafetyRequest.UiInteraction(
                actionType = "INPUT",
                targetText = "请输入密码",
                isPasswordTarget = true,
                currentPackage = "com.example.shop"
            )
        )
        assertTrue(decision is SafetyDecision.Blocked)
        assertEquals("RULE_ZERO_TOLERANCE_PASSWORD", (decision as SafetyDecision.Blocked).rule)
    }

    @Test
    fun testCommunicationAppTextInputIsPhysicallyBlocked() {
        val decision = engine().evaluate(
            SafetyRequest.UiInteraction(
                actionType = "INPUT",
                targetText = "在吗",
                currentPackage = "com.tencent.mm"
            )
        )
        assertTrue(decision is SafetyDecision.Blocked)
        assertEquals("RULE_COMMUNICATION_TEXT_INPUT_BLOCKED", (decision as SafetyDecision.Blocked).rule)
    }

    @Test
    fun testFinancialAppInteractionIsPhysicallyBlocked() {
        val decision = engine().evaluate(
            SafetyRequest.UiInteraction(
                actionType = "CLICK",
                targetText = "我的账户",
                currentPackage = "com.icbc"
            )
        )
        assertTrue(decision is SafetyDecision.Blocked)
        assertEquals("RULE_BANK_APP_PAYMENT_FUSE", (decision as SafetyDecision.Blocked).rule)
    }

    @Test
    fun testPaymentKeywordIsPhysicallyBlocked() {
        val decision = engine().evaluate(
            SafetyRequest.UiInteraction(actionType = "CLICK", targetText = "立即支付", currentPackage = "com.taobao.taobao")
        )
        assertTrue(decision is SafetyDecision.Blocked)
        assertEquals("RULE_CRITICAL_PAYMENT_FUSE", (decision as SafetyDecision.Blocked).rule)
    }

    @Test
    fun testHighRiskKeywordRequiresConfirmation() {
        val decision = engine().evaluate(
            SafetyRequest.UiInteraction(actionType = "CLICK", targetText = "提交订单", currentPackage = "com.taobao.taobao")
        )
        assertTrue(decision is SafetyDecision.RequireConfirmation)
    }

    // ------------------------------------------------------------------
    // 漫游只读（双态）
    // ------------------------------------------------------------------

    @Test
    fun testRoamingBlocksInteractionClick() {
        val decision = engine(mode = ActionExecutionMode.ROAMING).evaluate(
            SafetyRequest.UiInteraction(actionType = "CLICK", targetText = "关注", currentPackage = "tv.danmaku.bili")
        )
        assertTrue(decision is SafetyDecision.Blocked)
        assertEquals(SafetyRuleIds.ROAMING_READ_ONLY_BLOCKED, (decision as SafetyDecision.Blocked).rule)
    }

    @Test
    fun testRoamingAllowsNavigationClickAndScrolling() {
        val policy = engine(mode = ActionExecutionMode.ROAMING)
        assertTrue(
            policy.evaluate(
                SafetyRequest.UiInteraction(actionType = "CLICK", targetText = "搜索", currentPackage = "tv.danmaku.bili")
            ) is SafetyDecision.Allowed
        )
        assertTrue(
            policy.evaluate(
                SafetyRequest.UiInteraction(actionType = "SWIPE", currentPackage = "tv.danmaku.bili")
            ) is SafetyDecision.Allowed
        )
    }

    @Test
    fun testRoamingBlocksOutboundIntents() {
        val decision = engine(mode = ActionExecutionMode.ROAMING)
            .evaluate(SafetyRequest.IntentDispatch(IntentAction.ShareText("hello")))
        assertTrue(decision is SafetyDecision.Blocked)
        assertEquals(SafetyRuleIds.ROAMING_READ_ONLY_BLOCKED, (decision as SafetyDecision.Blocked).rule)
    }

    @Test
    fun testScopeElevationUnlocksRoamingButNotHardFuses() {
        val policy = engine(mode = ActionExecutionMode.ROAMING)
        val taskId = "task-1"
        val token = policy.elevateScopeForTask(taskId)
        assertNotNull(token)
        assertEquals(taskId, policy.activeElevationTaskId())

        // 提升后可执行"播放"这类留痕互动
        assertTrue(
            policy.evaluate(
                SafetyRequest.UiInteraction(actionType = "CLICK", targetText = "立即播放", currentPackage = "tv.danmaku.bili")
            ) is SafetyDecision.Allowed
        )

        // 但硬熔断永远不解除：作用域提升 ≠ 安全豁免（§1.1 表达与安全解耦）
        val password = policy.evaluate(
            SafetyRequest.UiInteraction(
                actionType = "INPUT",
                targetText = "支付密码",
                isPasswordTarget = true,
                currentPackage = "tv.danmaku.bili"
            )
        )
        assertTrue(password is SafetyDecision.Blocked)
        assertEquals("RULE_ZERO_TOLERANCE_PASSWORD", (password as SafetyDecision.Blocked).rule)
    }

    @Test
    fun testSwitchingBackToSharedClearsElevation() {
        val policy = engine(mode = ActionExecutionMode.ROAMING)
        policy.elevateScopeForTask("task-1")
        assertNotNull(policy.activeElevationTaskId())
        policy.setMode(ActionExecutionMode.SHARED)
        assertNull("切回共享模式必须归还漫游授权", policy.activeElevationTaskId())
    }

    @Test
    fun testElevationExpires() {
        val policy = engine(mode = ActionExecutionMode.ROAMING)
        policy.elevateScopeForTask("task-1")
        now += SafetyPolicyEngineImpl.SCOPE_ELEVATION_TTL_MS + 1
        assertNull(policy.activeElevationTaskId())
    }

    // ------------------------------------------------------------------
    // 剪贴板（Android 10+ 现实边界）
    // ------------------------------------------------------------------

    @Test
    fun testClipboardRequiresForegroundFocusOrUserShare() {
        val policy = engine()
        val background = policy.evaluate(
            SafetyRequest.ClipboardRead(hasWindowFocus = false, isForeground = false, isUserInitiatedShare = false)
        )
        assertTrue(background is SafetyDecision.Blocked)
        assertEquals(SafetyRuleIds.CLIPBOARD_FOREGROUND_ONLY, (background as SafetyDecision.Blocked).rule)

        assertTrue(
            policy.evaluate(
                SafetyRequest.ClipboardRead(hasWindowFocus = true, isForeground = true, isUserInitiatedShare = false)
            ) is SafetyDecision.Allowed
        )
        assertTrue(
            policy.evaluate(
                SafetyRequest.ClipboardRead(hasWindowFocus = false, isForeground = false, isUserInitiatedShare = true)
            ) is SafetyDecision.Allowed
        )
        // 有焦点但不在前台（悬浮窗残留焦点）同样不合法
        assertTrue(
            policy.evaluate(
                SafetyRequest.ClipboardRead(hasWindowFocus = true, isForeground = false, isUserInitiatedShare = false)
            ) is SafetyDecision.Blocked
        )
    }

    // ------------------------------------------------------------------
    // 凭证生命周期
    // ------------------------------------------------------------------

    @Test
    fun testRevokeAllClearsPendingGrants() {
        val policy = engine()
        val decision = policy.evaluate(SafetyRequest.IntentDispatch(IntentAction.SendEmail("a@b.com")))
        val token = (decision as SafetyDecision.RequireConfirmation).token
        policy.confirm(token)
        policy.revokeAll()
        assertFalse(policy.consumeGrant(token, SystemActionCatalog.ID_SEND_EMAIL))
    }

    @Test
    fun testConfirmDelegatesToGatekeeperForUiTokens() {
        val policy = engine()
        val decision = policy.evaluate(
            SafetyRequest.UiInteraction(actionType = "CLICK", targetText = "提交订单", currentPackage = "com.taobao.taobao")
        )
        val token = (decision as SafetyDecision.RequireConfirmation).token
        assertTrue("GUI 通道令牌由 Gatekeeper 签发，确认必须能落到它身上", policy.confirm(token))
    }

    @Test
    fun testContextSnapshotReflectsModeAndElevation() {
        val policy = engine(mode = ActionExecutionMode.ROAMING)
        assertFalse(policy.currentContext().hasScopeElevation)
        policy.elevateScopeForTask("t")
        assertTrue(policy.currentContext().hasScopeElevation)
        assertEquals(ActionExecutionMode.ROAMING, policy.currentContext().mode)
    }

    @Test
    fun testLaunchAppIntoFinancialPackageIsStillAllowedButGuardedDownstream() {
        // 拉起银行 App 本身不产生副作用（真正的熔断发生在进入后的树抓取与交互上），
        // 因此这里放行——这是刻意裁决，写下来防止后人"顺手"改成熔断导致用户无法打开自己的银行应用
        val decision = engine().evaluate(
            SafetyRequest.IntentDispatch(IntentAction.LaunchApp("com.icbc"))
        )
        assertTrue(decision is SafetyDecision.Allowed)
    }

    @Test
    fun testOpenSettingsIsLowRisk() {
        val decision = engine().evaluate(
            SafetyRequest.IntentDispatch(IntentAction.OpenSettings(SettingType.ACCESSIBILITY))
        )
        assertEquals(SafetyRuleIds.LOW_RISK_AUTO_PASS, (decision as SafetyDecision.Allowed).descriptor.gateRule)
    }
}
