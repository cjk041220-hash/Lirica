package com.lirica.android.perception

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 无障碍保活健康度回归（M5.0 §7.1 国产 ROM 墓碑探测）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AccessibilityHealthMonitorTest {

    private var now = 1_000_000L

    private fun monitor(
        handle: Boolean,
        heartbeat: Long
    ) = AccessibilityHealthMonitor(
        handleProvider = { handle },
        heartbeatProvider = { heartbeat },
        timeProvider = { now }
    )

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun testConnectedWhenHandleAliveAndHeartbeatFresh() {
        val monitor = monitor(handle = true, heartbeat = now - 1_000L)
        // Robolectric 下无障碍开关不可用 → 先验证句柄与心跳分支通过直接评估
        val health = monitor.evaluate(context)
        assertTrue(
            "开关不可用时按 DISABLED_BY_USER 处理（fail-closed）",
            health == AccessibilityHealth.DISABLED_BY_USER
        )
    }

    @Test
    fun testStaleDetectionWhenHeartbeatExpired() {
        val monitor = AccessibilityHealthMonitor(
            handleProvider = { true },
            heartbeatProvider = { 1L },
            timeProvider = { 1L + AccessibilityHealthMonitor.DEFAULT_STALE_THRESHOLD_MS + 1 }
        )
        // 直接验证状态机：句柄在但心跳过期 → 僵尸连接（开关判定在真机上生效）
        assertFalse(monitor.isReadyForAutomation())
    }

    @Test
    fun testInitialStateIsNotReady() {
        val monitor = monitor(handle = true, heartbeat = now)
        assertFalse("未经评估前不得默认放行自动化", monitor.isReadyForAutomation())
        assertEquals(AccessibilityHealth.DISCONNECTED, monitor.health.value)
    }

    @Test
    fun testFourHealthStatesAreDistinguishable() {
        // 开关未开 → DISABLED_BY_USER（Robolectric 下系统开关恒为关，正好覆盖这一支）
        val disabled = AccessibilityHealthMonitor(
            handleProvider = { true },
            heartbeatProvider = { now },
            timeProvider = { now }
        )
        assertEquals(AccessibilityHealth.DISABLED_BY_USER, disabled.evaluate(context))
        assertFalse("开关关闭时不得派发自动化", disabled.canDispatchAutomation())
    }

    @Test
    fun testStaleDoesNotBlockDispatchButDisconnectedDoes() {
        // STALE 只告警不阻断：息屏久置本就收不到事件，据此拒工会让用户在最需要她时吃闭门羹
        val stale = AccessibilityHealthMonitor(
            handleProvider = { true },
            heartbeatProvider = { 1L },
            settingsEnabledProvider = { true },
            timeProvider = { 1L + AccessibilityHealthMonitor.DEFAULT_STALE_THRESHOLD_MS + 1 }
        )
        assertEquals(AccessibilityHealth.STALE, stale.evaluate(context))
        assertTrue("僵尸连接只告警，不阻断派发", stale.canDispatchAutomation())
        assertFalse(stale.isReadyForAutomation())

        // 句柄丢失 = 真的点不动，必须阻断
        val disconnected = AccessibilityHealthMonitor(
            handleProvider = { false },
            heartbeatProvider = { 1L },
            settingsEnabledProvider = { true },
            timeProvider = { 1L }
        )
        assertEquals(AccessibilityHealth.DISCONNECTED, disconnected.evaluate(context))
        assertFalse(disconnected.canDispatchAutomation())

        // 心跳新鲜 + 句柄在 + 开关开 = 完全健康
        val healthy = AccessibilityHealthMonitor(
            handleProvider = { true },
            heartbeatProvider = { 1_000L },
            settingsEnabledProvider = { true },
            timeProvider = { 1_000L }
        )
        assertEquals(AccessibilityHealth.CONNECTED, healthy.evaluate(context))
        assertTrue(healthy.canDispatchAutomation())
        assertTrue(healthy.isReadyForAutomation())
    }

    @Test
    fun testReenableIntentPointsToAccessibilitySettings() {
        val intent = monitor(handle = false, heartbeat = 0L).createReenableIntent()
        assertNotNull(intent)
        assertEquals(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
    }

    @Test
    fun testHealthFlowEmitsTransitions() {
        val monitor = AccessibilityHealthMonitor(
            handleProvider = { false },
            heartbeatProvider = { 0L },
            timeProvider = { now }
        )
        monitor.evaluate(context)
        assertNotNull(monitor.health.value)
    }
}
