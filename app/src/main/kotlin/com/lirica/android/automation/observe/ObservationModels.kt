package com.lirica.android.automation.observe

/**
 * 观测表征层级（主方案5.0 §5.1.1 多级观测表征 · P2-1 吸收）。
 *
 * 代价严格递增，且**必须逐级降级使用**：能看语义树就绝不截屏，
 * 能本地 OCR 就绝不上云。这既是 Token 预算纪律，也是隐私纪律。
 */
enum class ObservationLevel(val costRank: Int) {
    /** Level 0：语义裁剪树（默认优先，≤30 项 / Prompt ≤8KB） */
    SEMANTIC_TREE(0),

    /** Level 1：端侧 ML Kit 本地 OCR 文本（自绘/无节点时） */
    LOCAL_OCR(1),

    /** Level 2：截图云端 VLM（最高代价，单任务硬顶 5 次） */
    CLOUD_VLM(2)
}

/** 节点角色（供大模型决策时快速识别可用动作面） */
enum class NodeRole {
    BUTTON,
    INPUT,
    TEXT,
    SCROLL,
    IMAGE,
    TOGGLE,
    OTHER
}

/**
 * 观测节点。
 *
 * 坐标一律为 **[0, 1000] 归一化比例空间**（§7.2 严禁像素写死），
 * 由接地引擎在需要落地时再换算回物理像素——分辨率、DPI 漂移与
 * 折叠屏宽高突变因此都不会污染决策链路上的语义表述。
 */
data class ObservationNode(
    /** 步间稳定引用 ID（供大模型跨步指代与回放核验） */
    val refId: String,
    val text: String?,
    val contentDescription: String?,
    val resourceId: String?,
    val className: String?,
    val role: NodeRole,
    val normalizedX: Int,
    val normalizedY: Int,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean,
    val isChecked: Boolean,
    val isScrollable: Boolean,
    /** 交互词提示：命中漫游互动词表时为 true（供决策层避开漫游禁区） */
    val isRoamingSensitive: Boolean = false,
    /** 本次观测是否位于可滚动容器内 */
    val inScrollableContainer: Boolean = false
) {
    /** 供 Prompt 渲染的一行紧凑表述 */
    fun render(): String {
        val label = text ?: contentDescription ?: resourceId?.substringAfterLast('/') ?: role.name
        val flags = buildString {
            if (isEditable) append("E")
            if (isClickable) append("C")
            if (isScrollable) append("S")
            if (isChecked) append("V")
            if (!isEnabled) append("X")
        }
        val flagPart = if (flags.isEmpty()) "" else " [" + flags + "]"
        val sensitivePart = if (isRoamingSensitive) " (roam-sensitive)" else ""
        return refId + " " + role.name + flagPart + " \"" + label.take(MAX_LABEL_CHARS) + "\" @(" +
            normalizedX + "," + normalizedY + ")" + sensitivePart
    }

    companion object {
        const val MAX_LABEL_CHARS = 60
    }
}

/**
 * 观测快照：交给决策层（LLM 或脚本化 Planner）的完整输入。
 */
data class ObservationSnapshot(
    val level: ObservationLevel,
    val packageName: String,
    val activityName: String?,
    val nodes: List<ObservationNode>,
    /** 被裁剪掉的节点数（诚实披露：决策层需要知道自己没看到全部） */
    val truncatedCount: Int,
    val promptText: String,
    val capturedAtMs: Long
) {
    val promptCharCount: Int get() = promptText.length
    val nodeCount: Int get() = nodes.size

    /** 依据 refId 取回节点（决策层用 refId 指代目标） */
    fun nodeByRef(refId: String): ObservationNode? = nodes.firstOrNull { it.refId == refId }
}

/** 观测结果（显式表达"看不到"而不是用空快照冒充成功） */
sealed interface ObservationResult {
    data class Success(val snapshot: ObservationSnapshot) : ObservationResult

    data class Unavailable(
        val attemptedLevels: List<ObservationLevel>,
        val reason: String
    ) : ObservationResult
}
