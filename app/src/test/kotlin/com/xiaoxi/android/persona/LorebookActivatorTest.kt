package com.xiaoxi.android.persona

import com.xiaoxi.android.persona.model.CharacterBook
import com.xiaoxi.android.persona.model.CharacterBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookActivatorTest {

    @Test
    fun testConstantEntriesAlwaysActivated() {
        val book = CharacterBook(
            name = "Test Book",
            entries = listOf(
                CharacterBookEntry(
                    id = 1,
                    keys = listOf("magic"),
                    content = "世界观恒定基础设定",
                    constant = true
                )
            )
        )

        val activated = LorebookActivator.activateEntries(book, "今天天气真好")
        assertEquals(1, activated.size)
        assertEquals("世界观恒定基础设定", activated[0].content)
    }

    @Test
    fun testKeywordMatchingAndCaseSensitivity() {
        val book = CharacterBook(
            name = "Test Book",
            entries = listOf(
                CharacterBookEntry(
                    id = 1,
                    keys = listOf("coffee", "咖啡"),
                    content = "小汐喜欢喝手冲咖啡",
                    caseSensitive = false
                ),
                CharacterBookEntry(
                    id = 2,
                    keys = listOf("Apple"),
                    content = "严格大小写苹果设定",
                    caseSensitive = true
                )
            )
        )

        // 命中不区分大小写
        val activated1 = LorebookActivator.activateEntries(book, "I want some COFFEE today.")
        assertEquals(1, activated1.size)
        assertEquals("小汐喜欢喝手冲咖啡", activated1[0].content)

        // 验证区分大小写
        val activatedLowerApple = LorebookActivator.activateEntries(book, "i have an apple.")
        assertEquals(0, activatedLowerApple.size)

        val activatedUpperApple = LorebookActivator.activateEntries(book, "i have an Apple.")
        assertEquals(1, activatedUpperApple.size)
        assertEquals("严格大小写苹果设定", activatedUpperApple[0].content)
    }

    @Test
    fun testSelectiveRequiresBothPrimaryAndSecondary() {
        val book = CharacterBook(
            name = "Test Book",
            entries = listOf(
                CharacterBookEntry(
                    id = 1,
                    keys = listOf("游戏"),
                    secondaryKeys = listOf("黑神话", "悟空"),
                    content = "黑神话悟空白金通关感悟",
                    selective = true
                )
            )
        )

        // 仅命中 primary keys，未命中 secondary keys
        val activatedPrimaryOnly = LorebookActivator.activateEntries(book, "我最近在玩别的游戏")
        assertEquals(0, activatedPrimaryOnly.size)

        // 仅命中 secondary keys，未命中 primary keys
        val activatedSecondaryOnly = LorebookActivator.activateEntries(book, "悟空真帅")
        assertEquals(0, activatedSecondaryOnly.size)

        // 同时命中
        val activatedBoth = LorebookActivator.activateEntries(book, "这个游戏叫黑神话悟空")
        assertEquals(1, activatedBoth.size)
        assertEquals("黑神话悟空白金通关感悟", activatedBoth[0].content)
    }

    @Test
    fun testDisabledEntriesSkipped() {
        val book = CharacterBook(
            entries = listOf(
                CharacterBookEntry(
                    id = 1,
                    keys = listOf("音乐"),
                    content = "禁用条目",
                    enabled = false
                )
            )
        )

        val activated = LorebookActivator.activateEntries(book, "来听音乐吧")
        assertEquals(0, activated.size)
    }

    @Test
    fun testInsertionOrderSortingAndFormatting() {
        val book = CharacterBook(
            name = "赛博朋克设定集",
            entries = listOf(
                CharacterBookEntry(
                    keys = listOf("义体"),
                    content = "军工级义体受严格管制",
                    insertionOrder = 200
                ),
                CharacterBookEntry(
                    keys = listOf("夜之城"),
                    content = "夜之城位于加州自由州",
                    insertionOrder = 50
                )
            )
        )

        val activated = LorebookActivator.activateEntries(book, "在夜之城安装军工级义体")
        assertEquals(2, activated.size)
        // 50 先于 200
        assertEquals("夜之城位于加州自由州", activated[0].content)
        assertEquals("军工级义体受严格管制", activated[1].content)

        val formatted = LorebookActivator.formatActivatedLorebook(activated, book.name)
        assertNotNull(formatted)
        assertTrue(formatted!!.contains("## 赛博朋克设定集"))
        assertTrue(formatted.contains("- 夜之城位于加州自由州"))
        assertTrue(formatted.contains("- 军工级义体受严格管制"))
    }
}
