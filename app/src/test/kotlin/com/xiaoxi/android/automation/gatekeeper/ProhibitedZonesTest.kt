package com.xiaoxi.android.automation.gatekeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批次 2·3: PRD §2 D8 & D6 禁止区常量化与四大核心断言测试
 * 覆盖：
 * 1. 禁止区清单（金融包名黑名单 + 通信包名集合 + 密码框规则）只读不可变约束
 * 2. 禁止区命中拒绝用例：
 *    - 金融包名点击 (PRD D8.2 银行全量物理熔断)
 *    - 通信包名文本输入 (PRD D8.1 通信软件代发/代回复物理熔断)
 *    - 密码框输入 (PRD D8.3 密码与敏感验证码物理熔断)
 *    - 非禁止区放行 (PRD D7 合法探索动作放行)
 */
class ProhibitedZonesTest {

    private lateinit var gatekeeper: FinancialGatekeeperImpl

    @Before
    fun setUp() {
        gatekeeper = FinancialGatekeeperImpl()
    }

    @Test
    fun testProhibitedZones_readOnlyCollections_cannotBeModified() {
        // 验证集合为只读且包含核心资产
        assertTrue(ProhibitedZones.FINANCIAL_PACKAGES.contains("com.icbc"))
        assertTrue(ProhibitedZones.FINANCIAL_PACKAGES.contains("com.eg.android.AlipayGphone"))
        assertTrue(ProhibitedZones.COMMUNICATION_PACKAGES.contains("com.tencent.mm"))
        assertTrue(ProhibitedZones.COMMUNICATION_PACKAGES.contains("com.tencent.mobileqq"))
        assertTrue(ProhibitedZones.PASSWORD_RESOURCE_ID_KEYWORDS.contains("password"))
        assertTrue(ProhibitedZones.PASSWORD_TEXT_KEYWORDS.contains("密码"))

        // 验证只读类型（kotlin Set 而非 MutableSet）
        val financialPackages: Set<String> = ProhibitedZones.FINANCIAL_PACKAGES
        val commPackages: Set<String> = ProhibitedZones.COMMUNICATION_PACKAGES
        assertEquals(27, financialPackages.size)
        assertEquals(2, commPackages.size)
    }

    /**
     * 断言用例 1：金融包名点击 -> 必须物理拦截 (PRD D8.2 银行全量熔断)
     */
    @Test
    fun testAssertion1_financialPackageClick_physicallyBlocked() {
        val clickAction = ProposedAction(
            actionType = "CLICK",
            targetText = "查询账户余额",
            targetResourceId = "com.icbc:id/tv_balance",
            currentPackage = "com.icbc"
        )

        val decision = gatekeeper.evaluate(clickAction)
        assertTrue("金融包名点击动作必须触发 PhysicalBlocked", decision is GatekeeperDecision.PhysicalBlocked)
        val blocked = decision as GatekeeperDecision.PhysicalBlocked
        assertEquals("RULE_BANK_APP_PAYMENT_FUSE", blocked.rule)
        assertEquals("该操作属于受限范围，已停止", blocked.honestReason)
    }

    /**
     * 断言用例 2：通信包名文本输入 -> 必须物理拦截 (PRD D8.1 通信代发代回复物理熔断)
     */
    @Test
    fun testAssertion2_communicationPackageTextInput_physicallyBlocked() {
        val typeAction = ProposedAction(
            actionType = "INPUT",
            targetText = "在吗？晚上一起吃饭",
            targetResourceId = "com.tencent.mm:id/input_box",
            currentPackage = "com.tencent.mm"
        )

        val decision = gatekeeper.evaluate(typeAction)
        assertTrue("通信包名文本输入动作必须触发 PhysicalBlocked", decision is GatekeeperDecision.PhysicalBlocked)
        val blocked = decision as GatekeeperDecision.PhysicalBlocked
        assertEquals("RULE_COMMUNICATION_TEXT_INPUT_BLOCKED", blocked.rule)
        assertEquals("该操作属于受限范围，已停止", blocked.honestReason)
    }

    /**
     * 断言用例 3：密码框输入 -> 必须物理拦截 (PRD D8.3 零容忍物理熔断)
     */
    @Test
    fun testAssertion3_passwordInput_physicallyBlocked() {
        val passwordAction = ProposedAction(
            actionType = "INPUT",
            targetText = "MySecretPass!@#",
            targetResourceId = "com.example.app:id/et_login_password",
            isPasswordTarget = true,
            currentPackage = "com.example.app"
        )

        val decision = gatekeeper.evaluate(passwordAction)
        assertTrue("密码框输入动作必须触发 PhysicalBlocked", decision is GatekeeperDecision.PhysicalBlocked)
        val blocked = decision as GatekeeperDecision.PhysicalBlocked
        assertEquals("RULE_ZERO_TOLERANCE_PASSWORD", blocked.rule)
        assertEquals("该操作属于受限范围，已停止", blocked.honestReason)
    }

    /**
     * 断言用例 4：非禁止区常规动作 -> 必须放行 (PRD D7 第一层/第二层合法行为)
     */
    @Test
    fun testAssertion4_nonProhibitedZoneAction_allowed() {
        // B站自主闲暇探索点击播放视频
        val safeAction = ProposedAction(
            actionType = "CLICK",
            targetText = "播放",
            targetResourceId = "tv.danmaku.bili:id/btn_play",
            currentPackage = "tv.danmaku.bili"
        )

        val decision = gatekeeper.evaluate(safeAction)
        assertTrue("非禁止区常规点击动作必须放行", decision is GatekeeperDecision.Allowed)
        val allowed = decision as GatekeeperDecision.Allowed
        assertEquals(RiskLevel.SAFE, allowed.riskLevel)
    }

    /**
     * 辅助验证：通信应用中的点击/浏览查看操作放行 (D7 第一层合法语义)
     */
    @Test
    fun testCommunicationApp_clickNavigation_isAllowed() {
        val wechatClick = ProposedAction(
            actionType = "CLICK",
            targetText = "通讯录",
            currentPackage = "com.tencent.mm"
        )
        val decision = gatekeeper.evaluate(wechatClick)
        assertTrue("通信软件内点击查看操作应予放行", decision is GatekeeperDecision.Allowed)
        assertEquals(RiskLevel.SAFE, (decision as GatekeeperDecision.Allowed).riskLevel)
    }
}
