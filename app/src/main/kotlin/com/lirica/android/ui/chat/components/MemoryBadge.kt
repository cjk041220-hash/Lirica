package com.lirica.android.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lirica.android.ui.chat.MemoryBadgeInfo
import com.lirica.android.ui.theme.LiricaColors

/**
 * 时光回响印记 —— 被记得的证据 (The Evidence of Being Remembered)
 *
 * 将工程上的向量检索外显为具有温情记忆手账感的时光印记。
 */
@Composable
fun MemoryBadge(
    badgeInfo: MemoryBadgeInfo,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    if (badgeInfo.isEmpty) return

    var showDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                onClick?.invoke() ?: run { showDialog = true }
            },
        color = LiricaColors.EchoHighlight,
        contentColor = LiricaColors.VioletLight
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, LiricaColors.BubbleHerBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(LiricaColors.EchoDot)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "🌿 被记得的证据 · ${badgeInfo.totalCount} 处岁月回响",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = LiricaColors.VioletLight
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = Color(0xFF181524),
            titleContentColor = LiricaColors.VioletLight,
            textContentColor = LiricaColors.TextMain,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🌿 时光回响 · 她的记忆手账",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LiricaColors.VioletLight
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "她对你说过的话泛起微光，是因为她依然清晰记得那些属于你们的过往瞬间：",
                        fontSize = 12.sp,
                        color = LiricaColors.TextSecondary,
                        lineHeight = 16.sp
                    )

                    if (badgeInfo.memoryDetails.isNotEmpty()) {
                        MemoryDetailSection(
                            title = "👤 你曾经提起的事情与习惯",
                            items = badgeInfo.memoryDetails,
                            accentColor = LiricaColors.VioletLight
                        )
                    } else if (badgeInfo.userMemories.isNotEmpty()) {
                        MemorySection(
                            title = "👤 你曾经提起的事情与习惯",
                            items = badgeInfo.userMemories,
                            accentColor = LiricaColors.VioletLight
                        )
                    }

                    if (badgeInfo.selfExperiences.isNotEmpty()) {
                        MemorySection(
                            title = "🌸 她曾与你分享的生活感触",
                            items = badgeInfo.selfExperiences,
                            accentColor = LiricaColors.Accent
                        )
                    }

                    if (badgeInfo.thoughts.isNotEmpty()) {
                        MemorySection(
                            title = "💭 她当时藏在心底的未言念头",
                            items = badgeInfo.thoughts,
                            accentColor = LiricaColors.VioletDeep
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("抱抱这段回忆 ✨", color = LiricaColors.VioletLight, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun MemorySection(
    title: String,
    items: List<String>,
    accentColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        for (item in items) {
            Text(
                text = "• $item",
                style = MaterialTheme.typography.bodySmall,
                color = LiricaColors.TextMain.copy(alpha = 0.9f),
                lineHeight = 17.sp,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MemoryDetailSection(
    title: String,
    items: List<com.lirica.android.ui.chat.MemoryTraceDetail>,
    accentColor: Color
) {
    val formatter = remember {
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accentColor.copy(alpha = 0.08f))
            .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        for (item in items) {
            Column(modifier = Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "• ${item.content}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LiricaColors.TextMain.copy(alpha = 0.92f),
                    lineHeight = 17.sp
                )
                if (item.createdAt > 0L) {
                    val timeStr = runCatching {
                        formatter.format(java.time.Instant.ofEpochMilli(item.createdAt))
                    }.getOrDefault("")
                    if (timeStr.isNotBlank()) {
                        Text(
                            text = "  └ $timeStr 记下 · 来自你的原话",
                            fontSize = 10.sp,
                            color = LiricaColors.TextDim
                        )
                    }
                }
            }
        }
    }
}
