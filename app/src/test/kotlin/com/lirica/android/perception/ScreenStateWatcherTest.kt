package com.lirica.android.perception

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.LiricaApplication
import com.lirica.android.perception.model.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = LiricaApplication::class, sdk = [34])
class ScreenStateWatcherTest {

    private lateinit var application: LiricaApplication
    private lateinit var watcher: ScreenStateWatcher

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        watcher = ScreenStateWatcher()
    }

    @Test
    fun testInitialState() {
        val snapshot = watcher.screenState.value
        assertEquals(ScreenState.OFF, snapshot.state)
        assertEquals(0L, snapshot.totalInteractiveDurationMs)
        assertEquals(0L, snapshot.totalStandbyDurationMs)
    }

    @Test
    fun testScreenOnTransition() {
        val t0 = 1000L
        watcher.handleScreenAction(Intent.ACTION_SCREEN_ON, t0)

        val snapshot = watcher.screenState.value
        assertEquals(ScreenState.ON, snapshot.state)
        assertEquals(t0, snapshot.lastStateChangeTimestamp)
    }

    @Test
    fun testUserPresentTransition() {
        val t0 = 1000L
        val t1 = 2000L
        watcher.handleScreenAction(Intent.ACTION_SCREEN_ON, t0)
        watcher.handleScreenAction(Intent.ACTION_USER_PRESENT, t1)

        val snapshot = watcher.screenState.value
        assertEquals(ScreenState.UNLOCKED, snapshot.state)
        assertEquals(t1, snapshot.lastStateChangeTimestamp)
    }

    @Test
    fun testScreenCycleAndDurationAccumulation() {
        // Initial OFF at t = 1000
        val t0 = 1000L
        watcher.handleScreenAction(Intent.ACTION_SCREEN_OFF, t0)

        // Screen turned ON at t = 3000 (Standby duration 2000ms)
        val t1 = 3000L
        watcher.handleScreenAction(Intent.ACTION_SCREEN_ON, t1)
        assertEquals(ScreenState.ON, watcher.screenState.value.state)
        assertEquals(2000L, watcher.screenState.value.totalStandbyDurationMs)

        // User unlocked at t = 4000 (ON for 1000ms)
        val t2 = 4000L
        watcher.handleScreenAction(Intent.ACTION_USER_PRESENT, t2)
        assertEquals(ScreenState.UNLOCKED, watcher.screenState.value.state)

        // Screen turned OFF at t = 9000 (Interactive duration: t2 -> t3 is 5000ms + t1->t2 1000ms = 6000ms)
        val t3 = 9000L
        watcher.handleScreenAction(Intent.ACTION_SCREEN_OFF, t3)
        val snapshot = watcher.screenState.value
        assertEquals(ScreenState.OFF, snapshot.state)
        assertEquals(6000L, snapshot.totalInteractiveDurationMs)
        assertEquals(2000L, snapshot.totalStandbyDurationMs)
    }

    @Test
    fun testStartAndStopRegistration() {
        assertFalse(watcher.isWatching)
        watcher.start(application)
        assertTrue(watcher.isWatching)

        // Calling start again is idempotent
        watcher.start(application)
        assertTrue(watcher.isWatching)

        watcher.stop(application)
        assertFalse(watcher.isWatching)

        // Calling stop again is safe
        watcher.stop(application)
        assertFalse(watcher.isWatching)
    }
}
