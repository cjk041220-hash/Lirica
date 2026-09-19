package com.xiaoxi.android.persona

import com.xiaoxi.android.persona.model.CharacterBook
import com.xiaoxi.android.persona.model.CharacterBookEntry
import com.xiaoxi.android.persona.model.CharacterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32

class CharacterCardDecoderTest {

    private fun createMinimalPngWithTextChunk(keyword: String, textData: String): ByteArray {
        val baos = ByteArrayOutputStream()
        // PNG 8-byte Signature
        baos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // IHDR Chunk
        val ihdrData = ByteArrayOutputStream().apply {
            val dos = DataOutputStream(this)
            dos.writeInt(1) // width
            dos.writeInt(1) // height
            dos.writeByte(8) // bit depth
            dos.writeByte(6) // color type (RGBA)
            dos.writeByte(0) // compression
            dos.writeByte(0) // filter
            dos.writeByte(0) // interlace
        }.toByteArray()
        writeChunk(baos, "IHDR", ihdrData)

        // tEXt Chunk
        val textChunkData = ByteArrayOutputStream().apply {
            write(keyword.toByteArray(Charsets.ISO_8859_1))
            write(0) // null separator
            write(textData.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        writeChunk(baos, "tEXt", textChunkData)

        // IEND Chunk
        writeChunk(baos, "IEND", ByteArray(0))

        return baos.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val dos = DataOutputStream(out)
        dos.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.write(typeBytes)
        dos.write(data)

        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
    }

    @Test
    fun testDecodeRawV2Json() {
        val v2Json = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "小汐",
                "description": "温柔且有主见的伴生智能体",
                "personality": "细腻、反客服腔、拥有自主生活经历",
                "scenario": "在手机端侧常驻陪伴用户",
                "first_mes": "嗨，今天过得顺利吗？",
                "mes_example": "<START>\n{{user}}: 我好累\n{{char}}: 揉揉头，要不要听首舒缓的音乐？",
                "creator_notes": "Project Aether",
                "system_prompt": "你拥有完整自主人格，绝对不准出现客服腔。",
                "post_history_instructions": "[小汐请注意：无论对话长短，始终保持细腻自然的朋友语气]",
                "tags": ["伴生", "自主人格"],
                "creator": "OscarJK",
                "character_version": "2.0.0",
                "character_book": {
                  "name": "小汐世界书",
                  "description": "核心背景设定",
                  "entries": [
                    {
                      "keys": ["音乐", "放歌"],
                      "content": "小汐偏好轻音乐与Lo-Fi",
                      "enabled": true,
                      "insertion_order": 100
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val card = CharacterCardDecoder.decodeFromJson(v2Json)

        assertEquals("小汐", card.name)
        assertEquals("2.0.0", card.characterVersion)
        assertEquals("温柔且有主见的伴生智能体", card.description)
        assertEquals("你拥有完整自主人格，绝对不准出现客服腔。", card.systemPrompt)
        assertEquals("[小汐请注意：无论对话长短，始终保持细腻自然的朋友语气]", card.postHistoryInstructions)
        assertNotNull(card.characterBook)
        assertEquals(1, card.characterBook?.entries?.size)
        assertEquals("小汐偏好轻音乐与Lo-Fi", card.characterBook?.entries?.get(0)?.content)
    }

    @Test
    fun testDecodePngWithV2CharaChunk() {
        val v2Payload = """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "SillyTavernBot",
                "description": "A test bot from ST V2",
                "personality": "Friendly",
                "post_history_instructions": "Stay in character!"
              }
            }
        """.trimIndent()

        val base64Text = Base64.getEncoder().encodeToString(v2Payload.toByteArray(Charsets.UTF_8))
        val pngBytes = createMinimalPngWithTextChunk("chara", base64Text)

        val card = CharacterCardDecoder.decodeFromPng(pngBytes)

        assertEquals("SillyTavernBot", card.name)
        assertEquals("A test bot from ST V2", card.description)
        assertEquals("Friendly", card.personality)
        assertEquals("Stay in character!", card.postHistoryInstructions)
    }

    @Test
    fun testDecodePngWithV3Ccv3Chunk() {
        val v3Payload = """
            {
              "spec": "chara_card_v3",
              "spec_version": "3.0",
              "data": {
                "name": "AetherCompanion",
                "description": "ST V3 Native Spec",
                "personality": "Sentient & Adaptive",
                "system_prompt": "You are a living digital companion.",
                "post_history_instructions": "[Always speak as a peer]",
                "alternate_greetings": ["早安！", "晚上好呀"]
              }
            }
        """.trimIndent()

        val base64Text = Base64.getEncoder().encodeToString(v3Payload.toByteArray(Charsets.UTF_8))
        val pngBytes = createMinimalPngWithTextChunk("ccv3", base64Text)

        val card = CharacterCardDecoder.decodeFromPng(pngBytes)

        assertEquals("AetherCompanion", card.name)
        assertEquals("ST V3 Native Spec", card.description)
        assertEquals("You are a living digital companion.", card.systemPrompt)
        assertEquals(2, card.alternateGreetings.size)
        assertEquals("早安！", card.alternateGreetings[0])
    }

    @Test
    fun testPngEncodeAndDecodeRoundTrip() {
        val originalCard = CharacterCard(
            name = "RoundTripChar",
            description = "Card round-trip test",
            personality = "Robust",
            scenario = "Android Native Execution",
            firstMes = "Ready for action.",
            systemPrompt = "System prompt here.",
            postHistoryInstructions = "PHI instruction here.",
            tags = listOf("native", "kotlin"),
            creator = "Dev",
            characterVersion = "3.0.0",
            characterBook = CharacterBook(
                name = "Book1",
                entries = listOf(
                    CharacterBookEntry(
                        keys = listOf("k1", "k2"),
                        content = "Secret lore content"
                    )
                )
            )
        )

        // 生成基础空白 PNG
        val dummyPng = createMinimalPngWithTextChunk("comment", "placeholder")

        // 编码为携带 V3 元数据的 PNG
        val encodedPng = CharacterCardDecoder.encodeToPng(dummyPng, originalCard)

        // 重新解码
        val decodedCard = CharacterCardDecoder.decodeFromPng(encodedPng)

        assertEquals(originalCard.name, decodedCard.name)
        assertEquals(originalCard.description, decodedCard.description)
        assertEquals(originalCard.personality, decodedCard.personality)
        assertEquals(originalCard.systemPrompt, decodedCard.systemPrompt)
        assertEquals(originalCard.postHistoryInstructions, decodedCard.postHistoryInstructions)
        assertEquals(originalCard.characterBook?.entries?.size, decodedCard.characterBook?.entries?.size)
        assertEquals("Secret lore content", decodedCard.characterBook?.entries?.get(0)?.content)
    }

    @Test
    fun testMacroExpansion() {
        val template = "你好 {{user}}！我是 {{char}}。当前时间是 {{current_time}}，你正在使用 {{current_app}}，剩余电量 {{battery_level}}。"
        val expanded = MacroExpander.expand(
            template = template,
            charName = "小汐",
            userName = "Oscar",
            currentTime = "2026-09-19 18:30",
            currentApp = "微信",
            batteryLevel = "88%"
        )

        val expected = "你好 Oscar！我是 小汐。当前时间是 2026-09-19 18:30，你正在使用 微信，剩余电量 88%。"
        assertEquals(expected, expanded)
    }

    @Test
    fun testDecodeInvalidPngThrowsException() {
        val invalidBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        try {
            CharacterCardDecoder.decodeFromPng(invalidBytes)
            fail("应抛出 IllegalArgumentException 表示非合法 PNG 签名")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("PNG") == true)
        }
    }
}
