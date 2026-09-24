package com.xiaoxi.android.perception

import android.view.accessibility.AccessibilityEvent
import app.cash.turbine.test
import com.xiaoxi.android.perception.model.ForegroundAppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ZeroPollingForegroundTest {

    @Test
    fun testZeroPollingForegroundMerge_prioritizesRealTimeEvents() = runTest {
        val accessibilityFlow = XiaoxiAccessibilityService.windowForegroundAppEvents
        val usageStatsFlow = MutableStateFlow<ForegroundAppInfo?>(null)

        val mergedFlow = merge(
            accessibilityFlow,
            usageStatsFlow.filterNotNull()
        )

        mergedFlow.test {
            // 1. 无障碍事件秒级驱动下发
            val service = XiaoxiAccessibilityService()
            val event = AccessibilityEvent.obtain().apply {
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                packageName = "com.tencent.mm"
            }
            service.onAccessibilityEvent(event)

            val item1 = awaitItem()
            assertEquals("com.tencent.mm", item1.packageName)

            // 2. 降级通道 UsageStats 产生事件
            usageStatsFlow.value = ForegroundAppInfo(
                packageName = "com.eg.android.AlipayGphone",
                timestamp = System.currentTimeMillis(),
                dwellDurationMs = 5000L
            )

            val item2 = awaitItem()
            assertEquals("com.eg.android.AlipayGphone", item2.packageName)
        }
    }
}
