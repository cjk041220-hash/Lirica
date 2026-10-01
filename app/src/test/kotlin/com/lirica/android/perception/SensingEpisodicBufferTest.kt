package com.lirica.android.perception

import com.lirica.android.database.dao.EpisodicSensingSliceDao
import com.lirica.android.database.entity.EpisodicSensingSliceEntity
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.HardwareEnvironmentState
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SensingEpisodicBufferTest {

    private val recordedSlices = mutableListOf<EpisodicSensingSliceEntity>()
    private var currentTime = 1000000L

    private val fakeDao = object : EpisodicSensingSliceDao {
        override suspend fun insert(slice: EpisodicSensingSliceEntity): Long {
            recordedSlices.add(slice)
            return recordedSlices.size.toLong()
        }
        override suspend fun insertAll(slices: List<EpisodicSensingSliceEntity>) {
            recordedSlices.addAll(slices)
        }
        override suspend fun getUnconsolidatedSlices(limit: Int): List<EpisodicSensingSliceEntity> {
            return recordedSlices.filter { !it.isConsolidated }.take(limit)
        }
        override suspend fun markAsConsolidated(ids: List<String>) {
            for (i in recordedSlices.indices) {
                if (recordedSlices[i].id in ids) {
                    recordedSlices[i] = recordedSlices[i].copy(isConsolidated = true)
                }
            }
        }
        override suspend fun getRecentSlices(sinceMs: Long, limit: Int): List<EpisodicSensingSliceEntity> {
            return recordedSlices.filter { it.timestampMs >= sinceMs }.take(limit)
        }
        override suspend fun getUnconsolidatedCount(): Int = recordedSlices.count { !it.isConsolidated }
        override suspend fun getTotalCount(): Int = recordedSlices.size
        override suspend fun deleteOldest(count: Int): Int {
            val toRemove = recordedSlices.take(count)
            recordedSlices.removeAll(toRemove)
            return toRemove.size
        }
        override suspend fun deleteOldConsolidated(beforeMs: Long): Int {
            val toRemove = recordedSlices.filter { it.isConsolidated && it.timestampMs < beforeMs }
            recordedSlices.removeAll(toRemove)
            return toRemove.size
        }
    }

    private lateinit var buffer: SensingEpisodicBuffer

    @Before
    fun setUp() {
        recordedSlices.clear()
        currentTime = 1000000L
        buffer = SensingEpisodicBuffer(
            sliceDao = fakeDao,
            minDwellDurationMs = 180000L, // 3 分钟
            maxDailySlices = 5,
            timeProvider = { currentTime }
        )
    }

    @Test
    fun testAppDwell_recordedWhenExceedingThreshold() = runBlocking {
        // 1. 进入自习应用
        val snap1 = PerceptionSnapshot(
            foregroundApp = ForegroundAppInfo("com.example.study.notes", "MainActivity")
        )
        buffer.processSnapshot(snap1, currentTime)

        // 2. 经过 4 分钟 (240000ms > 180000ms)，切换到桌面
        currentTime += 240000L
        val snap2 = PerceptionSnapshot(
            foregroundApp = ForegroundAppInfo("com.android.launcher", "LauncherActivity")
        )
        buffer.processSnapshot(snap2, currentTime)

        assertEquals(1, recordedSlices.size)
        val slice = recordedSlices[0]
        assertEquals("com.example.study.notes", slice.primaryPackage)
        assertEquals(EpisodicSensingSliceEntity.CATEGORY_STUDY, slice.appCategory)
        assertTrue(slice.dwellDurationMs >= 240000L)
        assertTrue(slice.summaryScene.contains("专注停留"))
    }

    @Test
    fun testAppDwell_ignoredWhenTooBrief() = runBlocking {
        // 1. 进入应用
        val snap1 = PerceptionSnapshot(
            foregroundApp = ForegroundAppInfo("com.example.study", "MainActivity")
        )
        buffer.processSnapshot(snap1, currentTime)

        // 2. 仅停留 1 分钟就切换了
        currentTime += 60000L
        val snap2 = PerceptionSnapshot(
            foregroundApp = ForegroundAppInfo("com.android.launcher", "LauncherActivity")
        )
        buffer.processSnapshot(snap2, currentTime)

        assertEquals(0, recordedSlices.size)
    }

    @Test
    fun testHeadsetPluggedWithMusic_triggersDirectSlice() = runBlocking {
        // 插入耳机并在听音乐
        val snap = PerceptionSnapshot(
            foregroundApp = ForegroundAppInfo("com.netease.cloudmusic", "PlayerActivity"),
            hardwareEnvironment = HardwareEnvironmentState(
                isHeadsetPlugged = true,
                isAudioPlaying = true,
                isCharging = false
            )
        )
        buffer.processSnapshot(snap, currentTime)

        assertEquals(1, recordedSlices.size)
        val slice = recordedSlices[0]
        assertEquals(EpisodicSensingSliceEntity.CATEGORY_ENTERTAINMENT, slice.appCategory)
        assertTrue(slice.summaryScene.contains("耳机"))
    }

    @Test
    fun testCapacityPruning_removesOldest() = runBlocking {
        // 连续产生 6 个切片（上限设置为 5）
        for (i in 1..6) {
            buffer.processSnapshot(
                PerceptionSnapshot(foregroundApp = ForegroundAppInfo("app.$i", "Activity")),
                currentTime
            )
            currentTime += 200000L
        }
        // 换到退出应用触发最后一次提交
        buffer.processSnapshot(
            PerceptionSnapshot(foregroundApp = ForegroundAppInfo("app.final", "Activity")),
            currentTime
        )

        assertTrue("总切片不应超过最大容量 5", recordedSlices.size <= 5)
    }
}