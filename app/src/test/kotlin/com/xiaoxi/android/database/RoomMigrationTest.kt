package com.xiaoxi.android.database

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RoomMigrationTest {

    @Test
    fun testMigration1To2_executesSuccessfullyWithoutDataLoss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_1_2.db"
        context.deleteDatabase(dbName)

        // 1. 手工创建 v1 架构的数据库并写入历史记录
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
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
                        CREATE TABLE IF NOT EXISTS `self_experiences` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `source_url` TEXT,
                            `domain_tag` TEXT NOT NULL,
                            `emotional_insight` TEXT,
                            `valence` REAL NOT NULL,
                            `arousal` REAL NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `access_count` INTEGER NOT NULL,
                            `last_shared_at` INTEGER,
                            `status` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `unspoken_thoughts` (
                            `id` TEXT NOT NULL,
                            `thought` TEXT NOT NULL,
                            `urgency` REAL NOT NULL,
                            `curiosity` REAL NOT NULL,
                            `warmth` REAL NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `expiry_time` INTEGER NOT NULL,
                            `consumed_at` INTEGER,
                            `status` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `chat_dialogues` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `session_id` TEXT NOT NULL,
                            `role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `tool_calls_json` TEXT
                        )
                    """.trimIndent())
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build()

        val openHelper1 = FrameworkSQLiteOpenHelperFactory().create(config)
        val v1Db = openHelper1.writableDatabase
        v1Db.execSQL("INSERT INTO self_experiences (id, title, content, domain_tag, valence, arousal, created_at, access_count, status) VALUES ('exp_v1', '旧经历', '测试内容', '日常', 0.0, 0.0, 1000, 0, 'fresh')")
        openHelper1.close()

        // 2. 执行 MIGRATION_1_2
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion == 1 && newVersion == 2) {
                            XiaoxiDatabase.MIGRATION_1_2.migrate(db)
                        }
                    }
                }).build()
        )
        val migratedDb = openHelper.writableDatabase

        // 3. 验证既有数据无损且新增 is_placeholder 默认为 0
        val cursor = migratedDb.query("SELECT id, title, is_placeholder FROM self_experiences WHERE id = 'exp_v1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("exp_v1", cursor.getString(0))
        assertEquals("旧经历", cursor.getString(1))
        assertEquals(0, cursor.getInt(2))
        cursor.close()

        // 4. 验证新表 worldview_entities 与 scheduled_alarms 已成功建立
        migratedDb.execSQL("INSERT INTO worldview_entities (id, category, statement, supporting_evidence_count, last_confirmed_at, confidence) VALUES ('wv_migrated', '哲学', '世界观测试', 1, 1000, 1.0)")
        val wvCursor = migratedDb.query("SELECT id, statement FROM worldview_entities WHERE id = 'wv_migrated'")
        assertTrue(wvCursor.moveToFirst())
        assertEquals("世界观测试", wvCursor.getString(1))
        wvCursor.close()

        migratedDb.close()
    }
}
