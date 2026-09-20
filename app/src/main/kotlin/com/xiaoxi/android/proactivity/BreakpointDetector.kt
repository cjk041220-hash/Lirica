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
        if (currIsLauncher && (prev == null || !prevIsLauncher)) {
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

        // 4. 桌面或阅读长闲暇断点：在桌面上停留时间 > 30秒
        val dwellMs = curr.foregroundApp?.dwellDurationMs ?: 0L
        if (currIsLauncher && dwellMs >= 30_000L) {
            return BreakpointResult(
                isBreakpoint = true,
                type = BreakpointType.LONG_IDLE,
                bonusFactor = 1.1
            )
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
}
