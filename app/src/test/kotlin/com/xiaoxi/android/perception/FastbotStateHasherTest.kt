package com.xiaoxi.android.perception

import com.xiaoxi.android.perception.model.AccessibilityNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FastbotStateHasherTest {

    @Test
    fun testSameNodesProduceIdenticalFingerprint() {
        val nodes1 = listOf(
            AccessibilityNodeSnapshot(text = "首页", className = "android.widget.TextView", viewId = "btn_home", isClickable = true),
            AccessibilityNodeSnapshot(text = "搜索", className = "android.widget.EditText", viewId = "search_box", isEditable = true)
        )
        val nodes2 = listOf(
            AccessibilityNodeSnapshot(text = "首页", className = "android.widget.TextView", viewId = "btn_home", isClickable = true),
            AccessibilityNodeSnapshot(text = "搜索", className = "android.widget.EditText", viewId = "search_box", isEditable = true)
        )

        val hash1 = FastbotStateHasher.computeFingerprint("MainActivity", nodes1)
        val hash2 = FastbotStateHasher.computeFingerprint("MainActivity", nodes2)

        assertEquals(hash1, hash2)
        assertNotEquals(0L, hash1)
    }

    @Test
    fun testContentChangeProducesDifferentFingerprint() {
        val nodes1 = listOf(
            AccessibilityNodeSnapshot(text = "待支付", className = "android.widget.TextView", viewId = "status")
        )
        val nodes2 = listOf(
            AccessibilityNodeSnapshot(text = "已完成", className = "android.widget.TextView", viewId = "status")
        )

        val hash1 = FastbotStateHasher.computeFingerprint("OrderDetailActivity", nodes1)
        val hash2 = FastbotStateHasher.computeFingerprint("OrderDetailActivity", nodes2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun testActivityChangeProducesDifferentFingerprint() {
        val nodes = listOf(
            AccessibilityNodeSnapshot(text = "我的", className = "android.widget.TextView", viewId = "tab_me")
        )

        val hash1 = FastbotStateHasher.computeFingerprint("HomeActivity", nodes)
        val hash2 = FastbotStateHasher.computeFingerprint("ProfileActivity", nodes)

        assertNotEquals(hash1, hash2)
    }
}
