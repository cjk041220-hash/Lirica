package com.lirica.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lirica.android.ui.chat.components.CharacterCardDialog
import com.lirica.android.ui.chat.components.HerLight
import com.lirica.android.ui.chat.components.MessageItem
import com.lirica.android.ui.chat.components.MoodDebugPanel
import com.lirica.android.ui.chat.components.TypingIndicatorCapsule
import com.lirica.android.ui.theme.LiricaColors

/**
 * Lirica 微信式单一连贯主界面 (One-Stream Architecture)
 *
 * 融合暮夜星紫调色板、顶部在场状态岛 (Presence Island)、
 * 融边柔雾气泡、时光回响记忆手账以及一键打断机制。
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= totalItems - 2
            }
        }
    }

    // 当有新消息进入时平滑滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // 当收到新文本更新时瞬时定位底部
    LaunchedEffect(uiState.messages.lastOrNull()?.text) {
        if (uiState.messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar("提示: $error")
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = LiricaColors.BackgroundDeep
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. 顶部：在场状态岛 (Presence Island)
            PresenceIsland(
                charName = uiState.activeCharacterCard?.name ?: "Lirica",
                currentMoodName = uiState.currentMood.displayName,
                isGenerating = uiState.isStreaming,
                isMindVisible = uiState.isMoodDebugPanelVisible,
                onIslandClick = { viewModel.toggleMoodDebugPanel() },
                onCharacterCardClick = { viewModel.toggleCharacterCardDialog() },
                onSettingsClick = onOpenSettings,
                onInterruptClick = { viewModel.interruptGeneration() }
            )

            // 心智引擎展开卡片
            MoodDebugPanel(
                isVisible = uiState.isMoodDebugPanelVisible,
                moodState = uiState.currentMood,
                vector = uiState.currentEmotionVector
            )

            // 未配置 API Key 引导横幅
            if (!uiState.isApiKeyConfigured || uiState.isKeyPromptBannerVisible) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = LiricaColors.Danger.copy(alpha = 0.15f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .border(1.dp, LiricaColors.Danger.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .clickable { onOpenSettings() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠️ 尚未配置模型密钥，点击前往设置",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = LiricaColors.Danger
                        )
                        Text(
                            text = "去配置 ›",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = LiricaColors.VioletLight
                        )
                    }
                }
            }

            // 2. 中间：单一连贯流 LazyColumn
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.messages.isEmpty()) {
                    item {
                        EmptyPresenceGreeting(
                            charName = uiState.activeCharacterCard?.name ?: "Lirica"
                        )
                    }
                } else {
                    items(
                        items = uiState.messages,
                        key = { it.id }
                    ) { msg ->
                        MessageItem(
                            message = msg
                        )
                    }

                    // 正在输入态：非流式真人发消息指示器 (生成中且最后一条尚在准备时)
                    if (uiState.isStreaming && uiState.messages.lastOrNull()?.text?.isEmpty() == true) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                TypingIndicatorCapsule()
                            }
                        }
                    }
                }
            }

            // 3. 底部悬浮输入条 (带打断切换)
            ChatInputBar(
                text = uiState.inputText,
                isGenerating = uiState.isStreaming,
                onTextChanged = { viewModel.onInputTextChanged(it) },
                onSend = { viewModel.sendMessage() },
                onInterrupt = { viewModel.interruptGeneration() }
            )
        }
    }

    // 角色卡管理弹窗
    CharacterCardDialog(
        isOpen = uiState.isCharacterCardDialogOpen,
        activeCard = uiState.activeCharacterCard,
        onDismiss = { viewModel.toggleCharacterCardDialog() },
        onCardImported = { card -> viewModel.importCharacterCard(card) }
    )
}

/**
 * 顶部在场状态岛 (Presence Island)
 */
@Composable
private fun PresenceIsland(
    charName: String,
    currentMoodName: String,
    isGenerating: Boolean,
    isMindVisible: Boolean,
    onIslandClick: () -> Unit,
    onCharacterCardClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onInterruptClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, LiricaColors.CardBorder, RoundedCornerShape(24.dp)),
        color = LiricaColors.BackgroundSurface.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧：HerLight + 在场小字 (点击展开心境)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onIslandClick() }
            ) {
                HerLight(
                    size = 30.dp,
                    isActing = isGenerating,
                    onClick = onIslandClick
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = charName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = LiricaColors.TextMain
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isGenerating) "正在打字…" else "· $currentMoodName",
                            fontSize = 11.5.sp,
                            color = if (isGenerating) LiricaColors.VioletLight else LiricaColors.TextSecondary,
                            fontWeight = if (isGenerating) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    Text(
                        text = if (isGenerating) "点击光核可打断 • 心境安宁" else "轻触展开心境与念头",
                        fontSize = 10.5.sp,
                        color = LiricaColors.TextDim
                    )
                }
            }

            // 右侧操作群
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isGenerating) {
                    // 打断快捷胶囊
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(LiricaColors.Danger.copy(alpha = 0.18f))
                            .clickable { onInterruptClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "打断 ✕",
                            fontSize = 11.sp,
                            color = LiricaColors.Danger,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // 角色卡入口
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { onCharacterCardClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🎭 角色卡",
                        fontSize = 11.sp,
                        color = LiricaColors.TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // 设置入口
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { onSettingsClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⚙️",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * 空态在场问候
 */
@Composable
private fun EmptyPresenceGreeting(
    charName: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 90.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HerLight(
                size = 54.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "我是 $charName，在手机里陪着你",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = LiricaColors.TextMain
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "不用刻意找话题，想说什么就随口告诉我。",
                style = MaterialTheme.typography.bodySmall,
                color = LiricaColors.TextDim
            )
        }
    }
}

/**
 * 底部悬浮输入条 (支持生成时一键打断)
 */
@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onInterrupt: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, LiricaColors.BackgroundDeep.copy(alpha = 0.95f))
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 药丸输入框
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(LiricaColors.CardSurface)
                .border(1.dp, LiricaColors.CardBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    color = LiricaColors.TextMain,
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(LiricaColors.VioletLight),
                maxLines = 4,
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            text = if (isGenerating) "她正在构思与打字，可轻点右侧打断…" else "对 Lirica 说点什么…",
                            fontSize = 14.sp,
                            color = LiricaColors.TextDim
                        )
                    }
                    innerTextField()
                }
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 发送 / 打断 按钮
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (isGenerating) {
                        LiricaColors.Danger
                    } else if (text.isNotBlank()) {
                        LiricaColors.VioletCore
                    } else {
                        Color(0xFF262335)
                    }
                )
                .clickable(enabled = isGenerating || text.isNotBlank()) {
                    if (isGenerating) {
                        onInterrupt()
                    } else {
                        onSend()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isGenerating) {
                // 打断方块图标 (可打断卡住)
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White)
                )
            } else {
                Text(
                    text = "↑",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (text.isNotBlank()) Color(0xFF0D0C13) else LiricaColors.TextDim
                )
            }
        }
    }
}
