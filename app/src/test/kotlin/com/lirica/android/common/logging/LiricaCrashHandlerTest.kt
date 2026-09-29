package com.lirica.android.common.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LiricaCrashHandlerTest {

    private lateinit var context: Context
    private lateinit var crashHandler: LiricaCrashHandler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        crashHandler = LiricaCrashHandler(context, null)
        LiricaLog.clear()
        val crashDir = File(context.filesDir, LiricaCrashHandler.CRASH_DIR_NAME)
        if (crashDir.exists()) {
            crashDir.deleteRecursively()
        }
    }

    @Test
    fun testSaveCrashReport_generatesReportWithDeviceInfoAndLogs() {
        LiricaLog.i("Perception", "前台切换至微信")
        LiricaLog.d("Automation", "准备执行自动化手势")

        val exception = NullPointerException("屏幕无障碍树节点为空")
        val thread = Thread.currentThread()

        val file = crashHandler.saveCrashReport(thread, exception)

        assertNotNull(file)
        assertTrue(file!!.exists())
        val content = file.readText()

        assertTrue(content.contains("=== Lirica 崩溃诊断报告 ==="))
        assertTrue(content.contains("NullPointerException"))
        assertTrue(content.contains("屏幕无障碍树节点为空"))
        assertTrue(content.contains("崩溃线程: ${thread.name}"))
        assertTrue(content.contains("前台切换至微信"))
        assertTrue(content.contains("准备执行自动化手势"))
    }

    @Test
    fun testCrashReportRotation_keepsMax5Files() {
        val crashDir = File(context.filesDir, LiricaCrashHandler.CRASH_DIR_NAME).apply { mkdirs() }

        // 预制 6 个旧崩溃文件，修改时间递增
        for (i in 1..6) {
            val file = File(crashDir, "crash_20260920_00000$i.txt")
            file.writeText("Old crash report #$i")
            file.setLastModified(1000L * i)
        }

        assertEquals(6, crashDir.listFiles()?.size)

        // 再次触发保存崩溃报告
        crashHandler.saveCrashReport(Thread.currentThread(), RuntimeException("New crash"))

        val files = crashDir.listFiles()
        assertNotNull(files)
        // 应该轮转淘汰最旧的，总数不超过 5
        assertEquals(5, files!!.size)
    }
}
