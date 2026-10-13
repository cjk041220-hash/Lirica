package com.lirica.android.automation.policy

import com.lirica.android.automation.intent.IntentAction

/**
 * 闸门规则 ID 常量表（DoD 第 2 条「固化回归用例」的锚点）。
 *
 * 规则 ID 一旦发布即为契约：回归用例按 ID 断言，改语义必须改 ID，
 * 杜绝"规则悄悄放宽而测试仍绿"的静默退化。
 */
object SafetyRuleIds {
    /** 低危动作自动放行 */
    const val LOW_RISK_AUTO_PASS = "RULE_LOW_RISK_AUTO_PASS"

    /** 拨号盘仅预填、绝不自动呼出 */
    const val DIAL_PREFILL_ONLY = "RULE_DIAL_PREFILL_ONLY"

    /** 预填草稿类外部通信（smsto:/mailto:）必须用户二次确认正文 */
    const val PREFILL_DRAFT_REQUIRES_CONFIRM = "RULE_PREFILL_DRAFT_REQUIRES_CONFIRM"

    /** 非幂等外部通信（分享/提交）必须用户二次确认 */
    const val NON_IDEMPOTENT_COMMS_CONFIRM = "RULE_NON_IDEMPOTENT_COMMS_CONFIRM"

    /** 手电筒无需权限（R1 事实修正） */
    const val FLASHLIGHT_NO_PERMISSION = "RULE_FLASHLIGHT_NO_PERMISSION"

    /** 亮度写入属特殊权限，需引导系统设置页授权 */
    const val WRITE_SETTINGS_GUIDED = "RULE_WRITE_SETTINGS_GUIDED"

    /** 剪贴板仅限前台/有焦点读取（Android 10+ 现实边界） */
    const val CLIPBOARD_FOREGROUND_ONLY = "RULE_CLIPBOARD_FOREGROUND_ONLY"

    /** 运行时权限缺失（日历等）：引导授权，不静默失败 */
    const val RUNTIME_PERMISSION_REQUIRED = "RULE_RUNTIME_PERMISSION_REQUIRED"

    /** 漫游模式只读拦截 */
    const val ROAMING_READ_ONLY_BLOCKED = "RULE_ROAMING_READ_ONLY_BLOCKED"

    /** 极高危动作在 Intent 通道的物理熔断 */
    const val INTENT_CRITICAL_BLOCKED = "RULE_INTENT_CRITICAL_BLOCKED"

    /** 键事件确认类按键（ENTER/DPAD_CENTER）的叠加闸门（M6.0 P0-1） */
    const val KEY_EVENT_CONFIRMATION_GATE = "RULE_KEY_EVENT_CONFIRMATION_GATE"

    /** 未知动作默认拒绝（fail-closed） */
    const val UNKNOWN_ACTION_DENIED = "RULE_UNKNOWN_ACTION_DENIED"
}

/**
 * 系统动作安全目录（主方案5.0 §4.1 三元组表的唯一代码事实来源）。
 *
 * 本表按复审报告 R1/R2/R3 修正了三处文档事实错误：
 * 1. **R1**：ToggleFlashlightAction 无需 CAMERA 权限（CameraManager.setTorchMode 自
 *    API 23 起免权限）；照抄旧表会往 Manifest 塞危险权限、触发运行时弹窗并被系统
 *    判定为占用相机。此处 permission = null，并保留设备相机被占用时调用返回 false 的失败分支。
 * 2. **R2**：SendSms 走 ACTION_SENDTO 预填草稿（打开短信 App、预填正文、用户手按发送），
 *    **不需要 SEND_SMS 权限**；同时按 §1.2 不变量精确化口径——"不经用户确认的自动发送"
 *    才物理熔断，"预填草稿类"降为中危 + 二次确认正文。
 * 3. **R3**：SendEmail 补齐同策略行。
 */
object SystemActionCatalog {

    const val ID_SET_ALARM = "SET_ALARM"
    const val ID_ADD_CALENDAR_EVENT = "ADD_CALENDAR_EVENT"
    const val ID_DIAL = "DIAL"
    const val ID_SEND_SMS = "SEND_SMS"
    const val ID_SEND_EMAIL = "SEND_EMAIL"
    const val ID_SHARE_TEXT = "SHARE_TEXT"
    const val ID_OPEN_MAP = "OPEN_MAP"
    const val ID_OPEN_SETTINGS = "OPEN_SETTINGS"
    const val ID_LAUNCH_APP = "LAUNCH_APP"
    const val ID_TOGGLE_FLASHLIGHT = "TOGGLE_FLASHLIGHT"
    const val ID_ADJUST_VOLUME = "ADJUST_VOLUME"
    const val ID_ADJUST_BRIGHTNESS = "ADJUST_BRIGHTNESS"
    const val ID_READ_CLIPBOARD = "READ_CLIPBOARD"

    const val ID_UI_CLICK = "UI_CLICK"
    const val ID_UI_LONG_CLICK = "UI_LONG_CLICK"
    const val ID_UI_INPUT_TEXT = "UI_INPUT_TEXT"
    const val ID_UI_SWIPE = "UI_SWIPE"
    const val ID_UI_GLOBAL_BACK = "UI_GLOBAL_BACK"
    const val ID_UI_SCREENSHOT = "UI_SCREENSHOT"
    const val ID_UI_KEY_EVENT = "UI_KEY_EVENT"

    private val LOW_PASS = SafeActionDescriptor(
        actionId = ID_SET_ALARM,
        channel = ActionChannel.INTENT,
        riskGrade = ActionRiskGrade.LOW,
        permission = null,
        gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
        requiresUserConfirmation = false,
        autoPass = true,
        note = "AlarmClock.ACTION_SET_ALARM 仅写入系统闹钟，可逆且无外部副作用"
    )

    private val DESCRIPTORS: Map<String, SafeActionDescriptor> = listOf(
        LOW_PASS,

        SafeActionDescriptor(
            actionId = ID_ADD_CALENDAR_EVENT,
            channel = ActionChannel.SYSTEM_API,
            riskGrade = ActionRiskGrade.LOW,
            permission = AutomationPermission.WRITE_CALENDAR,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "ContentResolver 插入日程；WRITE_CALENDAR 为运行时危险权限（R6 已纳入体检）"
        ),

        SafeActionDescriptor(
            actionId = ID_DIAL,
            channel = ActionChannel.INTENT,
            riskGrade = ActionRiskGrade.LOW,
            permission = null,
            gateRule = SafetyRuleIds.DIAL_PREFILL_ONLY,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "Intent.ACTION_DIAL 只拉起拨号盘预填号码，绝不自动呼出（CALL_PHONE 全程不申请）"
        ),

        SafeActionDescriptor(
            actionId = ID_SEND_SMS,
            channel = ActionChannel.INTENT,
            riskGrade = ActionRiskGrade.MEDIUM,
            // R2：ACTION_SENDTO 预填草稿不需要 SEND_SMS；真发通道（SmsManager）本工程不使用
            permission = null,
            gateRule = SafetyRuleIds.PREFILL_DRAFT_REQUIRES_CONFIRM,
            requiresUserConfirmation = true,
            autoPass = false,
            note = "R2 修正：SENDTO 预填形态无权限需求，但正文必须经用户二次确认后才可预填"
        ),

        SafeActionDescriptor(
            actionId = ID_SEND_EMAIL,
            channel = ActionChannel.INTENT,
            riskGrade = ActionRiskGrade.MEDIUM,
            permission = null,
            gateRule = SafetyRuleIds.PREFILL_DRAFT_REQUIRES_CONFIRM,
            requiresUserConfirmation = true,
            autoPass = false,
            note = "R3 补齐：mailto: SENDTO 与短信同策略（预填草稿 + 二次确认）"
        ),

        SafeActionDescriptor(
            actionId = ID_SHARE_TEXT,
            channel = ActionChannel.INTENT,
            riskGrade = ActionRiskGrade.MEDIUM,
            permission = null,
            gateRule = SafetyRuleIds.NON_IDEMPOTENT_COMMS_CONFIRM,
            requiresUserConfirmation = true,
            autoPass = false,
            note = "ACTION_SEND 分享面板属非幂等外部通信入口，正文与目标均须用户确认"
        ),

        SafeActionDescriptor(
            actionId = ID_OPEN_MAP,
            channel = ActionChannel.INTENT,
            riskGrade = ActionRiskGrade.LOW,
            permission = null,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "geo: 仅拉起地图应用定位展示，不触发导航与打车下单"
        ),

        SafeActionDescriptor(
            actionId = ID_OPEN_SETTINGS,
            channel = ActionChannel.INTENT,
            riskGrade = ActionRiskGrade.LOW,
            permission = null,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "系统设置页跳转（含无障碍引导），不代用户改变任何开关"
        ),

        SafeActionDescriptor(
            actionId = ID_LAUNCH_APP,
            channel = ActionChannel.INTENT,
            riskGrade = ActionRiskGrade.LOW,
            permission = null,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "前台应用拉起；金融/支付包名本身可拉起，进入后的自动化交互由 UI 闸门 (RULE_BANK_APP_PAYMENT_FUSE) 与采集中断 (PRIVACY_BLACKOUT) 双重物理熔断兜底"
        ),

        SafeActionDescriptor(
            actionId = ID_TOGGLE_FLASHLIGHT,
            channel = ActionChannel.SYSTEM_API,
            riskGrade = ActionRiskGrade.LOW,
            // R1：setTorchMode 免权限；此处绝不引入 CAMERA
            permission = null,
            gateRule = SafetyRuleIds.FLASHLIGHT_NO_PERMISSION,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "R1 修正：API 23+ 免权限；相机被占用时 setTorchMode 返回 false，按失败分支诚实上报"
        ),

        SafeActionDescriptor(
            actionId = ID_ADJUST_VOLUME,
            channel = ActionChannel.SYSTEM_API,
            riskGrade = ActionRiskGrade.LOW,
            permission = null,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "AudioManager.adjustStreamVolume 走系统音量 UI 抑制标记，不申请 MODIFY_AUDIO_SETTINGS 之外的能力"
        ),

        SafeActionDescriptor(
            actionId = ID_ADJUST_BRIGHTNESS,
            channel = ActionChannel.SYSTEM_API,
            riskGrade = ActionRiskGrade.MEDIUM,
            permission = AutomationPermission.WRITE_SETTINGS,
            gateRule = SafetyRuleIds.WRITE_SETTINGS_GUIDED,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "Settings.System.putInt 需 WRITE_SETTINGS 特殊权限：缺失时引导系统设置页，不静默吞掉"
        ),

        SafeActionDescriptor(
            actionId = ID_READ_CLIPBOARD,
            channel = ActionChannel.SYSTEM_API,
            riskGrade = ActionRiskGrade.SENSITIVE,
            permission = null,
            gateRule = SafetyRuleIds.CLIPBOARD_FOREGROUND_ONLY,
            requiresUserConfirmation = false,
            autoPass = false,
            note = "Android 10+ 禁止后台静默读剪贴板：仅前台 + 悬浮窗获焦瞬间或用户主动分享时合法"
        ),

        SafeActionDescriptor(
            actionId = ID_UI_CLICK,
            channel = ActionChannel.UI_GESTURE,
            riskGrade = ActionRiskGrade.LOW,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "点击风险由 FinancialGatekeeper 按目标文案与包名二次裁定"
        ),

        SafeActionDescriptor(
            actionId = ID_UI_LONG_CLICK,
            channel = ActionChannel.UI_GESTURE,
            riskGrade = ActionRiskGrade.LOW,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true
        ),

        SafeActionDescriptor(
            actionId = ID_UI_INPUT_TEXT,
            channel = ActionChannel.UI_GESTURE,
            riskGrade = ActionRiskGrade.LOW,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true,
            note = "文本输入风险由 FinancialGatekeeper 的密码框/通信代发零容忍规则二次裁定"
        ),

        SafeActionDescriptor(
            actionId = ID_UI_SWIPE,
            channel = ActionChannel.UI_GESTURE,
            riskGrade = ActionRiskGrade.LOW,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true
        ),

        SafeActionDescriptor(
            actionId = ID_UI_GLOBAL_BACK,
            channel = ActionChannel.UI_GESTURE,
            riskGrade = ActionRiskGrade.LOW,
            gateRule = SafetyRuleIds.LOW_RISK_AUTO_PASS,
            requiresUserConfirmation = false,
            autoPass = true
        ),

        SafeActionDescriptor(
            actionId = ID_UI_SCREENSHOT,
            channel = ActionChannel.UI_GESTURE,
            riskGrade = ActionRiskGrade.SENSITIVE,
            gateRule = SafetyRuleIds.CLIPBOARD_FOREGROUND_ONLY,
            requiresUserConfirmation = false,
            autoPass = false,
            note = "截屏触及隐私面：金融/密码场景由 PrivacyBreaker 整屏熔断，且受温度与电量梯度限流"
        ),

        SafeActionDescriptor(
            actionId = ID_UI_KEY_EVENT,
            channel = ActionChannel.UI_GESTURE,
            riskGrade = ActionRiskGrade.SENSITIVE,
            gateRule = SafetyRuleIds.KEY_EVENT_CONFIRMATION_GATE,
            requiresUserConfirmation = false,
            autoPass = false,
            note = "M6.0 P0-1 收口：键事件无目标文本可供文本级审查，ENTER/DPAD_CENTER 可激活焦点" +
                "默认按钮（支付确认弹窗的默认按钮往往正是它）。除统一引擎包名级零容忍外，" +
                "协调器对确认类按键叠加：通信应用代发熔断（extraInfo.isTextInput）、活窗口" +
                "焦点/关键词扫描、漫游只读熔断；数字键码直通已删除（KeycodeMapper 仅白名单符号名）"
        )
    ).associateBy { it.actionId }

    /** 目录全量快照（回归用例遍历用） */
    val all: List<SafeActionDescriptor> get() = DESCRIPTORS.values.toList()

    fun of(actionId: String): SafeActionDescriptor? = DESCRIPTORS[actionId]

    /**
     * 把 [IntentAction] 映射到目录表。未知动作 fail-closed（返回 null，由裁决层拒绝）。
     */
    fun describe(action: IntentAction): SafeActionDescriptor? = when (action) {
        is IntentAction.SetAlarm -> of(ID_SET_ALARM)
        is IntentAction.AddCalendarEvent -> of(ID_ADD_CALENDAR_EVENT)
        is IntentAction.Dial -> of(ID_DIAL)
        is IntentAction.SendSms -> of(ID_SEND_SMS)
        is IntentAction.SendEmail -> of(ID_SEND_EMAIL)
        is IntentAction.ShareText -> of(ID_SHARE_TEXT)
        is IntentAction.OpenMap -> of(ID_OPEN_MAP)
        is IntentAction.OpenSettings -> of(ID_OPEN_SETTINGS)
        is IntentAction.LaunchApp -> of(ID_LAUNCH_APP)
        is IntentAction.ToggleFlashlight -> of(ID_TOGGLE_FLASHLIGHT)
        is IntentAction.AdjustVolume -> of(ID_ADJUST_VOLUME)
        is IntentAction.AdjustBrightness -> of(ID_ADJUST_BRIGHTNESS)
    }

    /**
     * 该动作是否会在被浏览的应用内留下不可逆痕迹（漫游只读拦截的判定输入）。
     *
     * 注意与"设备级协助动作"（闹钟/手电筒/音量）区分：后者是用户显式求助的
     * 设备状态调整，不是在被浏览应用里留痕，漫游模式下照常放行。
     */
    fun leavesTraceInTargetApp(descriptor: SafeActionDescriptor): Boolean =
        descriptor.actionId in TRACE_LEAVING_ACTIONS

    private val TRACE_LEAVING_ACTIONS = setOf(
        ID_UI_CLICK,
        ID_UI_LONG_CLICK,
        ID_UI_INPUT_TEXT,
        ID_UI_KEY_EVENT,
        ID_SEND_SMS,
        ID_SEND_EMAIL,
        ID_SHARE_TEXT
    )
}
