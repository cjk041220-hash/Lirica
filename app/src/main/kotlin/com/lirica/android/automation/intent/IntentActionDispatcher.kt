package com.lirica.android.automation.intent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import com.lirica.android.automation.policy.SafeActionDescriptor
import com.lirica.android.automation.policy.SafetyDecision
import com.lirica.android.automation.policy.SafetyGrant
import com.lirica.android.automation.policy.SafetyPolicyEngine
import com.lirica.android.automation.policy.SafetyRequest
import com.lirica.android.automation.policy.SystemActionCatalog

interface IntentActionDispatcher {
    /**
     * 生成待派发 Intent。
     *
     * 对系统服务直调型动作（手电筒/音量/亮度/日程）返回 null——
     * 它们由 [SystemActionExecutor] 落地，不属于 Intent 语义。
     */
    fun createIntent(action: IntentAction): Intent?

    /**
     * 派发动作。
     *
     * P0-1 铁律：安全裁决在**本方法入口**强制执行，任何绕过本方法的直接
     * startActivity 都视为架构违规。非幂等外部通信需先经 [SafetyGrant] 核销。
     */
    fun dispatch(action: IntentAction, grant: SafetyGrant? = null): DispatchResult
}

/**
 * 默认派发实现（M5.1 双通道分流 + 安全收口）。
 *
 * 分流规则：
 * 1. Intent 型（拨号/短信/邮件/分享/闹钟/地图/设置/拉起应用）→ createIntent + startActivity；
 * 2. 系统服务型（手电筒/音量/亮度/日程）→ [SystemActionExecutor] 进程内直调；
 * 3. 两条分流都在裁决之后，且共用同一份 [SystemActionCatalog] 三元组。
 */
class IntentActionDispatcherImpl(
    private val context: Context,
    /**
     * 统一安全裁决引擎。
     *
     * 刻意**不给默认值**：给一个放行默认值就等于给未来某次装配失误留了后门，
     * 而漏注入会在编译期立刻暴露（单测直接注入真实引擎 + ALL_GRANTED 权限）。
     */
    private val safetyPolicy: SafetyPolicyEngine,
    private val systemActionExecutor: SystemActionExecutor = AndroidSystemActionExecutor(context)
) : IntentActionDispatcher {

    override fun createIntent(action: IntentAction): Intent? {
        val intent = when (action) {
            is IntentAction.Dial -> {
                if (action.phoneNumber.isBlank()) return null
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + action.phoneNumber.trim()))
            }
            is IntentAction.SendSms -> {
                if (action.phoneNumber.isBlank()) return null
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + action.phoneNumber.trim())).apply {
                    putExtra("sms_body", action.message)
                }
            }
            is IntentAction.SendEmail -> {
                if (action.recipient.isBlank()) return null
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + action.recipient.trim())).apply {
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
                    "geo:" + action.latitude + "," + action.longitude + "?q=" + Uri.encode(action.query)
                } else {
                    "geo:0,0?q=" + Uri.encode(action.query)
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
            // 系统服务直调型：不产生 Intent（由 SystemActionExecutor 落地）
            is IntentAction.ToggleFlashlight,
            is IntentAction.AdjustVolume,
            is IntentAction.AdjustBrightness,
            is IntentAction.AddCalendarEvent -> return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    override fun dispatch(action: IntentAction, grant: SafetyGrant?): DispatchResult {
        val descriptor = SystemActionCatalog.describe(action)
            ?: return DispatchResult.SecurityRejected(
                "动作 " + action::class.simpleName + " 未登记在系统动作安全目录，按 fail-closed 拒绝"
            )

        when (val decision = safetyPolicy.evaluate(SafetyRequest.IntentDispatch(action))) {
            is SafetyDecision.Blocked -> {
                val permission = decision.permissionToRequest
                return if (permission != null) {
                    DispatchResult.PermissionRequired(
                        permissionName = permission.manifestPermission ?: permission.name,
                        reason = decision.honestReason,
                        settingsAction = permission.settingsAction
                    )
                } else {
                    DispatchResult.SecurityRejected(decision.reason + " (" + decision.rule + ")")
                }
            }
            is SafetyDecision.RequireConfirmation -> {
                val authorized = grant != null &&
                    grant.actionId == descriptor.actionId &&
                    safetyPolicy.consumeGrant(grant.token, descriptor.actionId)
                if (!authorized) {
                    return DispatchResult.ConfirmationRequired(
                        token = decision.token,
                        promptMessage = decision.promptMessage
                    )
                }
            }
            is SafetyDecision.Allowed -> Unit
        }

        return when (action) {
            is IntentAction.ToggleFlashlight ->
                mapSystemResult(systemActionExecutor.toggleFlashlight(action.enabled))
            is IntentAction.AdjustVolume ->
                mapSystemResult(systemActionExecutor.adjustVolume(action.stream, action.deltaSteps))
            is IntentAction.AdjustBrightness ->
                mapSystemResult(systemActionExecutor.adjustBrightness(action.percent))
            is IntentAction.AddCalendarEvent ->
                mapSystemResult(systemActionExecutor.insertCalendarEvent(action))
            else -> dispatchViaActivity(action)
        }
    }

    private fun dispatchViaActivity(action: IntentAction): DispatchResult {
        val intent = createIntent(action)
            ?: return DispatchResult.SecurityRejected("Invalid parameters for action")

        val pm = context.packageManager
        val resolved = pm?.resolveActivity(intent, 0)
        if (resolved == null) {
            return DispatchResult.TargetNotFound("No Activity found to handle action: " + action::class.simpleName)
        }

        return try {
            context.startActivity(intent)
            DispatchResult.Success(intent, resolved.activityInfo?.name)
        } catch (e: SecurityException) {
            DispatchResult.SecurityRejected("Security exception starting activity: " + e.message)
        } catch (t: Throwable) {
            DispatchResult.ExecutionError(t)
        }
    }

    private fun mapSystemResult(result: SystemActionResult): DispatchResult = when (result) {
        is SystemActionResult.Success -> DispatchResult.Success(Intent(), null)
        is SystemActionResult.PermissionMissing -> DispatchResult.PermissionRequired(
            permissionName = result.permission.manifestPermission ?: result.permission.name,
            reason = result.reason,
            settingsAction = result.permission.settingsAction
        )
        is SystemActionResult.NotSupported -> DispatchResult.TargetNotFound(result.reason)
        is SystemActionResult.Failed -> DispatchResult.SecurityRejected(result.reason)
        is SystemActionResult.ExecutionError -> DispatchResult.ExecutionError(result.throwable)
    }

    /** 供装配测试断言真实引擎已注入 */
    fun safetyEngineForTest(): SafetyPolicyEngine = safetyPolicy

    /** 供装配测试取用描述符（保持目录表可达） */
    fun descriptorOf(action: IntentAction): SafeActionDescriptor? = SystemActionCatalog.describe(action)
}
