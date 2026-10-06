package com.lirica.android.common.settings

import androidx.test.core.app.ApplicationProvider
import com.lirica.android.automation.policy.ActionExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 双态行动模式持久化回归（M5.1 §八）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ActionModeSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun testDefaultIsSharedMode() {
        // 默认必须是共享模式：若默认漫游，用户会以为"她坏了"，而实际是权限被自己收紧
        assertEquals(ActionExecutionMode.SHARED, ActionModeSettings.create(context).load())
    }

    @Test
    fun testSaveAndLoadRoundTrip() {
        val settings = ActionModeSettings.create(context)
        settings.save(ActionExecutionMode.ROAMING)
        assertEquals(ActionExecutionMode.ROAMING, ActionModeSettings.create(context).load())
        settings.save(ActionExecutionMode.SHARED)
        assertEquals(ActionExecutionMode.SHARED, ActionModeSettings.create(context).load())
    }

    @Test
    fun testCorruptedValueFallsBackToShared() {
        context.getSharedPreferences(ActionModeSettings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(ActionModeSettings.KEY_MODE, "NOT_A_REAL_MODE")
            .commit()
        assertEquals(
            "脏数据必须回退到默认值而不是抛异常（否则应用启动即崩）",
            ActionExecutionMode.SHARED,
            ActionModeSettings.create(context).load()
        )
    }
}
