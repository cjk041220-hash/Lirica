package com.lirica.android.automation.macro

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.database.LiricaDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 经验轨迹库 + DAO 回归（M5.3 §5.2 · DB v6）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ActionMacroDaoTest {

    private lateinit var db: LiricaDatabase
    private lateinit var memory: RoomTrajectoryMemory
    private var now = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LiricaDatabase::class.java
        ).allowMainThreadQueries().build()
        memory = RoomTrajectoryMemory(dao = db.actionMacroDao(), timeProvider = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun spec(
        signature: String = "PLAY_MUSIC",
        appPackage: String = "com.example.app",
        versionCode: Long = 100L,
        startFingerprint: Long = 42L,
        steps: List<MacroStep> = listOf(
            MacroStep(kind = MacroStepKind.CLICK, normalizedX = 500, normalizedY = 100, description = "点击播放")
        )
    ) = MacroRecordSpec(
        intentSignature = signature,
        appPackage = appPackage,
        targetAppVersionCode = versionCode,
        startPageFingerprint = startFingerprint,
        steps = steps
    )

    @Test
    fun testRecordAndFindRoundTrip() = runTest {
        val recorded = memory.record(spec())
        assertNotNull(recorded)

        val found = memory.findUsable("PLAY_MUSIC", "com.example.app", 100L)
        assertNotNull(found)
        assertEquals(1, found!!.steps.size)
        assertEquals(MacroStepKind.CLICK, found.steps[0].kind)
        assertEquals(500, found.steps[0].normalizedX)
        assertEquals(42L, found.startPageFingerprint)
    }

    @Test
    fun testVersionUpgradeInvalidatesMacro() = runTest {
        memory.record(spec(versionCode = 100L))
        assertNotNull(memory.findUsable("PLAY_MUSIC", "com.example.app", 100L))
        // §5.2：目标 App 版本升级直接失效（靠查询条件排除，而非事后清理）
        assertNull(memory.findUsable("PLAY_MUSIC", "com.example.app", 101L))
    }

    @Test
    fun testDeprecateStaleVersionsSweepsOldRows() = runTest {
        memory.record(spec(versionCode = 100L))
        val deprecated = memory.deprecateStaleVersions("com.example.app", 200L)
        assertEquals(1, deprecated)
        assertEquals(0, memory.activeCount())
    }

    @Test
    fun testConsecutiveFailuresTriggerDeprecation() = runTest {
        val macro = memory.record(spec())!!
        assertTrue(!memory.markReplayFailure(macro.macroId))
        assertTrue("连续 2 次失败必须自动弃用", memory.markReplayFailure(macro.macroId))
        assertNull("弃用后不可再检索", memory.findUsable("PLAY_MUSIC", "com.example.app", 100L))
    }

    @Test
    fun testSuccessResetsConsecutiveFailures() = runTest {
        val macro = memory.record(spec())!!
        memory.markReplayFailure(macro.macroId)
        memory.markReplaySuccess(macro.macroId)
        // 成功一次即清零，因此再失败一次不应触发弃用
        assertTrue(!memory.markReplayFailure(macro.macroId))
    }

    @Test
    fun testRecordRejectsMalformedSteps() = runTest {
        // 缺落点的点击属结构非法：录下来就是盲回放的种子
        val malformed = MacroStep(kind = MacroStepKind.CLICK, refId = null, normalizedX = null, normalizedY = null)
        assertNull(memory.record(spec(steps = listOf(malformed))))
        assertEquals(0, memory.activeCount())
    }

    @Test
    fun testRecordRejectsEmptySteps() = runTest {
        assertNull(memory.record(spec(steps = emptyList())))
    }

    @Test
    fun testRecordRejectsMacroBeyondStepBudget() = runTest {
        // 录制端就拦住注定无法整段回放的超长轨迹，避免库里堆"永远命中不了"的垃圾宏
        val longSteps = (0 until 20).map {
            MacroStep(kind = MacroStepKind.CLICK, normalizedX = it, normalizedY = it)
        }
        assertNull(memory.record(spec(steps = longSteps)))
        assertEquals(0, memory.activeCount())
    }

    @Test
    fun testSlotKeysArePersistedAndDecoded() = runTest {
        val steps = listOf(MacroStep(kind = MacroStepKind.TYPE, slotKey = "keyword", normalizedX = 1, normalizedY = 2))
        memory.record(spec(steps = steps))
        val found = memory.findUsable("PLAY_MUSIC", "com.example.app", 100L)
        assertEquals(listOf("keyword"), found!!.explicitSlotKeys)
        assertEquals("keyword", found.steps[0].slotKey)
    }

    @Test
    fun testCapacityEnforcementDropsOldest() = runTest {
        repeat(4) { index ->
            now += 1_000L
            memory.record(spec(signature = "SIG_" + index))
        }
        assertEquals(4, memory.activeCount())
        val removed = memory.enforceCapacity(maxActive = 2)
        assertEquals(2, removed)
        assertEquals(2, memory.activeCount())
        // 最久未使用的两条被淘汰，最近两条保留
        assertNotNull(memory.findUsable("SIG_3", "com.example.app", 100L))
        assertNull(memory.findUsable("SIG_0", "com.example.app", 100L))
    }

    @Test
    fun testPurgeDeprecatedRemovesRows() = runTest {
        val macro = memory.record(spec())!!
        memory.markReplayFailure(macro.macroId)
        memory.markReplayFailure(macro.macroId)
        assertEquals(1, memory.purgeDeprecated())
        assertEquals(0, memory.activeCount())
    }

    @Test
    fun testFindUsablePrefersHigherSuccessCount() = runTest {
        val first = memory.record(spec())!!
        memory.markReplaySuccess(first.macroId)
        now += 10L
        // 第二条同签名同版本：查询按 success_count 降序，命中已被验证过的那条
        memory.record(spec())
        val found = memory.findUsable("PLAY_MUSIC", "com.example.app", 100L)
        assertEquals(first.macroId, found!!.macroId)
    }

    @Test
    fun testFindUsableSkipsInvalidJsonGracefully() = runTest {
        val entity = MacroCodec.toEntity(
            ActionMacro(
                macroId = "broken",
                intentSignature = "PLAY_MUSIC",
                appPackage = "com.example.app",
                targetAppVersionCode = 100L,
                startPageFingerprint = 1L,
                steps = listOf(MacroStep(kind = MacroStepKind.BACK)),
                explicitSlotKeys = emptyList()
            ),
            now
        ).copy(stepsJson = "{不是合法 JSON")
        db.actionMacroDao().upsert(entity)
        // 解码失败 → 空步序列 → isReplayable=false → 返回 null 而不是抛异常
        assertNull(memory.findUsable("PLAY_MUSIC", "com.example.app", 100L))
    }

    @Test
    fun testMacroCodecRoundTrip() {
        val steps = listOf(
            MacroStep(kind = MacroStepKind.CLICK, refId = "n0", normalizedX = 10, normalizedY = 20, description = "点"),
            MacroStep(kind = MacroStepKind.TYPE, slotKey = "k", description = "填"),
            MacroStep(kind = MacroStepKind.SCROLL, direction = "down"),
            MacroStep(kind = MacroStepKind.WAIT, delayMs = 500L),
            MacroStep(kind = MacroStepKind.BACK)
        )
        val decoded = MacroCodec.decodeSteps(MacroCodec.encodeSteps(steps))
        assertEquals(steps, decoded)
        assertEquals(listOf("k"), MacroCodec.decodeSlotKeys(MacroCodec.encodeSlotKeys(listOf("k"))))
    }

    @Test
    fun testWellFormedValidation() {
        assertTrue(MacroStep(kind = MacroStepKind.BACK).isWellFormed())
        assertTrue(MacroStep(kind = MacroStepKind.CLICK, normalizedX = 1, normalizedY = 2).isWellFormed())
        assertTrue(MacroStep(kind = MacroStepKind.CLICK, refId = "n0").isWellFormed())
        assertTrue(!MacroStep(kind = MacroStepKind.CLICK).isWellFormed())
        assertTrue(!MacroStep(kind = MacroStepKind.TYPE).isWellFormed())
        assertTrue(!MacroStep(kind = MacroStepKind.WAIT, delayMs = 0L).isWellFormed())
        assertTrue(!MacroStep(kind = MacroStepKind.SCROLL).isWellFormed())
    }
}
