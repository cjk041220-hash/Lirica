package com.lirica.android.proactivity

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.LiricaApplication
import io.mockk.spyk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScheduleWakeReceiverTest {

    @Test
    fun testScheduleWakeReceiver_onReceive_invokesGoAsync() {
        val context = ApplicationProvider.getApplicationContext<LiricaApplication>()
        val receiver = spyk(ScheduleWakeReceiver())

        val intent = Intent(AndroidSystemAlarmScheduler.ACTION_SCHEDULE_WAKE).apply {
            putExtra(AndroidSystemAlarmScheduler.EXTRA_ALARM_ID, "alarm_123")
        }

        receiver.onReceive(context, intent)

        verify(exactly = 1) {
            receiver.goAsync()
        }
    }
}
