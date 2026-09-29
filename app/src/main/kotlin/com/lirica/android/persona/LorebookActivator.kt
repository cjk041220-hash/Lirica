package com.lirica.android.persona

import com.lirica.android.persona.model.CharacterBook
import com.lirica.android.persona.model.CharacterBookEntry

/**
 * SillyTavern V3 世界书 / 角色书词条激活器 (Lorebook Activator)
 *
 * 依据 SillyTavern ccv3 规范：
 * - 扫描输入文本 / 对话历史 / 动态上下文
 * - 匹配条目的 keys 关键词（支持 case_sensitive 选项）
 * - 如果 selective == true，还需要同时匹配 secondary_keys 中的至少一个关键词
 * - 如果 constant == true，无需匹配任何关键词，直接强制激活
 * - 按照 insertion_order 升序排序并输出格式化的世界书背景设定块
 */
object LorebookActivator {

    fun activateEntries(
        characterBook: CharacterBook?,
        contextText: String
    ): List<CharacterBookEntry> {
        if (characterBook == null || characterBook.entries.isEmpty()) {
            return emptyList()
        }

        val activated = mutableListOf<CharacterBookEntry>()

        for (entry in characterBook.entries) {
            if (!entry.enabled) continue

            if (entry.constant) {
                activated.add(entry)
                continue
            }

            val isPrimaryMatched = matchAnyKey(contextText, entry.keys, entry.caseSensitive)
            if (!isPrimaryMatched) continue

            if (entry.selective) {
                val isSecondaryMatched = matchAnyKey(contextText, entry.secondaryKeys, entry.caseSensitive)
                if (!isSecondaryMatched) continue
            }

            activated.add(entry)
        }

        return activated.sortedBy { it.insertionOrder }
    }

    private fun matchAnyKey(text: String, keys: List<String>, caseSensitive: Boolean): Boolean {
        if (keys.isEmpty()) return false
        for (key in keys) {
            val cleanKey = key.trim()
            if (cleanKey.isEmpty()) continue
            if (text.contains(cleanKey, ignoreCase = !caseSensitive)) {
                return true
            }
        }
        return false
    }

    fun formatActivatedLorebook(
        activatedEntries: List<CharacterBookEntry>,
        bookName: String? = null
    ): String? {
        if (activatedEntries.isEmpty()) return null
        val title = bookName?.ifBlank { null } ?: "世界书与背景设定"
        return buildString {
            append("## $title\n")
            for (entry in activatedEntries) {
                append("- ${entry.content.trim()}\n")
            }
        }.trim()
    }
}
