package com.xiaoxi.android.automation.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppPackageResolverTest {

    @Test
    fun testResolve_standardPackageName_returnsDirectly() {
        val resolver = AppPackageResolver()
        assertEquals("com.example.customapp", resolver.resolve("com.example.customapp"))
        assertEquals("org.mozilla.firefox", resolver.resolve("org.mozilla.firefox"))
    }

    @Test
    fun testResolve_staticKnownApps_andAliases() {
        val resolver = AppPackageResolver()
        assertEquals("com.tencent.mm", resolver.resolve("微信"))
        assertEquals("com.tencent.mm", resolver.resolve("wx"))
        assertEquals("com.tencent.mm", resolver.resolve("wechat"))
        assertEquals("com.autonavi.minimap", resolver.resolve("高德地图"))
        assertEquals("com.autonavi.minimap", resolver.resolve("高德"))
        assertEquals("me.ele", resolver.resolve("饿了么"))
        assertEquals("me.ele", resolver.resolve("ele"))
        assertEquals("tv.danmaku.bili", resolver.resolve("哔哩哔哩"))
        assertEquals("tv.danmaku.bili", resolver.resolve("b站"))
        assertEquals("com.eg.android.AlipayGphone", resolver.resolve("支付宝"))
        assertEquals("com.eg.android.AlipayGphone", resolver.resolve("zfb"))
        assertEquals("com.android.settings", resolver.resolve("设置"))
        assertEquals("com.android.settings", resolver.resolve("系统设置"))
    }

    @Test
    fun testResolve_dynamicPackageManagerQuery_andCaching() {
        var queryCalls = 0
        val mockInstalled = mapOf(
            "原神" to "com.mihoyo.yuanshen",
            "网易有道词典" to "com.youdao.dict"
        )

        val resolver = AppPackageResolver(
            packageQueryRunner = {
                queryCalls++
                mockInstalled
            }
        )

        // 第一次查询命中动态列表
        val pkg1 = resolver.resolve("原神")
        assertEquals("com.mihoyo.yuanshen", pkg1)
        assertEquals(1, queryCalls)

        // 第二次查询命中内存缓存，不应重复查询
        val pkg2 = resolver.resolve("原神")
        assertEquals("com.mihoyo.yuanshen", pkg2)
        assertEquals(1, queryCalls)

        // 包含模糊匹配（输入 "有道词典" 匹配 "网易有道词典"）
        val pkg3 = resolver.resolve("有道词典")
        assertEquals("com.youdao.dict", pkg3)
    }

    @Test
    fun testResolve_unknownApp_returnsNull_neverReturnsRawChinese() {
        val resolver = AppPackageResolver(
            packageQueryRunner = { emptyMap() }
        )
        // 未安装且未知应用必须返回 null，绝不能返回 "不存在的应用" 误导底层 Intent
        assertNull(resolver.resolve("不存在的未知冷门应用"))
        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("   "))
    }
}
