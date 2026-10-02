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
        override suspend fun deleteOldestConsolidated(count: Int): Int {
            // 只按时间淘汰已消化切片：未消化的必须原样留下（这正是本轮修复的语义）
            val toRemove = recordedSlices
                .filter { it.isConsolidated }
                .sortedBy { it.timestampMs }
                .take(count)
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
            maxTotalSlices = 5,
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
        // 首帧建立耳机基线（不建切片）：修复"启动即假跃迁"后，存量状态不再被当成跃迁记录
        val baseline = PerceptionSnapshot(
            foregroundApp = ForegroundAppInfo("com.netease.cloudmusic", "PlayerActivity"),
            hardwareEnvironment = HardwareEnvironmentState(
                isHeadsetPlugged = false,
                isAudioPlaying = false,
                isCharging = false
            )
        )
        buffer.processSnapshot(baseline, currentTime)
        assertEquals(0, recordedSlices.size)

        // 插入耳机并在听音乐：false -> true 的真实跃迁才产生切片
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
    fun testHeadsetAlreadyPluggedAtStartup_noFalseTransitionSlice() = runBlocking {
        // 进程启动时耳机已插（无基线）：首个快照只记录状态，不得产生"戴上耳机"切片
        val snap = PerceptionSnapshot(
            foregroundApp = ForegroundAppInfo("com.netease.cloudmusic", "PlayerActivity"),
            hardwareEnvironment = HardwareEnvironmentState(
                isHeadsetPlugged = true,
                isAudioPlaying = true,
                isCharging = false
            )
        )
        buffer.processSnapshot(snap, currentTime)

        assertEquals(0, recordedSlices.size)
    }


    @Test
    fun testCapacityPruning_evictsConsolidatedHistoryAndKeepsAllUnconsolidated() = runBlocking {
        // 独立时间源 + 独立 buffer：容量守门是纯函数式行为，不依赖其他用例的残留状态
        var now = 5_000_000L
        val fresh = SensingEpisodicBuffer(
            sliceDao = fakeDao,
            minDwellDurationMs = 180000L,
            maxTotalSlices = 5,
            timeProvider = { now }
        )

        // 1. 先有 4 条"已消化"的历史切片（时间戳远早于本轮，代表昨天的库存）
        for (i in 1..4) {
            recordedSlices.add(
                EpisodicSensingSliceEntity(
                    id = "old_$i",
                    timestampMs = now - 1_000_000L + i,
                    dwellDurationMs = 300_000L,
                    appCategory = EpisodicSensingSliceEntity.CATEGORY_SYSTEM,
                    primaryPackage = "old.app.$i",
                    summaryScene = "历史切片 $i",
                    isConsolidated = true
                )
            )
        }

        // 2. 白天产生 2 条新的未消化原料（各停留 4 分钟，均超过阈值）
        fresh.processSnapshot(
            PerceptionSnapshot(foregroundApp = ForegroundAppInfo("com.example.study.notes", "Main")),
            now
        )
        now += 240_000L
        fresh.processSnapshot(
            PerceptionSnapshot(foregroundApp = ForegroundAppInfo("tv.danmaku.bili", "Main")),
            now
        )
        // 再切一次，把第 2 条新原料也结算进来，此时总量 4(old)+2(new)+1(在途)=7 > 上限 5，触发容量守门
        now += 240_000L
        fresh.processSnapshot(
            PerceptionSnapshot(foregroundApp = ForegroundAppInfo("com.tencent.mm", "Main")),
            now
        )

        val desc = recordedSlices.joinToString { "${it.id}:${it.isConsolidated}" }
        // 核心语义：未消化原料一条都不许被删（这会是被旧实现直接删掉的场景）
        assertEquals("未消化原料被误删，实际=$desc", 2, recordedSlices.count { !it.isConsolidated })
        // 容量由"已消化历史"顶上：总量被压回上限内，且最老的历史已被淘汰
        assertTrue("容量守门失效，实际=$desc", recordedSlices.size <= 5)
        assertTrue("已消化历史未被裁剪，实际=$desc", recordedSlices.none { it.id == "old_1" })
    }
}