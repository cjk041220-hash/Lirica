package com.lirica.android.automation.verify

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双采样抗噪指纹服务回归（M5.0 §5.1.4 · P2-3）。
 *
 * 本测试类的核心命题：**把"没看清"与"没生效"分开**。
 * 二者混同会让加载慢的页面被误判为失败，白白烧掉自愈额度与 120 秒预算。
 */
class StateFingerprintServiceTest {

    private fun view(text: String?, id: String? = null, enabled: Boolean = true) =
        com.lirica.android.perception.ViewSignature(
            text = text,
            className = "android.widget.TextView",
            viewId = id,
            isClickable = true,
            isEnabled = enabled
        )

    private fun sample(vararg nodes: com.lirica.android.perception.ViewSignature) =
        PageSample(packageName = "com.example.app", activityName = "MainActivity", nodes = nodes.toList())

    private fun service(
        samples: List<PageSample?>,
        settleDelayMs: Long = 300L
    ): StateFingerprintService {
        var index = 0
        return StateFingerprintService(
            sampler = {
                val value = samples.getOrNull(index)
                index++
                value
            },
            settleDelayMs = settleDelayMs,
            sleeper = { },
            timeProvider = { 1_000L }
        )
    }

    @Test
    fun testSampleOnceReturnsNullWhenUnavailable() = runTest {
        assertNull(service(listOf(null)).sampleOnce())
    }

    @Test
    fun testSampleOnceReturnsNullForEmptyTree() = runTest {
        assertNull(service(listOf(sample())).sampleOnce())
    }

    @Test
    fun testIdenticalTreesProduceIdenticalFingerprint() = runTest {
        val a = service(listOf(sample(view("搜索")))).sampleOnce()
        val b = service(listOf(sample(view("搜索")))).sampleOnce()
        assertNotNull(a)
        assertEquals(a!!.hash, b!!.hash)
    }

    @Test
    fun testVolatileTextDoesNotChangeFingerprint() = runTest {
        // 时钟、电量、点赞数在跳，但页面结构没变：指纹必须稳定，
        // 否则双采样永远不相等，核销层会一直报"震荡"
        val first = service(listOf(sample(view("12:34"), view("85%"), view("1.2万")))).sampleOnce()
        val second = service(listOf(sample(view("12:35"), view("84%"), view("1.3万")))).sampleOnce()
        assertEquals(first!!.hash, second!!.hash)
    }

    @Test
    fun testStructuralChangeAltersFingerprint() = runTest {
        val first = service(listOf(sample(view("搜索")))).sampleOnce()
        val second = service(listOf(sample(view("结果列表")))).sampleOnce()
        assertNotEquals(first!!.hash, second!!.hash)
    }

    @Test
    fun testAwaitStableRequiresTwoConsecutiveMatches() = runTest {
        val stable = service(
            listOf(sample(view("A")), sample(view("A")))
        ).awaitStable()
        assertTrue(stable is StateFingerprintService.StabilityOutcome.Stable)
        assertEquals(2, (stable as StateFingerprintService.StabilityOutcome.Stable).sampleCount)
    }

    @Test
    fun testAwaitStableReportsUnstableWhenOscillating() = runTest {
        val oscillating = service(
            listOf(sample(view("A")), sample(view("B")), sample(view("C")))
        ).awaitStable()
        assertTrue(oscillating is StateFingerprintService.StabilityOutcome.Unstable)
        assertEquals(3, (oscillating as StateFingerprintService.StabilityOutcome.Unstable).samples.size)
    }

    @Test
    fun testVerifyAfterActionDetectsPageChange() = runTest {
        val verifier = service(
            listOf(sample(view("首页")), sample(view("详情页")), sample(view("详情页")))
        )
        val before = service(listOf(sample(view("首页")))).sampleOnce()!!
        val verification = verifier.verifyAfterAction(before)
        assertTrue(verification is ActionVerification.EffectObserved)
        assertEquals(
            ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED,
            (verification as ActionVerification.EffectObserved).reason
        )
    }

    @Test
    fun testVerifyAfterActionDetectsNodeStateChange() = runTest {
        // 页面指纹没变（同一页），但目标控件从 enabled 变成 disabled：
        // 这正是"点击已生效"的细粒度证据——没有它，开关类控件会被误判为没点到
        val verifier = service(listOf(sample(view("已关注", enabled = true)), sample(view("已关注", enabled = true))))
        val before = service(listOf(sample(view("已关注", enabled = true)))).sampleOnce()!!
        val verification = verifier.verifyAfterAction(
            before = before,
            nodeProbe = { NodeStateToken(enabled = false, checked = false, selected = false, text = "已关注") },
            nodeStateBefore = NodeStateToken(enabled = true, checked = false, selected = false, text = "已关注")
        )
        assertTrue(verification is ActionVerification.EffectObserved)
        assertEquals(
            ActionVerification.EffectReason.NODE_STATE_CHANGED,
            (verification as ActionVerification.EffectObserved).reason
        )
    }

    @Test
    fun testVerifyAfterActionReportsNoEffect() = runTest {
        val verifier = service(listOf(sample(view("首页")), sample(view("首页"))))
        val before = service(listOf(sample(view("首页")))).sampleOnce()!!
        val verification = verifier.verifyAfterAction(before)
        assertTrue(verification is ActionVerification.NoVisibleEffect)
    }

    @Test
    fun testVerifyAfterActionReportsUnavailableWhenTreeUnreadable() = runTest {
        val verifier = service(listOf(null))
        val verification = verifier.verifyAfterAction(null)
        assertTrue(verification is ActionVerification.Unavailable)
    }

    @Test
    fun testVerifyAfterActionReportsUnstableRatherThanFailure() = runTest {
        val verifier = service(listOf(sample(view("A")), sample(view("B")), sample(view("C"))))
        val verification = verifier.verifyAfterAction(null)
        assertTrue("震荡必须与'未生效'区分开", verification is ActionVerification.Unstable)
    }

    @Test
    fun testFingerprintIsInvalidWhenHashIsZero() {
        assertTrue(PageFingerprint.NONE.isValid.not())
    }

    @Test
    fun testConvertExecutionNodesToViewSignatures() {
        val nodes = listOf(
            com.lirica.android.automation.grounding.ExecutionNode(
                nodeId = "n0",
                text = "搜索",
                contentDescription = null,
                resourceId = "com.example:id/search",
                className = "android.widget.Button",
                bounds = com.lirica.android.automation.grounding.ElementBounds(0, 0, 100, 100),
                isClickable = true,
                isEditable = false,
                isCheckable = false,
                isChecked = false,
                isScrollable = false,
                isEnabled = true,
                isVisibleToUser = true,
                isPassword = false
            )
        )
        val signatures = nodes.toViewSignatures()
        assertEquals(1, signatures.size)
        assertEquals("搜索", signatures[0].text)
        assertEquals("com.example:id/search", signatures[0].viewId)
        assertTrue(signatures[0].isClickable)
    }
}
