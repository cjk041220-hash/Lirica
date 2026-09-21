package com.xiaoxi.android.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.dao.ScheduledAlarmDao
import com.xiaoxi.android.database.dao.SelfExperienceDao
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.database.dao.WorldviewDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity
import com.xiaoxi.android.database.entity.WorldviewEntity

/**
 * 小汐 2.0 核心 Room 数据库（XiaoxiDatabase）
 *
 * 管理六大心智与交互核心表：
 * 1. user_memories (用户轨画像与事实记忆)
 * 2. self_experiences (自叙轨经历与见闻心得，支持 is_placeholder 标记)
 * 3. unspoken_thoughts (未完成念头与主动破冰事实池)
 * 4. chat_dialogues (会话多轮历史持久化)
 * 5. worldview_entities (高层世界观与认知升维表)
 * 6. scheduled_alarms (自主闹钟与日程约定表)
 */
@Database(
    entities = [
        UserMemoryEntity::class,
        SelfExperienceEntity::class,
        UnspokenThoughtEntity::class,
        ChatDialogueEntity::class,
        WorldviewEntity::class,
        ScheduledAlarmEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class XiaoxiDatabase : RoomDatabase() {

    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun selfExperienceDao(): SelfExperienceDao
    abstract fun unspokenThoughtDao(): UnspokenThoughtDao
    abstract fun chatDialogueDao(): ChatDialogueDao
    abstract fun worldviewDao(): WorldviewDao
    abstract fun scheduledAlarmDao(): ScheduledAlarmDao

    companion object {
        const val DATABASE_NAME = "xiaoxi_aether.db"

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

        @Volatile
        private var INSTANCE: XiaoxiDatabase? = null

        fun getInstance(context: Context): XiaoxiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        fun buildDatabase(context: Context): XiaoxiDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                XiaoxiDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        fun buildInMemory(context: Context): XiaoxiDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                XiaoxiDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
