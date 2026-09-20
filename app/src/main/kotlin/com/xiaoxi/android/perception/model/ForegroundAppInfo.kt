package com.xiaoxi.android.perception.model

/**
 * 前台应用活跃状态与停留信息
 * @param packageName 前台应用包名 (如 "com.tencent.mm")
 * @param className 前台顶层组件类名 (如 "com.tencent.mm.ui.LauncherUI")
 * @param timestamp 捕获时间戳
 * @param dwellDurationMs 当前应用在本次连续前台期间的停留时长 (毫秒)
 */
data class ForegroundAppInfo(
    val packageName: String,
    val className: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val dwellDurationMs: Long = 0L
)
