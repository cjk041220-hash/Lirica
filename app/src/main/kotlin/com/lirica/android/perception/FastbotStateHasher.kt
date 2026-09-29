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
}
