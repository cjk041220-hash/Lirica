package com.xiaoxi.android.memory.retrieval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class BertWordPieceTokenizerTest {

    companion object {
        private lateinit var tokenizer: BertWordPieceTokenizer

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val file = File("src/main/assets/models/bge-small-zh/tokenizer.json")
            tokenizer = BertWordPieceTokenizer.fromTokenizerJson(file.readText())
        }
    }

    @Test
    fun testSpecialTokensPresent() {
        assertEquals(0L, tokenizer.padTokenId)
        assertEquals(100L, tokenizer.unkTokenId)
        assertEquals(101L, tokenizer.clsTokenId)
        assertEquals(102L, tokenizer.sepTokenId)
    }

    @Test
    fun testChineseSentenceTokenization() {
        val input = "喜欢周杰伦"
        val tokenized = tokenizer.tokenize(input)

        // 应该是 [CLS] 喜 欢 周 杰 伦 [SEP] 共 7 个 token
        assertEquals(7, tokenized.size)
        assertEquals(101L, tokenized.inputIds[0])
        assertEquals(102L, tokenized.inputIds[6])

        assertEquals(tokenizer.vocab["喜"]?.toLong(), tokenized.inputIds[1])
        assertEquals(tokenizer.vocab["欢"]?.toLong(), tokenized.inputIds[2])
        assertEquals(tokenizer.vocab["周"]?.toLong(), tokenized.inputIds[3])
        assertEquals(tokenizer.vocab["杰"]?.toLong(), tokenized.inputIds[4])
        assertEquals(tokenizer.vocab["伦"]?.toLong(), tokenized.inputIds[5])

        // attention_mask 与 token_type_ids
        for (mask in tokenized.attentionMask) {
            assertEquals(1L, mask)
        }
        for (typeId in tokenized.tokenTypeIds) {
            assertEquals(0L, typeId)
        }
    }

    @Test
    fun testMixedChineseAndEnglishAndPunctuation() {
        val input = "你好，world！"
        val tokenized = tokenizer.tokenize(input)

        assertTrue(tokenized.size >= 5)
        assertEquals(101L, tokenized.inputIds.first())
        assertEquals(102L, tokenized.inputIds.last())
    }

    @Test
    fun testTruncation() {
        val longText = "周".repeat(600)
        val maxLen = 32
        val tokenized = tokenizer.tokenize(longText, maxSeqLen = maxLen)

        assertEquals(maxLen, tokenized.size)
        assertEquals(101L, tokenized.inputIds.first())
        assertEquals(102L, tokenized.inputIds.last())
    }

    @Test
    fun testEmptyInput() {
        val tokenized = tokenizer.tokenize("   ")
        assertEquals(2, tokenized.size)
        assertEquals(101L, tokenized.inputIds[0])
        assertEquals(102L, tokenized.inputIds[1])
    }

    @Test
    fun testDeterminism() {
        val t1 = tokenizer.tokenize("测试确定性输出")
        val t2 = tokenizer.tokenize("测试确定性输出")
        assertEquals(t1, t2)
    }
}
