package com.lirica.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.Configuration
import com.lirica.android.memory.consolidation.SubconsciousDreamWorker
import com.lirica.android.proactivity.AutonomousLifeWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 真机仪器测试：WorkManager 后台任务在**真实调度框架**下的入队与约束。
 *
 * ## 为什么必须有这个测试
 *
 * 640+ 项单测全部跑在 Robolectric 上，而 **WorkManager 在 Robolectric 中不真实调度**。
 * `LiricaApplication.registerAutonomousLife()` 又用 `runCatching` 包裹了两次 `enqueue`——
 * 一旦入队失败（如 OEM 限制、初始化时序），异常会被静默吞掉，
 * **表现为"夜间记忆固化从未发生"却没有任何错误日志**。
 *
 * 这个测试把"入队成功"从无声无息变成可见的断言。
 *
 * ## 断言策略（重要）
 *
 * 约束与周期通过**直接调用生产的 `createPeriodicWorkRequest()`** 来断言，
 * 而不读 `WorkInfo.workSpec` —— 后者在 work-testing 中不保证公开，
 * 依赖测试框架内部 API会随版本变化而碎裂。
 * 「生产函数返回什么」比「框架内部记录了什么」更接近真实契约。
 *
 * ## 运行方式
 *
 * ```
 * ./gradlew connectedDebugAndroidTest --tests '*BackgroundWorkInstrumentedTest*'
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BackgroundWorkInstrumentedTest {

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun queryWork(name: String): WorkInfo? =
        WorkManager.getInstance(ApplicationProvider.getApplicationContext())
            .getWorkInfosForUniqueWork(name)
            .get()
            .firstOrNull()

    @Test
    fun autonomousLifeWorker_必须真实入队() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 生产代码用 runCatching 静默兜底；此处直接调用，
        // 目的是让"入队失败"变成可见的测试失败而非无声无息。
        AutonomousLifeWorker.enqueue(context)

        val info = queryWork(AutonomousLifeWorker.WORK_NAME)
        assertNotNull("自主生活任务必须真实入队（生产 runCatching 会掩盖失败）", info)
    }

    @Test
    fun subconsciousDreamWorker_必须真实入队() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SubconsciousDreamWorker.enqueue(context)

        val info = queryWork(SubconsciousDreamWorker.WORK_NAME)
        assertNotNull(
            "入梦固化任务必须真实入队；失败意味着夜间记忆固化从未发生",
            info
        )
    }

    @Test
    fun 入队后状态应为ENQUEUED() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AutonomousLifeWorker.enqueue(context)

        val info = queryWork(AutonomousLifeWorker.WORK_NAME)
        assertNotNull(info)
        assertEquals(
            "KEEP 策略下首次入队应处于 ENQUEUED",
            WorkInfo.State.ENQUEUED,
            info!!.state
        )
    }

    @Test
    fun 两个Worker_均为24小时周期() {
        // 注意：intervalDuration / constraints 挂在 WorkSpec 上，
        // WorkRequest 只暴露 workSpec 入口（不是直接属性）。
        val life = AutonomousLifeWorker.createPeriodicWorkRequest().workSpec
        val dream = SubconsciousDreamWorker.createPeriodicWorkRequest().workSpec

        val day = TimeUnit.HOURS.toMillis(24)
        assertEquals("自主生活周期应为 24 小时", day, life.intervalDuration)
        assertEquals("入梦固化周期应为 24 小时", day, dream.intervalDuration)
    }

    /**
     * 约束校验：两个 Worker 都声明了「充电 + 设备空闲 + 网络」三重约束。
     *
     * ## 与文档的关系（已核实）
     *
     * 2026-10-02 审查报告曾记录「主方案 4.0 声称非充电状态靠空闲窗口触发，
     * 但实现未设 RequiresCharging」。**核对源码后推翻了该结论**：
     * 两个 Worker 都实际设置了 `setRequiresCharging(true)`
     * （见 `AutonomousLifeWorker.kt:68` 与 `SubconsciousDreamWorker.kt:70`）。
     *
     * 因此**真正的问题在文档侧**：主方案 4.0 的表述与实现不一致，应改文档而非改代码。
     * 本断言锁定实现的真实行为，防止后续有人误改。
     */
    @Test
    fun 两个Worker_均要求充电与设备空闲() {
        listOf(
            "AutonomousLifeWorker" to AutonomousLifeWorker.createPeriodicWorkRequest(),
            "SubconsciousDreamWorker" to SubconsciousDreamWorker.createPeriodicWorkRequest()
        ).forEach { (name, request) ->
            val c = request.workSpec.constraints
            assertTrue("$name 应要求充电", c.requiresCharging())
            assertTrue("$name 应要求设备空闲", c.requiresDeviceIdle())
        }
    }

    @Test
    fun 重复入队_应保持KEEP策略不重复堆积() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repeat(3) { AutonomousLifeWorker.enqueue(context) }

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AutonomousLifeWorker.WORK_NAME)
            .get()
        assertEquals(
            "KEEP 策略下重复入队只应保留 1 条记录，不应堆积",
            1,
            infos.size
        )
    }
}
