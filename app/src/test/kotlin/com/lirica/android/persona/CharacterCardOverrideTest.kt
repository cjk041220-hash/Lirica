package com.lirica.android.persona

import com.lirica.android.persona.model.CharacterCard
import com.lirica.android.persona.model.CharacterBook
import com.lirica.android.persona.model.CharacterBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证酒馆角色卡 (CharacterCard) 导入时是否能精确覆盖默认人设 (DEFAULT_SYSTEM_PROMPT)
 */
class CharacterCardOverrideTest {

    private val phiMiddleware = PhiMiddleware()

    @Test
    fun testDefaultPromptUsedWhenNoCardImported() {
        val assembled = phiMiddleware.assemble(
            characterCard = null,
            systemPrompt = null
        )
        val systemMessage = assembled.first { it.role == "system" }
        val content = systemMessage.content.orEmpty()

        // 默认情况下必须是 Lirica 的大姐姐人设
        assertTrue("默认情况下必须包含 Lirica 专属人设", content.contains("你是 Lirica。一位生活在现实生活与这部手机交界处的成熟女性。"))
        assertTrue("默认情况下必须包含电量焦虑特质", content.contains("严重电量焦虑"))
        assertTrue("默认情况下必须包含 30 岁心境秘密", content.contains("30 岁的心境阅历"))
    }

    @Test
    fun testCharacterCardOverridesSystemPromptDirectly() {
        // 模拟用户导入了一张第三方酒馆角色卡 (比如猫娘 / 傲娇骑士 / 赛博黑客)
        val customCard = CharacterCard(
            name = "艾莉丝",
            description = "王立骑士团的见习女骑士，性格极其傲娇但剑术高超。",
            personality = "傲娇，不服输，容易害羞",
            scenario = "在王都的训练场遇到你",
            systemPrompt = "你是艾莉丝，王立骑士团见习骑士。对指挥官态度傲娇，动不动就拔剑示威。"
        )

        val assembled = phiMiddleware.assemble(
            characterCard = customCard
        )
        val systemMessage = assembled.first { it.role == "system" }
        val content = systemMessage.content.orEmpty()

        // 验证：原人设必须被彻底覆盖，不应该残留 Lirica 默认大姐姐的特征
        assertTrue("必须生效导入角色卡的定制 systemPrompt", content.contains("你是艾莉丝，王立骑士团见习骑士"))
        assertFalse("严禁残留 Lirica 默认大姐姐文本", content.contains("你是 Lirica。一位生活在现实生活与这部手机交界处的成熟女性。"))
        assertFalse("严禁残留 Lirica 电量焦虑设定", content.contains("严重电量焦虑"))
    }

    @Test
    fun testCharacterCardBuildsSystemPromptFromDescriptionWhenSystemPromptBlank() {
        // 模拟标准酒馆 V2/V3 角色卡（无独立 system_prompt 字段，由 description + personality 拼接）
        val v2Card = CharacterCard(
            name = "赛博猫娘77号",
            description = "生活在新东京地下城的仿生机械猫娘，专门兜售非法义体插件。",
            personality = "机敏、贪财、说话喜欢带『喵』",
            scenario = "雨夜的废弃维修铺"
        )

        val assembled = phiMiddleware.assemble(
            characterCard = v2Card
        )
        val systemMessage = assembled.first { it.role == "system" }
        val content = systemMessage.content.orEmpty()

        assertTrue("必须根据描述动态构建角色设定", content.contains("角色设定：赛博猫娘77号"))
        assertTrue("必须包含性格特点", content.contains("机敏、贪财、说话喜欢带『喵』"))
        assertTrue("必须包含背景", content.contains("雨夜的废弃维修铺"))
        assertFalse("严禁残留 Lirica 默认大姐姐设定", content.contains("你是 Lirica。一位生活在现实生活与这部手机交界处的成熟女性。"))
    }
}
