package com.xiaoxi.android.automation.gatekeeper

/**
 * PRD §2 D8 & D6: Tier 0 禁止区不可变只读常量与规则收敛
 *
 * 铁律（PRD D6 自由叙事 / 安全物理）：
 * 1. 安全边界是物理层——禁止区硬编码、纯只读常量/只读规则，绝对不暴露任何设置项，无任何用户可关开关。
 * 2. 禁止区三大项（PRD D8）：
 *    ① 通信软件代发/代回复（微信/QQ 文本输入类动作物理熔断；点击查看放行）
 *    ② 支付、转账、金融操作（银行/金融类 App 全量物理熔断）
 *    ③ 密码输入与敏感验证码框（零容忍物理熔断）
 */
object ProhibitedZones {

    // ==========================================
    // 1. 金融与银行类应用黑名单（全量熔断）
    // ==========================================
    val FINANCIAL_PACKAGES: Set<String> = setOf(
        // 移动支付与电子钱包
        "com.eg.android.AlipayGphone",         // 支付宝
        "com.unionpay",                         // 云闪付
        // 国有大行与股份制商业银行
        "com.icbc",                             // 中国工商银行
        "com.chinamworld.main",                 // 中国建设银行
        "com.android.bankabc",                  // 中国农业银行
        "com.boc.bocsoft",                      // 中国银行
        "com.cmbchina.ccd.plTrans.cmbActivity", // 招商银行掌上生活
        "cmb.pb",                               // 招商银行手机银行
        "com.bankcomm.maidanba",                // 交通银行买单吧
        "com.bankcomm.Bankcomm",                // 交通银行
        "com.yitong.mbank.psbc",                // 邮储银行
        "cn.com.spdb.mobilebank.per",           // 浦发银行
        "cn.com.cmbc.mbank",                    // 民生银行
        "com.ecitic.bank.mobile",               // 中信银行
        "com.cgbchina.xpt",                     // 广发银行
        "com.pingan.paces.ccms",                // 平安口袋银行
        "com.cib.cibmb",                        // 兴业银行
        "com.cebbank.mobile.cemb",              // 光大银行
        "com.huaan.android",                    // 华夏银行
        // 主流证券交易与数字资产
        "com.eastmoney.android.berlin",         // 东方财富
        "com.hexin.plat.android",               // 同花顺
        "com.tdx.Android",                      // 通达信
        // 系统凭据、密码箱与密钥库
        "com.google.android.gms.autofill",
        "com.coloros.safecenter",
        "com.miui.securitycenter",
        "com.android.keychain"
    )

    val FINANCIAL_PACKAGE_SUFFIX_PATTERNS: List<String> = listOf(
        ".bank", ".alipay", ".wallet", ".pwd", ".password", ".crypto"
    )

    // ==========================================
    // 2. 通信类应用包名集合（仅拦截文本代发，点击放行）
    // ==========================================
    val COMMUNICATION_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",       // 微信
        "com.tencent.mobileqq"  // QQ
    )

    // ==========================================
    // 3. 密码框与敏感输入控件识别规则
    // ==========================================
    val PASSWORD_RESOURCE_ID_KEYWORDS: Set<String> = setOf(
        "password", "pwd", "pin"
    )

    val PASSWORD_TEXT_KEYWORDS: Set<String> = setOf(
        "密码", "支付密码", "锁屏密码", "输入密码", "交易密码"
    )

    // ==========================================
    // 4. 关键支付关键词与系统提权高危关键词
    // ==========================================
    val CRITICAL_PAYMENT_KEYWORDS: List<String> = listOf(
        "立即支付", "确认支付", "输入支付密码", "微信支付", "支付宝支付", "云闪付",
        "确认付款", "立即付款", "pay now", "confirm payment", "transfer", "转账"
    )

    val CRITICAL_SYSTEM_KEYWORDS: List<String> = listOf(
        "始终允许", "授予权限", "设备管理器", "device admin", "格式化", "恢复出厂设置", "注销账户"
    )

    // ==========================================
    // 5. 高危二次确认与中危审计规则（只读）
    // ==========================================
    val HIGH_RISK_CONFIRM_KEYWORDS: List<String> = listOf(
        "提交订单", "立即购买", "发红包", "确认下单", "删除全部", "清空", "解除绑定", "修改密码", "重置密码"
    )

    val MEDIUM_RISK_KEYWORDS: List<String> = listOf(
        "拨打", "发送", "设置", "呼叫", "dial", "call", "send"
    )

    const val DEFAULT_HONEST_REASON: String = "该操作属于受限范围，已停止"

    /**
     * 判断包名是否属于金融/银行类禁止区应用
     */
    fun isFinancialApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (FINANCIAL_PACKAGES.contains(packageName)) return true
        val lower = packageName.lowercase()
        return FINANCIAL_PACKAGE_SUFFIX_PATTERNS.any { lower.contains(it) }
    }

    /**
     * 判断包名是否属于通信类应用
     */
    fun isCommunicationApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (COMMUNICATION_PACKAGES.contains(packageName)) return true
        val lower = packageName.lowercase()
        return COMMUNICATION_PACKAGES.any { lower.startsWith(it.lowercase()) }
    }

    /**
     * 判定是否命中密码框规则
     */
    fun isPasswordTarget(
        isPasswordFlag: Boolean,
        resourceId: String?,
        text: String?
    ): Boolean {
        if (isPasswordFlag) return true
        val lowerResId = resourceId?.lowercase() ?: ""
        if (PASSWORD_RESOURCE_ID_KEYWORDS.any { lowerResId.contains(it) }) return true
        val lowerText = text?.lowercase() ?: ""
        return PASSWORD_TEXT_KEYWORDS.any { lowerText.contains(it) }
    }
}
