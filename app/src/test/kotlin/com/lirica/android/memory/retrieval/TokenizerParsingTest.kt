package com.lirica.android.memory.retrieval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Test
import java.io.File

class TokenizerParsingTest {
    @Test
    fun testParseTokenizerJson() {
        val file = File("src/main/assets/models/bge-small-zh/tokenizer.json")
        val start = System.currentTimeMillis()
        val text = file.readText()
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(text).jsonObject
        val model = root["model"]?.jsonObject ?: error("No model in tokenizer.json")
        val vocabJson = model["vocab"]?.jsonObject ?: error("No vocab in model")
        val vocab = HashMap<String, Int>(vocabJson.size)
        for ((k, v) in vocabJson) {
            vocab[k] = v.jsonPrimitive.int
        }
        val elapsed = System.currentTimeMillis() - start
        println("Loaded ${vocab.size} vocab tokens in ${elapsed}ms")
        assert(vocab.size > 20000)
        assert(vocab["[PAD]"] == 0)
        assert(vocab["[UNK]"] == 100)
        assert(vocab["[CLS]"] == 101)
        assert(vocab["[SEP]"] == 102)
        assert(vocab.containsKey("周"))
        assert(vocab.containsKey("杰"))
        assert(vocab.containsKey("伦"))
    }
}
