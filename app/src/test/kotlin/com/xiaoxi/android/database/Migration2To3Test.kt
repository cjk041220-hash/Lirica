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
 * Room v2→v3 迁移回归测试（2026-09-27 审计 P1 补齐：MIGRATION_2_3 此前零覆盖）。
 *
 * v2 建表语句逐列对照权威导出 `app/schemas/com.xiaoxi.android.database.XiaoxiDatabase/2.json`
 * （v2 = v1 四表 + MIGRATION_1_2 产物：worldview_entities / scheduled_alarms / is_placeholder 列）。
 *
 * 方法论沿用 RoomMigrationTest（真实夹具 + 逐字段数据保全断言）——该文件头记录过
 * 「凭印象手写夹具产出假绿灯」的教训，本文件直接对照 schema 导出，杜绝重蹈。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Migration2To3Test {

    @Test
    fun testMigration2To3_executesSuccessfullyWithoutDataLoss() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_migration_2_3.db"
        context.deleteDatabase(dbName)

        // 1. 手工创建 v2 架构的数据库（与 schemas/2.json 逐列一致）并写入历史记录
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v1 遗产四表（MIGRATION_1_2 不改动它们，v2 与 v1 逐列一致）
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
                            `is_placeholder` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
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
                    // MIGRATION_1_2 产物两张表（对照 schemas/2.json）
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `worldview_entities` (
                            `id` TEXT NOT NULL,
                            `category` TEXT NOT NULL,
                            `statement` TEXT NOT NULL,
                            `supporting_evidence_count` INTEGER NOT NULL,
                            `last_confirmed_at` INTEGER NOT NULL,
                            `confidence` REAL NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `scheduled_alarms` (
                            `id` TEXT NOT NULL,
                            `trigger_at` INTEGER NOT NULL,
                            `bot_note` TEXT NOT NULL,
                            `block_delivery` INTEGER NOT NULL,
                            `status` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                    """.trimIndent())
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build()

        val openHelper2 = FrameworkSQLiteOpenHelperFactory().create(config)
        val v2Db = openHelper2.writableDatabase
        // 灌入历史数据（覆盖 v2 全部六张表，迁移后必须逐字段无损）
        v2Db.execSQL(
            "INSERT INTO user_memories (id, content, category, confidence, importance, valence, arousal, created_at, last_mention_ts, access_count, hit_count, status, valid_from) " +
                "VALUES ('mem_v2', '用户在准备答辩', '日程', 0.9, 4, 0.1, 0.2, 2000, 2000, 1, 1, 'active', 2000)"
        )
        v2Db.execSQL(
            "INSERT INTO self_experiences (id, title, content, domain_tag, valence, arousal, created_at, access_count, status, is_placeholder) " +
                "VALUES ('exp_v2', '复检心得', '完成了第三轮复检', '工程', 0.2, 0.3, 2000, 0, 'fresh', 0)"
        )
        v2Db.execSQL(
            "INSERT INTO unspoken_thoughts (id, thought, urgency, decay_score, created_at, status) " +
                "VALUES ('thought_v2', '该提醒用户休息了', 3.0, 1.0, 2000, 'pending')"
        )
        v2Db.execSQL(
            "INSERT INTO chat_dialogues (session_id, role, content, msg_type, timestamp, token_count) " +
                "VALUES ('default_session', 'assistant', '好的，已记录', 'text', 2000, 5)"
        )
        v2Db.execSQL(
            "INSERT INTO worldview_entities (id, category, statement, supporting_evidence_count, last_confirmed_at, confidence) " +
                "VALUES ('wv_v2', '工程', '测试先于实现', 3, 2000, 0.9)"
        )
        v2Db.execSQL(
            "INSERT INTO scheduled_alarms (id, trigger_at, bot_note, block_delivery, status) " +
                "VALUES ('alarm_v2', 9000, '提醒复查迁移用例', 1, 'pending')"
        )
        openHelper2.close()

        // 2. 执行 MIGRATION_2_3
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        if (oldVersion == 2 && newVersion == 3) {
                            XiaoxiDatabase.MIGRATION_2_3.migrate(db)
                        }
                    }
                }).build()
        )
        val migratedDb = openHelper.writableDatabase

        // 3. 验证 v2 全部六张表既有数据逐字段无损（MIGRATION_2_3 不应触碰旧表）
        migratedDb.query("SELECT id, content, status FROM user_memories WHERE id = 'mem_v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("用户在准备答辩", cursor.getString(1))
            assertEquals("active", cursor.getString(2))
        }
        migratedDb.query("SELECT id, title, is_placeholder FROM self_experiences WHERE id = 'exp_v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("复检心得", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        migratedDb.query("SELECT id, thought, urgency FROM unspoken_thoughts WHERE id = 'thought_v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("该提醒用户休息了", cursor.getString(1))
            assertEquals(3.0f, cursor.getFloat(2))
        }
        migratedDb.query("SELECT session_id, role, content FROM chat_dialogues WHERE session_id = 'default_session'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("assistant", cursor.getString(1))
            assertEquals("好的，已记录", cursor.getString(2))
        }
        migratedDb.query("SELECT id, statement FROM worldview_entities WHERE id = 'wv_v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("测试先于实现", cursor.getString(1))
        }
        migratedDb.query("SELECT id, bot_note, block_delivery FROM scheduled_alarms WHERE id = 'alarm_v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("提醒复查迁移用例", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }

        // 4. 验证新增 memory_embeddings 表已建立、track 索引生效、可正常读写
        migratedDb.execSQL(
            "INSERT INTO memory_embeddings (memory_id, track, embedding, updated_at) " +
                "VALUES ('mem_v2', 'user', x'0000803F0000803F', 3000)"
        )
        migratedDb.query("SELECT memory_id, track, embedding FROM memory_embeddings WHERE memory_id = 'mem_v2'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("user", cursor.getString(1))
            assertEquals(8, cursor.getBlob(2).size)
        }
        migratedDb.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_memory_embeddings_track'"
        ).use { cursor ->
            assertTrue("track 索引必须随迁移建立", cursor.moveToFirst())
        }

        migratedDb.close()
    }
}
