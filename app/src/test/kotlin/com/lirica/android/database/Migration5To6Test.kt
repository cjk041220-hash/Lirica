package com.lirica.android.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Room v5 -> v6 迁移完整性测试（M5.3 经验轨迹表）。
 *
 * 与 MigrationTest 同款策略：按真实 v5 schema 建库并写入数据，
 * 再用生产构建入口打开——Room 会执行 MIGRATION_5_6 并做全量 schema 与
 * identity hash 校验，任何列缺失/类型不符/索引缺失都会直接抛 IllegalStateException
 * （即真机升级必崩场景）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Migration5To6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dbFile: File

    @Before
    fun prepareDbFile() {
        dbFile = context.getDatabasePath("migration-v5-v6-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun cleanUpDbFile() {
        dbFile.delete()
    }

    private fun locateSchemaFile(version: Int): File {
        val relative = "schemas/com.lirica.android.database.LiricaDatabase/$version.json"
        return File(relative).takeIf { it.exists() }
            ?: File("app/$relative").takeIf { it.exists() }
            ?: throw AssertionError("Room schema v$version JSON 不存在：请先执行构建生成 app/schemas")
    }

    private fun createV5Database() {
        val schemaJson = Json.parseToJsonElement(locateSchemaFile(5).readText()).jsonObject
        val database = schemaJson.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            for (entity in database.getValue("entities").jsonArray) {
                val obj = entity.jsonObject
                val tableName = obj.getValue("tableName").jsonPrimitive.content
                db.execSQL(
                    obj.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName)
                )
                for (index in obj["indices"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())) {
                    db.execSQL(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName)
                    )
                }
            }
            for (query in database.getValue("setupQueries").jsonArray) {
                db.execSQL(query.jsonPrimitive.content)
            }
            db.version = 5
        }
    }

    @Test
    fun testMigrate5To6CreatesActionMacrosAndKeepsData() {
        createV5Database()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                """
                INSERT INTO user_memories (id, content, category, confidence, importance, valence,
                    arousal, created_at, last_mention_ts, access_count, hit_count, status, valid_from)
                VALUES ('mem_v5', '用户偏爱纪录片', '兴趣爱好', 0.8, 6, 0.2, 0.0,
                    1726700000000, 1726700000000, 0, 0, 'active', 1726700000000)
                """.trimIndent()
            )
        }

        val db = Room.databaseBuilder(context, LiricaDatabase::class.java, dbFile.name)
            .allowMainThreadQueries()
            .addMigrations(LiricaDatabase.MIGRATION_5_6)
            .build()
        try {
            runBlocking {
                // 迁移前数据完好
                val memory = db.userMemoryDao().getById("mem_v5")
                assertNotNull(memory)
                assertEquals("用户偏爱纪录片", memory?.content)

                // v6 新表可读写（Room 已在 open 时完成全量 schema 校验）
                val dao = db.actionMacroDao()
                dao.upsert(
                    com.lirica.android.database.entity.ActionMacroEntity(
                        macroId = "m_v6",
                        intentSignature = "PLAY_DOCUMENTARY",
                        appPackage = "tv.danmaku.bili",
                        targetAppVersionCode = 7000000L,
                        startPageFingerprint = 4242L,
                        stepsJson = "[]",
                        explicitSlotKeys = "[]",
                        createdAtMs = 1L,
                        lastUsedAtMs = 1L
                    )
                )
                val found = dao.findById("m_v6")
                assertNotNull(found)
                assertEquals("PLAY_DOCUMENTARY", found?.intentSignature)
                assertEquals(1, dao.countActive())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun testV6SchemaExportsAllPlanMandatedColumns() {
        // §5.2 存储实体逐字段核对：缺任何一列，"核验前置/版本淘汰"就失去数据基础
        val schema = locateSchemaFile(6).readText()
        for (column in listOf(
            "macro_id", "intent_signature", "app_package", "target_app_version_code",
            "start_page_fingerprint", "steps_json", "explicit_slot_keys",
            "success_count", "failure_count", "consecutive_failures", "is_deprecated",
            "created_at_ms", "last_used_at_ms"
        )) {
            assertTrue("v6 schema 缺少列 $column", schema.contains(column))
        }
        assertTrue("v6 必须带查询索引", schema.contains("idx_macro_lookup"))
    }

    @Test
    fun testDatabaseVersionIsSix() {
        val db = LiricaDatabase.buildInMemory(context)
        try {
            assertTrue(db.openHelper.writableDatabase.version >= 6)
        } finally {
            db.close()
        }
    }
}
