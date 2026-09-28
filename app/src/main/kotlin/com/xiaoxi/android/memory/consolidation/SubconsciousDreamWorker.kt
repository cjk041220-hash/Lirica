package com.xiaoxi.android.memory.consolidation

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiaoxi.android.XiaoxiApplication
import com.xiaoxi.android.database.XiaoxiDatabase
import com.xiaoxi.android.proactivity.DiaryEngineImpl
import java.util.concurrent.TimeUnit

/**
 * 自叙梦系统后台调度器 (SubconsciousDreamWorker)
 *
 * 遵循严格的夜间待机能耗契约：
 * 1. 必须处于充电中 (requiresCharging = true)
 * 2. 必须处于设备空闲待机态 (requiresDeviceIdle = true)
 * 3. 必须已联网 (NetworkType.CONNECTED)
 * 4. 严禁持有常驻 WakeLock。
 */
class SubconsciousDreamWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = XiaoxiDatabase.getInstance(applicationContext)
            val app = applicationContext as? XiaoxiApplication
            val coordinator = SubconsciousDreamCoordinator(
                userMemoryDao = database.userMemoryDao(),
                worldviewDao = database.worldviewDao(),
                selfExperienceDao = database.selfExperienceDao(),
                unspokenThoughtDao = database.unspokenThoughtDao(),
                diaryEngine = DiaryEngineImpl(
                    selfExperienceDao = database.selfExperienceDao(),
                    chatDialogueDao = database.chatDialogueDao()
                ),
                llmClient = app?.llmClient,
                llmConfig = app?.backgroundLlmConfig,
                hybridRetriever = app?.memoryManager?.getHybridRetriever()
            )
            coordinator.runDreamCycle()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "lirica_subconscious_dream_worker"

        fun createPeriodicWorkRequest(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return PeriodicWorkRequestBuilder<SubconsciousDreamWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        }

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
