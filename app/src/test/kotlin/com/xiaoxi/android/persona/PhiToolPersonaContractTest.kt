package com.xiaoxi.android.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「动作伴随语」契约注入测试（M3.0 批次 2 / T2.1）。
 *
 * 契约只应在**有可用工具**时注入：无工具的纯聊天不需要这段约束，
 * 多注入反而稀释其他铁律在生成端的近因权重。
 */
class PhiToolPersonaContractTest {

    private val phi = PhiMiddleware()

    // ── 注入开关 ─────────────────────────────────────────────

    @Test
    fun buildPhi_withContract_includesSpec() {
        val content = phi.buildPhiContent(includeToolPersonaContract = true)
        assertTrue(content.contains("调用系统工具时的伴随语规范"))
        // 反面清单必须逐条在列（比抽象的"别用客服腔"更有效）
        for (phrase in PhiMiddleware.FORBIDDEN_TOOL_TONE_PHRASES) {
            assertTrue("禁止词「$phrase」应在提示词中列出", content.contains(phrase))
        }
        // 正面示例与安全红线
        assertTrue(content.contains("付款"))
    }

    @Test
    fun buildPhi_withoutContract_excludesSpec() {
        val content = phi.buildPhiContent(includeToolPersonaContract = false)
        assertFalse(content.contains("调用系统工具时的伴随语规范"))
        assertFalse(content.contains("伴随语"))
    }

    @Test
    fun buildPhi_defaultIsOff() {
        // 默认关闭：防止忘记传参的调用方意外改变纯聊天行为
        val content = phi.buildPhiContent()
        assertFalse(content.contains("调用系统工具时的伴随语规范"))
    }

    @Test
    fun assemble_withToolsFlag_contractLandsInFinalSystemMessage() {
        val messages = phi.assemble(
            systemPrompt = "测试人设",
            historyMessages = listOf(
                com.xiaoxi.android.network.model.ChatMessage(role = "user", content = "帮我看看天气")
            ),
            includeToolPersonaContract = true
        )

        // PHI 恒为最后一条 system 消息
        val last = messages.last()
        assertEquals("system", last.role)
        assertTrue(last.content.orEmpty().contains("调用系统工具时的伴随语规范"))
        // 不污染前面的 system / history
        assertTrue(
            messages.dropLast(1).none {
                it.content.orEmpty().contains("调用系统工具时的伴随语规范")
            }
        )
    }

    @Test
    fun assemble_withoutToolsFlag_noContract() {
        val messages = phi.assemble(systemPrompt = "测试人设")
        assertTrue(
            messages.none { it.content.orEmpty().contains("调用系统工具时的伴随语规范") }
        )
    }

    // ── 排序：契约在通用语气铁律之前 ─────────────────────────

    @Test
    fun contract_precedesGenericToneAnchor() {
        val content = phi.buildPhiContent(includeToolPersonaContract = true)
        val contractIndex = content.indexOf("调用系统工具时的伴随语规范")
        val toneIndex = content.indexOf(PhiMiddleware.getToneAnchor(PhiMiddleware.DEFAULT_CHAR_NAME))

        assertTrue("契约应被注入", contractIndex >= 0)
        assertTrue("语气铁律应被注入", toneIndex >= 0)
        assertTrue(
            "工具调用态是语气退化最重的场合，针对性契约的近因权重不应低于通用铁律",
            contractIndex < toneIndex
        )
    }

    // ── 契约内容质量（防回退） ───────────────────────────────

    @Test
    fun contract_requiresFirstPersonCommentaryInSameTurn() {
        val contract = PhiMiddleware.TOOL_PERSONA_CONTRACT
        assertTrue(contract.contains("同一轮"))
        assertTrue(contract.contains("第一人称"))
    }

    @Test
    fun contract_forbidsCustomerServiceTone() {
        val contract = PhiMiddleware.TOOL_PERSONA_CONTRACT
        // 三条最典型的执行器腔调必须在被禁之列
        assertTrue(contract.contains("正在为您"))
        assertTrue(contract.contains("请稍等"))
        assertTrue(contract.contains("已为您"))
    }

    @Test
    fun contract_doesNotHardcodeUserName() {
        // 提示词是通用资产，不得写死任何具体用户的称呼
        val contract = PhiMiddleware.TOOL_PERSONA_CONTRACT
        assertFalse(contract.contains("冷冷"))
        assertFalse(contract.contains("俊凯"))
    }
}
