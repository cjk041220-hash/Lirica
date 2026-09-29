package com.lirica.android.network

import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.network.model.ToolCall
import com.lirica.android.network.model.ToolCallFunction

class ToolCallAssembler {

    companion object {
        private const val MAX_TOOL_CALL_INDEX = 32
    }

    private class InProgressToolCall(
        var id: String = "",
        var name: String = "",
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    private val inProgress = mutableMapOf<Int, InProgressToolCall>()

    fun appendChunk(chunk: LlmStreamEvent.ToolCallChunk) {
        if (chunk.index < 0 || chunk.index > MAX_TOOL_CALL_INDEX) return
        val target = inProgress.getOrPut(chunk.index) { InProgressToolCall() }
        if (!chunk.id.isNullOrEmpty()) {
            target.id = chunk.id
        }
        if (!chunk.name.isNullOrEmpty()) {
            target.name = chunk.name
        }
        if (chunk.argumentsDelta.isNotEmpty()) {
            target.argumentsBuilder.append(chunk.argumentsDelta)
        }
    }

    fun getCompletedToolCalls(): List<ToolCall> {
        return inProgress.entries
            .sortedBy { it.key }
            .filter { (_, call) -> call.name.isNotBlank() }
            .map { (_, call) ->
                ToolCall(
                    id = call.id,
                    type = "function",
                    function = ToolCallFunction(
                        name = call.name,
                        arguments = call.argumentsBuilder.toString()
                    )
                )
            }
    }

    fun clear() {
        inProgress.clear()
    }
}
