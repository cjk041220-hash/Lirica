package com.lirica.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.memory.consolidation.SubconsciousDreamWorker
import com.lirica.android.proactivity.AutonomousLifeWorker
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
class LiricaApplicationTest {

    private lateinit var app: LiricaApplication

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
