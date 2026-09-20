package com.xiaoxi.android.perception

import com.xiaoxi.android.perception.model.AccessibilityNodeSnapshot
import com.xiaoxi.android.perception.model.PageContentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyBreakerTest {

    private lateinit var privacyBreaker: PrivacyBreaker

    @Before
    fun setUp() {
        privacyBreaker = PrivacyBreaker()
    }

    @Test
    fun testBankAndPaymentAppTriggersPhysicalFuse() {
        val bankPackages = listOf(
            "com.eg.android.AlipayGphone", // 支付宝
            "com.unionpay",                // 云闪付
            "com.icbc",                    // 工行
            "com.chinamworld.main",        // 建行
            "com.boc.bocsoft",             // 中行
            "com.cmbchina.ccd.plTrans.cmbActivity", // 招行
            "com.eastmoney.android.berlin" // 东方财富
        )

        for (pkg in bankPackages) {
            assertTrue("Package $pkg should be detected as sensitive", privacyBreaker.isSensitiveApp(pkg))

            val snapshot = PageContentSnapshot(
                packageName = pkg,
                className = "MainActivity",
                nodes = listOf(
                    AccessibilityNodeSnapshot(text = "账户余额: 10,000.00", viewId = "balance"),
                    AccessibilityNodeSnapshot(text = "转账给李四", viewId = "btn_transfer")
                ),
                focusedText = "10,000.00"
            )

            val sanitized = privacyBreaker.sanitizePageSnapshot(snapshot)
            assertTrue("Nodes must be completely emptied for sensitive app $pkg", sanitized.nodes.isEmpty())
            assertEquals(null, sanitized.focusedText)
        }
    }

    @Test
    fun testPasswordNodeTriggersFieldBlackout() {
        val normalPkg = "com.example.login"
        assertFalse(privacyBreaker.isSensitiveApp(normalPkg))

        val snapshot = PageContentSnapshot(
            packageName = normalPkg,
            className = "LoginActivity",
            nodes = listOf(
                AccessibilityNodeSnapshot(text = "用户名", className = "android.widget.TextView"),
                AccessibilityNodeSnapshot(text = "user123", className = "android.widget.EditText", isEditable = true),
                AccessibilityNodeSnapshot(
                    text = "MySecretPass123",
                    contentDescription = "密码输入框",
                    className = "android.widget.EditText",
                    isPassword = true,
                    isEditable = true
                )
            )
        )

        val sanitized = privacyBreaker.sanitizePageSnapshot(snapshot)
        assertEquals(3, sanitized.nodes.size)

        val pwdNode = sanitized.nodes.first { it.isPassword }
        assertEquals("[PROTECTED_PASSWORD]", pwdNode.text)
        assertEquals(null, pwdNode.contentDescription)
    }

    @Test
    fun testPhoneNumberSanitization() {
        val raw = "请联系李经理，电话是 13812345678，谢谢！"
        val sanitized = privacyBreaker.maskPiiText(raw)
        assertEquals("请联系李经理，电话是 138****5678，谢谢！", sanitized)
    }

    @Test
    fun testIdCardSanitization() {
        val raw = "客户身份证号为 110101199003072345 需核验"
        val sanitized = privacyBreaker.maskPiiText(raw)
        assertEquals("客户身份证号为 110101********2345 需核验", sanitized)
    }

    @Test
    fun testBankCardSanitization() {
        val raw = "转入卡号: 6222021000123456789 请确认"
        val sanitized = privacyBreaker.maskPiiText(raw)
        assertEquals("转入卡号: 6222***********6789 请确认", sanitized)
    }

    @Test
    fun testNormalSafeContentPassesUnmodified() {
        val normalPkg = "com.tencent.mm"
        val snapshot = PageContentSnapshot(
            packageName = normalPkg,
            className = "LauncherUI",
            nodes = listOf(
                AccessibilityNodeSnapshot(text = "今天天气真好", className = "android.widget.TextView")
            )
        )

        val sanitized = privacyBreaker.sanitizePageSnapshot(snapshot)
        assertEquals(1, sanitized.nodes.size)
        assertEquals("今天天气真好", sanitized.nodes.first().text)
    }
}
