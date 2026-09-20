package com.xiaoxi.android.automation.intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings

interface IntentActionDispatcher {
    fun createIntent(action: IntentAction): Intent?
    fun dispatch(action: IntentAction): DispatchResult
}

class IntentActionDispatcherImpl(
    private val context: Context
) : IntentActionDispatcher {

    override fun createIntent(action: IntentAction): Intent? {
        val intent = when (action) {
            is IntentAction.Dial -> {
                if (action.phoneNumber.isBlank()) return null
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.phoneNumber.trim()}"))
            }
            is IntentAction.SendSms -> {
                if (action.phoneNumber.isBlank()) return null
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${action.phoneNumber.trim()}")).apply {
                    putExtra("sms_body", action.message)
                }
            }
            is IntentAction.SendEmail -> {
                if (action.recipient.isBlank()) return null
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${action.recipient.trim()}")).apply {
                    action.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    action.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }
            }
            is IntentAction.SetAlarm -> {
                if (action.hour !in 0..23 || action.minutes !in 0..59) return null
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, action.minutes)
                    putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, action.skipUi)
                }
            }
            is IntentAction.OpenMap -> {
                val uriString = if (action.latitude != null && action.longitude != null) {
                    "geo:${action.latitude},${action.longitude}?q=${Uri.encode(action.query)}"
                } else {
                    "geo:0,0?q=${Uri.encode(action.query)}"
                }
                Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
            }
            is IntentAction.OpenSettings -> {
                when (action.settingType) {
                    SettingType.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
                    SettingType.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    SettingType.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    SettingType.GENERAL -> Intent(Settings.ACTION_SETTINGS)
                    SettingType.APP_DETAILS -> {
                        val pkg = action.packageName ?: context.packageName
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", pkg, null)
                        }
                    }
                }
            }
            is IntentAction.LaunchApp -> {
                val launchIntent = context.packageManager?.getLaunchIntentForPackage(action.packageName)
                if (launchIntent != null) {
                    launchIntent
                } else if (!action.fallbackWebUri.isNullOrBlank()) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(action.fallbackWebUri))
                } else {
                    return null
                }
            }
            is IntentAction.ShareText -> {
                if (action.text.isBlank()) return null
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, action.text)
                    action.targetPackage?.let { setPackage(it) }
                }
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    override fun dispatch(action: IntentAction): DispatchResult {
        val intent = createIntent(action) ?: return DispatchResult.SecurityRejected("Invalid parameters for action")

        val pm = context.packageManager
        val resolved = pm?.resolveActivity(intent, 0)
        if (resolved == null) {
            return DispatchResult.TargetNotFound("No Activity found to handle action: ${action::class.simpleName}")
        }

        return try {
            context.startActivity(intent)
            DispatchResult.Success(intent, resolved.activityInfo?.name)
        } catch (e: SecurityException) {
            DispatchResult.SecurityRejected("Security exception starting activity: ${e.message}")
        } catch (t: Throwable) {
            DispatchResult.ExecutionError(t)
        }
    }
}
