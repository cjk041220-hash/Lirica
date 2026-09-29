package com.lirica.android.perception

import com.lirica.android.perception.model.AccessibilityNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NodeExtractorTest {

    private lateinit var extractor: NodeExtractor

    @Before
    fun setUp() {
        extractor = NodeExtractor()
    }

    private data class FakeNode(
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val className: CharSequence? = "android.widget.TextView",
        override val viewIdResourceName: String? = null,
        override val isVisibleToUser: Boolean = true,
        override val isClickable: Boolean = false,
        override val isEditable: Boolean = false,
        override val isPassword: Boolean = false,
        override val isFocused: Boolean = false,
        val children: List<FakeNode> = emptyList()
    ) : AccessibilityNodeView {
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): AccessibilityNodeView? = children.getOrNull(index)
    }

    @Test
    fun testDepthPruningAtMaxDepth4() {
        // Root depth 0 -> Child 1 -> Child 2 -> Child 3 -> Child 4 (Kept) -> Child 5 (Pruned)
        val deepNode = FakeNode(text = "Depth 5 Text")
        val level4 = FakeNode(text = "Depth 4 Text", children = listOf(deepNode))
        val level3 = FakeNode(text = "Depth 3 Text", children = listOf(level4))
        val level2 = FakeNode(text = "Depth 2 Text", children = listOf(level3))
        val level1 = FakeNode(text = "Depth 1 Text", children = listOf(level2))
        val root = FakeNode(text = "Root Text", children = listOf(level1))

        val results = extractor.extract(root)

        // Must contain Depth 0, 1, 2, 3, 4, but NOT Depth 5
        val texts = results.mapNotNull { it.text }
        assertTrue(texts.contains("Root Text"))
        assertTrue(texts.contains("Depth 4 Text"))
        assertFalse(texts.contains("Depth 5 Text"))
    }

    @Test
    fun testTextTruncationAt128Chars() {
        val longText = "A".repeat(300)
        val node = FakeNode(text = longText, contentDescription = longText)

        val results = extractor.extract(node)
        assertEquals(1, results.size)
        val snapshot = results.first()
        assertEquals(128, snapshot.text?.length)
        assertEquals(128, snapshot.contentDescription?.length)
    }

    @Test
    fun testInvisibleNodesArePruned() {
        val invisibleChild = FakeNode(text = "Secret Text", isVisibleToUser = false)
        val visibleChild = FakeNode(text = "Visible Text", isVisibleToUser = true)
        val root = FakeNode(text = "Root", children = listOf(invisibleChild, visibleChild))

        val results = extractor.extract(root)
        val texts = results.mapNotNull { it.text }
        assertTrue(texts.contains("Visible Text"))
        assertFalse(texts.contains("Secret Text"))
    }

    @Test
    fun testMaxNodesLimitCappedAt64() {
        // 100 child nodes
        val children = (1..100).map { FakeNode(text = "Node-$it") }
        val root = FakeNode(text = "Root", children = children)

        val results = extractor.extract(root)
        assertTrue(results.size <= 64)
        assertEquals(64, results.size)
    }

    @Test
    fun testPasswordFlagIsPreserved() {
        val pwdNode = FakeNode(
            text = "••••••",
            className = "android.widget.EditText",
            isEditable = true,
            isPassword = true
        )
        val root = FakeNode(text = "Login Page", children = listOf(pwdNode))

        val results = extractor.extract(root)
        val passwordSnapshots = results.filter { it.isPassword }
        assertEquals(1, passwordSnapshots.size)
        assertTrue(passwordSnapshots.first().isPassword)
    }
}
