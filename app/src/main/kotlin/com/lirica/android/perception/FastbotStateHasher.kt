package com.lirica.android.perception

import com.lirica.android.perception.model.AccessibilityNodeSnapshot

/**
 * 基于 Fastbot 状态抽象的三元组状态指纹哈希计算器
 * 过滤界面无实质变更的微小动画或重复渲染
 */
object FastbotStateHasher {

    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    /**
     * 计算当前页面状态指纹
     */
    fun computeFingerprint(activityName: String, nodes: List<AccessibilityNodeSnapshot>): Long {
        var hash = FNV_OFFSET_BASIS xor activityName.hashCode().toLong()
        hash *= FNV_PRIME

        for (node in nodes) {
            val textHash = node.text?.hashCode()?.toLong() ?: 0L
            val descHash = node.contentDescription?.hashCode()?.toLong() ?: 0L
            val classHash = node.className?.hashCode()?.toLong() ?: 0L
            val idHash = node.viewId?.hashCode()?.toLong() ?: 0L
            val flagHash = (if (node.isClickable) 1L else 0L) or
                    (if (node.isEditable) 2L else 0L) or
                    (if (node.isPassword) 4L else 0L) or
                    (if (node.isFocused) 8L else 0L)

            val nodeSig = (((textHash * 31 + descHash) * 31 + classHash) * 31 + idHash) * 31 + flagHash
            hash = (hash xor nodeSig) * FNV_PRIME
        }

        return hash
    }

    // =====================================================================
    // M5.2 双指纹收敛（主方案5.0 §二 StateFingerprint / §5.1.4 双采样抗噪）
    // =====================================================================

    /**
     * 易变文本归一化：把时间戳、电量、计数、加载态等"页面结构没变但文字在跳"
     * 的片段替换为稳定占位符。
     *
     * 为什么必须有这一步：Fastbot 原始指纹对节点文本取哈希，而顶部状态栏时钟、
     * 电量百分比、点赞数与"加载中…"每秒都在变。不做归一化时，一次点击后的
     * 双采样永远不相等，核销逻辑会把"动作已生效"误判成"页面还在震荡"，
     * 进而触发无意义自愈重试——这是自动化闭环里最隐蔽的假阴性来源。
     *
     * 已知近似（诚实披露）：归一化会抹平"纯数字文本节点"的差异，
     * 因此指纹只用于稳定性与变更判定，绝不可用于"这两个页面是否语义相同"的强断言。
     */
    fun normalizeVolatileText(raw: String?): String? {
        if (raw == null) return null
        var text = raw.trim()
        if (text.isEmpty()) return null

        text = LOADING_REGEX.replace(text, VOLATILE_LOADING)

        // 时钟 HH:mm / HH:mm:ss（含状态栏与视频进度）
        text = CLOCK_REGEX.replace(text, VOLATILE_TIME)

        // 日期 2026-10-06 / 2026年10月6日 / 10-06
        text = DATE_REGEX.replace(text, VOLATILE_DATE)

        // 百分比（电量、进度）
        text = PERCENT_REGEX.replace(text, VOLATILE_PERCENT)

        // 带单位的计数（1.2万 / 3.4k / 12亿）——点赞数、播放量轮播
        text = UNIT_COUNT_REGEX.replace(text, VOLATILE_COUNT)

        // 纯数字节点（角标、页码、剩余秒数）
        if (STANDALONE_NUMBER_REGEX.matches(text)) {
            return VOLATILE_NUMBER
        }

        // 统一空白，避免多空格差异造成的假变更
        return text.replace(WHITESPACE_REGEX, " ").trim().ifEmpty { null }
    }

    /**
     * 以 Fastbot 三元组（结构 + 归一化文本 + 交互标志）为基准的视图指纹。
     *
     * 与 [computeFingerprint] 的关系：后者是感知管线既有入口（消费
     * AccessibilityNodeSnapshot，不做过多的文本归一化，保持历史行为不变）；
     * 本函数是执行闭环专用入口，额外纳入包名、交互标志位并施加易变文本归一化。
     * 双入口共享同一套 FNV 混合常数，符合方案"统一收敛为以 FastbotStateHasher
     * 为基准的双采样抗噪指纹服务"的裁决。
     */
    fun computeViewFingerprint(
        activityName: String,
        packageName: String,
        views: List<ViewSignature>
    ): Long {
        var hash = FNV_OFFSET_BASIS xor activityName.hashCode().toLong()
        hash = (hash xor packageName.hashCode().toLong()) * FNV_PRIME

        for (view in views) {
            val textHash = normalizeVolatileText(view.text)?.hashCode()?.toLong() ?: 0L
            val descHash = normalizeVolatileText(view.contentDescription)?.hashCode()?.toLong() ?: 0L
            val classHash = view.className?.hashCode()?.toLong() ?: 0L
            val idHash = view.viewId?.hashCode()?.toLong() ?: 0L
            val flagHash = (if (view.isClickable) 1L else 0L) or
                (if (view.isEditable) 2L else 0L) or
                (if (view.isPassword) 4L else 0L) or
                (if (view.isEnabled) 8L else 0L) or
                (if (view.isChecked) 16L else 0L) or
                (if (view.isSelected) 32L else 0L)

            val nodeSig = (((textHash * 31 + descHash) * 31 + classHash) * 31 + idHash) * 31 + flagHash
            hash = (hash xor nodeSig) * FNV_PRIME
        }

        return hash
    }

    const val VOLATILE_TIME = "<TIME>"
    const val VOLATILE_DATE = "<DATE>"
    const val VOLATILE_PERCENT = "<PCT>"
    const val VOLATILE_COUNT = "<COUNT>"
    const val VOLATILE_NUMBER = "<NUM>"
    const val VOLATILE_LOADING = "<LOADING>"

    private val CLOCK_REGEX = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
    private val DATE_REGEX = Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?""")
    private val PERCENT_REGEX = Regex("""\d{1,3}\s?%""")
    // 注意：尾部不能加 \b —— 中文字符在 Java 默认 \w 语义下不是"词字符"，
    // "1.2万" 会因此匹配失败（这是一处真实踩过的坑，别再改回去）
    private val UNIT_COUNT_REGEX = Regex("""\d+(\.\d+)?\s?(万|亿|k|K|w|W)""")
    private val STANDALONE_NUMBER_REGEX = Regex("""\d{1,6}""")
    private val LOADING_REGEX = Regex(
        """(加载中|正在加载|请稍候|载入中|loading|剩余\s*\d+\s*秒)[.。…]*""",
        RegexOption.IGNORE_CASE
    )
    private val WHITESPACE_REGEX = Regex("""\s+""")
}

/**
 * 执行闭环专用视图签名（与感知管线快照解耦，避免 Binder 侧模型被执行侧污染）。
 */
data class ViewSignature(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewId: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isPassword: Boolean = false,
    val isEnabled: Boolean = true,
    val isChecked: Boolean = false,
    val isSelected: Boolean = false
)

