package com.lirica.android.network

import com.lirica.android.network.model.ToolCall
import com.lirica.android.network.model.ToolCallFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

object ToolCallParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val THINK_BLOCK_REGEX = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
    private val LEADING_THINK_END_REGEX = Regex("""^\s*</think>\s*""", RegexOption.IGNORE_CASE)
    private val TRAILING_THINK_START_REGEX = Regex("""\s*<think>[\s\S]*$""", RegexOption.IGNORE_CASE)

    private val DSML_INVOKE_REGEX = Regex(
        """<[\s\uFEFF]*[|｜][\s\uFEFF]*DSML[\s\uFEFF]*[|｜][\s\uFEFF]*invoke\s+name=["']([^"']+)["'][^>]*>([\s\S]*?)<[\s\uFEFF]*/[\s\uFEFF]*[|｜][\s\uFEFF]*DSML[\s\uFEFF]*[|｜][\s\uFEFF]*invoke[\s\uFEFF]*>""",
        RegexOption.IGNORE_CASE
    )

    private val DSML_PARAM_REGEX = Regex(
        """<[\s\uFEFF]*[|｜][\s\uFEFF]*DSML[\s\uFEFF]*[|｜][\s\uFEFF]*parameter\s+name=["']([^"']+)["'](?:\s+(?:string|value)=["']([^"']*)["'])?\s*(?:/>|>([\s\S]*?)<[\s\uFEFF]*/[\s\uFEFF]*[|｜][\s\uFEFF]*DSML[\s\uFEFF]*[|｜][\s\uFEFF]*parameter[\s\uFEFF]*>)""",
        RegexOption.IGNORE_CASE
    )

    private val DEEPSEEK_UNICODE_TOOL_REGEX = Regex(
        """<[|｜]tool call begin[|｜]>function<[|｜]tool sep[|｜]>([a-zA-Z0-9_-]+)[\r\n]+([\s\S]*?)<[|｜]tool call end[|｜]>""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_XML_TOOL_REGEX = Regex(
        """<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>""",
        RegexOption.IGNORE_CASE
    )

    fun cleanDisplayText(rawText: String): String {
        if (rawText.isEmpty()) return ""
        var cleaned = rawText
        cleaned = THINK_BLOCK_REGEX.replace(cleaned, "")
        cleaned = LEADING_THINK_END_REGEX.replace(cleaned, "")
        cleaned = TRAILING_THINK_START_REGEX.replace(cleaned, "")

        cleaned = cleaned.replace(Regex("""<[\s\uFEFF]*[|｜][\s\uFEFF]*DSML[\s\uFEFF]*[|｜][\s\uFEFF]*tool_calls[\s\uFEFF]*>[\s\S]*?<[\s\uFEFF]*/[\s\uFEFF]*[|｜][\s\uFEFF]*DSML[\s\uFEFF]*[|｜][\s\uFEFF]*tool_calls[\s\uFEFF]*>""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""</?[\s\uFEFF]*[|｜][\s\uFEFF]*DSML[\s\uFEFF]*[|｜][\s\uFEFF]*[^>]*>""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<[|｜]tool calls[|｜]>[\s\S]*?<[|｜]tool calls end[|｜]>""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<[|｜][^>]+[|｜]>""", RegexOption.IGNORE_CASE), "")
        cleaned = GENERIC_XML_TOOL_REGEX.replace(cleaned, "")

        return cleaned.trim()
    }

    fun extract(rawText: String): Pair<String, List<ToolCall>> {
        val extractedCalls = mutableListOf<ToolCall>()

        DSML_INVOKE_REGEX.findAll(rawText).forEach { match ->
            val toolName = match.groupValues[1].trim()
            val body = match.groupValues[2]
            val argsMap = mutableMapOf<String, JsonElement>()

            DSML_PARAM_REGEX.findAll(body).forEach { pMatch ->
                val paramName = pMatch.groupValues[1].trim()
                val inlineVal = pMatch.groupValues[2]
                val contentVal = pMatch.groupValues[3]
                val rawVal = if (inlineVal.isNotEmpty()) inlineVal else contentVal.trim()

                val parsedElement = runCatching {
                    json.parseToJsonElement(rawVal)
                }.getOrElse {
                    JsonPrimitive(rawVal)
                }
                argsMap[paramName] = parsedElement
            }

            extractedCalls.add(
                ToolCall(
                    id = "call_" + UUID.randomUUID().toString().replace("-", "").take(12),
                    type = "function",
                    function = ToolCallFunction(
                        name = toolName,
                        arguments = JsonObject(argsMap).toString()
                    )
                )
            )
        }

        DEEPSEEK_UNICODE_TOOL_REGEX.findAll(rawText).forEach { match ->
            val toolName = match.groupValues[1].trim()
            var argStr = match.groupValues[2].trim()
            if (argStr.startsWith("```json", ignoreCase = true)) {
                argStr = argStr.removePrefix("```json").removePrefix("```JSON").trim()
            } else if (argStr.startsWith("```")) {
                argStr = argStr.removePrefix("```").trim()
            }
            if (argStr.endsWith("```")) {
                argStr = argStr.removeSuffix("```").trim()
            }

            val validJson = runCatching {
                json.parseToJsonElement(argStr.ifBlank { "{}" }).toString()
            }.getOrDefault("{}")

            extractedCalls.add(
                ToolCall(
                    id = "call_" + UUID.randomUUID().toString().replace("-", "").take(12),
                    type = "function",
                    function = ToolCallFunction(
                        name = toolName,
                        arguments = validJson
                    )
                )
            )
        }

        GENERIC_XML_TOOL_REGEX.findAll(rawText).forEach { match ->
            val jsonPayload = match.groupValues[1].trim()
            runCatching {
                val obj = json.parseToJsonElement(jsonPayload) as? JsonObject
                val name = obj?.get("name")?.let {
                    (it as? JsonPrimitive)?.content
                }
                if (!name.isNullOrBlank()) {
                    val arguments = obj["arguments"]?.toString() ?: "{}"
                    extractedCalls.add(
                        ToolCall(
                            id = "call_" + UUID.randomUUID().toString().replace("-", "").take(12),
                            type = "function",
                            function = ToolCallFunction(
                                name = name,
                                arguments = arguments
                            )
                        )
                    )
                }
            }
        }

        val cleaned = cleanDisplayText(rawText)
        return Pair(cleaned, extractedCalls)
    }
}