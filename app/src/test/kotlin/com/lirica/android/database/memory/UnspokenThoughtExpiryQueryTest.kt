package com.lirica.android.database.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.entity.UnspokenThoughtEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 过期念头必须在**查询层**被拦掉。
 *
 * 旧实现只在"刷新破冰气泡"时批量 discardExpiredThoughts，而主动裁决/L3 取数走的是
 * getTopUrgentPendingThoughts——没人刷新时，昨天考完的考试会继续被当成最高优先念头递到用户面前。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UnspokenThoughtExpiryQueryTest {

    private lateinit var context: Context
    private lateinit var database: LiricaDatabase
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LiricaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testExpiredThoughtExcludedFromUrgentQuery() = runBlocking {
        val dao = database.unspokenThoughtDao()
        dao.insertAll(
            listOf(
                UnspokenThoughtEntity(
                    id = "expired_open_loop",
                    thought = "毕业答辩｜准备得怎么样啦？",
                    urgency = 4.9f,
                    createdAt = now - 7200_000L,
                    expiryTime = now - 3600_000L, // 一小时前已过期
                    status = UnspokenThoughtEntity.STATUS_PENDING
                ),
                UnspokenThoughtEntity(
                    id = "valid_open_loop",
                    thought = "面试｜准备得怎么样啦？",
                    urgency = 4.7f,
                    createdAt = now - 600_000L,
                    expiryTime = now + 3600_000L,
                    status = UnspokenThoughtEntity.STATUS_PENDING
                ),
                UnspokenThoughtEntity(
                    id = "no_expiry",
                    thought = "看到你叹气了",
                    urgency = 0.6f,
                    createdAt = now - 60_000L,
                    expiryTime = null,
                    status = UnspokenThoughtEntity.STATUS_PENDING
                )
            )
        )

        val top = dao.getTopUrgentPendingThoughts(limit = 5, nowMs = now)

        assertEquals(2, top.size)
        assertTrue("已过期的最高优先念头必须被拦截", top.none { it.id == "expired_open_loop" })
        assertEquals("valid_open_loop", top[0].id)
        assertTrue("无过期时间的念头不受影响", top.any { it.id == "no_expiry" })
    }

    @Test
    fun testExpiredThoughtStillRetractableByTriggerContext() = runBlocking {
        // 开环闭环要能撤回"已过期但仍在池里"的念头，否则统计口径会被污染
        val dao = database.unspokenThoughtDao()
        dao.insert(
            UnspokenThoughtEntity(
                id = "thought_open_loop_x",
                thought = "答辩｜准备得怎么样啦？",
                triggerContext = "open_loop:loop_x",
                urgency = 4.8f,
                createdAt = now - 7200_000L,
                expiryTime = now - 3600_000L,
                status = UnspokenThoughtEntity.STATUS_PENDING
            )
        )

        val found = dao.getByTriggerContextPrefix("open_loop:loop_x")

        assertEquals(1, found.size)
        assertEquals("thought_open_loop_x", found[0].id)
    }
}
