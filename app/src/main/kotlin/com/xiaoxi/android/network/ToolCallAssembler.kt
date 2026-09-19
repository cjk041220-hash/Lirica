package com.xiaoxi.android.network

import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.network.model.ToolCall
import com.xiaoxi.android.network.model.ToolCallFunction

class ToolCallAssembler {

    private class InProgressToolCall(
        var id: String = "",
        var name: String = "",
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    private val inProgress = mutableMapOf<Int, InProgressToolCall>()

    fun appendChunk(chunk: LlmStreamEvent.ToolCallChunk) {
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
