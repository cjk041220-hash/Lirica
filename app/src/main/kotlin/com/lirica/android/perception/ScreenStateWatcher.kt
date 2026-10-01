package com.lirica.android.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕物理状态监听器与待机/交互时长统计器
 * 纯原生广播监听，零高频轮询开销
 */
class ScreenStateWatcher {

    private val _screenState = MutableStateFlow(ScreenStateSnapshot())
    val screenState: StateFlow<ScreenStateSnapshot> = _screenState.asStateFlow()

    @Volatile
    private var isRegistered: Boolean = false

    val isWatching: Boolean
        get() = isRegistered

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            handleScreenAction(action, System.currentTimeMillis())
        }
    }

    /**
     * 启动广播监听
     */
    @Synchronized
    fun start(context: Context) {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // 2026-09-27 审计 P3 修复：显式声明 NOT_EXPORTED（屏幕三广播为受保护系统广播，
        // 系统发送不受影响），不依赖 targetSdk 演进下的隐式豁免规则
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isRegistered = true
    }

    /**
     * 停止广播监听
     */
    @Synchronized
    fun stop(context: Context) {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // 避免未注册时可能抛出的异常
        } finally {
            isRegistered = false
        }
    }

    /**
     * 核心状态转移机与时长积分
     */
    fun handleScreenAction(action: String, currentTimeMs: Long) {
        synchronized(this) {
            val current = _screenState.value
            val lastTime = current.lastStateChangeTimestamp
            val delta = if (lastTime > 0L) (currentTimeMs - lastTime).coerceAtLeast(0L) else 0L

            when (action) {
                Intent.ACTION_SCREEN_ON -> {
                    val newStandby = if (current.state == ScreenState.OFF) {
                        current.totalStandbyDurationMs + delta
                    } else {
                        current.totalStandbyDurationMs
                    }
                    // 熄屏期间用户不在场：此刻把"上次在场"钉在息屏之前，
                    // 供重新解锁时计算真实离开时长（见 ScreenStateSnapshot.lastUserPresentTimestamp）
                    val lastPresent = if (current.state == ScreenState.OFF && current.lastUserPresentTimestamp > 0L) {
                        current.lastUserPresentTimestamp
                    } else {
                        lastTime.takeIf { it > 0L } ?: currentTimeMs
                    }
                    _screenState.value = current.copy(
                        state = ScreenState.ON,
                        lastStateChangeTimestamp = currentTimeMs,
                        totalStandbyDurationMs = newStandby,
                        lastUserPresentTimestamp = lastPresent
                    )
                }

                Intent.ACTION_USER_PRESENT -> {
                    val newInteractive = if (current.state == ScreenState.ON) {
                        current.totalInteractiveDurationMs + delta
                    } else {
                        current.totalInteractiveDurationMs
                    }
                    // 解锁：延续上一次在场时间（若从未记录过则用上一次状态变更时刻兜底），
                    // 不在这里刷新——"离开多久"要等下一次息屏才重新计时
                    val lastPresent = current.lastUserPresentTimestamp
                        .takeIf { it > 0L }
                        ?: (if (current.state == ScreenState.ON) lastTime else currentTimeMs)
                    _screenState.value = current.copy(
                        state = ScreenState.UNLOCKED,
                        lastStateChangeTimestamp = currentTimeMs,
                        totalInteractiveDurationMs = newInteractive,
                        lastUserPresentTimestamp = lastPresent
                    )
                }

                Intent.ACTION_SCREEN_OFF -> {
                    val newInteractive = if (current.state == ScreenState.ON || current.state == ScreenState.UNLOCKED) {
                        current.totalInteractiveDurationMs + delta
                    } else {
                        current.totalInteractiveDurationMs
                    }
                    _screenState.value = current.copy(
                        state = ScreenState.OFF,
                        lastStateChangeTimestamp = currentTimeMs,
                        totalInteractiveDurationMs = newInteractive,
                        // 息屏前最后一眼 = 本次在场的终点，离开计时从这里开始
                        lastUserPresentTimestamp = lastTime.takeIf { it > 0L } ?: currentTimeMs
                    )
                }
            }
        }
    }
}
