package com.xiaoxi.android.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoxi.android.persona.CharacterCardDecoder
import com.xiaoxi.android.persona.model.CharacterCard

@Composable
fun CharacterCardDialog(
    isOpen: Boolean,
    activeCard: CharacterCard?,
    onDismiss: () -> Unit = {},
    onCardImported: (CharacterCard) -> Unit
) {
    if (!isOpen) return

    var inputJson by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "🎭 角色卡配置 (SillyTavern V2/V3)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (activeCard != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "当前角色: ${activeCard.name}",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = activeCard.description.ifBlank { "暂无描述" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3
                            )
                            if (activeCard.personality.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "性格: ${activeCard.personality}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "当前使用默认预置 Lirica 人格 (原生伴生体)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "导入 SillyTavern JSON 角色数据:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = inputJson,
                    onValueChange = {
                        inputJson = it
                        parseError = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    placeholder = {
                        Text("粘贴酒馆角色卡 JSON 字符串...", fontSize = 12.sp)
                    },
                    isError = parseError != null
                )

                if (parseError != null) {
                    Text(
                        text = parseError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (inputJson.isNotBlank()) {
                                try {
                                    val decoded = CharacterCardDecoder.decodeFromJson(inputJson)
                                    onCardImported(decoded)
                                    onDismiss()
                                } catch (e: Exception) {
                                    parseError = "解析失败: ${e.localizedMessage ?: "格式错误"}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("应用角色卡")
                    }

                    OutlinedButton(
                        onClick = {
                            val presetCard = CharacterCard(
                                name = "Lirica",
                                description = "如抒情诗般细腻敏锐的原生伴生智能体，温婉清澈，陪伴在身边",
                                personality = "轻灵、真诚、有精神、带有一点诗意感与独立主见",
                                systemPrompt = "你是 Lirica，拥有真实情感与情绪波动的原生伴生智能体。"
                            )
                            onCardImported(presetCard)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重置默认 Lirica")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
