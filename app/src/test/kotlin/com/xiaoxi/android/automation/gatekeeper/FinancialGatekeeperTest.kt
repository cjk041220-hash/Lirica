package com.xiaoxi.android.automation.gatekeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FinancialGatekeeperTest {

    private var currentTime = 1000000L
    private lateinit var gatekeeper: FinancialGatekeeperImpl

    @Before
    fun setUp() {
        currentTime = 1000000L
        gatekeeper = FinancialGatekeeperImpl(
            tokenTtlMs = 300000L, // 5 minutes
            timeProvider = { currentTime }
        )
    }

    @Test
    fun testEvaluate_passwordTarget_physicallyBlocked() {
        val action = ProposedAction(
            actionType = "INPUT",
            targetText = "",
            isPasswordTarget = true,
            currentPackage = "com.example.app"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.PhysicalBlocked)
        val blocked = decision as GatekeeperDecision.PhysicalBlocked
        assertEquals("RULE_ZERO_TOLERANCE_PASSWORD", blocked.rule)
    }

    @Test
    fun testEvaluate_passwordResourceId_physicallyBlocked() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetResourceId = "com.example.app:id/et_login_pwd",
            currentPackage = "com.example.app"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.PhysicalBlocked)
    }

    @Test
    fun testEvaluate_paymentKeyword_physicallyBlocked() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetText = "立即支付 99.00元",
            currentPackage = "com.sankuai.meituan"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.PhysicalBlocked)
        val blocked = decision as GatekeeperDecision.PhysicalBlocked
        assertEquals("RULE_CRITICAL_PAYMENT_FUSE", blocked.rule)
    }

    @Test
    fun testEvaluate_bankAppPayment_physicallyBlocked() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetText = "确认转账",
            currentPackage = "com.eg.android.AlipayGphone"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.PhysicalBlocked)
    }

    @Test
    fun testEvaluate_systemPermissionEscalation_physicallyBlocked() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetText = "始终允许",
            currentPackage = "com.android.permissioncontroller"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.PhysicalBlocked)
        val blocked = decision as GatekeeperDecision.PhysicalBlocked
        assertEquals("RULE_SYSTEM_PRIVILEGE_FUSE", blocked.rule)
    }

    @Test
    fun testEvaluate_highRiskOrder_requiresUserConfirmation() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetText = "提交订单",
            currentPackage = "com.taobao.taobao"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.RequireUserConfirmation)
        val req = decision as GatekeeperDecision.RequireUserConfirmation
        assertEquals(RiskLevel.HIGH_RISK_CONFIRM, req.details.level)
        assertTrue(req.token.isNotBlank())
    }

    @Test
    fun testConfirmationFlow_approveAndConsume() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetText = "发红包",
            currentPackage = "com.tencent.mm"
        )
        val decision = gatekeeper.evaluate(action) as GatekeeperDecision.RequireUserConfirmation
        val token = decision.token

        // Initial state: not approved
        assertFalse(gatekeeper.isActionApproved(token))

        // Approve
        assertTrue(gatekeeper.confirmAction(token))
        assertTrue(gatekeeper.isActionApproved(token))

        // Consume (one-time use)
        assertTrue(gatekeeper.consumeApproval(token))
        assertFalse(gatekeeper.isActionApproved(token)) // Consumed
    }

    @Test
    fun testConfirmationFlow_expiredToken_rejected() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetText = "提交订单",
            currentPackage = "com.jingdong.app.mall"
        )
        val decision = gatekeeper.evaluate(action) as GatekeeperDecision.RequireUserConfirmation
        val token = decision.token

        // Advance time past 5 minutes TTL
        currentTime += 350000L

        assertFalse(gatekeeper.confirmAction(token))
        assertFalse(gatekeeper.isActionApproved(token))
        assertFalse(gatekeeper.consumeApproval(token))
    }

    @Test
    fun testEvaluate_mediumRiskAction_allowedWithAudit() {
        val action = ProposedAction(
            actionType = "INTENT",
            targetText = "拨打电话",
            currentPackage = "com.android.dialer"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.Allowed)
        assertEquals(RiskLevel.MEDIUM_RISK_AUDIT, (decision as GatekeeperDecision.Allowed).riskLevel)
    }

    @Test
    fun testEvaluate_safeBrowsingAction_allowed() {
        val action = ProposedAction(
            actionType = "CLICK",
            targetText = "查看更多评论",
            currentPackage = "com.zhihu.android"
        )
        val decision = gatekeeper.evaluate(action)
        assertTrue(decision is GatekeeperDecision.Allowed)
        assertEquals(RiskLevel.SAFE, (decision as GatekeeperDecision.Allowed).riskLevel)
    }
}
