package com.lirica.android.common.permissions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PermissionManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testCheckAll_returnsAllPermissionsIncludingM51Additions() {
        val states = PermissionManager.checkAll(context)
        // M5.1（复审 R6）：在原有六项体检基础上补入日历与修改系统设置两项。
        // 断言总数而非"包含"，是为了防止后人误删体检项而测试仍绿。
        assertEquals(PermissionType.entries.size, states.size)
        assertEquals(8, states.size)

        val types = states.map { it.type }.toSet()
        assertTrue(types.contains(PermissionType.ACCESSIBILITY))
        assertTrue(types.contains(PermissionType.OVERLAY))
        assertTrue(types.contains(PermissionType.NOTIFICATION_LISTENER))
        assertTrue(types.contains(PermissionType.USAGE_STATS))
        assertTrue(types.contains(PermissionType.BATTERY_OPTIMIZATION))
        assertTrue(types.contains(PermissionType.SHIZUKU))
        // M5.1 新增两项：原生 API 动作（日程 / 亮度）的前置授权入口
        assertTrue(types.contains(PermissionType.CALENDAR))
        assertTrue(types.contains(PermissionType.WRITE_SETTINGS))
    }

    @Test
    fun testAutomationPermissionProvider_mapsPolicyPermissionsToHealthCheck() {
        // 裁决引擎要求的权限状态必须与体检结果同源，否则会出现
        // "设置页显示已授权、动作派发时说没权限"的自相矛盾
        val provider = PermissionManager.automationPermissionProvider(context)
        assertEquals(
            PermissionManager.checkPermission(context, PermissionType.CALENDAR),
            provider.isGranted(com.lirica.android.automation.policy.AutomationPermission.WRITE_CALENDAR)
        )
        assertEquals(
            PermissionManager.checkPermission(context, PermissionType.WRITE_SETTINGS),
            provider.isGranted(com.lirica.android.automation.policy.AutomationPermission.WRITE_SETTINGS)
        )
    }

    @Test
    fun testCreateIntentForPermission_returnsValidIntents() {
        for (type in PermissionType.values()) {
            val intent = PermissionManager.createIntentForPermission(context, type)
            assertNotNull("Intent for $type must not be null", intent)
            assertNotNull("Action for $type must not be null", intent.action)
        }
    }
}
