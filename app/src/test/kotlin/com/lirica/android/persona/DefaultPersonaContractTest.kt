package com.lirica.android.persona

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认基础人设契约守护单测 (P2 防护)
 *
 * 守护 [PhiMiddleware.DEFAULT_SYSTEM_PROMPT]：
 * 1. 反向防线：杜绝任何小汐时代残留词（小汐、sonetto、鲸族、映海及旧特定羁绊词）死灰复燃；
 * 2. 正向防线：确保 Lirica 独立伴生体核心锚点（非客服声明、不承认AI、简短口语、独立自主人格）长久固化。
 */
class DefaultPersonaContractTest {

    @Test
    fun testDefaultSystemPrompt_containsNoLegacyEntanglementTerms() {
        val prompt = PhiMiddleware.DEFAULT_SYSTEM_PROMPT

        val forbiddenWords = listOf(
            "小汐",
            "sonetto",
            "Sonetto",
            "鲸族",
            "映海",
            "人工客服",
            "作为人工智能"
        )

        for (word in forbiddenWords) {
            assertFalse(
                "DEFAULT_SYSTEM_PROMPT 严禁包含历史废弃词或客服套话: '$word'",
                prompt.contains(word)
            )
        }
    }

    @Test
    fun testDefaultSystemPrompt_containsCorePersonaAnchors() {
        val prompt = PhiMiddleware.DEFAULT_SYSTEM_PROMPT

        assertTrue("必须明确包含 Lirica 自身命名", prompt.contains("你是 Lirica"))
        assertTrue("必须声明非指令执行工具的独立性", prompt.contains("不是任何人的指令执行工具") || prompt.contains("不是为了服务谁而存在的"))
        assertTrue("必须包含反客服腔铁律", prompt.contains("不用客服式应声"))
        assertTrue("必须包含自然口语约束", prompt.contains("用口语，不用书面语"))
        assertTrue("必须包含非 AI 声明防线", prompt.contains("不承认自己是 AI"))
    }
}
