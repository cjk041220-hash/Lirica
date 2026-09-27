package com.xiaoxi.android.memory.retrieval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.InputStream

/**
 * 纯 Kotlin 实现的 BERT WordPiece 分词器 (零第三方分词库依赖)
 *
 * 专门适配 `bge-small-zh-v1.5`（或通用中文 BERT / RoBERTa 架构）。
 * 遵循官方 Hugging Face BertTokenizer / BertWordPieceTokenizer 规范：
 * 1. 文本清洗与空白字符规整 (cleanText)
 * 2. 中文字符扩展拆分 (handle_chinese_chars: true，逐字空格隔离)
 * 3. 标点符号与空白切分 (Basic Tokenization)
 * 4. WordPiece 贪婪最大匹配与 `##` 子词合成
 * 5. 特殊标记拼接 ([CLS]=101, [SEP]=102, [PAD]=0, [UNK]=100) 与定长截断
 */
class BertWordPieceTokenizer(
    val vocab: Map<String, Int>,
    val unkTokenId: Long = 100L,
    val clsTokenId: Long = 101L,
    val sepTokenId: Long = 102L,
    val padTokenId: Long = 0L,
    val maxInputCharsPerWord: Int = 100
) {

    data class TokenizedInput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    ) {
        val size: Int get() = inputIds.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as TokenizedInput
            return inputIds.contentEquals(other.inputIds) &&
                    attentionMask.contentEquals(other.attentionMask) &&
                    tokenTypeIds.contentEquals(other.tokenTypeIds)
        }

        override fun hashCode(): Int {
            var result = inputIds.contentHashCode()
            result = 31 * result + attentionMask.contentHashCode()
            result = 31 * result + tokenTypeIds.contentHashCode()
            return result
        }
    }

    /**
     * 将输入文本分词并打包为模型输入 Tensor 格式
     */
    fun tokenize(text: String, maxSeqLen: Int = 512): TokenizedInput {
        val tokens = tokenizeToTokenIds(text)
        val maxContentLen = (maxSeqLen - 2).coerceAtLeast(0)
        val truncated = if (tokens.size > maxContentLen) {
            tokens.subList(0, maxContentLen)
        } else {
            tokens
        }

        val fullIds = LongArray(truncated.size + 2)
        fullIds[0] = clsTokenId
        for (i in truncated.indices) {
            fullIds[i + 1] = truncated[i]
        }
        fullIds[fullIds.size - 1] = sepTokenId

        val attentionMask = LongArray(fullIds.size) { 1L }
        val tokenTypeIds = LongArray(fullIds.size) { 0L }

        return TokenizedInput(
            inputIds = fullIds,
            attentionMask = attentionMask,
            tokenTypeIds = tokenTypeIds
        )
    }

    /**
     * 文本到 Token ID 列表的核心转换逻辑
     */
    fun tokenizeToTokenIds(text: String): List<Long> {
        val cleaned = cleanText(text)
        if (cleaned.isBlank()) return emptyList()

        val chineseSpaced = tokenizeChineseChars(cleaned)
        val words = splitWhitespaceAndPunctuation(chineseSpaced)

        val result = mutableListOf<Long>()
        for (word in words) {
            if (word.isEmpty()) continue
            if (word.length > maxInputCharsPerWord) {
                result.add(unkTokenId)
                continue
            }

            var isBad = false
            var start = 0
            val subTokens = mutableListOf<Long>()

            while (start < word.length) {
                var end = word.length
                var curId: Long? = null
                while (start < end) {
                    val substr = word.substring(start, end)
                    val piece = if (start > 0) "##$substr" else substr
                    val id = vocab[piece]
                    if (id != null) {
                        curId = id.toLong()
                        break
                    }
                    end--
                }
                if (curId == null) {
                    isBad = true
                    break
                }
                subTokens.add(curId)
                start = end
            }

            if (isBad) {
                result.add(unkTokenId)
            } else {
                result.addAll(subTokens)
            }
        }

        return result
    }

    companion object {
        private val JSON_PARSER = Json { ignoreUnknownKeys = true }

        /**
         * 从 tokenizer.json 的完整 JSON 字符串加载词表
         */
        fun fromTokenizerJson(jsonString: String): BertWordPieceTokenizer {
            val root = JSON_PARSER.parseToJsonElement(jsonString).jsonObject
            val model = root["model"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json 中未找到 model 字段")
            val vocabObj = model["vocab"]?.jsonObject
                ?: throw IllegalArgumentException("tokenizer.json 中未找到 vocab 字段")

            val vocab = HashMap<String, Int>(vocabObj.size)
            for ((key, element) in vocabObj) {
                vocab[key] = element.jsonPrimitive.int
            }

            val unkId = vocab["[UNK]"]?.toLong() ?: 100L
            val clsId = vocab["[CLS]"]?.toLong() ?: 101L
            val sepId = vocab["[SEP]"]?.toLong() ?: 102L
            val padId = vocab["[PAD]"]?.toLong() ?: 0L

            return BertWordPieceTokenizer(
                vocab = vocab,
                unkTokenId = unkId,
                clsTokenId = clsId,
                sepTokenId = sepId,
                padTokenId = padId
            )
        }

        /**
         * 从 InputStream 流加载 tokenizer.json
         */
        fun fromInputStream(inputStream: InputStream): BertWordPieceTokenizer {
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromTokenizerJson(content)
        }

        /**
         * 清洗文本：去除无效不可见控制字符，归一化空白
         */
        fun cleanText(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                val cp = ch.code
                if (cp == 0 || cp == 0xFFFD || isControl(ch)) {
                    continue
                }
                if (isWhitespace(ch)) {
                    sb.append(' ')
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        /**
         * 判定是否为 CJK 中文字符编码区间
         */
        fun isChineseChar(cp: Int): Boolean {
            return (cp in 0x4E00..0x9FFF) ||
                    (cp in 0x3400..0x4DBF) ||
                    (cp in 0x20000..0x2A6DF) ||
                    (cp in 0x2A700..0x2B73F) ||
                    (cp in 0x2B740..0x2B81F) ||
                    (cp in 0x2B820..0x2CEAF) ||
                    (cp in 0xF900..0xFAFF) ||
                    (cp in 0x2F800..0x2FA1F)
        }

        /**
         * 将中文字符前后添加空格，确保中文字符独立成词
         */
        fun tokenizeChineseChars(text: String): String {
            val sb = StringBuilder(text.length * 2)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val count = Character.charCount(cp)
                if (isChineseChar(cp)) {
                    sb.append(' ')
                    sb.appendCodePoint(cp)
                    sb.append(' ')
                } else {
                    sb.appendCodePoint(cp)
                }
                i += count
            }
            return sb.toString()
        }

        /**
         * 按照空白切分，并在此基础上按标点符号细分
         */
        fun splitWhitespaceAndPunctuation(text: String): List<String> {
            val result = mutableListOf<String>()
            val rawTokens = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            for (token in rawTokens) {
                val subTokens = splitPunctuation(token)
                result.addAll(subTokens)
            }
            return result
        }

        /**
         * 拆分标点符号（标点符号独立为单独 token）
         */
        fun splitPunctuation(token: String): List<String> {
            val list = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < token.length) {
                val cp = token.codePointAt(i)
                val count = Character.charCount(cp)
                if (isPunctuation(cp)) {
                    if (current.isNotEmpty()) {
                        list.add(current.toString())
                        current.clear()
                    }
                    list.add(String(Character.toChars(cp)))
                } else {
                    current.appendCodePoint(cp)
                }
                i += count
            }
            if (current.isNotEmpty()) {
                list.add(current.toString())
            }
            return list
        }

        private fun isPunctuation(cp: Int): Boolean {
            if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) {
                return true
            }
            val type = Character.getType(cp)
            return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                    type == Character.DASH_PUNCTUATION.toInt() ||
                    type == Character.START_PUNCTUATION.toInt() ||
                    type == Character.END_PUNCTUATION.toInt() ||
                    type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                    type == Character.OTHER_PUNCTUATION.toInt()
        }

        private fun isControl(ch: Char): Boolean {
            if (ch == '\t' || ch == '\n' || ch == '\r') return false
            val type = Character.getType(ch)
            return type == Character.CONTROL.toInt() ||
                    type == Character.FORMAT.toInt() ||
                    type == Character.PRIVATE_USE.toInt() ||
                    type == Character.SURROGATE.toInt() ||
                    type == Character.UNASSIGNED.toInt()
        }

        private fun isWhitespace(ch: Char): Boolean {
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') return true
            val type = Character.getType(ch)
            return type == Character.SPACE_SEPARATOR.toInt() ||
                    type == Character.LINE_SEPARATOR.toInt() ||
                    type == Character.PARAGRAPH_SEPARATOR.toInt()
        }
    }
}
