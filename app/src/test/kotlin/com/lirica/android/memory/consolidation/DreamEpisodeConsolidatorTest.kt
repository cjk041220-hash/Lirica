package com.lirica.android.memory.consolidation

import com.lirica.android.database.dao.EpisodicSensingSliceDao
import com.lirica.android.database.dao.SelfExperienceDao
import com.lirica.android.database.entity.EpisodicSensingSliceEntity
import com.lirica.android.database.entity.SelfExperienceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
class DreamEpisodeConsolidatorTest {

    private val slices = mutableListOf<EpisodicSensingSliceEntity>()
    private val experiences = mutableListOf<SelfExperienceEntity>()

    private val fakeSliceDao = object : EpisodicSensingSliceDao {
        override suspend fun insert(slice: EpisodicSensingSliceEntity): Long {
            slices.add(slice)
            return slices.size.toLong()
        }
        override suspend fun insertAll(list: List<EpisodicSensingSliceEntity>) {
            slices.addAll(list)
        }
        override suspend fun getUnconsolidatedSlices(limit: Int): List<EpisodicSensingSliceEntity> {
            return slices.filter { !it.isConsolidated }.take(limit)
        }
        override suspend fun markAsConsolidated(ids: List<String>) {
            for (i in slices.indices) {
                if (slices[i].id in ids) {
                    slices[i] = slices[i].copy(isConsolidated = true)
                }
            }
        }
        override suspend fun getRecentSlices(sinceMs: Long, limit: Int): List<EpisodicSensingSliceEntity> {
            return slices.filter { it.timestampMs >= sinceMs }
        }
        override suspend fun getUnconsolidatedCount(): Int = slices.count { !it.isConsolidated }
        override suspend fun getTotalCount(): Int = slices.size
        override suspend fun deleteOldestConsolidated(count: Int): Int = 0
        override suspend fun deleteOldConsolidated(beforeMs: Long): Int {
            val toDelete = slices.filter { it.isConsolidated && it.timestampMs < beforeMs }
            slices.removeAll(toDelete)
            return toDelete.size
        }
    }

    private val fakeExperienceDao = object : SelfExperienceDao {
        override suspend fun insert(entity: SelfExperienceEntity): Long {
            experiences.add(entity)
            return experiences.size.toLong()
        }
        override suspend fun insertAll(entities: List<SelfExperienceEntity>) {
            experiences.addAll(entities)
        }
        override suspend fun update(entity: SelfExperienceEntity) {}
        override suspend fun delete(entity: SelfExperienceEntity) {}
        override suspend fun deleteById(id: String) {
            experiences.removeAll { it.id == id }
        }
        override suspend fun getById(id: String): SelfExperienceEntity? = experiences.find { it.id == id }
        override fun getRecentExperiences(limit: Int): kotlinx.coroutines.flow.Flow<List<SelfExperienceEntity>> {
            return kotlinx.coroutines.flow.flowOf(experiences.takeLast(limit))
        }
        override suspend fun getUnsharedExperiences(status: String, limit: Int): List<SelfExperienceEntity> {
            return experiences.filter { it.status == status }.take(limit)
        }
        override suspend fun getActiveExperiences(excludeStatus: String, limit: Int): List<SelfExperienceEntity> {
            return experiences.filter { it.status != excludeStatus }.take(limit)
        }
        override suspend fun markAsShared(id: String, sharedAt: Long, sharedStatus: String) {
            val idx = experiences.indexOfFirst { it.id == id }
            if (idx != -1) {
                experiences[idx] = experiences[idx].copy(status = sharedStatus, lastSharedAt = sharedAt)
            }
        }
        override suspend fun searchByDomain(domainTag: String): List<SelfExperienceEntity> {
            return experiences.filter { it.domainTag == domainTag }
        }
    }

    private lateinit var consolidator: DreamEpisodeConsolidator

    @Before
    fun setUp() {
        slices.clear()
        experiences.clear()
        consolidator = DreamEpisodeConsolidator(fakeSliceDao, fakeExperienceDao)
    }

    @Test
    fun testConsolidate_withNoSlices_returnsZero() = runBlocking {
        val count = consolidator.consolidateDailyEpisodes(1000000L)
        assertEquals(0, count)
        assertEquals(0, experiences.size)
    }

    @Test
    fun testConsolidate_withSlices_generatesTrackBExperience() = runBlocking {
        // 准备 2 条未消化切片
        slices.add(
            EpisodicSensingSliceEntity(
                id = "s-1",
                timestampMs = 1000000L,
                dwellDurationMs = 1800000L,
                appCategory = EpisodicSensingSliceEntity.CATEGORY_STUDY,
                primaryPackage = "com.study.app",
                summaryScene = "在学习应用专注停留约 30 分钟",
                isConsolidated = false
            )
        )
        slices.add(
            EpisodicSensingSliceEntity(
                id = "s-2",
                timestampMs = 1003600L,
                dwellDurationMs = 600000L,
                appCategory = EpisodicSensingSliceEntity.CATEGORY_ENTERTAINMENT,
                primaryPackage = "com.music.app",
                summaryScene = "戴上耳机听音乐",
                isConsolidated = false
            )
        )

        val count = consolidator.consolidateDailyEpisodes(1100000L, charName = "Lirica")
        assertEquals(2, count)
        assertEquals(1, experiences.size)

        val exp = experiences[0]
        assertEquals("今日陪伴与生活印记", exp.title)
        assertEquals("情境感知", exp.domainTag)
        assertTrue(exp.content.contains("学习或工作"))
        assertTrue(exp.content.contains("耳机"))

        // 验证已标记为已消化
        assertTrue(slices.all { it.isConsolidated })
    }
}