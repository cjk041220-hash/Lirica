package com.lirica.android

import androidx.test.core.app.ApplicationProvider
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
    fun testHandoffManagerIsStableSingleton() {
        val app = ApplicationProvider.getApplicationContext<LiricaApplication>()

        // 交接快照跨 VM 重建不丢的前提：handoffManager 是 Application 级单例
        assertSame(app.handoffManager, app.handoffManager)
    }
}
