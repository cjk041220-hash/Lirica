package com.lirica.android.automation.macro

import com.lirica.android.database.entity.ActionMacroEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 宏动作原子种类（M5.3 经验轨迹 · §5.2）。
 *
 * 刻意只保留 6 种可归一化、可核销的原语：
 * 任何无法用它们表达的动作（如"识别验证码"）都不该被录成宏——
 * 那类动作依赖现场语义，录下来就是盲回放的温床。
 */
@Serializable
enum class MacroStepKind {
    CLICK,
    TYPE,
    SCROLL,
    KEY,
    WAIT,
    BACK
}

/**
 * 宏动作序列中的单步。
 *
 * 坐标同时保留两种表达：
 * - [refId]：录制当时的语义引用（节点序号），回放时优先按引用重定位；
 * - [normalizedX]/[normalizedY]：[0,1000] 归一化比例坐标兜底。
 *
 * 为什么不只存归一化坐标：§7.2 明令"严禁像素写死"，归一化已满足分辨率漂移，
 * 但**布局改版**（同分辨率下按钮换位置）只有语义引用能救。
 * 为什么不只存 refId：refId 在目标应用改版后会指向别的东西，
 * 此时归一化坐标作为"同屏同位置"的弱兜底比失败更好。二者互为校验。
 */
@Serializable
data class MacroStep(
    val kind: MacroStepKind,
    val refId: String? = null,
    val normalizedX: Int? = null,
    val normalizedY: Int? = null,
    /** 固定文本（非槽位） */
    val text: String? = null,
    /** 显式槽位键：文本由调用方在回放时注入（一期不做模糊参数推断） */
    val slotKey: String? = null,
    val direction: String? = null,
    val keyCode: Int? = null,
    val delayMs: Long? = null,
    val description: String = ""
) {
    /** 是否依赖显式槽位 */
    val usesSlot: Boolean get() = !slotKey.isNullOrBlank()

    /**
     * 解析本步实际要注入的文本。
     * @return 文本；[usesSlot] 为真但槽位缺失时返回 null（调用方须判失败而非注入空串）
     */
    fun resolveText(slots: Map<String, String>): String? {
        if (usesSlot) return slots[slotKey]
        return text
    }

    /** 结构自检：坐标类动作必须有落点 */
    fun isWellFormed(): Boolean = when (kind) {
        MacroStepKind.CLICK -> (normalizedX != null && normalizedY != null) || refId != null
        MacroStepKind.TYPE -> !text.isNullOrBlank() || usesSlot
        MacroStepKind.SCROLL -> !direction.isNullOrBlank()
        MacroStepKind.KEY -> keyCode != null
        MacroStepKind.WAIT -> (delayMs ?: 0L) > 0L
        MacroStepKind.BACK -> true
    }
}

/**
 * 领域层宏实体（与 Room 实体解耦：DAO 只认扁平字符串，领域层只认强类型序列）。
 */
data class ActionMacro(
    val macroId: String,
    val intentSignature: String,
    val appPackage: String,
    val targetAppVersionCode: Long,
    val startPageFingerprint: Long,
    val steps: List<MacroStep>,
    val explicitSlotKeys: List<String>,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val isDeprecated: Boolean = false
) {
    /** 回放前置校验：结构非法或空序列的宏一律不可回放 */
    val isReplayable: Boolean
        get() = steps.isNotEmpty() && !isDeprecated && steps.all { it.isWellFormed() }
}

/** 录制请求（仅在任务成功且声明了意图签名时才录制） */
data class MacroRecordSpec(
    val intentSignature: String,
    val appPackage: String,
    val targetAppVersionCode: Long,
    val startPageFingerprint: Long,
    val steps: List<MacroStep>,
    val explicitSlotKeys: List<String> = steps.mapNotNull { it.slotKey }.distinct()
)

/** 宏 JSON 编解码器（集中一处，杜绝各调用点各写一套导致格式漂移） */
object MacroCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeSteps(steps: List<MacroStep>): String = json.encodeToString(steps)

    fun decodeSteps(raw: String): List<MacroStep> = runCatching {
        json.decodeFromString<List<MacroStep>>(raw)
    }.getOrDefault(emptyList())

    fun encodeSlotKeys(keys: List<String>): String = json.encodeToString(keys)

    fun decodeSlotKeys(raw: String): List<String> = runCatching {
        json.decodeFromString<List<String>>(raw)
    }.getOrDefault(emptyList())

    fun toEntity(macro: ActionMacro, nowMs: Long): ActionMacroEntity = ActionMacroEntity(
        macroId = macro.macroId,
        intentSignature = macro.intentSignature,
        appPackage = macro.appPackage,
        targetAppVersionCode = macro.targetAppVersionCode,
        startPageFingerprint = macro.startPageFingerprint,
        stepsJson = encodeSteps(macro.steps),
        explicitSlotKeys = encodeSlotKeys(macro.explicitSlotKeys),
        successCount = macro.successCount,
        failureCount = macro.failureCount,
        consecutiveFailures = macro.consecutiveFailures,
        isDeprecated = macro.isDeprecated,
        createdAtMs = nowMs,
        lastUsedAtMs = nowMs
    )

    fun toDomain(entity: ActionMacroEntity): ActionMacro = ActionMacro(
        macroId = entity.macroId,
        intentSignature = entity.intentSignature,
        appPackage = entity.appPackage,
        targetAppVersionCode = entity.targetAppVersionCode,
        startPageFingerprint = entity.startPageFingerprint,
        steps = decodeSteps(entity.stepsJson),
        explicitSlotKeys = decodeSlotKeys(entity.explicitSlotKeys),
        successCount = entity.successCount,
        failureCount = entity.failureCount,
        consecutiveFailures = entity.consecutiveFailures,
        isDeprecated = entity.isDeprecated
    )
}
