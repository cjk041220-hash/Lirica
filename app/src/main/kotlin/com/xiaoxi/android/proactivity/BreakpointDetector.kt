package com.xiaoxi.android.proactivity

import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.perception.model.ScreenState

/**
 * 任务断点时机探测器
 */
interface BreakpointDetector {
    fun detect(prev: PerceptionSnapshot?, curr: PerceptionSnapshot): BreakpointResult
}

class BreakpointDetectorImpl : BreakpointDetector {

    // 2026-09-27 审计 P2 修复：LONG_IDLE 是持续状态而非事件，必须单次触发；
    // 冷却窗内不重复报断点，防止每次裁决都重复 +1.1 增益导致动机系统性偏高。
    // 哨兵值用 -1（从未触发）：Long.MIN_VALUE 会在 nowMs - last 时整型溢出回负数
    private var lastLongIdleMs: Long = -1L

    override fun detect(prev: PerceptionSnapshot?, curr: PerceptionSnapshot): BreakpointResult {
        val currState = curr.screenState.state
        val prevState = prev?.screenState?.state

        // 1. 屏幕解锁断点：由锁屏/息屏变为解锁状态
        if (currState == ScreenState.UNLOCKED && prevState != null && prevState != ScreenState.UNLOCKED) {
            return BreakpointResult(
                isBreakpoint = true,
                type = BreakpointType.SCREEN_UNLOCK,
                bonusFactor = 1.3
            )
        }

        val currPkg = curr.foregroundApp?.packageName?.lowercase() ?: ""
        val prevPkg = prev?.foregroundApp?.packageName?.lowercase() ?: ""

        val currIsLauncher = isLauncher(currPkg)
        val prevIsLauncher = isLauncher(prevPkg)

        // 2. 返回桌面断点：用户退出具体 App 回到 Launcher
        //    （prev == null 是冷启动首拍而非"回桌面"，不构成断点——审计 P2 修复）
        if (currIsLauncher && prev != null && !prevIsLauncher) {
            return BreakpointResult(
                isBreakpoint = true,
                type = BreakpointType.RETURN_TO_LAUNCHER,
                bonusFactor = 1.4
            )
        }

        // 3. 应用切换断点：两个不同的非 Launcher 应用之间切换
        if (currPkg.isNotEmpty() && prevPkg.isNotEmpty() && currPkg != prevPkg) {
            return BreakpointResult(
                isBreakpoint = true,
                type = BreakpointType.APP_SWITCH,
                bonusFactor = 1.2
            )
        }

        // 4. 桌面或阅读长闲暇断点：在桌面上停留时间 > 30秒（冷却窗内只报一次）
        val dwellMs = curr.foregroundApp?.dwellDurationMs ?: 0L
        if (currIsLauncher && dwellMs >= 30_000L) {
            val elapsed = if (lastLongIdleMs < 0L) Long.MAX_VALUE else curr.timestamp - lastLongIdleMs
            if (elapsed >= LONG_IDLE_COOLDOWN_MS) {
                lastLongIdleMs = curr.timestamp
                return BreakpointResult(
                    isBreakpoint = true,
                    type = BreakpointType.LONG_IDLE,
                    bonusFactor = 1.1
                )
            }
        }

        return BreakpointResult(
            isBreakpoint = false,
            type = BreakpointType.NONE,
            bonusFactor = 1.0
        )
    }

    private fun isLauncher(pkg: String): Boolean {
        return pkg.contains("launcher") || pkg.contains("home") || pkg == "android"
    }

    companion object {
        private const val LONG_IDLE_COOLDOWN_MS = 5 * 60 * 1000L // LONG_IDLE 单次触发冷却窗：5 分钟
    }
}
