package com.xiaoxi.android.common.permissions

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
    fun testCheckAll_returnsAllSixPermissions() {
        val states = PermissionManager.checkAll(context)
        assertEquals(6, states.size)

        val types = states.map { it.type }.toSet()
        assertTrue(types.contains(PermissionType.ACCESSIBILITY))
        assertTrue(types.contains(PermissionType.OVERLAY))
        assertTrue(types.contains(PermissionType.NOTIFICATION_LISTENER))
        assertTrue(types.contains(PermissionType.USAGE_STATS))
        assertTrue(types.contains(PermissionType.BATTERY_OPTIMIZATION))
        assertTrue(types.contains(PermissionType.SHIZUKU))
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
