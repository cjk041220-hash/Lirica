package com.xiaoxi.android.memory.consolidation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import com.xiaoxi.android.XiaoxiApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(application = XiaoxiApplication::class, sdk = [34])
class SubconsciousDreamWorkerTest {

    @Test
    fun testSubconsciousDreamWorker_constraintsAndPeriodicContract() {
        val request = SubconsciousDreamWorker.createPeriodicWorkRequest()
        val workSpec = request.workSpec

        // 1. 验证能耗与待机铁律：必须处于充电中 + 空闲待机态 + 已联网
        val constraints = workSpec.constraints
        assertTrue(constraints.requiresCharging())
        assertTrue(constraints.requiresDeviceIdle())
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)

        // 2. 验证周期为 24 小时
        assertEquals(TimeUnit.HOURS.toMillis(24), workSpec.intervalDuration)

        // 3. 验证 Worker 唯一任务标识
        assertEquals("xiaoxi_subconscious_dream_worker", SubconsciousDreamWorker.WORK_NAME)
    }
}
