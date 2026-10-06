package com.lirica.android.automation.intent

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntentActionDispatcherTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var dispatcher: IntentActionDispatcherImpl

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.lirica.android"
        // M5.1 P0-1：派发入口强制挂安全裁决引擎（生产装配同源），
        // 既有用例因此顺带覆盖"低危动作穿过闸门"这条主路径。
        dispatcher = IntentActionDispatcherImpl(
            context = context,
            safetyPolicy = com.lirica.android.automation.policy.SafetyPolicyEngineImpl(
                permissionProvider = com.lirica.android.automation.policy.AutomationPermissionProvider.ALL_GRANTED
            )
        )
    }

    @Test
    fun testCreateIntent_dial_validAndBlank() {
        val valid = dispatcher.createIntent(IntentAction.Dial("10086"))
        assertNotNull(valid)
        assertEquals(Intent.ACTION_DIAL, valid?.action)
        assertEquals(Uri.parse("tel:10086"), valid?.data)
        assertTrue((valid!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        val blank = dispatcher.createIntent(IntentAction.Dial("   "))
        assertNull(blank)
    }

    @Test
    fun testCreateIntent_sendSms_validAndBlank() {
        val valid = dispatcher.createIntent(IntentAction.SendSms("10086", "Hello Lirica"))
        assertNotNull(valid)
        assertEquals(Intent.ACTION_SENDTO, valid?.action)
        assertEquals(Uri.parse("smsto:10086"), valid?.data)
        assertEquals("Hello Lirica", valid?.getStringExtra("sms_body"))

        val blank = dispatcher.createIntent(IntentAction.SendSms("", "Hello"))
        assertNull(blank)
    }

    @Test
    fun testCreateIntent_sendEmail_validAndBlank() {
        val valid = dispatcher.createIntent(IntentAction.SendEmail("test@example.com", "Subject", "Body"))
        assertNotNull(valid)
        assertEquals(Intent.ACTION_SENDTO, valid?.action)
        assertEquals(Uri.parse("mailto:test@example.com"), valid?.data)
        assertEquals("Subject", valid?.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Body", valid?.getStringExtra(Intent.EXTRA_TEXT))

        val blank = dispatcher.createIntent(IntentAction.SendEmail(" "))
        assertNull(blank)
    }

    @Test
    fun testCreateIntent_setAlarm_validAndInvalid() {
        val valid = dispatcher.createIntent(IntentAction.SetAlarm(hour = 7, minutes = 30, label = "Wake up", skipUi = true))
        assertNotNull(valid)
        assertEquals(AlarmClock.ACTION_SET_ALARM, valid?.action)
        assertEquals(7, valid?.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, valid?.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Wake up", valid?.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(true, valid?.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))

        val invalidHour = dispatcher.createIntent(IntentAction.SetAlarm(hour = 25, minutes = 0))
        assertNull(invalidHour)

        val invalidMinutes = dispatcher.createIntent(IntentAction.SetAlarm(hour = 12, minutes = -1))
        assertNull(invalidMinutes)
    }

    @Test
    fun testCreateIntent_openMap_withAndWithoutCoordinates() {
        val withCoords = dispatcher.createIntent(IntentAction.OpenMap(query = "Cafe", latitude = 39.9, longitude = 116.4))
        assertNotNull(withCoords)
        assertEquals(Intent.ACTION_VIEW, withCoords?.action)
        assertEquals(Uri.parse("geo:39.9,116.4?q=Cafe"), withCoords?.data)

        val withoutCoords = dispatcher.createIntent(IntentAction.OpenMap(query = "Tiananmen Square"))
        assertNotNull(withoutCoords)
        assertEquals(Intent.ACTION_VIEW, withoutCoords?.action)
        assertEquals(Uri.parse("geo:0,0?q=Tiananmen%20Square"), withoutCoords?.data)
    }

    @Test
    fun testCreateIntent_openSettings_allTypes() {
        val wifi = dispatcher.createIntent(IntentAction.OpenSettings(SettingType.WIFI))
        assertEquals(Settings.ACTION_WIFI_SETTINGS, wifi?.action)

        val bt = dispatcher.createIntent(IntentAction.OpenSettings(SettingType.BLUETOOTH))
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, bt?.action)

        val acc = dispatcher.createIntent(IntentAction.OpenSettings(SettingType.ACCESSIBILITY))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, acc?.action)

        val gen = dispatcher.createIntent(IntentAction.OpenSettings(SettingType.GENERAL))
        assertEquals(Settings.ACTION_SETTINGS, gen?.action)

        val appDetails = dispatcher.createIntent(IntentAction.OpenSettings(SettingType.APP_DETAILS, "com.target.app"))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appDetails?.action)
        assertEquals(Uri.parse("package:com.target.app"), appDetails?.data)
    }

    @Test
    fun testCreateIntent_launchApp_installedAndFallback() {
        val installedIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        every { packageManager.getLaunchIntentForPackage("com.tencent.mm") } returns installedIntent

        val resInstalled = dispatcher.createIntent(IntentAction.LaunchApp("com.tencent.mm"))
        assertNotNull(resInstalled)

        every { packageManager.getLaunchIntentForPackage("com.unknown.app") } returns null
        val fallback = dispatcher.createIntent(IntentAction.LaunchApp("com.unknown.app", fallbackWebUri = "https://example.com"))
        assertNotNull(fallback)
        assertEquals(Intent.ACTION_VIEW, fallback?.action)
        assertEquals(Uri.parse("https://example.com"), fallback?.data)

        val noFallback = dispatcher.createIntent(IntentAction.LaunchApp("com.unknown.app", fallbackWebUri = null))
        assertNull(noFallback)
    }

    @Test
    fun testCreateIntent_shareText_withAndWithoutTargetPackage() {
        val plainShare = dispatcher.createIntent(IntentAction.ShareText("Hello world"))
        assertNotNull(plainShare)
        assertEquals(Intent.ACTION_SEND, plainShare?.action)
        assertEquals("text/plain", plainShare?.type)
        assertEquals("Hello world", plainShare?.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(plainShare?.`package`)

        val targetedShare = dispatcher.createIntent(IntentAction.ShareText("Hello wechat", targetPackage = "com.tencent.mm"))
        assertEquals("com.tencent.mm", targetedShare?.`package`)

        val blankShare = dispatcher.createIntent(IntentAction.ShareText("   "))
        assertNull(blankShare)
    }

    @Test
    fun testDispatch_success_resolvesAndStartsActivity() {
        val action = IntentAction.Dial("10086")
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                name = "com.android.phone.DialtactsActivity"
                packageName = "com.android.phone"
            }
        }
        every { packageManager.resolveActivity(any(), 0) } returns resolveInfo

        val result = dispatcher.dispatch(action)
        assertTrue(result is DispatchResult.Success)
        val success = result as DispatchResult.Success
        assertEquals("com.android.phone.DialtactsActivity", success.targetComponent)
        verify { context.startActivity(any()) }
    }

    @Test
    fun testDispatch_targetNotFound_whenNoActivityResolves() {
        val action = IntentAction.Dial("10086")
        every { packageManager.resolveActivity(any(), 0) } returns null

        val result = dispatcher.dispatch(action)
        assertTrue(result is DispatchResult.TargetNotFound)
        val notFound = result as DispatchResult.TargetNotFound
        assertTrue(notFound.reason.contains("No Activity found"))
    }

    @Test
    fun testDispatch_securityRejected_whenInvalidAction() {
        val invalidAction = IntentAction.Dial("")
        val result = dispatcher.dispatch(invalidAction)
        assertTrue(result is DispatchResult.SecurityRejected)
    }

    @Test
    fun testDispatch_securityRejected_whenSecurityExceptionThrown() {
        val action = IntentAction.Dial("10086")
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                name = "com.android.phone.DialtactsActivity"
            }
        }
        every { packageManager.resolveActivity(any(), 0) } returns resolveInfo
        every { context.startActivity(any()) } throws SecurityException("Permission denied")

        val result = dispatcher.dispatch(action)
        assertTrue(result is DispatchResult.SecurityRejected)
        assertTrue((result as DispatchResult.SecurityRejected).reason.contains("Permission denied"))
    }

    @Test
    fun testDispatch_executionError_whenUnexpectedException() {
        val action = IntentAction.Dial("10086")
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                name = "com.android.phone.DialtactsActivity"
            }
        }
        every { packageManager.resolveActivity(any(), 0) } returns resolveInfo
        every { context.startActivity(any()) } throws RuntimeException("System crashed")

        val result = dispatcher.dispatch(action)
        assertTrue(result is DispatchResult.ExecutionError)
        val error = result as DispatchResult.ExecutionError
        assertEquals("System crashed", error.throwable.message)
    }
}
