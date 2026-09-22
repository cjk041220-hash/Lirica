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

/**
 * Room v1→v2 迁移回归测试。
 *
 * v1 建表语句逐列对照权威导出 `app/schemas/com.xiaoxi.android.database.XiaoxiDatabase/1.json`
 * （含索引——索引是 schema 的一部分，真实 v1 库必有）。
 *
 * 历史教训：旧版本测试的 v1 夹具凭印象手写（unspoken_thoughts 写了从不存在的
 * `curiosity`/`warmth` 列；chat_dialogues 缺 `msg_type`/`token_count` 却多了
 * `tool_calls_json`），迁移照样绿——绿灯毫无验证力，还曾误导过一次评审。
 * 本文件修正为真实 v1 形状，并补上"四张旧表数据迁移后逐字段无损"的断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RoomMigrationTest {

    @Test
    fun testMigration1To2_executesSuccessfullyWithoutDataLoss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_1_2.db"
        context.deleteDatabase(dbName)

        // 1. 手工创建 v1 架构的数据库（与 schemas/1.json 逐列一致）并写入历史记录
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_user_memories_status` ON `user_memories` (`status`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_user_memories_category_status` ON `user_memories` (`category`, `status`)"
                    )
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
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_self_exp_domain` ON `self_experiences` (`domain_tag`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_self_exp_created_at` ON `self_experiences` (`created_at`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_self_exp_status` ON `self_experiences` (`status`)"
                    )
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `unspoken_thoughts` (
                            `id` TEXT NOT NULL,
                            `thought` TEXT NOT NULL,
                            `trigger_context` TEXT,
                            `urgency` REAL NOT NULL,
                            `decay_score` REAL NOT NULL,
                            `target_scenario` TEXT,
                            `created_at` INTEGER NOT NULL,
                            `expiry_time` INTEGER,
                            `consumed_at` INTEGER,
                            `status` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_thoughts_status` ON `unspoken_thoughts` (`status`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_thoughts_urgency` ON `unspoken_thoughts` (`urgency`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_thoughts_created_at` ON `unspoken_thoughts` (`created_at`)"
                    )
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `chat_dialogues` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `session_id` TEXT NOT NULL,
                            `role` TEXT NOT NULL,
                            `content` TEXT NOT NULL,
                            `msg_type` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `token_count` INTEGER NOT NULL,
                            `model` TEXT,
                            `metadata_json` TEXT
                        )
                    """.trimIndent())
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_dialogue_session` ON `chat_dialogues` (`session_id`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `idx_dialogue_session_time` ON `chat_dialogues` (`session_id`, `timestamp`)"
                    )
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build()

        val openHelper1 = FrameworkSQLiteOpenHelperFactory().create(config)
        val v1Db = openHelper1.writableDatabase
        v1Db.execSQL(
            "INSERT INTO self_experiences (id, title, content, domain_tag, valence, arousal, created_at, access_count, status) " +
                "VALUES ('exp_v1', '旧经历', '测试内容', '日常', 0.0, 0.0, 1000, 0, 'fresh')"
        )
        v1Db.execSQL(
            "INSERT INTO user_memories (id, content, category, confidence, importance, valence, arousal, created_at, last_mention_ts, access_count, hit_count, status, valid_from) " +
                "VALUES ('mem_v1', '用户喜欢喝咖啡', '偏好', 0.8, 5, 0.0, 0.0, 1000, 1000, 0, 0, 'active', 1000)"
        )
        v1Db.execSQL(
            "INSERT INTO unspoken_thoughts (id, thought, urgency, decay_score, created_at, status) " +
                "VALUES ('thought_v1', '想提醒用户喝水', 4.0, 1.0, 1000, 'pending')"
        )
        v1Db.execSQL(
            "INSERT INTO chat_dialogues (session_id, role, content, msg_type, timestamp, token_count) " +
                "VALUES ('default_session', 'user', '早上好', 'text', 1000, 3)"
        )
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

        // 3. 验证四张旧表既有数据逐字段无损，且新增 is_placeholder 默认为 0
        migratedDb.query("SELECT id, title, content, is_placeholder FROM self_experiences WHERE id = 'exp_v1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("exp_v1", cursor.getString(0))
            assertEquals("旧经历", cursor.getString(1))
            assertEquals("测试内容", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
        }
        migratedDb.query("SELECT id, content, status FROM user_memories WHERE id = 'mem_v1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("用户喜欢喝咖啡", cursor.getString(1))
            assertEquals("active", cursor.getString(2))
        }
        migratedDb.query("SELECT id, thought, decay_score, status FROM unspoken_thoughts WHERE id = 'thought_v1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("想提醒用户喝水", cursor.getString(1))
            assertEquals(1.0f, cursor.getFloat(2))
            assertEquals("pending", cursor.getString(3))
        }
        migratedDb.query("SELECT session_id, role, content, msg_type FROM chat_dialogues WHERE session_id = 'default_session'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("user", cursor.getString(1))
            assertEquals("早上好", cursor.getString(2))
            assertEquals("text", cursor.getString(3))
        }

        // 4. 验证新表 worldview_entities 与 scheduled_alarms 已成功建立
        migratedDb.execSQL(
            "INSERT INTO worldview_entities (id, category, statement, supporting_evidence_count, last_confirmed_at, confidence) " +
                "VALUES ('wv_migrated', '哲学', '世界观测试', 1, 1000, 1.0)"
        )
        migratedDb.query("SELECT id, statement FROM worldview_entities WHERE id = 'wv_migrated'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("世界观测试", cursor.getString(1))
        }

        migratedDb.execSQL(
            "INSERT INTO scheduled_alarms (id, trigger_at, bot_note, block_delivery, status) " +
                "VALUES ('alarm_migrated', 2000, '闹钟测试', 0, 'pending')"
        )
        migratedDb.query("SELECT id, bot_note FROM scheduled_alarms WHERE id = 'alarm_migrated'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("闹钟测试", cursor.getString(1))
        }

        migratedDb.close()
    }
}
