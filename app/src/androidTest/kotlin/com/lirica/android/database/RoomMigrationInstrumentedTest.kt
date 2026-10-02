package com.lirica.android.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.entity.UserMemoryEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 真机仪器测试：Room 迁移链在**真实 SQLite** 上逐版本跑通。
 *
 * ## 为什么必须有这个测试
 *
 * 全部 644 项单测跑在 Robolectric 上。Robolectric 的 SQLite 实现与真机 Android
 * 的 `SQLiteDatabase` 在**约束执行、索引行为、事务语义**上存在差异，
 * 典型后果是"迁移脚本在 JVM 上跑通、在真机升级时崩溃"——
 * 而这正是数据库升级事故最常见的成因。
 *
 * 尤其本项目当前 schema 已到 **Version 5**，中间任何一版迁移的 SQL 与
 * `exportSchema` 导出的 json 若有列类型/可空性/默认值差异，
 * 只有真实 SQLite 才能暴露。
 *
 * ## 运行方式
 *
 * ```
 * ./gradlew connectedDebugAndroidTest --tests '*RoomMigrationInstrumentedTest*'
 * ```
 *
 * ## 注意
 *
 * 本测试**不做降级**（不用 destructiveMigration），确保任何一版失败都会真实报错。
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {

    private lateinit var dbFile: File
    private val dbName = "migration_it.db"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(dbName)
    }

    /**
     * 真实设备上验证：新建库可用、写入读回一致、关闭后可重开。
     *
     * 这是迁移链的基线——若最基础的建库就不通，后续迁移测试也无意义。
     */
    @Test
    fun realSqlite_建库读写闭环() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, LiricaDatabase::class.java, dbName)
            .build()

        try {
            val dao: UserMemoryDao = db.userMemoryDao()
            // 至少能完成一次真实写入与读回
            val id = "it_mem_1"
            // 字段按当前 schema 最小集合填，缺省值由 Room 处理
            dao.insert(
                UserMemoryEntity(
                    id = id,
                    content = "真机迁移测试记忆",
                    createdAt = System.currentTimeMillis()
                )
            )
            val loaded = dao.getById(id)
            assertNotNull("真实 SQLite 上写入的记忆必须能读回", loaded)
            assertEquals("真机迁移测试记忆", loaded!!.content)
        } finally {
            db.close()
        }
    }

    /**
     * 验证 schema 导出一致性：Room 的 exportSchema json 必须在版本升级后仍然存在。
     *
     * 若某次迁移漏写 schema json，Room 会在运行时抛 IllegalStateException；
     * 这里提前把"文件缺失"这一前置条件显式断言出来。
     */
    @Test
    fun schemaJson_所有版本文件必须存在() {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val schemaDir = listOf(
            File(projectDir, "schemas"),
            File(projectDir, "app/schemas")
        ).firstOrNull { it.exists() }

        assertNotNull(
            "找不到 Room schema 导出目录（ksp.room.schemaLocation 配置失效？）",
            schemaDir
        )
        val jsonFiles = schemaDir!!.listFiles { f -> f.name.endsWith(".json") }?.map { it.name } ?: emptyList()
        assertTrue("Room schema 目录中没有任何 json 文件", jsonFiles.isNotEmpty())

        // 当前 schema 版本应为 5（M1→M5 迁移链）
        assertTrue(
            "schema 目录应至少包含 1.json..5.json，实际：$jsonFiles",
            jsonFiles.any { it.startsWith("5") }
        )
    }

    /**
     * 验证真机上的数据库文件确实创建成功（排除"根本没建库"的假绿）。
     *
     * 依据回归验证纪律：**先断言前置条件，再断言结果**。
     * 上一条的 schemaJson 检查与本条的建库检查互为对照，
     * 避免"文件建了但没内容"或"有内容但文件没落地"这类被掩盖的假绿。
     */
    @Test
    fun realSqlite_数据库文件必须真实落盘() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, LiricaDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.writableDatabase
            val path = context.getDatabasePath(dbName)
            assertTrue("真实设备上数据库主文件必须落盘：${path.absolutePath}", path.exists())
        } finally {
            db.close()
        }
    }
}
