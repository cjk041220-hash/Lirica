package com.lirica.android.perception

import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.dao.EpisodicSensingSliceDao
import com.lirica.android.database.entity.EpisodicSensingSliceEntity
import com.lirica.android.perception.model.HardwareEnvironmentState
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/**
 * 情境感知切片缓冲器 (SensingEpisodicBuffer - M4.1 核心)
 *
 * 核心职能：
 * 1. 监听 PerceptionPipeline 快照流，聚合应用停留时长与关键物理环境跃迁；
 * 2. 聚类为轻量情境切片（Episodic Slice），写入 episodic_sensing_slices 表；
 * 3. 严格限制单日存储上限 50 条（全天额外 DB 占用 <15KB），杜绝日志无界膨胀；
 * 4. 为夜间自叙梦系统（SubconsciousDreamCoordinator）提供情境原料。
 */
class SensingEpisodicBuffer(
    private val sliceDao: EpisodicSensingSliceDao,
    val minDwellDurationMs: Long = DEFAULT_MIN_DWELL_MS,
    val maxDailySlices: Int = MAX_DAILY_SLICES,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        const val DEFAULT_MIN_DWELL_MS = 3 * 60 * 1000L // 默认 3 分钟以上的前台专注才计入切片
        const val MAX_DAILY_SLICES = 50                 // 单日上限 50 条切片，防 DB 膨胀
    }

    private var lastPackage: String? = null
    private var lastActivity: String? = null
    private var dwellStartTimeMs: Long = 0L
    private var lastHeadsetPlugged: Boolean = false
    private var lastLateNightRecordedDate: String? = null
    private var collectionJob: Job? = null

    /** 启动协程自动监听快照流 */
    fun start(scope: CoroutineScope, snapshotFlow: StateFlow<PerceptionSnapshot>, dispatcher: CoroutineDispatcher = Dispatchers.Default) {
        collectionJob?.cancel()
        collectionJob = scope.launch(dispatcher) {
            snapshotFlow.collect { snapshot ->
                processSnapshot(snapshot, timeProvider())
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    /** 处理单次感知快照并判定是否沉淀切片 */
    suspend fun processSnapshot(snapshot: PerceptionSnapshot, nowMs: Long = timeProvider()) {
        val currentApp = snapshot.foregroundApp
        val currentPkg = currentApp?.packageName

        // 1. 前台应用切换：结算上一应用的停留块
        if (currentPkg != lastPackage) {
            if (lastPackage != null && dwellStartTimeMs > 0) {
                val dwellDuration = nowMs - dwellStartTimeMs
                if (dwellDuration >= minDwellDurationMs) {
                    commitSliceForApp(lastPackage!!, lastActivity.orEmpty(), dwellDuration, nowMs, snapshot)
                }
            }
            lastPackage = currentPkg
            lastActivity = currentApp?.className
            dwellStartTimeMs = nowMs
        }

        // 2. 物理硬件跃迁：耳机插拔且开启媒体
        val hw = snapshot.hardwareEnvironment
        if (hw != null) {
            if (hw.isHeadsetPlugged && hw.isAudioPlaying && !lastHeadsetPlugged) {
                commitDirectSlice(
                    summary = "戴上耳机听音乐，沉浸在私人音频空间",
                    category = EpisodicSensingSliceEntity.CATEGORY_ENTERTAINMENT,
                    pkg = currentPkg ?: "system.audio",
                    durationMs = 0L,
                    nowMs = nowMs,
                    snapshot = snapshot
                )
            }
            lastHeadsetPlugged = hw.isHeadsetPlugged
        }

        // 3. 深夜亮屏异常关注 (0:00~4:59 亮屏且停留超过 10 分钟)
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dateStr = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        if (hour in 0..4 && snapshot.screenState.state == ScreenState.UNLOCKED) {
            if (lastLateNightRecordedDate != dateStr && (nowMs - dwellStartTimeMs) >= 10 * 60 * 1000L) {
                commitDirectSlice(
                    summary = "深夜 ${hour} 点仍在使用手机",
                    category = EpisodicSensingSliceEntity.CATEGORY_LATE_NIGHT,
                    pkg = currentPkg ?: "system.screen",
                    durationMs = nowMs - dwellStartTimeMs,
                    nowMs = nowMs,
                    snapshot = snapshot
                )
                lastLateNightRecordedDate = dateStr
            }
        }
    }

    private suspend fun commitSliceForApp(
        pkg: String,
        activity: String,
        durationMs: Long,
        nowMs: Long,
        snapshot: PerceptionSnapshot
    ) {
        val category = classifyCategory(pkg)
        val minutes = durationMs / 60000L
        val summary = "在应用 $pkg 专注停留约 ${minutes} 分钟"
        commitDirectSlice(summary, category, pkg, durationMs, nowMs, snapshot)
    }

    private suspend fun commitDirectSlice(
        summary: String,
        category: String,
        pkg: String,
        durationMs: Long,
        nowMs: Long,
        snapshot: PerceptionSnapshot
    ) {
        val hwJson = snapshot.hardwareEnvironment?.let {
            "{\"charging\":${it.isCharging},\"headset\":${it.isHeadsetPlugged},\"audioPlaying\":${it.isAudioPlaying}}"
        } ?: "{}"

        val slice = EpisodicSensingSliceEntity(
            id = UUID.randomUUID().toString(),
            timestampMs = nowMs,
            dwellDurationMs = durationMs,
            appCategory = category,
            primaryPackage = pkg,
            summaryScene = summary,
            hardwareContextJson = hwJson,
            isConsolidated = false
        )

        sliceDao.insert(slice)
        pruneIfNeeded()
        LiricaLog.d("SensingEpisodicBuffer", "已记录情境切片: $summary")
    }

    private suspend fun pruneIfNeeded() {
        val count = sliceDao.getTotalCount()
        if (count > maxDailySlices) {
            val toDelete = count - maxDailySlices
            sliceDao.deleteOldest(toDelete)
        }
    }

    private fun classifyCategory(pkg: String): String {
        val lower = pkg.lowercase()
        return when {
            lower.contains("study") || lower.contains("read") || lower.contains("book") ||
            lower.contains("note") || lower.contains("office") || lower.contains("pdf") ||
            lower.contains("edu") || lower.contains("academic") -> EpisodicSensingSliceEntity.CATEGORY_STUDY
            lower.contains("video") || lower.contains("music") || lower.contains("bilibili") ||
            lower.contains("game") || lower.contains("youtube") || lower.contains("tiktok") -> EpisodicSensingSliceEntity.CATEGORY_ENTERTAINMENT
            lower.contains("mm") || lower.contains("mobileqq") || lower.contains("chat") ||
            lower.contains("wechat") || lower.contains("message") -> EpisodicSensingSliceEntity.CATEGORY_COMMUNICATION
            else -> EpisodicSensingSliceEntity.CATEGORY_SYSTEM
        }
    }
}