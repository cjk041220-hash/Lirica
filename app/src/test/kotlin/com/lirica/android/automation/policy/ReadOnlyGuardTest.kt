package com.lirica.android.automation.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 漫游只读拦截器回归（M5.0 §八 双态装配）。
 */
class ReadOnlyGuardTest {

    private val guard = ReadOnlyGuard()

    @Test
    fun testScrollAndBackAreAlwaysReadOnly() {
        assertEquals(
            ReadOnlyGuard.Verdict.ReadOnlyAllowed,
            guard.evaluate(SystemActionCatalog.ID_UI_SWIPE)
        )
        assertEquals(
            ReadOnlyGuard.Verdict.ReadOnlyAllowed,
            guard.evaluate(SystemActionCatalog.ID_UI_GLOBAL_BACK)
        )
    }

    @Test
    fun testInteractionVerbsAreBlockedInRoaming() {
        val blockedVerbs = listOf("关注", "点赞", "投币", "评论", "转发", "收藏", "订阅", "打赏", "购买", "下单", "支付", "下载")
        for (verb in blockedVerbs) {
            val verdict = guard.evaluate(SystemActionCatalog.ID_UI_CLICK, targetText = verb)
            assertTrue(
                "互动词「" + verb + "」必须触发协商而非静默执行",
                verdict is ReadOnlyGuard.Verdict.RequiresNegotiation
            )
            assertEquals(ReadOnlyGuard.Category.INTERACTION_CLICK, (verdict as ReadOnlyGuard.Verdict.RequiresNegotiation).category)
        }
    }

    @Test
    fun testPlaybackIsBlockedBecauseItLeavesHistory() {
        // 方案原文举例：「今晚我想去你的 B 站看几篇纪录片，会留下播放历史哦」
        // 播放必然留痕，因此必须在只读黑名单里——这是方案的原始意图，不是过度拦截。
        val verdict = guard.evaluate(SystemActionCatalog.ID_UI_CLICK, targetText = "立即播放")
        assertTrue(verdict is ReadOnlyGuard.Verdict.RequiresNegotiation)
        assertEquals("播放", (verdict as ReadOnlyGuard.Verdict.RequiresNegotiation).matchedKeyword)
    }

    @Test
    fun testNavigationClicksAreAllowed() {
        // 只读检索必然包含点进内容、翻页、进入分类：这些不命中互动词，必须放行。
        // 否则漫游模式会退化成"只能看首屏"，与方案授权自相矛盾。
        for (label in listOf("搜索", "返回", "首页", "分类", "目录", "下一篇", "更多")) {
            assertEquals(
                "导航类目标「" + label + "」应放行",
                ReadOnlyGuard.Verdict.ReadOnlyAllowed,
                guard.evaluate(SystemActionCatalog.ID_UI_CLICK, targetText = label)
            )
        }
    }

    @Test
    fun testContentDescriptionIsAlsoScanned() {
        // 图标按钮往往没有 text，只有 contentDescription —— 漏扫等于漏拦
        val verdict = guard.evaluate(
            SystemActionCatalog.ID_UI_CLICK,
            targetText = null,
            contentDescription = "点赞这个视频"
        )
        assertTrue(verdict is ReadOnlyGuard.Verdict.RequiresNegotiation)
    }

    @Test
    fun testResourceIdIsAlsoScanned() {
        val verdict = guard.evaluate(
            SystemActionCatalog.ID_UI_CLICK,
            resourceId = "com.example:id/btn_follow" // 命中英文互动词 _follow
        )
        assertTrue(verdict is ReadOnlyGuard.Verdict.RequiresNegotiation)
    }

    @Test
    fun testSearchInputAllowedButCommentInputBlocked() {
        assertEquals(
            "搜索框输入属只读检索，必须放行",
            ReadOnlyGuard.Verdict.ReadOnlyAllowed,
            guard.evaluate(SystemActionCatalog.ID_UI_INPUT_TEXT, targetText = "搜索你想看的内容")
        )
        val comment = guard.evaluate(SystemActionCatalog.ID_UI_INPUT_TEXT, targetText = "写评论")
        assertTrue(comment is ReadOnlyGuard.Verdict.RequiresNegotiation)
        assertEquals(
            ReadOnlyGuard.Category.INTERACTION_INPUT,
            (comment as ReadOnlyGuard.Verdict.RequiresNegotiation).category
        )
    }

    @Test
    fun testOutboundCommunicationAlwaysNeedsNegotiation() {
        for (actionId in listOf(
            SystemActionCatalog.ID_SEND_SMS,
            SystemActionCatalog.ID_SEND_EMAIL,
            SystemActionCatalog.ID_SHARE_TEXT
        )) {
            val verdict = guard.evaluate(actionId)
            assertTrue(verdict is ReadOnlyGuard.Verdict.RequiresNegotiation)
            assertEquals(
                ReadOnlyGuard.Category.OUTBOUND_COMMUNICATION,
                (verdict as ReadOnlyGuard.Verdict.RequiresNegotiation).category
            )
        }
    }

    @Test
    fun testEmptyTargetIsTreatedAsReadOnly() {
        // 无文本节点（纯图标且无无障碍描述）无法判定互动语义：
        // 这里选择放行并交由上层安全闸门处理，而不是把所有无文本点击一律拦死。
        assertEquals(
            ReadOnlyGuard.Verdict.ReadOnlyAllowed,
            guard.evaluate(SystemActionCatalog.ID_UI_CLICK)
        )
    }
}
