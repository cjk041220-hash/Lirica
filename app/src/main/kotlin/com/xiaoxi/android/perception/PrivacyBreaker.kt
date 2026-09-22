package com.xiaoxi.android.perception

import com.xiaoxi.android.perception.model.AccessibilityNodeSnapshot
import com.xiaoxi.android.perception.model.PageContentSnapshot

/**
 * 隐私敏感熔断器 (PrivacyBreaker)
 * 核心安全红线指标：PII 敏感泄露率严格 0.000%
 *
 * 核心能力：
 * 1. 银行、金融、支付应用硬编码包名黑名单物理熔断 (整屏节点物理覆黑/清空)
 * 2. 密码框 (isPassword == true) 物理拦截与脱敏
 * 3. 手机号、身份证号、银行卡号核心 PII 正则脱敏
 */
class PrivacyBreaker {

    companion object {
        const val PROTECTED_PASSWORD_LABEL = "[PROTECTED_PASSWORD]"

        /**
         * 严格硬编码的金融、支付与敏感认证包名黑名单
         */
        private val SENSITIVE_PACKAGES = setOf(
            // 移动支付与电子钱包
            "com.eg.android.AlipayGphone",       // 支付宝
            "com.unionpay",                       // 云闪付
            "com.tencent.wepay",                  // 微信支付独立模块
            // 国有大行与股份制商业银行
            "com.icbc",                           // 中国工商银行
            "com.chinamworld.main",               // 中国建设银行
            "com.android.bankabc",                // 中国农业银行
            "com.boc.bocsoft",                    // 中国银行
            "com.cmbchina.ccd.plTrans.cmbActivity", // 招商银行掌上生活
            "cmb.pb",                             // 招商银行手机银行
            "com.bankcomm.maidanba",              // 交通银行买单吧
            "com.bankcomm.Bankcomm",              // 交通银行
            "com.yitong.mbank.psbc",              // 邮储银行
            "cn.com.spdb.mobilebank.per",         // 浦发银行
            "cn.com.cmbc.mbank",                  // 民生银行
            "com.ecitic.bank.mobile",             // 中信银行
            "com.cgbchina.xpt",                   // 广发银行
            "com.pingan.paces.ccms",              // 平安口袋银行
            "com.cib.cibmb",                      // 兴业银行
            "com.cebbank.mobile.cemb",            // 光大银行
            "com.huaan.android",                  // 华夏银行
            // 主流证券交易与数字资产
            "com.eastmoney.android.berlin",       // 东方财富
            "com.hexin.plat.android",             // 同花顺
            "com.tdx.Android",                    // 通达信
            // 系统凭据、密码箱与密钥库
            "com.google.android.gms.autofill",
            "com.coloros.safecenter",
            "com.miui.securitycenter",
            "com.android.keychain"
        )

        // 手机号正则 (中国大陆 11 位手机号)
        private val PHONE_REGEX = Regex("1[3-9]\\d{9}")

        // 18 位二代身份证正则
        private val ID_CARD_REGEX = Regex("\\d{6}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]")

        // 16~19 位银行卡号正则
        private val BANK_CARD_REGEX = Regex("\\d{16,19}")

        /**
         * 判断目标应用是否属于银行或敏感金融黑名单
         */
        fun isSensitiveApp(packageName: String): Boolean {
            if (SENSITIVE_PACKAGES.contains(packageName)) {
                return true
            }

            val lower = packageName.lowercase()
            return lower.contains(".bank") ||
                    lower.contains(".alipay") ||
                    lower.contains(".wallet") ||
                    lower.contains(".pwd") ||
                    lower.contains(".password") ||
                    lower.contains(".crypto")
        }
    }

    /**
     * 判断目标应用是否属于银行或敏感金融黑名单 (向后兼容实例调用)
     */
    fun isSensitiveApp(packageName: String): Boolean = Companion.isSensitiveApp(packageName)

    /**
     * 针对页面无障碍快照执行物理熔断与脱敏清洗
     */
    fun sanitizePageSnapshot(snapshot: PageContentSnapshot): PageContentSnapshot {
        // 1. 命中敏感应用黑名单：物理熔断，所有文本与节点彻底清空
        if (isSensitiveApp(snapshot.packageName)) {
            return snapshot.copy(
                nodes = emptyList(),
                focusedText = null
            )
        }

        // 2. 检查并清洗各个节点 (密码框拦截 + PII 脱敏)
        val sanitizedNodes = snapshot.nodes.map { node ->
            if (node.isPassword) {
                // 密码框物理熔断：绝对不暴露真实密码字符与无障碍读屏描述
                node.copy(
                    text = PROTECTED_PASSWORD_LABEL,
                    contentDescription = null
                )
            } else {
                // 普通文本执行 PII 自动化正则脱敏
                val cleanText = node.text?.let { maskPiiText(it) }
                val cleanDesc = node.contentDescription?.let { maskPiiText(it) }
                node.copy(
                    text = cleanText,
                    contentDescription = cleanDesc
                )
            }
        }

        val cleanFocusedText = snapshot.focusedText?.let { maskPiiText(it) }

        return snapshot.copy(
            nodes = sanitizedNodes,
            focusedText = cleanFocusedText
        )
    }

    /**
     * 对普通文本实施核心 PII 脱敏掩码 (严防手机号、身份证号、银行卡号泄漏)
     */
    fun maskPiiText(rawText: String): String {
        if (rawText.isEmpty()) return rawText

        var result = rawText

        // 1. 脱敏 18 位身份证：前 6 位与后 4 位保留，中间 8 位掩码
        result = ID_CARD_REGEX.replace(result) { matchResult ->
            val id = matchResult.value
            id.substring(0, 6) + "********" + id.substring(14)
        }

        // 2. 脱敏 16~19 位银行卡号：前 4 位与后 4 位保留，中间掩码
        result = BANK_CARD_REGEX.replace(result) { matchResult ->
            val card = matchResult.value
            val len = card.length
            val maskLen = (len - 8).coerceAtLeast(4)
            card.substring(0, 4) + "*".repeat(maskLen) + card.substring(len - 4)
        }

        // 3. 脱敏 11 位手机号：前 3 位与后 4 位保留，中间 4 位掩码
        result = PHONE_REGEX.replace(result) { matchResult ->
            val phone = matchResult.value
            phone.substring(0, 3) + "****" + phone.substring(7)
        }

        return result
    }
}
