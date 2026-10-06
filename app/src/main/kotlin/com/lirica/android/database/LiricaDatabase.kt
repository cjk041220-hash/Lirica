package com.lirica.android.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lirica.android.database.dao.ActionMacroDao
import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.dao.EpisodicSensingSliceDao
import com.lirica.android.database.dao.OpenLoopDao
import com.lirica.android.database.dao.ScheduledAlarmDao
import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.dao.WorldviewDao
import com.lirica.android.database.entity.ActionMacroEntity
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.database.entity.EpisodicSensingSliceEntity
import com.lirica.android.database.entity.OpenLoopEntity
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.database.entity.SelfExperienceEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.entity.WorldviewEntity
import com.lirica.android.database.memory.MemoryEmbeddingDao
import com.lirica.android.database.memory.MemoryEmbeddingEntity

/**
 * Lirica 核心 Room 数据库（LiricaDatabase）
 *
 * 管理心智与交互核心表：
 * 1. user_memories (用户轨画像与事实记忆)
 * 2. self_experiences (自叙轨经历与见闻心得，支持 is_placeholder 标记)
 * 3. unspoken_thoughts (未完成念头与主动破冰事实池)
 * 4. chat_dialogues (会话多轮历史持久化)
 * 5. worldview_entities (高层世界观与认知升维表)
 * 6. scheduled_alarms (自主闹钟与日程约定表)
 * 7. memory_embeddings (双轨记忆端侧向量索引表)
 * 8. episodic_sensing_slices (情境感知驻留切片缓存表 - v4 新增)
 * 9. open_loops (开环记忆未决事务生命周期表 - v4 新增)
 * 10. action_macros (机型自适应经验轨迹表 - v6 新增，M5.3)
 */
@Database(
    entities = [
        UserMemoryEntity::class,
        SelfExperienceEntity::class,
        UnspokenThoughtEntity::class,
        ChatDialogueEntity::class,
        WorldviewEntity::class,
        ScheduledAlarmEntity::class,
        MemoryEmbeddingEntity::class,
        EpisodicSensingSliceEntity::class,
        OpenLoopEntity::class,
        ActionMacroEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class LiricaDatabase : RoomDatabase() {

    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun selfExperienceDao(): SelfExperienceDao
    abstract fun unspokenThoughtDao(): UnspokenThoughtDao
    abstract fun chatDialogueDao(): ChatDialogueDao
    abstract fun worldviewDao(): WorldviewDao
    abstract fun scheduledAlarmDao(): ScheduledAlarmDao
    abstract fun memoryEmbeddingDao(): MemoryEmbeddingDao
    abstract fun episodicSensingSliceDao(): EpisodicSensingSliceDao
    abstract fun openLoopDao(): OpenLoopDao
    abstract fun actionMacroDao(): ActionMacroDao

    companion object {
        const val DATABASE_NAME = "lirica_aether.db"

        @JvmField
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                db.execSQL("""
                    ALTER TABLE `self_experiences` ADD COLUMN `is_placeholder` INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
            }
        }

        @JvmField
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `memory_embeddings` (
                        `memory_id` TEXT NOT NULL,
                        `track` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`memory_id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_memory_embeddings_track` ON `memory_embeddings` (`track`)
                """.trimIndent())
            }
        }

        @JvmField
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `episodic_sensing_slices` (
                        `id` TEXT NOT NULL,
                        `timestamp_ms` INTEGER NOT NULL,
                        `dwell_duration_ms` INTEGER NOT NULL,
                        `app_category` TEXT NOT NULL,
                        `primary_package` TEXT NOT NULL,
                        `summary_scene` TEXT NOT NULL,
                        `hardware_context_json` TEXT NOT NULL,
                        `is_consolidated` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_sensing_slices_timestamp` ON `episodic_sensing_slices` (`timestamp_ms`)
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_sensing_slices_consolidated` ON `episodic_sensing_slices` (`is_consolidated`)
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `open_loops` (
                        `id` TEXT NOT NULL,
                        `source_memory_id` TEXT,
                        `title` TEXT NOT NULL,
                        `target_time_ms` INTEGER NOT NULL,
                        `trigger_window_start_ms` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `urgency_level` INTEGER NOT NULL,
                        `follow_up_prompt` TEXT NOT NULL,
                        `retry_count` INTEGER NOT NULL,
                        `last_prompted_ms` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_open_loops_status` ON `open_loops` (`status`)
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_open_loops_target_time` ON `open_loops` (`target_time_ms`)
                """.trimIndent())
            }
        }

        /**
         * v5：未完成念头池新增 content_title 列（L3 通知标题）。
         * 用 NOT NULL DEFAULT '' 加列：老行自动补空串，由 L3 通道退化为兜底标题，
         * 不产生 NULL 语义分支，也不需要回填脚本。
         */
        @JvmField
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    ALTER TABLE `unspoken_thoughts` ADD COLUMN `content_title` TEXT NOT NULL DEFAULT ''
                """.trimIndent())
            }
        }

        /**
         * v6：机型自适应经验轨迹表（M5.3 §5.2）。
         *
         * 建表语句必须与 [ActionMacroEntity] 的 Room 生成 schema 逐字段一致，
         * 否则 Room 的 schema 校验会在真机升级时抛 IllegalStateException
         * （单测覆盖见 Migration5To6Test / RoomMigrationTest）。
         * 三个索引与实体注解中的索引一一对应。
         */
        @JvmField
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `action_macros` (
                        `macro_id` TEXT NOT NULL,
                        `intent_signature` TEXT NOT NULL,
                        `app_package` TEXT NOT NULL,
                        `target_app_version_code` INTEGER NOT NULL,
                        `start_page_fingerprint` INTEGER NOT NULL,
                        `steps_json` TEXT NOT NULL,
                        `explicit_slot_keys` TEXT NOT NULL,
                        `success_count` INTEGER NOT NULL,
                        `failure_count` INTEGER NOT NULL,
                        `consecutive_failures` INTEGER NOT NULL,
                        `is_deprecated` INTEGER NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `last_used_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`macro_id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_macro_lookup` ON `action_macros` (`intent_signature`, `app_package`, `target_app_version_code`)
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_macro_last_used` ON `action_macros` (`last_used_at_ms`)
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `idx_macro_deprecated` ON `action_macros` (`is_deprecated`)
                """.trimIndent())
            }
        }

        @Volatile
        private var INSTANCE: LiricaDatabase? = null

        fun getInstance(context: Context): LiricaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        fun buildDatabase(context: Context): LiricaDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                LiricaDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        fun buildInMemory(context: Context): LiricaDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                LiricaDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}