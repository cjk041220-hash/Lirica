package com.lirica.android.automation.coordinator

import com.lirica.android.common.logging.LiricaLog

/**
 * 原子动作执行器延迟绑定桥。
 *
 * 存在理由是一个真实的构造环：
 * - [AutonomousActionLoop] 需要 [AtomicActionExecutor] 才能落地动作；
 * - [HelixGuiCoordinatorImpl] 需要 [AutonomousActionLoop] 才能提供动态任务入口；
 * - 而执行器本身就是协调器。
 *
 * 三种解法里只有延迟绑定是可接受的：
 * 1. 让闭环自己实现一遍落地逻辑 → 直接违反"拒绝重复造轮子"（会分裂出第二套闸门路径）；
 * 2. 让协调器自己实现闭环 → 单个类膨胀到无法审查，且闭环的预算/核销纪律会被业务分支淹没；
 * 3. 延迟绑定 → 依赖方向保持单向，装配期一次性接线，运行期零开销（一次 volatile 读）。
 *
 * 绑定前的调用一律 fail-closed：返回明确失败而不是静默 no-op——
 * 静默 no-op 会让"没装配好"表现为"操作成功但屏幕没反应"，这是最贵的调试成本。
 */
class AtomicActionExecutorBridge : AtomicActionExecutor {

    @Volatile
    private var delegate: AtomicActionExecutor? = null

    fun bind(executor: AtomicActionExecutor) {
        delegate = executor
        LiricaLog.i("AtomicActionExecutorBridge", "原子动作执行器已绑定: " + executor::class.simpleName)
    }

    val isBound: Boolean get() = delegate != null

    override suspend fun executeAtomic(
        action: CoordinatorAction,
        stepIndex: Int,
        totalSteps: Int
    ): AtomicExecutionOutcome {
        val executor = delegate
            ?: return AtomicExecutionOutcome.Failed("原子动作执行器尚未绑定（装配期缺陷，非运行期异常）")
        return executor.executeAtomic(action, stepIndex, totalSteps)
    }
}
