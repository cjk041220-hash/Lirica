package com.lirica.android.automation.policy

/**
 * 漫游模式只读拦截器（主方案5.0 §八 双态装配）。
 *
 * 设计口径（把方案的一句话原则落成可穷举判定）：
 *
 * 1. **拦截目标不是"点击"本身，而是"在被浏览的应用里留下痕迹"**。
 *    只读检索必然包含点进帖子、翻页、输入搜索词，若把点击一律拦死，
 *    漫游模式就退化成"只能看首屏"，与方案"只读检索"的授权自相矛盾。
 * 2. **互动动词黑名单**是判定核心：关注 / 点赞 / 投币 / 评论 / 播放 /
 *    购买 / 支付 … 这类目标一旦被点击就会改写对方应用里的状态。
 *    方案原文举例正是"去 B 站看纪录片会留下播放历史"——因此「播放」在列。
 * 3. **黑名单之外仍可协商**：命中黑名单时不做静默突破，而是由心智层
 *    在主会话流里以年上口吻向用户协商，用户同意后经
 *    [SafetyPolicyEngine.elevateScopeForTask] 临时提升**单次任务**作用域。
 * 4. **不可协商的硬熔断不在这里**：密码框、金融 App、通信代发、系统提权
 *    由 FinancialGatekeeper 负责，作用域提升也解除不了（§1.2 零破坏不变量）。
 *
 * 已知近似（诚实披露）：
 * - 文案匹配是词表法，存在误伤（如「关注列表」是只读入口却命中「关注」）
 *   与漏判（纯图标无文本节点依赖 contentDescription）。误伤方向是安全的
 *   （多问一句），漏判方向由作用域提升前的用户协商兜底。
 */
class ReadOnlyGuard(
    private val interactionKeywords: Set<String> = DEFAULT_INTERACTION_KEYWORDS,
    private val interactionInputKeywords: Set<String> = DEFAULT_INTERACTION_INPUT_KEYWORDS
) {

    /** 漫游只读判定结果 */
    sealed interface Verdict {
        /** 放行（无痕只读操作） */
        data object ReadOnlyAllowed : Verdict

        /** 需用户协商授权才可执行（留下痕迹的互动） */
        data class RequiresNegotiation(
            val matchedKeyword: String,
            val category: Category
        ) : Verdict
    }

    enum class Category {
        /** 互动类点击：关注/点赞/评论/播放… */
        INTERACTION_CLICK,

        /** 互动类输入：评论框/弹幕/私信… */
        INTERACTION_INPUT,

        /** 非幂等外部通信：短信/邮件/分享 */
        OUTBOUND_COMMUNICATION
    }

    /**
     * 依据动作标识与目标特征判定漫游合规性。
     *
     * @param actionId 目录表动作 ID
     * @param targetText 目标节点文本
     * @param contentDescription 目标节点无障碍描述
     * @param resourceId 目标节点资源 ID
     */
    fun evaluate(
        actionId: String,
        targetText: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null
    ): Verdict {
        return when (actionId) {
            SystemActionCatalog.ID_UI_CLICK,
            SystemActionCatalog.ID_UI_LONG_CLICK -> evaluateClick(targetText, contentDescription, resourceId)

            SystemActionCatalog.ID_UI_INPUT_TEXT -> evaluateInput(targetText, contentDescription, resourceId)

            SystemActionCatalog.ID_SEND_SMS,
            SystemActionCatalog.ID_SEND_EMAIL,
            SystemActionCatalog.ID_SHARE_TEXT -> Verdict.RequiresNegotiation(
                matchedKeyword = actionId,
                category = Category.OUTBOUND_COMMUNICATION
            )

            else -> Verdict.ReadOnlyAllowed
        }
    }

    private fun evaluateClick(
        targetText: String?,
        contentDescription: String?,
        resourceId: String?
    ): Verdict {
        val haystack = mergeHaystack(targetText, contentDescription, resourceId)
        if (haystack.isEmpty()) return Verdict.ReadOnlyAllowed
        val hit = interactionKeywords.firstOrNull { haystack.contains(it, ignoreCase = true) }
        return if (hit != null) {
            Verdict.RequiresNegotiation(hit, Category.INTERACTION_CLICK)
        } else {
            Verdict.ReadOnlyAllowed
        }
    }

    private fun evaluateInput(
        targetText: String?,
        contentDescription: String?,
        resourceId: String?
    ): Verdict {
        val haystack = mergeHaystack(targetText, contentDescription, resourceId)
        if (haystack.isEmpty()) return Verdict.ReadOnlyAllowed
        val hit = interactionInputKeywords.firstOrNull { haystack.contains(it, ignoreCase = true) }
        return if (hit != null) {
            Verdict.RequiresNegotiation(hit, Category.INTERACTION_INPUT)
        } else {
            Verdict.ReadOnlyAllowed
        }
    }

    private fun mergeHaystack(vararg parts: String?): String =
        parts.filterNotNull().joinToString(" ").trim()

    companion object {
        /**
         * 互动动词黑名单：命中即"会在对方应用里留下痕迹"。
         * 词表保持短小可审计，宁可漏判交由用户协商兜底，也不无限膨胀成噪音。
         */
        val DEFAULT_INTERACTION_KEYWORDS: Set<String> = setOf(
            // 社交互动
            "关注", "点赞", "投币", "一键三连", "评论", "回复", "转发", "分享",
            "收藏", "订阅", "打赏", "举报", "拉黑", "私信", "加好友", "添加好友",
            // 内容消费（会留下播放/阅读历史）
            "播放", "立即播放", "继续播放", "下载", "缓存", "投屏",
            // 交易与授权
            "购买", "下单", "支付", "付款", "结算", "开通", "领取", "签到", "报名",
            "提交", "确认", "同意", "授权", "安装", "卸载", "删除",
            // 英文资源 ID 常见动词：必须带下划线前缀，否则 display 会命中 play、
            // unlike 会命中 like，把只读页面误判成互动页面（误伤方向虽安全，但会逼用户反复授权）
            "_follow", "_like", "_subscribe", "_comment", "_share", "_collect", "_favorite",
            "_buy", "_pay", "_purchase", "_order", "_submit", "_confirm", "_download", "_play"
        )

        /**
         * 互动输入黑名单：输入框本身用于评论/弹幕/私信时才拦截；
         * 搜索框输入属"只读检索"，明确放行。
         */
        val DEFAULT_INTERACTION_INPUT_KEYWORDS: Set<String> = setOf(
            "评论", "回复", "弹幕", "私信", "发消息", "说点什么", "有什么想说的",
            "写评论", "发表", "留言"
        )
    }
}
