package com.xiaoxi.android.proactivity

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiaoxi.android.database.XiaoxiDatabase
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
            val database = XiaoxiDatabase.getInstance(applicationContext)
            val diaryEngine = DiaryEngineImpl(
                selfExperienceDao = database.selfExperienceDao(),
                chatDialogueDao = database.chatDialogueDao()
            )
            diaryEngine.generateDailyDiary()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
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
