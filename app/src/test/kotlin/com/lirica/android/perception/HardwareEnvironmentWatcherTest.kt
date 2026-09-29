package com.lirica.android.perception

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.perception.model.HardwareEnvironmentState
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HardwareEnvironmentWatcherTest {

    private lateinit var context: Context
    private lateinit var watcher: HardwareEnvironmentWatcher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        watcher = HardwareEnvironmentWatcher()
    }

    @Test
    fun testInitialState_isNotNull() {
        watcher.start(context)
        val state = watcher.hardwareState.value
        assertNotNull(state)
        watcher.stop(context)
    }

    @Test
    fun testUpdateState_updatesFlow() {
        val newState = HardwareEnvironmentState(
            isPhoneInCall = true,
            isHeadsetPlugged = true,
            isCharging = true,
            isAudioPlaying = false
        )
        watcher.updateStateForTest(newState)
        val current = watcher.hardwareState.value
        org.junit.Assert.assertTrue(current.isPhoneInCall)
        org.junit.Assert.assertTrue(current.isHeadsetPlugged)
        org.junit.Assert.assertTrue(current.isCharging)
    }
}
