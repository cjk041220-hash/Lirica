# 第二轮优化·第 0 步工程护栏与稳定性兜底实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地第二轮优化第 0 步三大关键工程护栏（P1 对话历史上下文硬预算截断器、P6 聊天链路双重记忆召回消除、P2 本地未捕获异常崩溃守护与定长环形日志落盘），堵死长会话爆 Token、算力浪费与异常闪退无迹可循三大隐患。

**Architecture:** 
1. `ChatHistoryTrimmer` 采用无状态纯函数，基于中英字符比进行 Token 估算，逆向倒序收集近期消息并在老端向 `user` 角色对齐，单条极端超长安全截断；
2. `DualTrackMemoryManager` 为 `assembleDynamicContext` 扩充预召回可选入参，`ChatViewModel` 一次召回同时赋能 UI 徽章与提示词装配；
3. `XiaoxiLog` 维护 200 条定长线程安全内存环形缓冲，`XiaoxiCrashHandler` 在 `XiaoxiApplication` 启动时挂载，捕获未捕获异常并落盘到私有 `crashes/` 目录并保持 5 份轮转，替换零散 `println`。

**Tech Stack:** Kotlin 2.0.21, Coroutines 1.9.0, AndroidX, Robolectric, JUnit 4.

## Global Constraints

- **100% Native 原生纯度**：严禁引入第三方云端上报 SDK（Bugly / Sentry / Firebase），日志仅限设备本地私密沙盒；
- **严格向下兼容**：保留所有既有公共函数默认参数，已通过的 249 个单元测试必须全绿无回退；
- **严格 TDD 纪律**：红灯测试 -> 编写实现跑绿 -> 故意变异破坏验证变红 -> 恢复全绿 -> 原子提交。

---

### Task 1: P1 对话历史上下文硬预算截断器 (`ChatHistoryTrimmer`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmer.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmerTest.kt`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatViewModel.kt:181-203`

**Interfaces:**
- Consumes: `com.xiaoxi.android.network.model.ChatMessage`
- Produces:
  - `ChatHistoryTrimmer.estimateTokens(text: String): Int`
  - `ChatHistoryTrimmer.trim(history: List<ChatMessage>, maxTokens: Int = 4000): List<ChatMessage>`

- [x] **Step 1: 编写红灯单测 `ChatHistoryTrimmerTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmerTest.kt` 创建测试：
```kotlin
package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.network.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTrimmerTest {

    @Test
    fun testEstimateTokens_calculatesCorrectly() {
        assertEquals(0, ChatHistoryTrimmer.estimateTokens(""))
        // 15 个中文字符: 15 / 1.5 = 10 tokens
        assertEquals(10, ChatHistoryTrimmer.estimateTokens("一二三四五六七八九十一二三四五"))
        // 极短字符至少 1 token
        assertEquals(1, ChatHistoryTrimmer.estimateTokens("a"))
    }

    @Test
    fun testTrim_emptyHistory_returnsEmpty() {
        val result = ChatHistoryTrimmer.trim(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun testTrim_withinBudget_preservesAllMessages() {
        val history = listOf(
            ChatMessage(role = "user", content = "你好，小汐"),
            ChatMessage(role = "assistant", content = "你好！今天过得怎么样？"),
            ChatMessage(role = "user", content = "我今天刚下班，有点累。")
        )
        val result = ChatHistoryTrimmer.trim(history, maxTokens = 1000)
        assertEquals(3, result.size)
        assertEquals(history, result)
    }

    @Test
    fun testTrim_exceedsBudget_dropsOldestTurns() {
        // 每条约 60 字符 (40 tokens)
        val history = mutableListOf<ChatMessage>()
        for (i in 1..20) {
            history.add(ChatMessage(role = "user", content = "用户第 $i 次提问，详细描述当前任务以及各种要求细节 $i"))
            history.add(ChatMessage(role = "assistant", content = "助手第 $i 次回答，给出相应的解答方案与指引 $i"))
        }
        // 设定 maxTokens 为 120，大约只够保留最近 1~2 轮 (约 2~3 条)
        val result = ChatHistoryTrimmer.trim(history, maxTokens = 120)
        
        assertTrue(result.size < history.size)
        // 必须保留的是最新的一条
        assertEquals(history.last().content, result.last().content)
        // 且首条必须是对齐的 user 消息
        assertEquals("user", result.first().role)
    }

    @Test
    fun testTrim_alignsToUserMessageStart_whenOldestRemainingIsAssistant() {
        val history = listOf(
            ChatMessage(role = "user", content = "最老的提问"),
            ChatMessage(role = "assistant", content = "最老的回答"),
            ChatMessage(role = "user", content = "中间的提问"),
            ChatMessage(role = "assistant", content = "中间的回答"),
            ChatMessage(role = "user", content = "最新的提问")
        )
        // 限制 token 恰好装下 "中间的回答" 与 "最新的提问"
        // 此时预算截断若切在 "中间的回答"，算法应自动丢弃该 assistant 消息，保证输出从 user 开始
        val tokensLatest = ChatHistoryTrimmer.estimateTokens("最新的提问")
        val tokensMidAnswer = ChatHistoryTrimmer.estimateTokens("中间的回答")
        val budget = tokensLatest + tokensMidAnswer + 1

        val result = ChatHistoryTrimmer.trim(history, maxTokens = budget)
        assertEquals("user", result.first().role)
        assertEquals("最新的提问", result.first().content)
    }

    @Test
    fun testTrim_singleExtremelyLongMessage_truncatedSafely() {
        val hugeText = "超长文本片段".repeat(1000) // 6000 字符，约 4000 tokens
        val history = listOf(
            ChatMessage(role = "user", content = hugeText)
        )
        val result = ChatHistoryTrimmer.trim(history, maxTokens = 1000)
        assertEquals(1, result.size)
        assertTrue(result.first().content.contains("[...已省略超长历史内容...]"))
        assertTrue(ChatHistoryTrimmer.estimateTokens(result.first().content) <= 1000)
    }
}
```

- [x] **Step 2: 运行测试验证红灯**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.ui.chat.ChatHistoryTrimmerTest"
```
预期输出：编译报错 `Unresolved reference: ChatHistoryTrimmer`。

- [x] **Step 3: 实现 `ChatHistoryTrimmer.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmer.kt` 编写最小实现：
```kotlin
package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.network.model.ChatMessage

/**
 * 对话历史有界硬预算截断器
 *
 * 杜绝长程会话消息体无界膨胀触发大模型 400 报错或账单失控。
 */
object ChatHistoryTrimmer {

    /** 默认历史硬预算上限（Tokens） */
    const val DEFAULT_MAX_HISTORY_TOKENS = 4000

    /** 中英混合 Token 粗算比例：1 token ≈ 1.5 字符 */
    const val CHARS_PER_TOKEN = 1.5

    /** 截断占位符 */
    private const val TRUNCATION_MARKER = "\n[...已省略超长历史内容...]\n"

    /**
     * 估算给定文本的 Token 数量
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    /**
     * 对历史消息列表进行倒序硬预算截断：
     * 1. 空列表直接返回；
     * 2. 从最新消息开始倒序累加 Token 消耗；
     * 3. 超出预算的消息被丢弃；
     * 4. 轮次完整性修剪：截断后若首条消息为 assistant，丢弃该条，确保以 user 开头；
     * 5. 单条极端超长安全保护：头尾保留并插入省略标记。
     */
    fun trim(
        history: List<ChatMessage>,
        maxTokens: Int = DEFAULT_MAX_HISTORY_TOKENS
    ): List<ChatMessage> {
        if (history.isEmpty()) return emptyList()

        // 1. 倒序收集在预算内的消息
        var remainingBudget = maxTokens
        val retainedReversed = mutableListOf<ChatMessage>()

        for (i in history.indices.reversed()) {
            val original = history[i]
            val estimated = estimateTokens(original.content)

            if (estimated <= remainingBudget) {
                retainedReversed.add(original)
                remainingBudget -= estimated
            } else {
                // 如果是最新的一条消息（retainedReversed 为空）本身就超过了全部预算，进行单条强制安全截断
                if (retainedReversed.isEmpty()) {
                    val safeContent = truncateSingleMessage(original.content, maxTokens)
                    retainedReversed.add(original.copy(content = safeContent))
                }
                // 预算耗尽，停止向前收集
                break
            }
        }

        val retained = retainedReversed.asReversed()

        // 2. 轮次完整性修剪：确保截断后的历史首条是 user 提问
        val firstUserIndex = retained.indexOfFirst { it.role == "user" }
        if (firstUserIndex == -1) {
            // 如果全部留下的都不是 user（罕见防御），返回空
            return emptyList()
        }

        return if (firstUserIndex == 0) {
            retained
        } else {
            retained.subList(firstUserIndex, retained.size)
        }
    }

    private fun truncateSingleMessage(content: String, maxTokens: Int): String {
        val maxChars = (maxTokens * CHARS_PER_TOKEN).toInt()
        val markerChars = TRUNCATION_MARKER.length
        if (maxChars <= markerChars + 20) {
            return content.take(maxChars.coerceAtLeast(10))
        }
        val half = (maxChars - markerChars) / 2
        val head = content.take(half)
        val tail = content.takeLast(half)
        return "$head$TRUNCATION_MARKER$tail"
    }
}
```

- [x] **Step 4: 运行单测验证跑绿**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.ui.chat.ChatHistoryTrimmerTest"
```
预期输出：`ChatHistoryTrimmerTest` 5 个用例全部 PASSED。

- [x] **Step 5: 将 `ChatHistoryTrimmer` 接入 `ChatViewModel.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatViewModel.kt:181-190` 替换原有的无界历史：
```kotlin
// 4. 装配上下文消息
val currentMessages = _uiState.value.messages
val rawHistory = currentMessages
    .dropLast(1)
    .map { item ->
        ChatMessage(
            role = if (item.sender == MessageSender.USER) "user" else "assistant",
            content = item.text
        )
    }

// 接入 P1 上下文硬预算截断器，防止长程会话溢出大模型上下文
val history = ChatHistoryTrimmer.trim(rawHistory)
```
运行 `ChatViewModelTest` 确保原有会话全绿：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.ui.chat.ChatViewModelTest"
```

- [x] **Step 6: 变异测试验证**

故意将 `ChatHistoryTrimmer.kt` 中轮次修剪逻辑注释掉（直接 `return retained`），重新运行单测：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.ui.chat.ChatHistoryTrimmerTest"
```
验证触发红灯：`testTrim_alignsToUserMessageStart_whenOldestRemainingIsAssistant FAILED`。  
恢复代码后重新跑绿。

- [x] **Step 7: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmer.kt \
        app/src/test/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmerTest.kt \
        app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatViewModel.kt
git commit -m "feat(chat): 实现对话历史上下文硬预算截断器 ChatHistoryTrimmer (P1)"
```

---

### Task 2: P6 聊天链路双重记忆召回消除 (`DualTrackMemoryManager` + `ChatViewModel`)

**Files:**
- Modify: `app/src/main/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManager.kt:131-171`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatViewModel.kt:132-164`
- Test: `app/src/test/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManagerTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/ui/chat/ChatViewModelTest.kt`

**Interfaces:**
- Consumes:
  - `UserMemoryEntity`, `SelfExperienceEntity`, `UnspokenThoughtEntity`
- Produces:
  - `DualTrackMemoryManager.assembleDynamicContext(query: String, moodValence: Float = 0.0f, charName: String = PhiMiddleware.DEFAULT_CHAR_NAME, nowTs: Long = System.currentTimeMillis(), preRecalledUserMemories: List<UserMemoryEntity>? = null, preRecalledSelfExperiences: List<SelfExperienceEntity>? = null, preRecalledUnspokenThoughts: List<UnspokenThoughtEntity>? = null): String?`

- [x] **Step 1: 在 `DualTrackMemoryManagerTest` 编写红灯测试**

在 `app/src/test/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManagerTest.kt` 底部增加新用例：
```kotlin
@Test
fun testAssembleDynamicContext_usesPreRecalledMemoriesDirectly() = runTest {
    val preRecalledUser = listOf(
        UserMemoryEntity(
            id = "pre_user_1",
            category = "PRE_USER",
            content = "预召回用户记忆：喜欢喝黑咖啡",
            importance = 0.9f,
            createdAt = System.currentTimeMillis()
        )
    )
    val preRecalledSelf = listOf(
        SelfExperienceEntity(
            id = "pre_self_1",
            domainTag = "PRE_SELF",
            content = "预召回自叙经历：今天看了一本好书",
            emotionalInsight = "平静且充实",
            createdAt = System.currentTimeMillis()
        )
    )
    val preRecalledThought = listOf(
        UnspokenThoughtEntity(
            id = "pre_thought_1",
            thought = "预召回念头：提醒用户早点休息",
            urgency = 0.8f,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000
        )
    )

    // 传入完全不同于数据库内容的预召回实体
    val result = memoryManager.assembleDynamicContext(
        query = "完全无关的查询关键词",
        charName = "小汐",
        preRecalledUserMemories = preRecalledUser,
        preRecalledSelfExperiences = preRecalledSelf,
        preRecalledUnspokenThoughts = preRecalledThought
    )

    assertNotNull(result)
    assertTrue(result!!.contains("预召回用户记忆：喜欢喝黑咖啡"))
    assertTrue(result.contains("预召回自叙经历：今天看了一本好书"))
    assertTrue(result.contains("平静且充实"))
    assertTrue(result.contains("预召回念头：提醒用户早点休息"))
}
```

- [x] **Step 2: 运行单测验证红灯**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.database.memory.DualTrackMemoryManagerTest.testAssembleDynamicContext_usesPreRecalledMemoriesDirectly"
```
预期输出：编译报错 `None of the following functions can be called with the arguments supplied: assembleDynamicContext(...)`。

- [x] **Step 3: 改造 `DualTrackMemoryManager.kt` 支持预召回入参**

修改 `app/src/main/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManager.kt:131-145`：
```kotlin
    suspend fun assembleDynamicContext(
        query: String,
        moodValence: Float = 0.0f,
        charName: String = PhiMiddleware.DEFAULT_CHAR_NAME,
        nowTs: Long = System.currentTimeMillis(),
        preRecalledUserMemories: List<UserMemoryEntity>? = null,
        preRecalledSelfExperiences: List<SelfExperienceEntity>? = null,
        preRecalledUnspokenThoughts: List<UnspokenThoughtEntity>? = null
    ): String? {
        val userMemories = preRecalledUserMemories
            ?: recallUserMemories(query, limit = 5, moodValence = moodValence, nowTs = nowTs)
        val selfExperiences = preRecalledSelfExperiences
            ?: recallSelfExperiences(limit = 2, unsharedOnly = true)
        val unspokenThoughts = preRecalledUnspokenThoughts
            ?: recallUnspokenThoughts(limit = 2, currentTs = nowTs)

        if (userMemories.isEmpty() && selfExperiences.isEmpty() && unspokenThoughts.isEmpty()) {
            return null
        }
```

- [x] **Step 4: 运行 `DualTrackMemoryManagerTest` 验证跑绿**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.database.memory.DualTrackMemoryManagerTest"
```
预期输出：全量 7 个用例 100% PASSED。

- [x] **Step 5: 改造 `ChatViewModel.kt` 将单次召回结果注入 `assembleDynamicContext`**

修改 `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatViewModel.kt:132-164`：
```kotlin
                // 2. 检索双轨记忆与念头（单次全量检索）
                val currentMood = emotionEngine.currentMood
                val nowTs = System.currentTimeMillis()
                val userMemories = memoryManager.recallUserMemories(
                    query = trimmed,
                    limit = 5,
                    moodValence = currentMood.defaultVector.valence,
                    nowTs = nowTs
                )
                val selfExps = memoryManager.recallSelfExperiences(limit = 2, unsharedOnly = true)
                val thoughts = memoryManager.recallUnspokenThoughts(limit = 2, currentTs = nowTs)

                // 记忆命中回写：本轮被召回的用户记忆立即更新按日去重命中统计与使用标记
                if (userMemories.isNotEmpty()) {
                    val todayDate = LocalDate.now().toString()
                    userMemories.forEach { memory ->
                        memoryManager.recordMemoryHit(memory.id, todayDate, nowTs)
                        memoryManager.markMemoryUsed(memory.id)
                    }
                }

                val badgeInfo = MemoryBadgeInfo(
                    userMemories = userMemories.map { it.content },
                    selfExperiences = selfExps.map { it.content },
                    thoughts = thoughts.map { it.thought }
                )

                // 注入预召回结果，杜绝二次检索与多维打分重复计算
                val dynamicContext = memoryManager.assembleDynamicContext(
                    query = trimmed,
                    moodValence = currentMood.defaultVector.valence,
                    charName = _uiState.value.activeCharacterCard?.name ?: PhiMiddleware.DEFAULT_CHAR_NAME,
                    nowTs = nowTs,
                    preRecalledUserMemories = userMemories,
                    preRecalledSelfExperiences = selfExps,
                    preRecalledUnspokenThoughts = thoughts
                )
```
运行 `ChatViewModelTest` 确保回归全绿：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.ui.chat.ChatViewModelTest"
```

- [x] **Step 6: 变异测试验证**

故意在 `DualTrackMemoryManager.kt` 中忽略 `preRecalledUserMemories`（强行写死 `val userMemories = recallUserMemories(...)`），运行：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.database.memory.DualTrackMemoryManagerTest.testAssembleDynamicContext_usesPreRecalledMemoriesDirectly"
```
确认变红失败：`AssertionError: expected pre_user_1 to be present`。  
恢复后重新跑绿。

- [x] **Step 7: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManager.kt \
        app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatViewModel.kt \
        app/src/test/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManagerTest.kt
git commit -m "perf(memory): 消除聊天主链路双重记忆召回与算分 (P6)"
```

---

### Task 3: P2 本地崩溃守护与环形日志落盘 (`XiaoxiLog` & `XiaoxiCrashHandler`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiLog.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiCrashHandler.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/common/logging/XiaoxiLogTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/common/logging/XiaoxiCrashHandlerTest.kt`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/XiaoxiApplication.kt:18-28`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/network/XiaoxiLlmClient.kt:158`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/perception/PerceptionPipeline.kt:176`

**Interfaces:**
- Produces:
  - `XiaoxiLog.d(tag: String, message: String)`
  - `XiaoxiLog.i(tag: String, message: String)`
  - `XiaoxiLog.w(tag: String, message: String, throwable: Throwable? = null)`
  - `XiaoxiLog.e(tag: String, message: String, throwable: Throwable? = null)`
  - `XiaoxiLog.getRecentLogs(): List<String>`
  - `XiaoxiCrashHandler.install(context: Context)`
  - `XiaoxiCrashHandler.saveCrashReport(thread: Thread, throwable: Throwable): File?`

- [x] **Step 1: 编写红灯单测 `XiaoxiLogTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/common/logging/XiaoxiLogTest.kt` 编写：
```kotlin
package com.xiaoxi.android.common.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XiaoxiLogTest {

    @Before
    fun setUp() {
        XiaoxiLog.clear()
    }

    @Test
    fun testLogRecording_recordsToRingBuffer() {
        XiaoxiLog.d("TestTag", "Debug message")
        XiaoxiLog.i("TestTag", "Info message")
        XiaoxiLog.w("TestTag", "Warn message", RuntimeException("Warn cause"))
        XiaoxiLog.e("TestTag", "Error message", IllegalStateException("Error cause"))

        val logs = XiaoxiLog.getRecentLogs()
        assertEquals(4, logs.size)
        assertTrue(logs[0].contains("[DEBUG][TestTag] Debug message"))
        assertTrue(logs[1].contains("[INFO][TestTag] Info message"))
        assertTrue(logs[2].contains("[WARN][TestTag] Warn message"))
        assertTrue(logs[3].contains("[ERROR][TestTag] Error message"))
        assertTrue(logs[3].contains("IllegalStateException"))
    }

    @Test
    fun testRingBufferCapacity_capsAt200AndDropsOldest() {
        for (i in 1..250) {
            XiaoxiLog.i("Tag", "Message #$i")
        }

        val logs = XiaoxiLog.getRecentLogs()
        assertEquals(200, logs.size)
        // 应该包含最后写入的 Message #250，而前 50 条被挤出
        assertTrue(logs.last().contains("Message #250"))
        assertTrue(logs.first().contains("Message #51"))
    }
}
```

- [x] **Step 2: 运行测试验证红灯**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.common.logging.XiaoxiLogTest"
```
预期输出：编译报错 `Unresolved reference: logging`。

- [x] **Step 3: 实现 `XiaoxiLog.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiLog.kt` 编写实现：
```kotlin
package com.xiaoxi.android.common.logging

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 小汐 2.0 统一日志门面与内存定长环形缓冲器
 *
 * 既输出到 Android LogCat，又在内存维护最近 200 条系统轨迹，为崩溃报告提供现场上下文。
 */
object XiaoxiLog {

    const val MAX_RING_BUFFER_SIZE = 200
    private val ringBuffer = ConcurrentLinkedDeque<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        record("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        record("INFO", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        val trace = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        record("WARN", tag, "$message$trace")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val trace = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        record("ERROR", tag, "$message$trace")
    }

    private fun record(level: String, tag: String, message: String) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$timestamp][$level][$tag] $message"
        ringBuffer.addLast(entry)
        while (ringBuffer.size > MAX_RING_BUFFER_SIZE) {
            ringBuffer.pollFirst()
        }
    }

    /**
     * 获取当前内存中缓存的全部最近日志快照
     */
    fun getRecentLogs(): List<String> = ringBuffer.toList()

    /**
     * 测试用：清理内存缓冲区
     */
    internal fun clear() {
        ringBuffer.clear()
    }
}
```

- [x] **Step 4: 运行 `XiaoxiLogTest` 验证跑绿**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.common.logging.XiaoxiLogTest"
```
预期输出：`XiaoxiLogTest` 全部 PASSED。

- [x] **Step 5: 编写红灯单测 `XiaoxiCrashHandlerTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/common/logging/XiaoxiCrashHandlerTest.kt` 编写：
```kotlin
package com.xiaoxi.android.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class XiaoxiCrashHandlerTest {

    private lateinit var context: Context
    private lateinit var crashHandler: XiaoxiCrashHandler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        crashHandler = XiaoxiCrashHandler(context, null)
        XiaoxiLog.clear()
        val crashDir = File(context.filesDir, XiaoxiCrashHandler.CRASH_DIR_NAME)
        if (crashDir.exists()) {
            crashDir.deleteRecursively()
        }
    }

    @Test
    fun testSaveCrashReport_generatesReportWithDeviceInfoAndLogs() {
        XiaoxiLog.i("Perception", "前台切换至微信")
        XiaoxiLog.d("Automation", "准备执行自动化手势")

        val exception = NullPointerException("屏幕无障碍树节点为空")
        val thread = Thread.currentThread()

        val file = crashHandler.saveCrashReport(thread, exception)

        assertNotNull(file)
        assertTrue(file!!.exists())
        val content = file.readText()

        assertTrue(content.contains("=== 小汐 2.0 崩溃诊断报告 ==="))
        assertTrue(content.contains("NullPointerException"))
        assertTrue(content.contains("屏幕无障碍树节点为空"))
        assertTrue(content.contains("崩溃线程: ${thread.name}"))
        assertTrue(content.contains("前台切换至微信"))
        assertTrue(content.contains("准备执行自动化手势"))
    }

    @Test
    fun testCrashReportRotation_keepsMax5Files() {
        val crashDir = File(context.filesDir, XiaoxiCrashHandler.CRASH_DIR_NAME).apply { mkdirs() }
        
        // 预制 6 个旧崩溃文件，修改时间递增
        for (i in 1..6) {
            val file = File(crashDir, "crash_20260920_00000$i.txt")
            file.writeText("Old crash report #$i")
            file.setLastModified(1000L * i)
        }

        assertEquals(6, crashDir.listFiles()?.size)

        // 再次触发保存崩溃报告
        crashHandler.saveCrashReport(Thread.currentThread(), RuntimeException("New crash"))

        val files = crashDir.listFiles()
        assertNotNull(files)
        // 应该轮转淘汰最旧的，总数不超过 5
        assertEquals(5, files!!.size)
    }
}
```

- [x] **Step 6: 运行测试验证红灯**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.common.logging.XiaoxiCrashHandlerTest"
```
预期输出：编译报错 `Unresolved reference: XiaoxiCrashHandler`。

- [x] **Step 7: 实现 `XiaoxiCrashHandler.kt`**

在 `app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiCrashHandler.kt` 编写实现：
```kotlin
package com.xiaoxi.android.common.logging

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小汐 2.0 本地私密崩溃守护器
 *
 * 坚守 100% 本地安全沙盒原则，绝不上报云端，将未捕获异常与环形日志格式化落盘并轮转清理。
 */
class XiaoxiCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
) : Thread.UncaughtExceptionHandler {

    companion object {
        const val MAX_CRASH_FILES = 5
        const val CRASH_DIR_NAME = "crashes"

        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current !is XiaoxiCrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(XiaoxiCrashHandler(context, current))
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashReport(thread, throwable)
        } catch (t: Throwable) {
            XiaoxiLog.e("XiaoxiCrashHandler", "保存崩溃报告异常", t)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    internal fun saveCrashReport(thread: Thread, throwable: Throwable): File? {
        val crashDir = File(context.filesDir, CRASH_DIR_NAME).apply { mkdirs() }

        // 轮转清理超额旧文件，保持最多 MAX_CRASH_FILES - 1 份，以便放入新报告
        val existing = crashDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        if (existing.size >= MAX_CRASH_FILES) {
            val toDeleteCount = existing.size - MAX_CRASH_FILES + 1
            existing.take(toDeleteCount).forEach { it.delete() }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val file = File(crashDir, "crash_$timestamp.txt")

        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        val stackTrace = stringWriter.toString()

        val report = buildString {
            append("=== 小汐 2.0 崩溃诊断报告 ===\n")
            append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}\n")
            append("崩溃线程: ${thread.name} (id=${thread.id})\n")
            append("设备信息: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
            append("异常类型: ${throwable.javaClass.name}\n")
            append("异常信息: ${throwable.message ?: "无详细描述"}\n\n")
            append("=== 异常堆栈 ===\n")
            append(stackTrace)
            append("\n=== 崩溃前最近环形日志 (${XiaoxiLog.getRecentLogs().size}条) ===\n")
            for (log in XiaoxiLog.getRecentLogs()) {
                append(log).append("\n")
            }
            append("=== 报告结束 ===\n")
        }

        file.writeText(report)
        return file
    }
}
```

- [x] **Step 8: 运行单测验证跑绿**

运行命令：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.common.logging.XiaoxiCrashHandlerTest"
```
预期输出：`XiaoxiCrashHandlerTest` 全部 PASSED。

- [x] **Step 9: 在 `XiaoxiApplication` 挂载并替换全局 `println`**

1. 修改 `app/src/main/kotlin/com/xiaoxi/android/XiaoxiApplication.kt`：
```kotlin
package com.xiaoxi.android

import android.app.Application
import com.xiaoxi.android.common.logging.XiaoxiCrashHandler
import com.xiaoxi.android.common.logging.XiaoxiLog

class XiaoxiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 注册本地崩溃守护
        XiaoxiCrashHandler.install(this)
        XiaoxiLog.i("XiaoxiApplication", "小汐 2.0 系统已启动，本地崩溃守护与环形日志已就绪")
    }
}
```

2. 替换 `app/src/main/kotlin/com/xiaoxi/android/network/XiaoxiLlmClient.kt:158`：
```kotlin
// 原有: println("HTTP stream error: $errorText")
XiaoxiLog.e("XiaoxiLlmClient", "HTTP stream error: $errorText")
```

3. 替换 `app/src/main/kotlin/com/xiaoxi/android/perception/PerceptionPipeline.kt:176`：
```kotlin
// 原有: println("PerceptionSnapshot emitted: ...")
XiaoxiLog.d("PerceptionPipeline", "PerceptionSnapshot emitted: ${snapshot.naturalSummary}")
```

- [x] **Step 10: 变异测试验证**

故意将 `XiaoxiCrashHandler.kt` 中 `MAX_CRASH_FILES` 从 5 改为 999，运行：
```bash
./gradlew testDebugUnitTest --tests "com.xiaoxi.android.common.logging.XiaoxiCrashHandlerTest.testCrashReportRotation_keepsMax5Files"
```
确认测试红灯：`AssertionError: expected:<5> but was:<7>`。  
恢复后重新跑绿。

- [x] **Step 11: 运行全量单元测试与回归套件**

运行命令：
```bash
./gradlew testDebugUnitTest
```
预期输出：全量测试（249 基线 + 本次新增用例）全绿通过，0 failed。

- [x] **Step 12: 提交原子 Commit**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiLog.kt \
        app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiCrashHandler.kt \
        app/src/test/kotlin/com/xiaoxi/android/common/logging/XiaoxiLogTest.kt \
        app/src/test/kotlin/com/xiaoxi/android/common/logging/XiaoxiCrashHandlerTest.kt \
        app/src/main/kotlin/com/xiaoxi/android/XiaoxiApplication.kt \
        app/src/main/kotlin/com/xiaoxi/android/network/XiaoxiLlmClient.kt \
        app/src/main/kotlin/com/xiaoxi/android/perception/PerceptionPipeline.kt
git commit -m "feat(stability): 落地本地崩溃守护与环形日志落盘 (P2)"
```
