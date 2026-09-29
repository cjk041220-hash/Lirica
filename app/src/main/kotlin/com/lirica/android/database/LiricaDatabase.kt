package com.lirica.android.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.dao.ScheduledAlarmDao
import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.dao.UserMemoryDao
import com.lirica.android.database.dao.WorldviewDao
import com.lirica.android.database.entity.ChatDialogueEntity
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
 */
@Database(
    entities = [
        UserMemoryEntity::class,
        SelfExperienceEntity::class,
        UnspokenThoughtEntity::class,
        ChatDialogueEntity::class,
        WorldviewEntity::class,
        ScheduledAlarmEntity::class,
        MemoryEmbeddingEntity::class
    ],
    version = 3,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
