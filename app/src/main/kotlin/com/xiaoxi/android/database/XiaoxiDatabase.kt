package com.xiaoxi.android.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.dao.SelfExperienceDao
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.dao.UserMemoryDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.entity.SelfExperienceEntity
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.entity.UserMemoryEntity

/**
 * 小汐 2.0 核心 Room 数据库（XiaoxiDatabase）
 *
 * 管理四大心智与交互核心表：
 * 1. user_memories (用户轨画像与事实记忆)
 * 2. self_experiences (自叙轨经历与见闻心得)
 * 3. unspoken_thoughts (未完成念头与主动破冰事实池)
 * 4. chat_dialogues (会话多轮历史持久化)
 */
@Database(
    entities = [
        UserMemoryEntity::class,
        SelfExperienceEntity::class,
        UnspokenThoughtEntity::class,
        ChatDialogueEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class XiaoxiDatabase : RoomDatabase() {

    abstract fun userMemoryDao(): UserMemoryDao
    abstract fun selfExperienceDao(): SelfExperienceDao
    abstract fun unspokenThoughtDao(): UnspokenThoughtDao
    abstract fun chatDialogueDao(): ChatDialogueDao

    companion object {
        const val DATABASE_NAME = "xiaoxi_aether.db"

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
                .fallbackToDestructiveMigration()
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
