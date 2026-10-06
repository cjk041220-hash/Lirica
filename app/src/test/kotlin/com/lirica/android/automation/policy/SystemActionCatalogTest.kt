package com.lirica.android.automation.policy

import com.lirica.android.automation.intent.IntentAction
import com.lirica.android.automation.intent.SettingType
import com.lirica.android.automation.intent.VolumeStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统动作安全目录回归（M5.1 §4.1 三元组表 · 复审 R1/R2/R3 修正固化）。
 *
 * 本测试类的核心价值不是"覆盖率"，而是**把文档事实错误钉死**：
 * R1（手电筒免权限）、R2（SENDTO 免 SEND_SMS 且不漏发）、R3（邮件同策略）
 * 三条一旦被回退，这里会立刻红——因为它们都是"照抄旧文档就会写错"的地方。
 */
class SystemActionCatalogTest {

    @Test
    fun testR1_flashlightRequiresNoPermission() {
        val descriptor = SystemActionCatalog.of(SystemActionCatalog.ID_TOGGLE_FLASHLIGHT)
        assertNotNull(descriptor)
        // R1：CameraManager.setTorchMode 自 API 23 起免权限。
        // 若这里出现 CAMERA，说明有人照抄了错误文档，会触发运行时弹窗并被判定占用相机。
        assertNull("手电筒动作绝不引入 CAMERA 权限（复审 R1）", descriptor!!.permission)
        assertEquals(SafetyRuleIds.FLASHLIGHT_NO_PERMISSION, descriptor.gateRule)
        assertEquals(ActionRiskGrade.LOW, descriptor.riskGrade)
        assertTrue(descriptor.autoPass)
    }

    @Test
    fun testR2_sendSmsIsPrefillDraftWithoutSendSmsPermission() {
        val descriptor = SystemActionCatalog.of(SystemActionCatalog.ID_SEND_SMS)
        assertNotNull(descriptor)
        // R2 前半：ACTION_SENDTO 预填草稿不需要 SEND_SMS
        assertNull("SENDTO 预填形态不需要 SEND_SMS（复审 R2）", descriptor!!.permission)
        // R2 后半：与 §1.2 不变量的一致口径 —— 预填草稿 = 中危 + 二次确认正文
        assertEquals(ActionRiskGrade.MEDIUM, descriptor.riskGrade)
        assertTrue("预填草稿必须经用户二次确认", descriptor.requiresUserConfirmation)
        assertFalse("绝不能自动放行外部通信", descriptor.autoPass)
        assertEquals(SafetyRuleIds.PREFILL_DRAFT_REQUIRES_CONFIRM, descriptor.gateRule)
    }

    @Test
    fun testR3_sendEmailSharesSendSmsPolicy() {
        val sms = SystemActionCatalog.of(SystemActionCatalog.ID_SEND_SMS)!!
        val email = SystemActionCatalog.of(SystemActionCatalog.ID_SEND_EMAIL)!!
        assertEquals("R3：邮件与短信必须同策略", sms.riskGrade, email.riskGrade)
        assertEquals(sms.gateRule, email.gateRule)
        assertEquals(sms.requiresUserConfirmation, email.requiresUserConfirmation)
        assertNull(email.permission)
    }

    @Test
    fun testDialNeverAutoCalls() {
        val descriptor = SystemActionCatalog.of(SystemActionCatalog.ID_DIAL)!!
        assertEquals(SafetyRuleIds.DIAL_PREFILL_ONLY, descriptor.gateRule)
        assertTrue(descriptor.autoPass)
        // 拨号盘只是预填，因此不申请 CALL_PHONE；此处断言无权限即等于"绝不自动呼出"
        assertNull(descriptor.permission)
    }

    @Test
    fun testEveryDescriptorSatisfiesTriadInvariants() {
        assertTrue("目录表不得为空", SystemActionCatalog.all.isNotEmpty())
        for (descriptor in SystemActionCatalog.all) {
            assertTrue("actionId 必须非空", descriptor.actionId.isNotBlank())
            assertTrue("闸门规则必须非空", descriptor.gateRule.isNotBlank())
            // 二次确认与自动放行互斥（构造器已 require，这里再断言一次以防有人放宽构造器）
            assertFalse(
                "动作 " + descriptor.actionId + " 不能既要求确认又自动放行",
                descriptor.requiresUserConfirmation && descriptor.autoPass
            )
            // 高危及以上的动作绝不能自动放行
            if (descriptor.riskGrade.severity >= ActionRiskGrade.SENSITIVE.severity) {
                assertFalse(
                    "敏感及以上动作 " + descriptor.actionId + " 不允许自动放行",
                    descriptor.autoPass
                )
            }
        }
    }

    @Test
    fun testEveryIntentActionIsRegisteredInCatalog() {
        val actions = listOf(
            IntentAction.Dial("10086"),
            IntentAction.SendSms("10086", "hi"),
            IntentAction.SendEmail("a@b.com"),
            IntentAction.SetAlarm(7, 30),
            IntentAction.AddCalendarEvent("会议", 1000L, 2000L),
            IntentAction.OpenMap("故宫"),
            IntentAction.OpenSettings(SettingType.GENERAL),
            IntentAction.LaunchApp("com.example"),
            IntentAction.ShareText("hello"),
            IntentAction.ToggleFlashlight(true),
            IntentAction.AdjustVolume(VolumeStream.MEDIA, 1),
            IntentAction.AdjustBrightness(50)
        )
        for (action in actions) {
            assertNotNull(
                "动作 " + action::class.simpleName + " 未登记在安全目录中（会导致 fail-closed 拒绝）",
                SystemActionCatalog.describe(action)
            )
        }
    }

    @Test
    fun testPermissionOnlyDeclaresTrulyNeededPermissions() {
        // 目录里允许出现的权限仅限两项：日历写入（运行时）与系统设置（特殊）
        val declared = SystemActionCatalog.all.mapNotNull { it.permission }.toSet()
        assertEquals(
            setOf(AutomationPermission.WRITE_CALENDAR, AutomationPermission.WRITE_SETTINGS),
            declared
        )
    }

    @Test
    fun testTraceLeavingActionsCoverMutatingChannels() {
        // 漫游只读拦截依赖这份分类：漏一个就会在漫游模式下留下痕迹
        assertTrue(SystemActionCatalog.leavesTraceInTargetApp(SystemActionCatalog.of(SystemActionCatalog.ID_UI_CLICK)!!))
        assertTrue(SystemActionCatalog.leavesTraceInTargetApp(SystemActionCatalog.of(SystemActionCatalog.ID_UI_INPUT_TEXT)!!))
        assertTrue(SystemActionCatalog.leavesTraceInTargetApp(SystemActionCatalog.of(SystemActionCatalog.ID_SEND_SMS)!!))
        // 设备级协助动作不算"在对方应用里留痕"，漫游下照常放行
        assertFalse(SystemActionCatalog.leavesTraceInTargetApp(SystemActionCatalog.of(SystemActionCatalog.ID_TOGGLE_FLASHLIGHT)!!))
        assertFalse(SystemActionCatalog.leavesTraceInTargetApp(SystemActionCatalog.of(SystemActionCatalog.ID_SET_ALARM)!!))
    }

    @Test
    fun testDescriptorRejectsEmptyActionId() {
        val thrown = runCatching {
            SafeActionDescriptor(
                actionId = "",
                channel = ActionChannel.INTENT,
                riskGrade = ActionRiskGrade.LOW,
                gateRule = "X",
                requiresUserConfirmation = false,
                autoPass = true
            )
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun testRiskGradeMapsToGatekeeperRiskLevel() {
        assertEquals(com.lirica.android.automation.gatekeeper.RiskLevel.SAFE, ActionRiskGrade.LOW.toRiskLevel())
        assertEquals(
            com.lirica.android.automation.gatekeeper.RiskLevel.MEDIUM_RISK_AUDIT,
            ActionRiskGrade.MEDIUM.toRiskLevel()
        )
        assertEquals(
            com.lirica.android.automation.gatekeeper.RiskLevel.HIGH_RISK_CONFIRM,
            ActionRiskGrade.SENSITIVE.toRiskLevel()
        )
        assertEquals(
            com.lirica.android.automation.gatekeeper.RiskLevel.CRITICAL_BLOCK,
            ActionRiskGrade.CRITICAL.toRiskLevel()
        )
    }
}
