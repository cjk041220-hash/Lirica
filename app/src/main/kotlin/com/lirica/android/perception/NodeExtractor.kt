package com.lirica.android.perception

import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.perception.model.AccessibilityNodeSnapshot

/**
 * 无障碍节点抽象视图接口，使算法与 AOSP 框架深度解耦，支持零反射单元测试
 */
interface AccessibilityNodeView {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val className: CharSequence?
    val viewIdResourceName: String?
    val isVisibleToUser: Boolean
    val isClickable: Boolean
    val isEditable: Boolean
    val isPassword: Boolean
    val isFocused: Boolean
    val childCount: Int
    fun getChild(index: Int): AccessibilityNodeView?
}

/**
 * 针对 AOSP 真实 AccessibilityNodeInfo 的原生轻量适配器
 */
class AndroidNodeViewAdapter(private val info: AccessibilityNodeInfo) : AccessibilityNodeView {
    override val text: CharSequence? get() = info.text
    override val contentDescription: CharSequence? get() = info.contentDescription
    override val className: CharSequence? get() = info.className
    override val viewIdResourceName: String? get() = info.viewIdResourceName
    override val isVisibleToUser: Boolean get() = info.isVisibleToUser
    override val isClickable: Boolean get() = info.isClickable
    override val isEditable: Boolean get() = info.isEditable
    override val isPassword: Boolean get() = info.isPassword
    override val isFocused: Boolean get() = info.isFocused
    override val childCount: Int get() = info.childCount
    override fun getChild(index: Int): AccessibilityNodeView? {
        val child = info.getChild(index) ?: return null
        return AndroidNodeViewAdapter(child)
    }
}

/**
 * 前台无障碍节点轻量抽取器
 * 核心指标严守：
 * 1. 深度严格限高：depth <= 4 递归剪枝
 * 2. 节点总数限额：最多抽取 64 个关键节点，杜绝全量 dump 导致的高能耗与 GC 停顿
 * 3. 字符严格限长：单节点文本截断至 128 字符
 * 4. 严格只读隔离：绝不调用任何 performAction()
 */
class NodeExtractor {

    companion object {
        const val MAX_DEPTH = 4
        const val MAX_NODES = 64
        const val MAX_CHAR_LENGTH = 128
    }

    /**
     * 从系统 AccessibilityNodeInfo 抽取轻量快照
     */
    fun extract(root: AccessibilityNodeInfo?): List<AccessibilityNodeSnapshot> {
        if (root == null) return emptyList()
        return extract(AndroidNodeViewAdapter(root))
    }

    /**
     * 从抽象节点视图抽取轻量快照
     */
    fun extract(root: AccessibilityNodeView?): List<AccessibilityNodeSnapshot> {
        if (root == null) return emptyList()
        val results = mutableListOf<AccessibilityNodeSnapshot>()
        traverse(root, currentDepth = 0, results)
        return results
    }

    private fun traverse(
        node: AccessibilityNodeView,
        currentDepth: Int,
        collected: MutableList<AccessibilityNodeSnapshot>
    ) {
        // 剪枝 1: 用户不可见节点立即放弃
        if (!node.isVisibleToUser) {
            return
        }

        // 剪枝 2: 深度超过限高 (MAX_DEPTH = 4) 立即停止递归
        if (currentDepth > MAX_DEPTH) {
            return
        }

        // 剪枝 3: 达到单屏最大节点容量限制 (64)
        if (collected.size >= MAX_NODES) {
            return
        }

        val rawText = node.text?.toString()?.trim()
        val rawDesc = node.contentDescription?.toString()?.trim()
        val isActionable = node.isClickable || node.isEditable || node.isFocused
        val hasContent = !rawText.isNullOrEmpty() || !rawDesc.isNullOrEmpty()

        // 过滤无交互且无文本的纯布局容器
        if (isActionable || hasContent || node.isPassword) {
            val safeText = rawText?.take(MAX_CHAR_LENGTH)
            val safeDesc = rawDesc?.take(MAX_CHAR_LENGTH)

            collected.add(
                AccessibilityNodeSnapshot(
                    text = safeText,
                    contentDescription = safeDesc,
                    className = node.className?.toString(),
                    viewId = node.viewIdResourceName,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isPassword = node.isPassword,
                    isFocused = node.isFocused,
                    depth = currentDepth
                )
            )
        }

        // 递归子节点
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (collected.size >= MAX_NODES) break
            val child = node.getChild(i) ?: continue
            traverse(child, currentDepth + 1, collected)
        }
    }
}
