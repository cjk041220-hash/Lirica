package com.lirica.android.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.database.entity.WorldviewEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room v1 -> v2 迁移完整性测试
 *
 * 依据 git 历史核对的 v1 真实 schema（app/schemas/.../1.json）：
 * v1 已包含 unspoken_thoughts 的 trigger_context / decay_score / target_scenario 列，
 * v1 -> v2 的真实差异仅为：新增 worldview_entities、scheduled_alarms 两表
 * 与 self_experiences 增加 is_placeholder 列。
 *
 * 测试模拟真实生产升级路径：按 v1 schema 建库并写入数据（user_version=1），
 * 再用生产构建入口 Room.databaseBuilder + MIGRATION_1_2 打开——
 * Room 会在 open 时自动执行迁移并做全量 schema 校验与 identity hash 校验，
 * 任何列缺失、类型不符或索引缺失都会直接抛 IllegalStateException（即真机"必崩"场景）。
 *
 * 注：未使用 MigrationTestHelper，因其 schema JSON 仅支持从 assets 读取，
 * 而 AGP 8.x 单测产物（apk-for-local-test）不合并 test sourceSet assets。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: File

    @Before
    fun prepareDbFile() {
        dbFile = context.getDatabasePath("migration-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun cleanUpDbFile() {
        dbFile.delete()
    }

    /**
     * 定位 schemas 目录：单测 user.dir 可能是 app/ 模块目录或项目根目录（与 BackupAndByokTest 同款双路径兜底）
     */
    private fun locateSchemaFile(version: Int): File {
        val relative = "schemas/com.lirica.android.database.LiricaDatabase/$version.json"
        return File(relative).takeIf { it.exists() }
            ?: File("app/$relative").takeIf { it.exists() }
            ?: throw AssertionError("Room schema v$version JSON 不存在：请先执行构建以生成 app/schemas（ksp room.schemaLocation）")
    }

    /**
     * 按指定版本的 schema JSON 在文件系统上真实建库（v1：四张表 + room_master_table + user_version=1）
     */
    private fun createV1Database() {
        val schemaJson = Json.parseToJsonElement(locateSchemaFile(1).readText()).jsonObject
        val database = schemaJson.getValue("database").jsonObject

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            // 1. 按 v1 建表（createSql 中的 ${TABLE_NAME} 占位符以 tableName 填充）
            for (entity in database.getValue("entities").jsonArray) {
                val obj = entity.jsonObject
                val tableName = obj.getValue("tableName").jsonPrimitive.content
                val createSql = obj.getValue("createSql").jsonPrimitive.content
                    .replace("\${TABLE_NAME}", tableName)
                db.execSQL(createSql)
                for (index in obj["indices"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())) {
                    val idx = index.jsonObject
                    db.execSQL(
                        idx.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName)
                    )
                }
            }
            // 2. room_master_table 与 v1 identity hash（schema JSON 自带 setupQueries）
            for (query in database.getValue("setupQueries").jsonArray) {
                db.execSQL(query.jsonPrimitive.content)
            }
            // 3. 标记 v1 版本号，让 Room 打开时走迁移路径
            db.version = 1
        }
    }

    private fun insertV1SeedData() {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """
                INSERT INTO user_memories (id, content, category, confidence, importance, valence,
                    arousal, created_at, last_mention_ts, access_count, hit_count, status, valid_from)
                VALUES ('mem_v1', '用户住在佛山', '用户画像', 0.9, 7, 0.1, 0.0,
                    1726700000000, 1726700000000, 0, 0, 'active', 1726700000000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO self_experiences (id, title, content, domain_tag, valence, arousal,
                    created_at, access_count, status)
                VALUES ('exp_v1', '雨夜随想', '今天佛山下雨了，听雨声很安静。', '日常感悟',
                    0.3, 0.1, 1726700000000, 0, 'fresh')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO unspoken_thoughts (id, thought, trigger_context, urgency, decay_score,
                    target_scenario, created_at, status)
                VALUES ('thought_v1', '等她忙完提醒喝水', 'app:com.tencent.mm', 0.8, 1.0,
                    'user_idle', 1726700000000, 'pending')
                """.trimIndent()
            )
        }
    }

    @Test
    fun testMigrate1To2SchemaAndDataIntegrity() {
        // 1. 按 v1 真实 schema 建库并写入代表性数据
        createV1Database()
        insertV1SeedData()

        // 2. 用生产构建入口打开：Room 检测到 version 1 自动执行 MIGRATION_1_2 与 MIGRATION_2_3，
        //    随后全量校验 v3 schema 与 identity hash，不匹配即抛异常
        val db = Room.databaseBuilder(context, LiricaDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .addMigrations(LiricaDatabase.MIGRATION_1_2, LiricaDatabase.MIGRATION_2_3)
            .build()
        try {
            runBlocking {
                // v1 既有数据完好
                val memory = db.userMemoryDao().getById("mem_v1")
                assertNotNull(memory)
                assertEquals("用户住在佛山", memory?.content)

                val experience = db.selfExperienceDao().getById("exp_v1")
                assertNotNull(experience)
                // 迁移新增列取默认值 0（非占位经历）
                assertEquals(false, experience?.isPlaceholder)

                val thought = db.unspokenThoughtDao().getById("thought_v1")
                assertNotNull(thought)
                assertEquals("等她忙完提醒喝水", thought?.thought)
                assertEquals("app:com.tencent.mm", thought?.triggerContext)
                assertEquals(0.8f, thought?.urgency ?: 0f, 0.001f)

                // v2 新增表可正常读写
                db.scheduledAlarmDao().insert(
                    ScheduledAlarmEntity(
                        id = "alarm_v2",
                        triggerAt = 1726800000000,
                        botNote = "迁移后新增闹钟",
                        blockDelivery = false,
                        status = ScheduledAlarmEntity.STATUS_PENDING
                    )
                )
                val pendingAlarms = db.scheduledAlarmDao().getPendingAlarms(1726800000000 + 1000L)
                assertEquals(1, pendingAlarms.size)
                assertEquals("alarm_v2", pendingAlarms[0].id)

                db.worldviewDao().insert(
                    WorldviewEntity(
                        id = "wv_v2",
                        category = "生活习惯",
                        statement = "用户习惯晚睡",
                        supportingEvidenceCount = 3,
                        lastConfirmedAt = 1726800000000,
                        confidence = 0.85f
                    )
                )

                // v3 新增 memory_embeddings 表可正常读写
                db.memoryEmbeddingDao().insertOrUpdate(
                    com.lirica.android.database.memory.MemoryEmbeddingEntity.fromFloatArray(
                        memoryId = "emb_v3",
                        track = com.lirica.android.database.memory.MemoryEmbeddingEntity.TRACK_USER,
                        vector = FloatArray(512) { 0.1f }
                    )
                )
                val emb = db.memoryEmbeddingDao().getByMemoryId("emb_v3")
                assertNotNull(emb)
                assertEquals(512, emb?.toFloatArray()?.size)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun testV1SchemaJsonMatchesGitHistory() {
        // 防御性断言：v1 schema JSON 必须与 git 历史事实一致——
        // unspoken_thoughts 在 v1 就已包含 trigger_context / decay_score / target_scenario，
        // 若有人错误地"补 ALTER"这三个列，迁移将在真机上因 duplicate column 崩溃。
        val schemaJson = locateSchemaFile(1).readText()
        assertTrue(schemaJson.contains("trigger_context"))
        assertTrue(schemaJson.contains("decay_score"))
        assertTrue(schemaJson.contains("target_scenario"))
        // v1 的 self_experiences 不应有 is_placeholder（该列在 v2 迁移中新增）
        val selfExpSection = schemaJson.substringAfter("\"self_experiences\"")
            .substringBefore("\"unspoken_thoughts\"")
        assertTrue(
            "v1 的 self_experiences 不应包含 is_placeholder 列",
            !selfExpSection.contains("is_placeholder")
        )
    }
}
