package com.lirica.android.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气数据模型与格式化的纯函数单测（不涉及网络）。
 */
class WeatherModelsTest {

    // ── WeatherFormatter ─────────────────────────────────────

    @Test
    fun format_fullSnapshot_rendersAllSections() {
        val snapshot = WeatherSnapshot(
            placeName = "佛山",
            weatherCode = 61,
            temperatureC = 26.3,
            apparentC = 28.1,
            humidityPercent = 78,
            windKmh = 12.4,
            tomorrowCode = 63,
            tomorrowMaxC = 31.0,
            tomorrowMinC = 24.0,
            tomorrowPrecipProb = 60
        )
        val text = WeatherFormatter.format(snapshot)

        assertTrue(text.contains("佛山实时天气"))
        assertTrue(text.contains("🌧️ 小雨"))
        assertTrue(text.contains("26.3°C"))
        assertTrue(text.contains("体感 28.1°C"))
        assertTrue(text.contains("湿度 78%"))
        assertTrue(text.contains("风速 12.4 km/h"))
        // 降水代码 → 带伞提示，且不叠加热/冷提示（互斥分支）
        assertTrue(text.contains("带伞"))
        assertFalse(text.contains("多喝水"))
        // 明日预报
        assertTrue(text.contains("明天"))
        assertTrue(text.contains("24°C ~ 31°C"))
        assertTrue(text.contains("降水概率 60%"))
    }

    @Test
    fun format_missingNumericFields_fallsBackToQuestionMark() {
        val snapshot = WeatherSnapshot(placeName = "某地", weatherCode = 0)
        val text = WeatherFormatter.format(snapshot)

        assertTrue(text.contains("☀️ 晴天"))
        assertTrue(text.contains("气温 ?°C"))
        assertTrue(text.contains("湿度 ?%"))
        // 晴天无提示语，且无明日预报段
        assertFalse(text.contains("明天"))
        assertFalse(text.contains("带伞"))
    }

    @Test
    fun format_stormCode_overridesTemperatureTip() {
        val snapshot = WeatherSnapshot(
            placeName = "广州",
            weatherCode = 95,
            temperatureC = 35.0
        )
        val text = WeatherFormatter.format(snapshot)
        assertTrue(text.contains("⛈️ 雷暴"))
        assertTrue(text.contains("注意安全"))
        // 分支互斥：同时满足高温也不出第二句提示
        assertFalse(text.contains("多喝水"))
    }

    @Test
    fun format_coldWeather_showsWarmTip() {
        val snapshot = WeatherSnapshot(placeName = "哈尔滨", weatherCode = 71, temperatureC = -12.5)
        val text = WeatherFormatter.format(snapshot)
        assertTrue(text.contains("❄️ 小雪"))
        assertTrue(text.contains("多穿一件"))
    }

    @Test
    fun format_tomorrowPrecipUnder50_isOmitted() {
        val snapshot = WeatherSnapshot(
            placeName = "苏州",
            weatherCode = 0,
            tomorrowCode = 0,
            tomorrowMaxC = 20.0,
            tomorrowMinC = 12.0,
            tomorrowPrecipProb = 30
        )
        val text = WeatherFormatter.format(snapshot)
        assertTrue(text.contains("明天"))
        assertFalse(text.contains("降水概率"))
    }

    @Test
    fun format_unknownWeatherCode_degradesGracefully() {
        // WMO 表外代码不抛异常，降级为「未知(N)」
        val text = WeatherFormatter.format(WeatherSnapshot(placeName = "测试", weatherCode = 999))
        assertTrue(text.contains("未知(999)"))
    }

    @Test
    fun format_oneDecimalTrailingZeroIsStripped() {
        val text = WeatherFormatter.format(
            WeatherSnapshot(placeName = "测试", weatherCode = 0, temperatureC = 20.0, humidityPercent = 50)
        )
        // 20.0 → "20" 而非 "20.0"，避免中文播报里出现多余的小数位
        assertTrue(text.contains("气温 20°C"))
        assertFalse(text.contains("20.0"))
    }

    // ── CityCoords ───────────────────────────────────────────

    @Test
    fun lookup_exactMatch() {
        assertEquals(23.02 to 113.12, CityCoords.lookup("佛山"))
    }

    @Test
    fun lookup_stripsAdministrativeSuffix() {
        // 「佛山市」→ 剥掉「市」再查
        assertEquals(23.02 to 113.12, CityCoords.lookup("佛山市"))
        assertEquals(39.91 to 116.40, CityCoords.lookup("北京市"))
    }

    @Test
    fun lookup_caseAndWhitespaceInsensitive() {
        assertEquals(22.30 to 114.17, CityCoords.lookup("  Hong Kong "))
    }

    @Test
    fun lookup_zhouSuffixIsPartOfName_neverStripped() {
        // 广州/杭州/苏州/郑州/兰州的「州」是地名固有部分；
        // 若把「州」也当后缀剥掉会得到「广」「杭」，必须确保没这么干
        assertEquals(23.13 to 113.26, CityCoords.lookup("广州"))
        assertEquals(32.06 to 118.80, CityCoords.lookup("南京"))
        assertTrue(CityCoords.lookup("广州") != CityCoords.lookup("兰州"))
    }

    @Test
    fun lookup_unknownCity_returnsNull() {
        assertNull(CityCoords.lookup("不存在的地方"))
        assertNull(CityCoords.lookup(""))
        assertNull(CityCoords.lookup("   "))
    }
}
