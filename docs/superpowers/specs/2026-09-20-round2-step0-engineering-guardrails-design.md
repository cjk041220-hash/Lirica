# 第二轮优化·第 0 步技术规格设计：先期工程护栏与稳定性兜底 (P1 + P2 + P6)

> **文档标识**：`docs/superpowers/specs/2026-09-20-round2-step0-engineering-guardrails-design.md`  
> **制定日期**：2026-09-20  
> **所属阶段**：小汐 2.0（Project Aether）第二轮系统级架构优化 · 第 0 步  
> **涉及评审优化项**：P1（对话历史上下文硬预算）、P2（本地崩溃守护与环形日志）、P6（聊天链路双重记忆召回消除）  
> **技术基准**：100% Native Kotlin + Coroutines + Android SDK  
> **当前测试基线**：249 tests passed (100% green)  

---

## 1. 目标与设计原则

### 1.1 背景与目标
在《主方案2.0》与《评审报告》中，系统全面指出了第一轮沙盒阶段遗留的四大系统级断层与多项工程护栏缺口。依据“**先兜底，后升维**”的工程原则，在全面展开 Milestone 2.1 执行态无障碍树抽取与坐标桥接之前，必须优先落地第 0 步工程护栏，彻底消除长会话 400 崩溃风险、进程异常死亡无迹可寻以及双重记忆召回造成的 CPU 浪费。

### 1.2 核心原则
1. **纯原生与零外部依赖**：不引入任何第三方云端上报 SDK（如 Bugly、Firebase 等），所有诊断报告严守 100% 本地私密沙盒；
2. **非侵入式与完全向下兼容**：已有方法签名扩展均保持默认参数，不破坏任何已有的 249 个测试用例契约；
3. **严格 TDD 纪律**：先编写红灯测试，确认失败后实现，经破坏性变异测试验证后恢复全绿并提交。

---

## 2. 模块技术规格

### 2.1 P1：对话历史上下文硬预算截断器 (`ChatHistoryTrimmer`)

#### 2.1.1 核心问题
在 `ChatViewModel.kt:181-189` 中，消息历史直接全量映射自 `_uiState.value.messages.dropLast(1)`。由于没有任何 Token 预算或轮次限制，长程会话或包含长文本的消息会无限膨胀请求体，导致大模型 API 触发 400 报错（超过模型单次请求上下文限制）。

#### 2.1.2 模块路径与接口契约
包名：`com.xiaoxi.android.ui.chat`  
文件：`app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmer.kt`

```kotlin
package com.xiaoxi.android.ui.chat

import com.xiaoxi.android.network.model.ChatMessage

/**
 * 对话历史有界硬预算截断器
 */
object ChatHistoryTrimmer {
    /** 默认历史硬预算上限（Tokens），为角色卡、System 提示词、PHI 重锚与模型回复预留充足空间 */
    const val DEFAULT_MAX_HISTORY_TOKENS = 4000

    /** 中英混合 Token 粗算比例：1 token ≈ 1.5 字符 */
    const val CHARS_PER_TOKEN = 1.5

    /** 单条消息极端保护截断上限（Tokens） */
    const val MAX_SINGLE_MESSAGE_TOKENS = 2000

    /**
     * 估算文本对应的大致 Token 数量
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    /**
     * 对历史消息进行有界硬预算裁剪：
     * 1. 空列表与预算内列表原样保留；
     * 2. 从最新历史向最旧历史逆向累加 Token；
     * 3. 超过 maxTokens 时截止收集；
     * 4. 轮次完整性对齐：若截取出的最旧消息角色为 assistant，将其移除，
     *    确保裁出的历史以 user 消息开头，避免模型接收孤立回答导致上下文漂移；
     * 5. 单条消息极端保护：若单条消息估算超过 MAX_SINGLE_MESSAGE_TOKENS，
     *    做头尾保留式安全截断，中间插入 "[...已省略超长历史内容...]"。
     */
    fun trim(
        history: List<ChatMessage>,
        maxTokens: Int = DEFAULT_MAX_HISTORY_TOKENS
    ): List<ChatMessage>
}
```

#### 2.1.3 `ChatViewModel` 接线改造
在 `ui/chat/ChatViewModel.kt` 的 `sendMessage` 方法中，在组装历史消息后接入截断：
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

val trimmedHistory = ChatHistoryTrimmer.trim(rawHistory)

val assembledMessages = phiMiddleware.assemble(
    characterCard = _uiState.value.activeCharacterCard,
    dynamicContext = dynamicContext,
    historyMessages = trimmedHistory, // 接入有界历史
    moodState = currentMood,
    sessionId = sessionId,
    charName = _uiState.value.activeCharacterCard?.name ?: PhiMiddleware.DEFAULT_CHAR_NAME,
    turnCount = turnCount
)
```

---

### 2.2 P6：聊天链路双重记忆召回消除

#### 2.2.1 核心问题
在当前 `ChatViewModel.kt:134-162` 中：
1. 先显式调用 `memoryManager.recallUserMemories(trimmed, limit=5, ...)`、`recallSelfExperiences`、`recallUnspokenThoughts`，用于装配 UI 状态栏的 `MemoryBadgeInfo` 并在命中时写回 `recordMemoryHit`；
2. 紧接着调用 `memoryManager.assembleDynamicContext(trimmed, ...)`；
3. `DualTrackMemoryManager.kt:138-140` 内部又无条件调用了相同的 3 个召回方法，再次执行 Room 捞取、Bigram 分词与 6 因子多维算分。

这不仅导致 CPU 算力开销翻倍与内存抖动，而且在写回发生后二次召回的内容可能与 UI 徽章产生微小失步。

#### 2.2.2 接口增强契约 (`DualTrackMemoryManager.kt`)
保持原有参数与默认值兼容，增加可选的预召回参数：
```kotlin
fun assembleDynamicContext(
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

    return buildString {
        // 原有拼接逻辑保持完全一致...
    }.trim()
}
```

#### 2.2.3 `ChatViewModel` 接线改造
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

// 注入预召回结果，杜绝二次检索与多维打分计算
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

---

### 2.3 P2：本地崩溃守护与环形日志落盘

#### 2.3.1 核心问题
1. 工程未设置 `Thread.setDefaultUncaughtExceptionHandler`，后台服务（前台感知轮询、通知捕获、悬浮窗）发生未捕获异常时静默闪退，无本地凭据；
2. 代码中零星散落 `println`（例如 `XiaoxiLlmClient.kt:158`、`PerceptionPipeline.kt:176`），缺乏统一的日志拦截；
3. 崩溃分析需要知晓崩溃发生前最后几十条系统状态与业务流水。

#### 2.3.2 模块路径与接口契约
包名：`com.xiaoxi.android.common.logging`  
文件 1：`app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiLog.kt`  
文件 2：`app/src/main/kotlin/com/xiaoxi/android/common/logging/XiaoxiCrashHandler.kt`

##### ① `XiaoxiLog.kt`
```kotlin
package com.xiaoxi.android.common.logging

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

object XiaoxiLog {
    private const val MAX_RING_BUFFER_SIZE = 200
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

    fun getRecentLogs(): List<String> = ringBuffer.toList()

    internal fun clear() {
        ringBuffer.clear()
    }
}
```

##### ② `XiaoxiCrashHandler.kt`
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
        
        // 轮转清理超额旧文件，最多保留 5 份
        val existing = crashDir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        if (existing.size >= MAX_CRASH_FILES) {
            val toDelete = existing.size - MAX_CRASH_FILES + 1
            existing.take(toDelete).forEach { it.delete() }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(crashDir, "crash_$timestamp.txt")

        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        val stackTrace = stringWriter.toString()

        val report = buildString {
            append("=== 小汐 2.0 崩溃诊断报告 ===\n")
            append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            append("崩溃线程: ${thread.name} (id=${thread.id})\n")
            append("设备信息: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})\n")
            append("异常类型: ${throwable.javaClass.name}\n")
            append("异常信息: ${throwable.message}\n\n")
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

#### 2.3.3 全局装配与规范清理
1. 在 `XiaoxiApplication.kt:onCreate()` 注册：`XiaoxiCrashHandler.install(this)`；
2. 替换 `network/XiaoxiLlmClient.kt:158` 的 `println("HTTP stream error: $errorText")` 为 `XiaoxiLog.e("XiaoxiLlmClient", "HTTP stream error: $errorText")`；
3. 替换 `perception/PerceptionPipeline.kt:176` 的 `println("PerceptionSnapshot emitted: ...")` 为 `XiaoxiLog.d("PerceptionPipeline", "PerceptionSnapshot emitted: ...")`。

---

## 3. 测试与验证策略 (TDD)

### 3.1 测试套件清单
1. **`ChatHistoryTrimmerTest`**：
   - `testEstimateTokens_calculatesCorrectly`：检验字符到 token 的折算；
   - `testTrim_emptyList_returnsEmpty`：空历史测试；
   - `testTrim_withinBudget_keepsAllMessages`：额度内完全保留；
   - `testTrim_exceedsBudget_dropsOldestTurns`：超限剔除最早轮次；
   - `testTrim_alignsToUserMessageRole`：剔除后若首条为 assistant，自动修剪保证首条为 user；
   - `testTrim_singleExtremelyLongMessage_truncatesSafely`：单条超大消息截断保护。
2. **`DualTrackMemoryManagerTest` 增补**：
   - `testAssembleDynamicContext_usesPreRecalledMemories`：显式传入预召回列表，验证不重复调用检索且返回格式正确。
3. **`XiaoxiLogTest`**：
   - `testLogRecording_recordsToRingBuffer`：写入 debug/info/warn/error；
   - `testRingBufferCapacity_capsAt200AndDropsOldest`：并发/循环写入 250 条，验证容量恒定为 200 且最旧记录被覆盖淘汰。
4. **`XiaoxiCrashHandlerTest`**：
   - `testSaveCrashReport_generatesValidReportWithLogsAndStackTrace`：验证报告结构包含设备、线程、堆栈与环形日志；
   - `testCrashReportRotation_keepsMax5Files`：生成 7 份崩溃文件，验证自动轮转删除最旧文件，维持最多 5 份。

### 3.2 变异测试（Mutation Testing）验证规划
- **变异点 1**：故意将 `ChatHistoryTrimmer.kt` 中 `dropWhile { it.role != "user" }` 对齐逻辑注释，验证 `testTrim_alignsToUserMessageRole` 变红。
- **变异点 2**：故意在 `DualTrackMemoryManager.kt` 中忽略 `preRecalledUserMemories` 强制重新查询，验证单测拦截。
- **变异点 3**：故意将 `XiaoxiLog.MAX_RING_BUFFER_SIZE` 从 200 改坏为 99999，验证 `testRingBufferCapacity` 变红。
- **变异点 4**：故意在 `XiaoxiCrashHandler.kt` 中将 `MAX_CRASH_FILES` 判定条件关闭，验证轮转测试变红。
