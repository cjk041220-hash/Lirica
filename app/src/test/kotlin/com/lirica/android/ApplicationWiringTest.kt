package com.lirica.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 装配完整性测试（P1-5）
 *
 * 主界面工具闭环依赖 Application 层装配：若 nativeToolAgent 缺位，
 * ChatViewModel 拿不到 toolAgent，LLM 收不到 tools 声明，
 * 代行点击 / 文本输入 / 定时闹钟等全部工具能力在真机上是死路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LiricaApplication::class, sdk = [34])
class ApplicationWiringTest {

    @Test
    fun testApplicationAssemblesToolAgentCoordinatorAndHandoff() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()

        assertNotNull("nativeToolAgent 必须在 Application 装配（主界面工具闭环的前提）", app.nativeToolAgent)
        assertNotNull("helixCoordinator 必须在 Application 装配", app.helixCoordinator)
        assertNotNull("handoffManager 必须在 Application 装配", app.handoffManager)
    }

    @Test
    fun testToolAgentDeclaresToolsForLlmLoop() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()

        // ChatViewModel 通过 toolAgent?.declaredTools 传给 LLM——声明为空则工具闭环同样死路
        val tools = app.nativeToolAgent.declaredTools
        assertTrue("工具声明不得为空（open_app / click_element / input_text / schedule_wake 等）", tools.isNotEmpty())
    }

    @Test
    fun testMc51ActionEngineComponentsAreWired() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()

        // M5.1 P0-1：安全裁决引擎必须存在。它缺席时协调器会静默退回"仅 Gatekeeper"的
        // 历史行为——双态模式与漫游只读整体失效，且不会有任何报错。
        assertNotNull("安全裁决引擎必须在 Application 装配", app.safetyPolicyEngine)

        // M5.1 §八：双态模式可读（默认共享模式）
        assertEquals(
            com.lirica.android.automation.policy.ActionExecutionMode.SHARED,
            app.helixCoordinator.currentMode()
        )
    }

    @Test
    fun testMc52ObservabilityAndInterruptionAreWired() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()
        assertNotNull("用户抢占熔断器必须在 Application 装配", app.userInterruptionDetector)
        assertNotNull("执行轨迹记录器必须在 Application 装配（三项核心指标的数据源）", app.executionTraceRecorder)
        assertNotNull("无障碍保活健康监视器必须在 Application 装配", app.accessibilityHealthMonitor)
    }

    @Test
    fun testMc53TrajectoryMemoryAndDatabaseV6AreWired() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()
        assertNotNull("经验轨迹库必须在 Application 装配", app.trajectoryMemory)
        // DAO 可达 = DB v6 迁移路径已在真实构建入口注册
        assertNotNull(app.database.actionMacroDao())
    }

    @Test
    fun testDualModeSwitchPropagatesFromUiToEngineAndPersists() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()

        // 设置页切换 → Application 落盘 → 裁决引擎与协调器同步生效。
        // 这条链路任何一环断开，都会出现"UI 显示漫游、底层全权限执行"的危险窗口。
        app.setActionMode(com.lirica.android.automation.policy.ActionExecutionMode.ROAMING)
        try {
            assertEquals(
                com.lirica.android.automation.policy.ActionExecutionMode.ROAMING,
                app.safetyPolicyEngine.mode.value
            )
            assertEquals(
                com.lirica.android.automation.policy.ActionExecutionMode.ROAMING,
                app.helixCoordinator.currentMode()
            )
            assertEquals(
                com.lirica.android.automation.policy.ActionExecutionMode.ROAMING,
                app.actionModeSettings.load()
            )
        } finally {
            app.setActionMode(com.lirica.android.automation.policy.ActionExecutionMode.SHARED)
        }
    }

    @Test
    fun testHandoffManagerIsStableSingleton() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()

        // 交接快照跨 VM 重建不丢的前提：handoffManager 是 Application 级单例
        assertSame(app.handoffManager, app.handoffManager)
    }
}
