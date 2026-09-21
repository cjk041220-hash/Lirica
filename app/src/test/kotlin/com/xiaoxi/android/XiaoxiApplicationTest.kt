package com.xiaoxi.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoxi.android.memory.consolidation.SubconsciousDreamWorker
import com.xiaoxi.android.proactivity.AutonomousLifeWorker
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class XiaoxiApplicationTest {

    private lateinit var app: XiaoxiApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        mockkObject(AutonomousLifeWorker)
        mockkObject(SubconsciousDreamWorker)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testRegisterAutonomousLife_enqueuesBothAutonomousLifeAndSubconsciousDreamWorkers() {
        app.registerAutonomousLife()

        verify(exactly = 1) {
            AutonomousLifeWorker.enqueue(app)
        }
        verify(exactly = 1) {
            SubconsciousDreamWorker.enqueue(app)
        }
    }
}
