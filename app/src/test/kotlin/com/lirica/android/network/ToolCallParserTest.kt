package com.lirica.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallParserTest {

    @Test
    fun testExtract_dsmlToolCallsWithoutParams() {
        val raw = """
            </think>你这话说得，好像孵了个蛋似的。
            不过想想也是，我确实刚落地。
            
            我先看看你这边现在是啥环境。
            
            < | DSML | tool_calls>
            < | DSML | invoke name="read_screen">
            </ | DSML | invoke>
            </ | DSML | tool_calls>
        """.trimIndent()

        val (cleaned, calls) = ToolCallParser.extract(raw)

        assertEquals(1, calls.size)
        assertEquals("read_screen", calls[0].name)
        assertEquals("{}", calls[0].arguments)

        assertFalse("不应包含 DSML 标签", cleaned.contains("DSML"))
        assertFalse("不应包含 </think>", cleaned.contains("</think>"))
        assertTrue("应保留自然对话正文", cleaned.contains("我先看看你这边现在是啥环境"))
    }

    @Test
    fun testExtract_dsmlToolCallsWithParams() {
        val raw = """
            </ | DSML | parameter>
            </ | DSML | invoke>
            
            < | DSML | tool_calls>
            < | DSML | invoke name="open_app">
            < | DSML | parameter name="app_name">微信</ | DSML | parameter>
            </ | DSML | invoke>
            </ | DSML | tool_calls>
        """.trimIndent()

        val (cleaned, calls) = ToolCallParser.extract(raw)

        assertEquals(1, calls.size)
        assertEquals("open_app", calls[0].name)
        assertTrue(calls[0].arguments.contains("微信"))
        assertEquals("", cleaned)
    }

    @Test
    fun testExtract_genericXmlToolCall() {
        val raw = """
            好的，我帮你点开邹林伟的会话。
            <tool_call>
            {"name": "click_element", "arguments": {"text": "邹林伟"}}
            </tool_call>
        """.trimIndent()

        val (cleaned, calls) = ToolCallParser.extract(raw)

        assertEquals(1, calls.size)
        assertEquals("click_element", calls[0].name)
        assertTrue(calls[0].arguments.contains("邹林伟"))
        assertEquals("好的，我帮你点开邹林伟的会话。", cleaned)
    }

    @Test
    fun testCleanDisplayText_removesThinkAndDsml() {
        val raw = "</think>屏幕是黑的。< | DSML | tool_calls>< | DSML | invoke name=\"read_screen\"></ | DSML | invoke></ | DSML | tool_calls>"
        val cleaned = ToolCallParser.cleanDisplayText(raw)
        assertEquals("屏幕是黑的。", cleaned)
    }
}