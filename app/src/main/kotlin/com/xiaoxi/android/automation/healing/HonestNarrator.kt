package com.xiaoxi.android.automation.healing

import com.xiaoxi.android.common.logging.XiaoxiLog
import kotlin.random.Random

/**
 * PRD §1 D5 诚实失败原则与 §7 批次 3 · 失败叙述接口
 *
 * 核心要求：
 * 1. 当自动化因禁止区熔断、系统安全、接地失败或自愈耗尽而无法继续时，严禁静默重试装没事、裸抛技术异常或伪造成功。
 * 2. 输出人设化的第一人称坦白文案（"这种无奈反而更有活人感"），语气克制、无奈、不卖惨。
 * 3. 话术库为中性占位版（末尾统一注明：正式话术库待俊凯过目定稿），采用结构化占位符（{appName}、{targetElement}、{actionType}）。
 * 4. 日志纪律：technicalReason 与 rule 写入日志，honestNarrative 透传给用户可见输出；extraInfo（含用户输入明文）绝不进入日志。
 */
enum class FailureScenario {
    FINANCIAL_PROHIBITED,       // 金融禁止区（银行/支付/钱包/证券）
    COMMUNICATION_SEND,         // 通信代发（微信/QQ 文本输入代发）
    PASSWORD_INPUT,             // 密码框（密码/PIN/验证码输入）
    SYSTEM_PRIVILEGE,           // 系统提权（权限授予/设备管理器/安全中心）
    GROUNDING_FAILED,           // 接地失败/元素找不到
    SELF_HEALING_EXHAUSTED,     // 自愈耗尽（卡死循环/弹窗无法消除/回退超限）
    GENERIC_FALLBACK            // 通用兜底
}

data class FailureContext(
    val rule: String? = null,
    val technicalReason: String? = null,
    val targetPackage: String? = null,
    val actionType: String? = null,
    val targetElement: String? = null,
    val scenario: FailureScenario? = null,
    val extraInfo: Map<String, Any?> = emptyMap()
)

interface HonestNarrator {
    fun narrate(context: FailureContext): String
    fun getTemplates(scenario: FailureScenario): List<String>
}

class HonestNarratorImpl(
    private val random: Random = Random.Default,
    private val appNameResolver: (String?) -> String = ::resolveDefaultAppName
) : HonestNarrator {

    companion object {
        private const val TAG = "HonestNarrator"

        const val PENDING_REVIEW_NOTICE = "（正式话术库待俊凯过目定稿）"

        val TEMPLATES: Map<FailureScenario, List<String>> = mapOf(
            FailureScenario.FINANCIAL_PROHIBITED to listOf(
                "涉及{appName}的钱袋子，我可不敢碰，这里得你亲自来确认啦。$PENDING_REVIEW_NOTICE",
                "碰到{appName}的资金和支付界面了，规矩摆在这我碰不得，辛苦你自己点一下。$PENDING_REVIEW_NOTICE",
                "钱相关的事情是绝对禁区，{appName}这边我只能退后，交给你接手了。$PENDING_REVIEW_NOTICE"
            ),
            FailureScenario.COMMUNICATION_SEND to listOf(
                "在{appName}里替你给人发消息容易搞砸，文字我写好了，发不发还是你说了算。$PENDING_REVIEW_NOTICE",
                "给别人发消息是你的私事，在{appName}这步我停下了，免得说错话，你自己来发吧。$PENDING_REVIEW_NOTICE",
                "通信代发按规矩我不能替你按发送，在{appName}这里交棒给你，免得惹麻烦。$PENDING_REVIEW_NOTICE"
            ),
            FailureScenario.PASSWORD_INPUT to listOf(
                "遇到输密码的地方了，这种隐私我自觉闭上眼睛，留给你自己输入。$PENDING_REVIEW_NOTICE",
                "密码和验证码是最高警戒线，我碰不了也不该看，你自己输一下吧。$PENDING_REVIEW_NOTICE",
                "碰到密码框我只能停在门外，这里的安全交还给你亲手操作。$PENDING_REVIEW_NOTICE"
            ),
            FailureScenario.SYSTEM_PRIVILEGE to listOf(
                "这是系统底层权限设置，我不能自己给自己盖章，得你来拿主意。$PENDING_REVIEW_NOTICE",
                "权限修改属于高危操作，我被物理规矩拦下了，这一步得你亲自核准。$PENDING_REVIEW_NOTICE",
                "系统权限这道锁我动不了，免得弄坏你的手机，辛苦你手动确认一下。$PENDING_REVIEW_NOTICE"
            ),
            FailureScenario.GROUNDING_FAILED to listOf(
                "看了好几遍也没在屏幕上找到{targetElement}，它今天好像跟我捉迷藏，你自己来呗？$PENDING_REVIEW_NOTICE",
                "界面变样了，没找到{targetElement}在哪，我不敢乱戳，免得点错，你来点一下吧。$PENDING_REVIEW_NOTICE",
                "在当前界面找了一圈没认出{targetElement}，可能是被挡住了，辛苦你接手操作一下。$PENDING_REVIEW_NOTICE"
            ),
            FailureScenario.SELF_HEALING_EXHAUSTED to listOf(
                "试了好几次都没能穿过这个页面，再折腾怕给你添乱，这关只能由你亲自过了。$PENDING_REVIEW_NOTICE",
                "界面一直卡在这里，我退后尝试了几遍都没反应，力不从心了，交给你来解围吧。$PENDING_REVIEW_NOTICE",
                "反复尝试了几次还是卡在原地，我的自愈办法用光了，不得不坦白投降，交给你啦。$PENDING_REVIEW_NOTICE"
            ),
            FailureScenario.GENERIC_FALLBACK to listOf(
                "这步操作我遇到点困难搞不定，为了不出差错，交由你来亲自操作吧。$PENDING_REVIEW_NOTICE",
                "执行到这里卡住了，我不确定该怎么继续，退后一步交给你来解决。$PENDING_REVIEW_NOTICE"
            )
        )

        private val KNOWN_APP_NAMES = mapOf(
            "com.tencent.mm" to "微信",
            "com.tencent.mobileqq" to "QQ",
            "com.eg.android.AlipayGphone" to "支付宝",
            "com.unionpay" to "云闪付",
            "tv.danmaku.bili" to "哔哩哔哩",
            "com.icbc" to "中国工商银行",
            "com.chinamworld.main" to "中国建设银行",
            "com.android.bankabc" to "中国农业银行",
            "com.boc.bocsoft" to "中国银行",
            "com.cmbchina.ccd.plTrans.cmbActivity" to "招商银行掌上生活",
            "cmb.pb" to "招商银行",
            "com.bankcomm.maidanba" to "买单吧",
            "com.bankcomm.Bankcomm" to "交通银行",
            "com.yitong.mbank.psbc" to "邮储银行",
            "cn.com.spdb.mobilebank.per" to "浦发银行",
            "cn.com.cmbc.mbank" to "民生银行",
            "com.ecitic.bank.mobile" to "中信银行",
            "com.cgbchina.xpt" to "广发银行",
            "com.pingan.paces.ccms" to "平安口袋银行",
            "com.cib.cibmb" to "兴业银行",
            "com.cebbank.mobile.cemb" to "光大银行",
            "com.huaan.android" to "华夏银行",
            "com.eastmoney.android.berlin" to "东方财富",
            "com.hexin.plat.android" to "同花顺",
            "com.tdx.Android" to "通达信",
            "com.coloros.safecenter" to "手机管家",
            "com.miui.securitycenter" to "安全中心",
            "com.android.settings" to "系统设置"
        )

        fun resolveDefaultAppName(packageName: String?): String {
            if (packageName.isNullOrBlank()) return "这个应用"
            val known = KNOWN_APP_NAMES[packageName]
            if (known != null) return known
            val lower = packageName.lowercase()
            if (lower.contains("bank")) return "银行应用"
            if (lower.contains("alipay") || lower.contains("wallet") || lower.contains("pay")) return "支付应用"
            return "这个应用"
        }
    }

    override fun getTemplates(scenario: FailureScenario): List<String> {
        return TEMPLATES[scenario] ?: TEMPLATES[FailureScenario.GENERIC_FALLBACK].orEmpty()
    }

    override fun narrate(context: FailureContext): String {
        val scenario = context.scenario ?: inferScenario(context)

        // 日志纪律：technicalReason 与 rule 记入日志；extraInfo 绝不进日志
        XiaoxiLog.w(
            TAG,
            "Action execution blocked/failed [scenario=$scenario, rule=${context.rule ?: "N/A"}, " +
                "package=${context.targetPackage ?: "N/A"}, reason=${context.technicalReason ?: "N/A"}]"
        )

        val templates = getTemplates(scenario)
        val selectedTemplate = if (templates.isNotEmpty()) {
            val index = random.nextInt(templates.size)
            templates[index]
        } else {
            "当前操作遇到阻碍，辛苦你亲自接手完成。$PENDING_REVIEW_NOTICE"
        }

        return formatTemplate(selectedTemplate, context)
    }

    private fun inferScenario(context: FailureContext): FailureScenario {
        val rule = context.rule?.uppercase() ?: ""
        val tech = context.technicalReason?.uppercase() ?: ""
        val pkg = context.targetPackage ?: ""
        val action = context.actionType?.uppercase() ?: ""

        // 1. 密码与敏感凭据 (Tier 0 最高优先级)
        if (rule.contains("PASSWORD") || tech.contains("PASSWORD") || tech.contains("密码")) {
            return FailureScenario.PASSWORD_INPUT
        }

        // 2. 通信软件代发代回复 (微信/QQ 文本输入拦截)
        if (rule.contains("COMMUNICATION") || ((pkg.contains("tencent.mm") || pkg.contains("mobileqq")) && action.contains("INPUT"))) {
            return FailureScenario.COMMUNICATION_SEND
        }

        // 3. 金融禁止区 / 支付熔断
        if (rule.contains("BANK") || rule.contains("PAYMENT") || rule.contains("FINANCIAL") || rule.contains("PRIVACY_BLACKOUT")
            || tech.contains("BANK") || tech.contains("PAYMENT") || tech.contains("支付") || tech.contains("银行")
        ) {
            return FailureScenario.FINANCIAL_PROHIBITED
        }

        // 4. 系统提权与安全中心
        if (rule.contains("SYSTEM") || rule.contains("PRIVILEGE") || tech.contains("PRIVILEGE") || tech.contains("PERMISSION") || tech.contains("权限")) {
            return FailureScenario.SYSTEM_PRIVILEGE
        }

        // 5. 自愈耗尽 (看门狗判定 Unrecoverable)
        if (tech.contains("EXCEEDED MAX RECOVERY") || tech.contains("STUCK_LOOP") || tech.contains("POPUP_BLOCKING")
            || tech.contains("UNEXPECTED_EXIT") || tech.contains("自愈") || tech.contains("UNRECOVERABLE")
        ) {
            return FailureScenario.SELF_HEALING_EXHAUSTED
        }

        // 6. 接地失败 / 找不到元素
        if (tech.contains("NOTFOUND") || tech.contains("NOT FOUND") || tech.contains("未找到") || tech.contains("接地")) {
            return FailureScenario.GROUNDING_FAILED
        }

        return FailureScenario.GENERIC_FALLBACK
    }

    private fun formatTemplate(template: String, context: FailureContext): String {
        val appName = appNameResolver(context.targetPackage)
        val targetElement = if (!context.targetElement.isNullOrBlank()) {
            "「${context.targetElement.trim()}」"
        } else {
            "那个按钮"
        }
        val actionType = when (context.actionType?.uppercase()) {
            "CLICK" -> "点击"
            "INPUT" -> "输入"
            "SWIPE" -> "滑动"
            else -> context.actionType ?: "操作"
        }

        return template
            .replace("{appName}", appName)
            .replace("{targetElement}", targetElement)
            .replace("{actionType}", actionType)
    }
}
