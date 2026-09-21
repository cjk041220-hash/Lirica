# 第二轮优化·Milestone 2.3 自叙梦系统与 5 层记忆地图化重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地小汐 2.0 第二轮优化第三里程碑（Milestone 2.3），构建深邃连续的心智世界：交付夜间入梦记忆整理 Worker（`SubconsciousDreamWorker`）、记忆语义去重、相对时间绝对化与矛盾消歧算法、高层世界观（`WorldviewEntity`）晋升机制、5 层地图式上下文组装器（`MapBasedPromptAssembler` 融合 P11 即时感知）、上下文交接快照（`HandoffSnapshot` 机制替代机械截断），并补齐 Room v1->v2 安全迁移测试（P12）、抽取节流门控（P8）与模板日记标记（M3）。

**Architecture:**
1. **持久层升级与安全迁移**：Room 升级至 Version 2，引入 `WorldviewEntity`、`ScheduledAlarmEntity` 及 `SelfExperienceEntity.isPlaceholder`，通过 `MIGRATION_1_2` 与全量测试杜绝丢库风险（P12 & M3）；
2. **记忆抽取节流中枢**：为 `MemoryExtractor` 增加 3 分钟冷却 / 3 轮会话积攒节流门控与水位线管理，削减 70% 的冗余 LLM 抽取调用（P8）；
3. **自叙梦整理核心算法网**：`SemanticDeduplicator`（语义聚合与反囤积）、`TimeDisambiguator`（相对时间锚定至绝对 ISO 日期）、`BeliefRevisionEngine`（认知修正与旧条目 superseded 状态迁移）、`WorldviewPromoter`（跨天/多证据高层世界观提炼）；
4. **自叙梦中枢与夜间后台调度**：`SubconsciousDreamCoordinator` 编排整理流水线，`DreamEmergenceSynthesizer` 提炼次日清晨破冰活水，`SubconsciousDreamWorker` 严格锁定充电+WiFi+闲时执行约束；
5. **5 层记忆地图化组装**：`MapBasedPromptAssembler` 分层映射 Level 0 (记忆地图) -> Level 1 (世界观与花名册) -> Level 2 (Memo 三分层工作记忆) -> Level 3 (梦境浮现与经历) -> Level 4 (即时情境感知 P11)，消除静态 Token 浪费；
6. **长程对话交接快照**：`HandoffSnapshot` 与 `HandoffManager` 在会话超限（>20 轮或预算截断）时提炼结构化交接摘要并锚定于历史头部，根除“瞬间失忆”。

**Tech Stack:** Kotlin 2.0.21, Coroutines 1.9.0, AndroidX Room 2.6.1 + KSP, WorkManager 2.9.1, JUnit 4, Robolectric, MockK, Turbine.

## Global Constraints

- **100% Native 原生纯度红线**：严禁引入任何 Python/Chaquopy 依赖，所有算法、实体与状态机均以纯 Kotlin 协程构筑；
- **反囤积铁律（Anti-Hoarding）**：梦系统消化、总结、归纳出的内容总量必须少于或等于原始追加的内容总量，保障条目数具备数学收敛性；
- **认识论历史留痕**：矛盾消歧决不允许直接 DELETE 历史事实，必须标记为 `status = 'superseded'` 并将 `superseded_by` 指向新事实；
- **严密 TDD 与变异验证法网**：每个任务严格执行“红灯测试 -> 编写实现跑绿 -> 故意改坏破坏分支做变异验证（确认变红） -> 恢复全绿 -> 原子提交”；
- **基线零回归**：全仓既有 293 个单测必须 100% 保持常绿，打包 `./gradlew assembleDebug` 0 错误 0 警告。

---

## 任务拆解总览

| 任务 | 目标组件 | 解决痛点 / 对应编号 | 关键交付产物 |
|:---|:---|:---|:---|
| **Task 1** | Room v1->v2 迁移与持久化拓展 | P12, M3, Section 5 | `WorldviewEntity`, `ScheduledAlarmEntity`, `SelfExperienceEntity.isPlaceholder`, `XiaoxiDatabase` v2 + `MIGRATION_1_2`, `RoomMigrationTest` |
| **Task 2** | 记忆抽取节流门控与水位线管理 | P8 抽取节流 | `MemoryExtractor` 节流门（3min / 3 轮）、批量抽取水位线、`MemoryExtractorThrottlingTest` |
| **Task 3** | 梦系统核心整理与消歧算法 | Section 4.3 核心算法 | `SemanticDeduplicator`, `TimeDisambiguator`, `BeliefRevisionEngine`, `WorldviewPromoter` 及各自独立单测与变异验证 |
| **Task 4** | 梦系统总控中枢与夜间 Worker | Section 4.3.2, 用例 3 | `DreamEmergenceSynthesizer`, `SubconsciousDreamCoordinator`, `SubconsciousDreamWorker`, `SubconsciousDreamCoordinatorTest` |
| **Task 5** | 5 层地图式上下文组装器 | Section 4.4.2, P11 | `MapBasedPromptAssembler` (L0~L4 层拓扑), 融合即时情境 `PerceptionSnapshot`, `MapBasedPromptAssemblerTest` |
| **Task 6** | 会话交接快照与聊天链路接线 | Section 4.4.3, 4.B | `HandoffSnapshot`, `HandoffManager`, `ChatHistoryTrimmer` 升级, `ChatViewModel` 全链路接线与回归 |

---

### Task 1: Room v1->v2 迁移与持久化拓展 (`WorldviewEntity`, `ScheduledAlarmEntity`, `MIGRATION_1_2`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/database/entity/WorldviewEntity.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/database/dao/WorldviewDao.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/database/entity/ScheduledAlarmEntity.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/database/dao/ScheduledAlarmDao.kt`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/database/entity/SelfExperienceEntity.kt` (增加 `is_placeholder` 字段)
- Modify: `app/src/main/kotlin/com/xiaoxi/android/database/XiaoxiDatabase.kt` (升级至 version 2，注册新实体，提供 `MIGRATION_1_2`)
- Modify: `app/src/main/kotlin/com/xiaoxi/android/proactivity/DiaryEngine.kt` (对模板占位日记打上 `isPlaceholder = true` 标记)
- Test: `app/src/test/kotlin/com/xiaoxi/android/database/RoomMigrationTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/database/WorldviewAndAlarmDaoTest.kt`

**Interfaces:**
- Produces:
  - `WorldviewEntity(id, category, statement, supportingEvidenceCount, lastConfirmedAt, confidence)`
  - `WorldviewDao` (`insert`, `insertAll`, `update`, `getAll`, `getByCategory`, `getTopConfirmed`, `deleteById`)
  - `ScheduledAlarmEntity(id, triggerAt, botNote, blockDelivery, status)`
  - `ScheduledAlarmDao` (`insert`, `getPendingAlarms`, `updateStatus`, `deleteById`)
  - `SelfExperienceEntity.isPlaceholder: Boolean = false`
  - `XiaoxiDatabase.MIGRATION_1_2: Migration`

- [ ] **Step 1: 编写红灯测试 `WorldviewAndAlarmDaoTest` 与 `RoomMigrationTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/database/WorldviewAndAlarmDaoTest.kt` 创建：
```kotlin
package com.xiaoxi.android.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.WorldviewEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorldviewAndAlarmDaoTest {

    private lateinit var database: XiaoxiDatabase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            XiaoxiDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testWorldviewDao_crudAndCategoryQuery() = runBlocking {
        val dao = database.worldviewDao()
        val entry1 = WorldviewEntity(
            id = "wv_1",
            category = "生活习惯",
            statement = "用户习惯每晚 11 点后入睡，喜欢在睡前听轻音乐",
            supportingEvidenceCount = 5,
            lastConfirmedAt = 1726848000000L,
            confidence = 0.95f
        )
        val entry2 = WorldviewEntity(
            id = "wv_2",
            category = "工作哲学",
            statement = "用户倾向于采用深度工作法，厌恶碎片化的临时打断",
            supportingEvidenceCount = 8,
            lastConfirmedAt = 1726849000000L,
            confidence = 1.0f
        )

        dao.insertAll(listOf(entry1, entry2))

        val all = dao.getAll()
        assertEquals(2, all.size)

        val habitList = dao.getByCategory("生活习惯")
        assertEquals(1, habitList.size)
        assertEquals("wv_1", habitList.first().id)

        val top = dao.getTopConfirmed(limit = 1)
        assertEquals(1, top.size)
        assertEquals("wv_2", top.first().id)
        assertEquals(8, top.first().supportingEvidenceCount)
    }

    @Test
    fun testScheduledAlarmDao_crudAndStatusTransitions() = runBlocking {
        val dao = database.scheduledAlarmDao()
        val alarm = ScheduledAlarmEntity(
            id = "alarm_1",
            triggerAt = 1726850000000L,
            botNote = "提醒喝温开水与休息眼睛",
            blockDelivery = true,
            status = ScheduledAlarmEntity.STATUS_PENDING
        )

        dao.insert(alarm)

        val pending = dao.getPendingAlarms(nowMs = 1726851000000L)
        assertEquals(1, pending.size)
        assertEquals("alarm_1", pending.first().id)
        assertTrue(pending.first().blockDelivery)

        dao.updateStatus(id = "alarm_1", status = ScheduledAlarmEntity.STATUS_TRIGGERED)
        val pendingAfter = dao.getPendingAlarms(nowMs = 1726851000000L)
        assertTrue(pendingAfter.isEmpty())
    }

    @Test
    fun testSelfExperience_isPlaceholderField() = runBlocking {
        val dao = database.selfExperienceDao()
        val realExp = SelfExperienceEntity(
            id = "exp_real",
            title = "真实互动感悟",
            content = "今天和用户讨论了架构演进",
            createdAt = 1000L,
            isPlaceholder = false
        )
        val placeholderExp = SelfExperienceEntity(
            id = "exp_placeholder",
            title = "独处占位",
            content = "今天用户没有发消息",
            createdAt = 2000L,
            isPlaceholder = true
        )

        dao.insertAll(listOf(realExp, placeholderExp))

        val fetchedReal = dao.getById("exp_real")
        assertNotNull(fetchedReal)
        assertFalse(fetchedReal!!.isPlaceholder)

        val fetchedPlaceholder = dao.getById("exp_placeholder")
        assertNotNull(fetchedPlaceholder)
        assertTrue(fetchedPlaceholder!!.isPlaceholder)
    }
}
```

在 `app/src/test/kotlin/com/xiaoxi/android/database/RoomMigrationTest.kt` 创建：
```kotlin
package com.xiaoxi.android.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomMigrationTest {

    @Test
    fun testMigration1To2_executesSuccessfullyWithoutDataLoss() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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

        val v1Db = config.writableDatabase
        v1Db.execSQL("INSERT INTO self_experiences (id, title, content, domain_tag, valence, arousal, created_at, access_count, status) VALUES ('exp_v1', '旧经历', '测试内容', '日常', 0.0, 0.0, 1000, 0, 'fresh')")
        v1Db.close()

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
```

- [ ] **Step 2: 运行测试验证红灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.database.WorldviewAndAlarmDaoTest`
预期：编译报错或失败（未定义相关类）。

- [ ] **Step 3: 编写实现代码**

1. 创建 `app/src/main/kotlin/com/xiaoxi/android/database/entity/WorldviewEntity.kt`:
```kotlin
package com.xiaoxi.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 高层世界观认知实体 (Track A 顶级提炼 - Worldview Entity)
 * 记录在不同日期、多个独立情境下反复得到验证的深层规律。
 */
@Entity(tableName = "worldview_entities")
data class WorldviewEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "statement")
    val statement: String,

    @ColumnInfo(name = "supporting_evidence_count")
    val supportingEvidenceCount: Int = 1,

    @ColumnInfo(name = "last_confirmed_at")
    val lastConfirmedAt: Long,

    @ColumnInfo(name = "confidence")
    val confidence: Float = 1.0f
)
```

2. 创建 `app/src/main/kotlin/com/xiaoxi/android/database/dao/WorldviewDao.kt`:
```kotlin
package com.xiaoxi.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xiaoxi.android.database.entity.WorldviewEntity

@Dao
interface WorldviewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: WorldviewEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WorldviewEntity>)

    @Update
    suspend fun update(entity: WorldviewEntity)

    @Delete
    suspend fun delete(entity: WorldviewEntity)

    @Query("DELETE FROM worldview_entities WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM worldview_entities ORDER BY supporting_evidence_count DESC, last_confirmed_at DESC")
    suspend fun getAll(): List<WorldviewEntity>

    @Query("SELECT * FROM worldview_entities WHERE category = :category ORDER BY supporting_evidence_count DESC")
    suspend fun getByCategory(category: String): List<WorldviewEntity>

    @Query("SELECT * FROM worldview_entities ORDER BY supporting_evidence_count DESC, confidence DESC LIMIT :limit")
    suspend fun getTopConfirmed(limit: Int = 10): List<WorldviewEntity>
}
```

3. 创建 `app/src/main/kotlin/com/xiaoxi/android/database/entity/ScheduledAlarmEntity.kt`:
```kotlin
package com.xiaoxi.android.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自主闹钟与日程约定实体 (Track C - Scheduled Alarms)
 */
@Entity(tableName = "scheduled_alarms")
data class ScheduledAlarmEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "trigger_at")
    val triggerAt: Long,

    @ColumnInfo(name = "bot_note")
    val botNote: String,

    @ColumnInfo(name = "block_delivery")
    val blockDelivery: Boolean = false,

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_TRIGGERED = "triggered"
        const val STATUS_CANCELLED = "cancelled"
    }
}
```

4. 创建 `app/src/main/kotlin/com/xiaoxi/android/database/dao/ScheduledAlarmDao.kt`:
```kotlin
package com.xiaoxi.android.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity

@Dao
interface ScheduledAlarmDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScheduledAlarmEntity): Long

    @Update
    suspend fun update(entity: ScheduledAlarmEntity)

    @Delete
    suspend fun delete(entity: ScheduledAlarmEntity)

    @Query("DELETE FROM scheduled_alarms WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM scheduled_alarms WHERE status = 'pending' AND trigger_at <= :nowMs ORDER BY trigger_at ASC")
    suspend fun getPendingAlarms(nowMs: Long): List<ScheduledAlarmEntity>

    @Query("UPDATE scheduled_alarms SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)
}
```

5. 更新 `SelfExperienceEntity.kt`：增加 `@ColumnInfo(name = "is_placeholder", defaultValue = "0") val isPlaceholder: Boolean = false`。
6. 更新 `XiaoxiDatabase.kt`：版本升级至 `version = 2`，包含 `WorldviewEntity` 与 `ScheduledAlarmEntity`，添加 `abstract fun worldviewDao(): WorldviewDao`、`abstract fun scheduledAlarmDao(): ScheduledAlarmDao`，并声明 `MIGRATION_1_2`。
7. 更新 `DiaryEngine.kt`：当生成没有用户对话的模板日记时，传入 `isPlaceholder = true`；有用户对话提炼时 `isPlaceholder = false`。

- [ ] **Step 4: 运行测试验证绿灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.database.WorldviewAndAlarmDaoTest --tests com.xiaoxi.android.database.RoomMigrationTest`
预期：PASS。

- [ ] **Step 5: 变异验证**

故意将 `MIGRATION_1_2` 中的 `is_placeholder` 默认值或者新表创建 SQL 改坏（如表名错拼），运行测试确认触发红灯；恢复后重新全绿。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/database/
git add app/src/main/kotlin/com/xiaoxi/android/proactivity/DiaryEngine.kt
git add app/src/test/kotlin/com/xiaoxi/android/database/
git commit -m "feat(database): 落地Room v2迁移与世界观及闹钟表拓展 (M2.3 Task 1 - P12, M3)"
```

---

### Task 2: 记忆抽取节流门控与水位线管理 (`MemoryExtractor` P8)

**Files:**
- Modify: `app/src/main/kotlin/com/xiaoxi/android/database/memory/MemoryExtractor.kt`
- Create: `app/src/test/kotlin/com/xiaoxi/android/database/memory/MemoryExtractorThrottlingTest.kt`

**Interfaces:**
- Produces:
  - `MemoryExtractor.maybeExtract(userText, assistantText, nowMs, force): Int`
  - `MemoryExtractor.getPendingTurnCount(): Int`
  - `MemoryExtractor.getLastExtractionTime(): Long`
  - `MemoryExtractor.resetWatermark()`

- [ ] **Step 1: 编写红灯测试 `MemoryExtractorThrottlingTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/database/memory/MemoryExtractorThrottlingTest.kt` 创建：
```kotlin
package com.xiaoxi.android.database.memory

import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MemoryExtractorThrottlingTest {

    private val llmClient = mockk<XiaoxiLlmClient>()
    private val userMemoryDao = mockk<UserMemoryDao>(relaxed = true)
    private val config = LlmConfig(apiKey = "test")
    private lateinit var extractor: MemoryExtractor

    @Before
    fun setup() {
        extractor = MemoryExtractor(
            llmClient = llmClient,
            userMemoryDao = userMemoryDao,
            llmConfig = config,
            throttleIntervalMs = 180_000L, // 3分钟
            minTurnsAccumulated = 3
        )
    }

    @Test
    fun testThrottling_firstTurnAccumulates_doesNotCallLlmUntilConditionMet() = runBlocking {
        // 第 1 轮：文本足够长，但 turnCount=1 且未超 3 分钟，不触发 LLM
        val res1 = extractor.maybeExtract(
            userText = "我非常喜欢看科幻小说尤其是大刘的作品",
            nowMs = 1000L
        )
        assertEquals(0, res1)
        assertEquals(1, extractor.getPendingTurnCount())
        coVerify(exactly = 0) { llmClient.streamChat(any(), any()) }

        // 第 2 轮：turnCount=2，仍未超 3 分钟，继续积累
        val res2 = extractor.maybeExtract(
            userText = "我也喜欢在周末去图书馆借书",
            nowMs = 2000L
        )
        assertEquals(0, res2)
        assertEquals(2, extractor.getPendingTurnCount())
        coVerify(exactly = 0) { llmClient.streamChat(any(), any()) }

        // 第 3 轮：turnCount=3 达到 minTurnsAccumulated，触发 LLM 抽取
        coEvery { llmClient.streamChat(any(), any()) } returns flowOf(
            LlmStreamEvent.Token("""{"memories":[{"content":"用户喜欢看科幻小说和大刘作品","category":"偏好","importance":6}]}""")
        )

        val res3 = extractor.maybeExtract(
            userText = "最近在重温三体",
            nowMs = 3000L
        )
        assertEquals(1, res3)
        assertEquals(0, extractor.getPendingTurnCount()) // 水位线已重置
        assertEquals(3000L, extractor.getLastExtractionTime())
        coVerify(exactly = 1) { llmClient.streamChat(any(), any()) }
    }

    @Test
    fun testThrottling_timeThresholdExceeded_triggersExtractionEvenIfFewerTurns() = runBlocking {
        extractor.maybeExtract("我下个月打算考驾照", nowMs = 1000L)
        assertEquals(1, extractor.getPendingTurnCount())

        // 经过 3.5 分钟（210秒），即使只有第 2 轮也应触发抽取
        coEvery { llmClient.streamChat(any(), any()) } returns flowOf(
            LlmStreamEvent.Token("""{"memories":[{"content":"用户计划下月考驾照","category":"计划","importance":7}]}""")
        )

        val res = extractor.maybeExtract("教练说下周开始科目二", nowMs = 1000L + 210_000L)
        assertEquals(1, res)
        assertEquals(0, extractor.getPendingTurnCount())
    }

    @Test
    fun testThrottling_forceFlag_bypassesThrottling() = runBlocking {
        coEvery { llmClient.streamChat(any(), any()) } returns flowOf(
            LlmStreamEvent.Token("""{"memories":[{"content":"用户有只猫叫雪球","category":"身份","importance":8}]}""")
        )

        val res = extractor.maybeExtract(
            userText = "我家的小猫叫雪球",
            nowMs = 1000L,
            force = true
        )
        assertEquals(1, res)
        coVerify(exactly = 1) { llmClient.streamChat(any(), any()) }
    }
}
```

- [ ] **Step 2: 运行测试验证红灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.database.memory.MemoryExtractorThrottlingTest`
预期：FAIL（缺少参数与节流门控逻辑）。

- [ ] **Step 3: 优化 `MemoryExtractor.kt` 增加节流门与水位线**

在 `MemoryExtractor.kt` 中引入：
- `throttleIntervalMs: Long = 180_000L` (3分钟)
- `minTurnsAccumulated: Int = 3`
- `pendingTurns: MutableList<String>` 累积批次上下文
- `lastExtractionTimestamp: Long = 0L`
- 当 `force || (pendingTurns.size >= minTurnsAccumulated) || (nowMs - lastExtractionTimestamp >= throttleIntervalMs && pendingTurns.isNotEmpty())` 时触发聚合提取。

- [ ] **Step 4: 运行测试验证绿灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.database.memory.MemoryExtractorThrottlingTest --tests com.xiaoxi.android.database.memory.MemoryExtractorTest`
预期：PASS（既有测试与新测试全绿）。

- [ ] **Step 5: 变异验证**

故意将 `MemoryExtractor.kt` 中的节流判定反转（使未达标时也触发），确认单测变红；恢复后重新全绿。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/database/memory/MemoryExtractor.kt
git add app/src/test/kotlin/com/xiaoxi/android/database/memory/MemoryExtractorThrottlingTest.kt
git commit -m "feat(memory): 落地长期记忆抽取节流门控与水位线管理 (M2.3 Task 2 - P8)"
```

---

### Task 3: 梦系统记忆去重、时间绝对化与矛盾消歧核心算法

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/SemanticDeduplicator.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/TimeDisambiguator.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/BeliefRevisionEngine.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/WorldviewPromoter.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/SemanticDeduplicatorTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/TimeDisambiguatorTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/BeliefRevisionEngineTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/WorldviewPromoterTest.kt`

**Interfaces:**
- Produces:
  - `SemanticDeduplicator.deduplicate(memories: List<UserMemoryEntity>): DeduplicationResult`
  - `TimeDisambiguator.disambiguate(content: String, baseTimestamp: Long): String`
  - `BeliefRevisionEngine.detectAndRevise(existingActive: List<UserMemoryEntity>, newMemories: List<UserMemoryEntity>, nowMs: Long): RevisionResult`
  - `WorldviewPromoter.evaluatePromotions(memories: List<UserMemoryEntity>, nowMs: Long): List<WorldviewEntity>`

- [ ] **Step 1: 编写红灯测试**

编写：
1. `SemanticDeduplicatorTest`: 测试近义重复事实聚类（例如“买了新耳机”、“喜欢索尼降噪耳机”、“注重降噪音质”聚合合并，合并后总条数减少，且总 access/hitCount 被保留；验证输出 <= 输入反囤积铁律）；
2. `TimeDisambiguatorTest`: 测试“明天”、“后天”、“大后天”、“昨天”、“前天”、“下周一”在基准时间戳（如 2026-09-21）下精确替换为“2026-09-22”等绝对 ISO 日期；
3. `BeliefRevisionEngineTest`: 测试冲突事实（“喜欢吃麻辣香锅” vs “胃炎严重医生嘱咐严禁吃辣”），旧条目被标记为 `STATUS_SUPERSEDED`，`superseded_by` 指向新条目 ID，`prev_content` 备份旧内容，`valid_to` 置为当前时间；
4. `WorldviewPromoterTest`: 测试跨越 2 天以上且 hitCount >= 3 的稳定偏好/习惯被晋升为 `WorldviewEntity`，而偶然发生的单次事件不晋升。

- [ ] **Step 2: 运行测试确认红灯**

运行测试，确认因类与函数不存在而变红。

- [ ] **Step 3: 编写最小实现代码**

1. `SemanticDeduplicator.kt`: 基于 N-Gram / 核心词重合度聚类近义记忆，合并为高信息密度条目，归档被合并的碎片条目，满足条目数净减少；
2. `TimeDisambiguator.kt`: 使用正则扫描时间词，结合 `Calendar`/`LocalDate` 算得绝对日期并替换文本；
3. `BeliefRevisionEngine.kt`: 维护正负极性与排斥关键词表（“喜欢”vs“厌恶/不喜欢/戒掉/不能”，“搬到”vs“住在”），触发时调用 `UserMemoryDao.markSuperseded` 规范结构；
4. `WorldviewPromoter.kt`: 过滤 `hitCount >= 3` 或跨日期多次提及的事实，归纳为“生活习惯”、“人际交往”、“处世哲学”等范畴，生成 `WorldviewEntity`。

- [ ] **Step 4: 运行测试验证绿灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.memory.consolidation.*`
预期：全部 PASS。

- [ ] **Step 5: 变异验证**

针对每一个算法进行破坏性变异（如反转绝对时间加减法、取消矛盾覆盖标记、破坏反囤积约束断言），确认测试精准变红后复原。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/
git add app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/
git commit -m "feat(memory): 落地梦系统语义去重、时间绝对化与矛盾消歧算法 (M2.3 Task 3)"
```

---

### Task 4: 自叙梦系统协调器与夜间 Worker (`SubconsciousDreamCoordinator` & `SubconsciousDreamWorker`)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/DreamEmergenceSynthesizer.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/SubconsciousDreamCoordinator.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/SubconsciousDreamWorker.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/SubconsciousDreamCoordinatorTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/SubconsciousDreamWorkerTest.kt`

**Interfaces:**
- Produces:
  - `DreamEmergenceSynthesizer.synthesize(worldviews: List<WorldviewEntity>, revisedMemories: List<UserMemoryEntity>, nowMs: Long): UnspokenThoughtEntity?`
  - `SubconsciousDreamCoordinator.runDreamCycle(nowMs: Long): DreamCycleReport`
  - `SubconsciousDreamWorker` (WorkManager CoroutineWorker, charging + idle + network constraints)

- [ ] **Step 1: 编写红灯测试 `SubconsciousDreamCoordinatorTest` 与 `SubconsciousDreamWorkerTest`**

在 `SubconsciousDreamCoordinatorTest.kt` 中：
- 模拟数据库中存在 10 条重复与时效记忆，包含 1 条矛盾事实（“旧：爱喝黑咖啡” vs “新：胃溃疡改喝大麦茶”）；
- 执行 `runDreamCycle(nowMs)`；
- 断言：
  1. 事实总量具备收敛性（去重率验证，条目数净减少）；
  2. 旧黑咖啡记忆迁移为 `superseded`，世界观中出现“温和饮品偏好”；
  3. 相对时间词被绝对化为 YYYY-MM-DD；
  4. 生成了次日清晨破冰念头 `DreamEmergence` 存入 `unspoken_thoughts`。
在 `SubconsciousDreamWorkerTest.kt` 中：
- 验证 WorkManager 约束：`requiresCharging(true)`、`requiresDeviceIdle(true)`、`NetworkType.CONNECTED`，周期为 24 小时。

- [ ] **Step 2: 运行测试确认红灯**

运行测试，确认红灯。

- [ ] **Step 3: 编写实现代码**

1. `DreamEmergenceSynthesizer.kt`: 结合当日整理的重要世界观与被修正事实，组装一段第一人称亲昵体贴的心得札记，写入 `UnspokenThoughtEntity(urgency = 4.5f)`；
2. `SubconsciousDreamCoordinator.kt`: 串联 Step 1 去重 -> Step 2 时间绝对化 -> Step 3 矛盾消歧 -> Step 4 世界观晋升 -> Step 5 占位日记刷新 -> Step 6 梦境浮现念头生成并落库；
3. `SubconsciousDreamWorker.kt`: 继承 `CoroutineWorker`，在 `doWork()` 中调用 `SubconsciousDreamCoordinator.runDreamCycle()`，提供安全捕获与重试。

- [ ] **Step 4: 运行测试验证绿灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.memory.consolidation.SubconsciousDream*`
预期：PASS。

- [ ] **Step 5: 变异验证**

故意将 `SubconsciousDreamCoordinator` 中梦境浮现念头注入逻辑或者世界观持久化逻辑注释掉，确认单测变红报错；恢复后重新全绿。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/memory/consolidation/
git add app/src/test/kotlin/com/xiaoxi/android/memory/consolidation/
git commit -m "feat(memory): 落地自叙梦系统总控中枢与夜间后台调度Worker (M2.3 Task 4)"
```

---

### Task 5: 5 层地图式上下文组装器与即时情境感知 (`MapBasedPromptAssembler` & P11)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/persona/MapBasedPromptAssembler.kt`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManager.kt` (接入 Worldview 与地图化装配接口)
- Test: `app/src/test/kotlin/com/xiaoxi/android/persona/MapBasedPromptAssemblerTest.kt`

**Interfaces:**
- Produces:
  - `MapBasedPromptAssembler.assemble(worldviews, activeMemories, activeTasks, dreamEmergence, selfExperiences, perceptionSnapshot, query): String`
  - 5 层拓扑结构：
    - Level 0: 记忆地图 (Memory Map: 领域目录与数量统计)
    - Level 1: 核心认知与花名册 (Worldview & Roster)
    - Level 2: 备忘与工作记忆 (Memo 3-Tier: Resident, Active, Archived)
    - Level 3: 梦境浮现与自叙经历 (Dream Emergence & Reflection)
    - Level 4: 即时情境感知 (Immediate Environment Context - 接入 P11 `PerceptionSnapshot.perceivedSummary`)

- [ ] **Step 1: 编写红灯测试 `MapBasedPromptAssemblerTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/persona/MapBasedPromptAssemblerTest.kt` 编写：
1. 测试空状态优雅容错（不输出冗余无意义空块）；
2. 测试完整的 5 层结构清晰排列（`Level 0 记忆地图`、`Level 1 核心认知`、`Level 2 工作记忆`、`Level 3 梦境见闻`、`Level 4 即时环境`）；
3. 测试与粗暴 Top-5 全文拼接相比，Level 0 地图式索引结合 Level 1 高层世界观大幅压缩静态 Token；
4. 测试 Level 4 正确提取 `PerceptionSnapshot` 中的前台 App、停留时长、脱敏通知与屏幕状态（解决 P11 情境感知“采而不议”断层）。

- [ ] **Step 2: 运行测试确认红灯**

运行测试，确认缺少 `MapBasedPromptAssembler` 而变红。

- [ ] **Step 3: 编写实现代码**

实现 `MapBasedPromptAssembler.kt` 与升级 `DualTrackMemoryManager.kt`：
- 构建格式严整且结构清晰的 Markdown 块；
- 精准注入 Level 0 记忆地图统计（例如 `[记忆地图] 偏好(5条), 作息(2条), 社交(3条)`）；
- 精准注入 Level 4 即时感知（例如 `[即时情境] 屏幕使用中 | 前台应用: 微信 (已停留 12 分钟) | 通知: 顺丰速运快递已到达驿站`）；
- 保持与 `PhiMiddleware` 的无缝装配衔接。

- [ ] **Step 4: 运行测试验证绿灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.persona.MapBasedPromptAssemblerTest`
预期：PASS。

- [ ] **Step 5: 变异验证**

故意将 Level 4 感知快照注入逻辑短路置空，确认测试变红；恢复后重新全绿。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/persona/MapBasedPromptAssembler.kt
git add app/src/main/kotlin/com/xiaoxi/android/database/memory/DualTrackMemoryManager.kt
git add app/src/test/kotlin/com/xiaoxi/android/persona/MapBasedPromptAssemblerTest.kt
git commit -m "feat(persona): 落地5层地图式上下文组装器与即时情境接入 (M2.3 Task 5 - P11)"
```

---

### Task 6: 上下文交接快照机制与长程会话平滑过渡 (`HandoffSnapshot`, `HandoffManager`, 聊天链路接线)

**Files:**
- Create: `app/src/main/kotlin/com/xiaoxi/android/persona/model/HandoffSnapshot.kt`
- Create: `app/src/main/kotlin/com/xiaoxi/android/persona/HandoffManager.kt`
- Modify: `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatHistoryTrimmer.kt` (升级为支持注入 `HandoffSnapshot` 锚定头)
- Modify: `app/src/main/kotlin/com/xiaoxi/android/ui/chat/ChatViewModel.kt` (挂载 `HandoffManager` 与 `MapBasedPromptAssembler`)
- Test: `app/src/test/kotlin/com/xiaoxi/android/persona/HandoffManagerTest.kt`
- Test: `app/src/test/kotlin/com/xiaoxi/android/ui/chat/ChatViewModelHandoffTest.kt`

**Interfaces:**
- Produces:
  - `HandoffSnapshot(sessionId, sessionSummary, unresolvedCommitments, emotionalTone, timestamp, coveredTurns)`
  - `HandoffManager.maybeCreateSnapshot(sessionId, outgoingMessages): HandoffSnapshot?`
  - `HandoffManager.getLatestSnapshot(sessionId): HandoffSnapshot?`
  - `ChatHistoryTrimmer.trimWithSnapshot(history, snapshot, maxTokens): List<ChatMessage>`

- [ ] **Step 1: 编写红灯测试 `HandoffManagerTest` 与 `ChatViewModelHandoffTest`**

在 `app/src/test/kotlin/com/xiaoxi/android/persona/HandoffManagerTest.kt` 中：
1. 测试当轮次达到 20 轮或被裁剪时，`HandoffManager` 提取 outgoing 历史生成快照（含讨论话题、未尽承诺与当前情感基调）；
2. 测试快照作为首条伪消息注入 `ChatHistoryTrimmer`，验证截断后历史不丢失前文语境；
3. 测试 `ChatViewModel` 在长程对话中发送消息时，平滑衔接快照，UI 徽章与提示词完整承载记忆。

- [ ] **Step 2: 运行测试确认红灯**

运行测试，确认红灯。

- [ ] **Step 3: 编写实现代码**

1. `HandoffSnapshot.kt`: 数据契约声明；
2. `HandoffManager.kt`: 快照提炼与内存/持久化管理；
3. `ChatHistoryTrimmer.kt`: 增加 `trimWithSnapshot` 接口，在裁剪后将快照转化为自然且不突兀的前文记忆重锚条目插入顶部；
4. `ChatViewModel.kt`: 接入 `HandoffManager` 与 `MapBasedPromptAssembler`，在装配对话时将地图化上下文与交接快照一体化输出。

- [ ] **Step 4: 运行测试验证绿灯**

运行 `./gradlew testDebugUnitTest --tests com.xiaoxi.android.persona.HandoffManagerTest --tests com.xiaoxi.android.ui.chat.ChatViewModelHandoffTest`
预期：PASS。

- [ ] **Step 5: 变异验证**

故意将 `ChatHistoryTrimmer.trimWithSnapshot` 中快照插入逻辑禁用，确认单测变红；恢复后重新全绿。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/com/xiaoxi/android/persona/
git add app/src/main/kotlin/com/xiaoxi/android/ui/chat/
git add app/src/test/kotlin/com/xiaoxi/android/persona/
git add app/src/test/kotlin/com/xiaoxi/android/ui/chat/
git commit -m "feat(chat): 落地上下文交接快照机制与长程会话平滑过渡 (M2.3 Task 6)"
```

---

## 阶段全量回归与收尾验收

在全部 6 个 Task 实施完成后：
1. 运行 `./gradlew testDebugUnitTest`：确认全部测试（基线 293 + M2.3 增补测试，预计 320+ 项）100% 全绿通过；
2. 运行 `./gradlew assembleDebug`：确保 APK 构建成功，0 错误，0 警告；
3. 更新 `docs/PROGRESS_TRACKER.md` 记录 M2.3 达成与变异验证证据；
4. 运行 `superpowers:finishing-a-development-branch` 交付成果。
