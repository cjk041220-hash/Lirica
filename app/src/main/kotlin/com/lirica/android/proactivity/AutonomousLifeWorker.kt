package com.lirica.android.proactivity

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.database.LiricaDatabase
import java.util.concurrent.TimeUnit

/**
 * 闲时自主生活后台任务 (AutonomousLifeWorker)
 *
 * 遵循严苛的能耗契约：仅在设备充电中 (requiresCharging)、处于空闲待机态 (requiresDeviceIdle)
 * 且已联网 (NetworkType.CONNECTED) 时执行。
 * 严禁常驻 WakeLock。
 */
class AutonomousLifeWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = LiricaDatabase.getInstance(applicationContext)
            val app = applicationContext as? com.lirica.android.LiricaApplication
            val diaryEngine = DiaryEngineImpl(
                selfExperienceDao = database.selfExperienceDao(),
                chatDialogueDao = database.chatDialogueDao()
            )
            diaryEngine.generateDailyDiary(
                llmClient = app?.llmClient,
                llmConfig = app?.backgroundLlmConfig
            )

            // M4.2 联动：闲时自动清理与失效超期 72 小时的开环未决事项
            app?.openLoopLifecycleManager?.expireOverdueLoops()

            // 闲时清理已闭环 7 天以上的历史环（RESOLVED/EXPIRED），防表缓胀。
            // 用数据库时钟而非 Worker 本地变量，语义与 expireOldLoops 的阈值口径一致。
            val nowMs = System.currentTimeMillis()
            val cleaned = database.openLoopDao().cleanupClosedLoops(nowMs - 7L * 24 * 3600 * 1000L)
            if (cleaned > 0) {
                LiricaLog.i(TAG, "闲时清理历史闭环事项: $cleaned 条")
            }

            Result.success()
        } catch (e: Exception) {
            LiricaLog.e(TAG, "AutonomousLifeWorker 自主日记生成任务异常，将触发重试", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AutonomousLifeWorker"
        const val WORK_NAME = "lirica_autonomous_life_worker"

        /**
         * 构建具有高严密能耗约束的 WorkRequest
         */
        fun createPeriodicWorkRequest(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return PeriodicWorkRequestBuilder<AutonomousLifeWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        }

        /**
         * 幂等排队注册后台周期任务
         */
        fun enqueue(context: Context) {
            val request = createPeriodicWorkRequest()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
