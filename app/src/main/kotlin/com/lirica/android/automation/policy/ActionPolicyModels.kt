package com.lirica.android.automation.policy

import android.provider.Settings
import com.lirica.android.automation.gatekeeper.GatekeeperDecision
import com.lirica.android.automation.gatekeeper.RiskDetails
import com.lirica.android.automation.gatekeeper.RiskLevel
import com.lirica.android.automation.intent.IntentAction

/**
 * 行动引擎安全裁决域模型（主方案5.0 §1.2 / §4.1 / §8）。
 *
 * 本文件是「风险等级 + 权限状态 + 闸门规则」三元组的类型底座：
 * 三元组是方案 §4.1 的安全通行契约，任何通道（Intent / 系统 API / GUI 手势）
 * 的动作在落地前都必须先被降维成 [SafeActionDescriptor] 再做裁决，
 * 杜绝"某条通道漏调闸门"的心智缺口（复审 R7）。
 */

/**
 * 双态执行模式（主方案5.0 §八）。
 *
 * - [SHARED] 共享模式：人机同屏，完整操控权限（受安全闸门管辖）；
 * - [ROAMING] 漫游模式：只读拦截，严禁关注/点赞/投币/评论/支付等有副作用交互，
 *   仅放行滑动翻页与只读检索。
 *
 * 模式属于**心智层**决策（用户显式切换或对话协商），但一旦确定即成为
 * 底层安全裁决的输入常量——模式本身不因人格参数浮动（§1.1 表达与安全解耦）。
 */
enum class ActionExecutionMode {
    SHARED,
    ROAMING
}

/** 动作落地通道。用于区分"同一语义动作从哪条物理通道出去"，便于审计与回归用例固化。 */
enum class ActionChannel {
    /** 标准 Intent / 系统 Activity 拉起 */
    INTENT,

    /** 进程内系统服务直调（CameraManager / AudioManager / ContentResolver / Settings） */
    SYSTEM_API,

    /** 无障碍语义树手势与文本注入 */
    UI_GESTURE
}

/**
 * 方案 §4.1 的风险分级词表（低危 / 中危 / 敏感 / 极高危）。
 *
 * 与 [RiskLevel] 保持一一映射而非直接复用：[ActionRiskGrade] 是"动作静态属性"
 * （写死在目录表里，编译期确定），[RiskLevel] 是"单次裁决结果的运行期结论"
 * （还要叠加当前包名、目标文案与用户态）。二者混用会让目录表失去可审计性。
 */
enum class ActionRiskGrade(val severity: Int) {
    /** 允许自动放行 */
    LOW(0),

    /** 需用户二次确认或权限引导 */
    MEDIUM(1),

    /** 敏感数据接触面：即使无副作用也需前台/焦点等运行期前提 */
    SENSITIVE(2),

    /** 零破坏不变量：硬件级物理熔断，任何通道都不放行 */
    CRITICAL(3);

    fun toRiskLevel(): RiskLevel = when (this) {
        LOW -> RiskLevel.SAFE
        MEDIUM -> RiskLevel.MEDIUM_RISK_AUDIT
        SENSITIVE -> RiskLevel.HIGH_RISK_CONFIRM
        CRITICAL -> RiskLevel.CRITICAL_BLOCK
    }
}

/**
 * 行动模块所需的系统权限抽象（复审 R1/R2/R6 修正后的口径）。
 *
 * 铁律：**只有真正需要声明的权限才出现在这里**。
 * - 手电筒 CameraManager.setTorchMode 自 API 23 起**无需任何权限**（R1），
 *   因此 [SafeActionDescriptor.permission] 为 null，严禁因此往 Manifest 里塞 CAMERA；
 * - ACTION_SENDTO（smsto:/mailto: 预填草稿）**不需要 SEND_SMS**（R2），
 *   真发通道才需要，本工程不走 SmsManager 直发。
 */
enum class AutomationPermission(
    /** Manifest 权限名；null 表示无需声明 */
    val manifestPermission: String?,
    /** 是否属于运行时危险权限（需动态申请弹窗） */
    val isRuntimeDangerous: Boolean,
    /** 是否属于特殊权限（需跳系统设置页手动授权） */
    val isSpecialSettingsGrant: Boolean,
    /** 特殊权限的引导落地页 Action；运行时危险权限为 null（走 requestPermissions） */
    val settingsAction: String?
) {
    WRITE_CALENDAR(
        manifestPermission = "android.permission.WRITE_CALENDAR",
        isRuntimeDangerous = true,
        isSpecialSettingsGrant = false,
        settingsAction = null
    ),
    WRITE_SETTINGS(
        manifestPermission = "android.permission.WRITE_SETTINGS",
        isRuntimeDangerous = false,
        isSpecialSettingsGrant = true,
        settingsAction = Settings.ACTION_MANAGE_WRITE_SETTINGS
    );

    companion object {
        fun fromManifestPermission(name: String): AutomationPermission? =
            entries.firstOrNull { it.manifestPermission == name }
    }
}

/**
 * 运行期权限状态提供者。
 *
 * 之所以抽象成函数式接口而不是直接依赖 Context：安全裁决服务必须能在纯 JVM
 * 单测里被穷举（闸门拦截率 DoD 要求 100.0%，回归矩阵不能依赖真机权限态）。
 */
fun interface AutomationPermissionProvider {
    fun isGranted(permission: AutomationPermission): Boolean

    companion object {
        /** 默认全授予：仅在单测与不支持权限模型的宿主中使用 */
        val ALL_GRANTED = AutomationPermissionProvider { true }

        /** 默认全拒绝：fail-closed 语义，未注入 provider 时宁可少做也不越权 */
        val ALL_DENIED = AutomationPermissionProvider { false }
    }
}

/**
 * 动作静态安全描述（三元组的完整表达）。
 */
data class SafeActionDescriptor(
    /** 动作标识（审计与回归用例的稳定主键） */
    val actionId: String,
    val channel: ActionChannel,
    val riskGrade: ActionRiskGrade,
    /** 所需权限；null 表示无需任何权限 */
    val permission: AutomationPermission? = null,
    /** 命中的闸门规则 ID（DoD 第 2 条"固化回归用例"的锚点） */
    val gateRule: String,
    /** 是否必须显式用户二次确认后才可落地 */
    val requiresUserConfirmation: Boolean,
    /** 是否允许自动放行（低危且无前置条件） */
    val autoPass: Boolean,
    /** 被拦截时向用户坦白的人话理由 */
    val honestReason: String = DEFAULT_HONEST_REASON,
    /** 说明性备注（写清事实依据，避免后人照错误文档改回来） */
    val note: String = ""
) {
    init {
        require(actionId.isNotBlank()) { "actionId 不得为空：它是闸门回归用例的锚点" }
        require(!(requiresUserConfirmation && autoPass)) {
            "动作 $actionId 不能同时要求二次确认与自动放行"
        }
    }

    companion object {
        const val DEFAULT_HONEST_REASON = "这个操作超出我能替你做的范围，我停在这里了"
    }
}

/**
 * 裁决上下文：与动作静态属性正交的运行期事实。
 */
data class PolicyContext(
    val mode: ActionExecutionMode = ActionExecutionMode.SHARED,
    /** 当前前台包名 */
    val currentPackage: String = "",
    /** 漫游模式下是否已获得用户对"本次任务"的临时作用域提升 */
    val hasScopeElevation: Boolean = false
)

/**
 * 裁决请求：统一入口的三种形态（复审 R7 的"单一裁决服务"要求）。
 */
sealed interface SafetyRequest {
    /** GUI 通道交互动作 */
    data class UiInteraction(
        val actionType: String,
        val targetText: String? = null,
        val targetResourceId: String? = null,
        val isPasswordTarget: Boolean = false,
        val currentPackage: String = ""
    ) : SafetyRequest

    /** Intent 通道派发 */
    data class IntentDispatch(val action: IntentAction) : SafetyRequest

    /** 剪贴板读取（Android 10+ 现实边界） */
    data class ClipboardRead(
        val hasWindowFocus: Boolean,
        val isForeground: Boolean,
        val isUserInitiatedShare: Boolean
    ) : SafetyRequest
}

/**
 * 裁决结论。
 */
sealed interface SafetyDecision {
    /** 放行 */
    data class Allowed(
        val descriptor: SafeActionDescriptor,
        val riskLevel: RiskLevel
    ) : SafetyDecision

    /** 需用户二次确认（返回可核销的令牌） */
    data class RequireConfirmation(
        val token: String,
        val descriptor: SafeActionDescriptor,
        val promptMessage: String,
        val details: RiskDetails
    ) : SafetyDecision

    /** 物理熔断（任何通道都不放行） */
    data class Blocked(
        val rule: String,
        val reason: String,
        val honestReason: String = SafeActionDescriptor.DEFAULT_HONEST_REASON,
        val descriptor: SafeActionDescriptor? = null,
        /** 缺权限时的引导落地页 */
        val permissionToRequest: AutomationPermission? = null
    ) : SafetyDecision

    companion object {
        fun fromGatekeeper(decision: GatekeeperDecision, descriptor: SafeActionDescriptor): SafetyDecision =
            when (decision) {
                is GatekeeperDecision.Allowed -> Allowed(descriptor, decision.riskLevel)
                is GatekeeperDecision.RequireUserConfirmation -> RequireConfirmation(
                    token = decision.token,
                    descriptor = descriptor,
                    promptMessage = decision.promptMessage,
                    details = decision.details
                )
                is GatekeeperDecision.PhysicalBlocked -> Blocked(
                    rule = decision.rule,
                    reason = decision.reason,
                    honestReason = decision.honestReason,
                    descriptor = descriptor
                )
            }
    }
}

/**
 * 已核销的动作授权凭证（Intent 通道二次确认后签发）。
 *
 * 凭证是**动作绑定**的：token 只能核销它所确认的那一个 actionId，
 * 防止"确认了发短信、却被用来转账"的凭证串用。
 */
data class SafetyGrant(val token: String, val actionId: String)
