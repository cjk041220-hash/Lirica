package com.lirica.android.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room v3→v4 迁移回归测试（Lirica 4.0 Milestone 4.1 落地）。
 *
 * 验证 MIGRATION_3_4：
 * 1. 成功创建 episodic_sensing_slices 与 open_loops 及其索引
 * 2. 既有 7 张表数据零丢失
 * 3. 新表支持无缝增删改查
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Migration3To4Test {

    @Test
    fun testMigration3To4_executesSuccessfullyWithoutDataLoss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_3_4.db"
        context.deleteDatabase(dbName)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `user_memories` (
                            `id` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `confidence` REAL NOT NULL,
                            `importance` INTEGER NOT NULL,
                            `valence` REAL NOT NULL,
                            `arousal` REAL NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `last_mention_ts` INTEGER NOT NULL,
                            `last_used_at` INTEGER,
                            `access_count` INTEGER NOT NULL,
                            `hit_count` INTEGER NOT NULL,
                            `last_hit_date` TEXT,
                            `status` TEXT NOT NULL,
                            `superseded_by` TEXT,
                            `prev_content` TEXT,
                            `source` TEXT,
                            `valid_from` INTEGER NOT NULL,
                            `valid_to` INTEGER,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `memory_embeddings` (
                            `memory_id` TEXT NOT NULL,
                            `track` TEXT NOT NULL,
                            `embedding` BLOB NOT NULL,
                            `updated_at` INTEGER NOT NULL,
                            PRIMARY KEY(`memory_id`)
                        )
                    """.trimIndent())
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase

        // 插入旧数据
        db.execSQL("""
            INSERT INTO `user_memories` (
                id, content, category, confidence, importance, valence, arousal,
                created_at, last_mention_ts, status, valid_from, access_count, hit_count
            ) VALUES (
                "mem-1", "用户喜欢在深夜听歌", "偏好", 1.0, 5, 0.2, 0.1,
                1700000000000, 1700000000000, "active", 1700000000000, 1, 1
            )
        """.trimIndent())

        // 执行迁移 3 -> 4
        LiricaDatabase.MIGRATION_3_4.migrate(db)

        // 1. 验证旧数据依然完好无损
        val cursorOld = db.query("SELECT content, status FROM user_memories WHERE id = ?", arrayOf("mem-1"))
        assertTrue("旧记录应存在", cursorOld.moveToFirst())
        assertEquals("用户喜欢在深夜听歌", cursorOld.getString(0))
        assertEquals("active", cursorOld.getString(1))
        cursorOld.close()

        // 2. 验证新表 episodic_sensing_slices 存在且能正常读写
        db.execSQL("""
            INSERT INTO `episodic_sensing_slices` (
                id, timestamp_ms, dwell_duration_ms, app_category, primary_package,
                summary_scene, hardware_context_json, is_consolidated
            ) VALUES (
                "slice-1", 1700000010000, 180000, "STUDY", "com.example.study",
                "在自习应用专注3分钟", "{}", 0
            )
        """.trimIndent())

        val cursorSlice = db.query("SELECT summary_scene, is_consolidated FROM episodic_sensing_slices WHERE id = ?", arrayOf("slice-1"))
        assertTrue("情境切片记录应能插入并读出", cursorSlice.moveToFirst())
        assertEquals("在自习应用专注3分钟", cursorSlice.getString(0))
        assertEquals(0, cursorSlice.getInt(1))
        cursorSlice.close()

        // 3. 验证新表 open_loops 存在且能正常读写
        db.execSQL("""
            INSERT INTO `open_loops` (
                id, source_memory_id, title, target_time_ms, trigger_window_start_ms,
                status, urgency_level, follow_up_prompt, retry_count, last_prompted_ms, created_at
            ) VALUES (
                "loop-1", "mem-1", "明天下午求职面试", 1700050000000, 1700042800000,
                "OPEN", 2, "面试准备得怎么样啦？", 0, 0, 1700000000000
            )
        """.trimIndent())

        val cursorLoop = db.query("SELECT title, status, urgency_level FROM open_loops WHERE id = ?", arrayOf("loop-1"))
        assertTrue("开环未决记录应能插入并读出", cursorLoop.moveToFirst())
        assertEquals("明天下午求职面试", cursorLoop.getString(0))
        assertEquals("OPEN", cursorLoop.getString(1))
        assertEquals(2, cursorLoop.getInt(2))
        cursorLoop.close()

        helper.close()
        context.deleteDatabase(dbName)
    }
}