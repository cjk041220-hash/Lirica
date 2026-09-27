package com.xiaoxi.android.automation.healing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * 批次 3 · 失败叙述接口单测：
 * 1. 覆盖全部 6 大核心场景（金融、通信代发、密码框、系统提权、接地失败、自愈耗尽）+ 通用兜底
 * 2. 验证非空、占位符填充（{appName}, {targetElement}）、中性占位标语
 * 3. 验证多变体随机选取机制
 * 4. 验证根据上下文（rule, technicalReason, package, actionType）的自动场景推断
 */
class HonestNarratorTest {

    private lateinit var narrator: HonestNarrator

    @Before
    fun setUp() {
        narrator = HonestNarratorImpl()
    }

    @Test
    fun testFinancialProhibited_narrativeNonEmptyAndContainsAppName() {
        val context = FailureContext(
            rule = "RULE_BANK_APP_PAYMENT_FUSE",
            technicalReason = "Automated execution inside banking app is blocked",
            targetPackage = "com.icbc",
            actionType = "CLICK"
        )
        val narrative = narrator.narrate(context)

        assertNotNull(narrative)
        assertTrue("文案必须非空", narrative.isNotBlank())
        assertTrue("必须包含占位符解析的应用名称", narrative.contains("中国工商银行"))
        assertTrue("必须包含待定稿注明", narrative.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testCommunicationSend_narrativeNonEmptyAndContainsAppName() {
        val context = FailureContext(
            rule = "RULE_COMMUNICATION_TEXT_INPUT_BLOCKED",
            technicalReason = "Automated text input inside communication app is physically blocked",
            targetPackage = "com.tencent.mm",
            actionType = "INPUT"
        )
        val narrative = narrator.narrate(context)

        assertNotNull(narrative)
        assertTrue(narrative.isNotBlank())
        assertTrue("必须包含解析的通信应用名称「微信」", narrative.contains("微信"))
        assertTrue("必须包含代发消息/说话人设文案", narrative.contains("发消息") || narrative.contains("代发"))
        assertTrue("必须包含待定稿注明", narrative.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testPasswordInput_narrativeNonEmptyAndProtectsPrivacy() {
        val context = FailureContext(
            rule = "RULE_ZERO_TOLERANCE_PASSWORD",
            technicalReason = "Password target automation blocked",
            targetPackage = "com.example.app",
            actionType = "INPUT"
        )
        val narrative = narrator.narrate(context)

        assertNotNull(narrative)
        assertTrue(narrative.isNotBlank())
        assertTrue("必须提及密码或隐私保护", narrative.contains("密码"))
        assertTrue("必须包含待定稿注明", narrative.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testSystemPrivilege_narrativeNonEmptyAndMentionsPermission() {
        val context = FailureContext(
            rule = "RULE_SYSTEM_PRIVILEGE_FUSE",
            technicalReason = "Security privilege escalation blocked",
            targetPackage = "com.coloros.safecenter",
            actionType = "CLICK"
        )
        val narrative = narrator.narrate(context)

        assertNotNull(narrative)
        assertTrue(narrative.isNotBlank())
        assertTrue("必须提及系统权限或高危设置", narrative.contains("权限") || narrative.contains("系统"))
        assertTrue("必须包含待定稿注明", narrative.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testGroundingFailed_narrativeNonEmptyAndContainsTargetElement() {
        val context = FailureContext(
            scenario = FailureScenario.GROUNDING_FAILED,
            technicalReason = "NotFound: query button not in active tree",
            targetPackage = "tv.danmaku.bili",
            targetElement = "投币支持",
            actionType = "CLICK"
        )
        val narrative = narrator.narrate(context)

        assertNotNull(narrative)
        assertTrue(narrative.isNotBlank())
        assertTrue("必须包含目标元素名称", narrative.contains("「投币支持」"))
        assertTrue("语气具备活人感/捉迷藏等无奈感", narrative.contains("捉迷藏") || narrative.contains("变样") || narrative.contains("挡住"))
        assertTrue("必须包含待定稿注明", narrative.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testGroundingFailed_defaultTargetElementFallback() {
        val context = FailureContext(
            scenario = FailureScenario.GROUNDING_FAILED,
            technicalReason = "NotFound: element not found",
            targetPackage = "com.example.app",
            targetElement = null // 未提供具体元素
        )
        val narrative = narrator.narrate(context)

        assertTrue("未提供元素时回退为「那个按钮」", narrative.contains("那个按钮"))
    }

    @Test
    fun testSelfHealingExhausted_narrativeNonEmptyAndConfessesDefeat() {
        val context = FailureContext(
            scenario = FailureScenario.SELF_HEALING_EXHAUSTED,
            technicalReason = "Exceeded max recovery attempts (3) for STUCK_LOOP",
            targetPackage = "com.example.app"
        )
        val narrative = narrator.narrate(context)

        assertNotNull(narrative)
        assertTrue(narrative.isNotBlank())
        assertTrue("体现自愈尝试多次无果后的坦白投降与交棒", narrative.contains("试了好几次") || narrative.contains("卡在") || narrative.contains("自愈办法用光"))
        assertTrue("必须包含待定稿注明", narrative.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testGenericFallback_narrativeNonEmpty() {
        val context = FailureContext(
            rule = "UNKNOWN_RULE",
            technicalReason = "Some unknown failure",
            targetPackage = "com.unknown.app"
        )
        val narrative = narrator.narrate(context)

        assertNotNull(narrative)
        assertTrue(narrative.isNotBlank())
        assertTrue("必须包含待定稿注明", narrative.contains("正式话术库待俊凯过目定稿"))
    }

    @Test
    fun testVariantMechanism_returnsDifferentVariantsAcrossRandomSeeds() {
        // 验证每个场景至少有 2-3 个变体
        for (scenario in FailureScenario.entries) {
            val templates = narrator.getTemplates(scenario)
            assertTrue("场景 $scenario 的变体数必须在 2 到 3 之间", templates.size in 2..3)
        }

        // 用受控的 Random 验证变体选择器能准确抽取不同索引的文案
        val mockRandom0 = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextInt(until: Int): Int = 0
        }
        val mockRandom1 = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextInt(until: Int): Int = 1
        }

        val narrator0 = HonestNarratorImpl(random = mockRandom0)
        val narrator1 = HonestNarratorImpl(random = mockRandom1)

        val context = FailureContext(
            scenario = FailureScenario.GROUNDING_FAILED,
            targetElement = "发送"
        )

        val narrative0 = narrator0.narrate(context)
        val narrative1 = narrator1.narrate(context)

        assertNotNull(narrative0)
        assertNotNull(narrative1)
        assertFalse("不同变体选择机制必须产生不同的文案", narrative0 == narrative1)
    }

    @Test
    fun testAppNameResolver_knownAndUnknownPackages() {
        assertEquals("微信", HonestNarratorImpl.resolveDefaultAppName("com.tencent.mm"))
        assertEquals("支付宝", HonestNarratorImpl.resolveDefaultAppName("com.eg.android.AlipayGphone"))
        assertEquals("中国建设银行", HonestNarratorImpl.resolveDefaultAppName("com.chinamworld.main"))
        assertEquals("银行应用", HonestNarratorImpl.resolveDefaultAppName("com.unknown.bank.mobile"))
        assertEquals("支付应用", HonestNarratorImpl.resolveDefaultAppName("com.global.wallet"))
        assertEquals("这个应用", HonestNarratorImpl.resolveDefaultAppName("com.foo.bar"))
        assertEquals("这个应用", HonestNarratorImpl.resolveDefaultAppName(null))
        assertEquals("这个应用", HonestNarratorImpl.resolveDefaultAppName(""))
    }
}
