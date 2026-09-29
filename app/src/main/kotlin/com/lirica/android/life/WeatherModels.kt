package com.lirica.android.life

import kotlin.math.roundToInt

/**
 * WMO 天气代码 → 中文描述映射。
 *
 * 数据源 Open-Meteo 使用 WMO 4677 标准天气代码，非枚举名，必须查表。
 * 未知代码降级为「未知(N)」而不是抛异常——天气工具的价值在于"答上一句"，
 * 不在于代码完备性。
 */
object WeatherCodes {

    private val DESCRIPTIONS: Map<Int, String> = mapOf(
        0 to "☀️ 晴天",
        1 to "🌤️ 大部晴朗",
        2 to "⛅ 多云",
        3 to "☁️ 阴天",
        45 to "🌫️ 雾",
        48 to "🌫️ 霜雾",
        51 to "🌧️ 毛毛雨",
        53 to "🌧️ 毛毛雨",
        55 to "🌧️ 毛毛雨",
        61 to "🌧️ 小雨",
        63 to "🌧️ 中雨",
        65 to "🌧️ 大雨",
        71 to "❄️ 小雪",
        73 to "❄️ 中雪",
        75 to "❄️ 大雪",
        80 to "🌦️ 阵雨",
        81 to "🌦️ 阵雨",
        82 to "🌦️ 阵雨",
        95 to "⛈️ 雷暴",
        96 to "⛈️ 雷暴",
        99 to "⛈️ 雷暴"
    )

    /** 需要提醒带伞的降水类代码 */
    private val RAIN_CODES = setOf(51, 53, 55, 61, 63, 65, 80, 81, 82)

    /** 需要提醒避让的雷暴类代码 */
    private val STORM_CODES = setOf(95, 96, 99)

    fun describe(code: Int): String = DESCRIPTIONS[code] ?: "未知($code)"

    fun isRain(code: Int): Boolean = code in RAIN_CODES

    fun isStorm(code: Int): Boolean = code in STORM_CODES
}

/**
 * 常用城市坐标表（快速路径）。
 *
 * 命中即免一次网络请求；未命中回退 Open-Meteo 官方 geocoding API（同样免 Key）。
 * 这张表刻意保持"常用 + 一眼能认出来"的规模，不做全量行政区划数据——
 * 全量交给 geocoding，避免把维护成本搬进代码。
 */
object CityCoords {

    /** 值语义：(纬度, 经度) */
    private val TABLE: Map<String, Pair<Double, Double>> = buildMap {
        // 港澳
        put("香港", 22.30 to 114.17); put("hong kong", 22.30 to 114.17); put("hk", 22.30 to 114.17)
        put("九龙", 22.32 to 114.19); put("旺角", 22.32 to 114.17); put("沙田", 22.38 to 114.19)
        put("屯门", 22.39 to 113.98); put("元朗", 22.44 to 114.03); put("将军澳", 22.32 to 114.26)
        put("澳门", 22.20 to 113.55); put("macau", 22.20 to 113.55)
        // 大湾区（本项目的主要活动区域）
        put("广州", 23.13 to 113.26); put("深圳", 22.54 to 114.06)
        put("佛山", 23.02 to 113.12); put("东莞", 23.04 to 113.75)
        put("珠海", 22.27 to 113.58); put("中山", 22.52 to 113.39)
        put("惠州", 23.11 to 114.42); put("江门", 22.58 to 113.08)
        // 大陆主要城市
        put("北京", 39.91 to 116.40); put("上海", 31.23 to 121.47)
        put("杭州", 30.27 to 120.15); put("南京", 32.06 to 118.80)
        put("苏州", 31.30 to 120.59); put("成都", 30.57 to 104.07)
        put("重庆", 29.56 to 106.55); put("武汉", 30.59 to 114.31)
        put("西安", 34.26 to 108.94); put("长沙", 28.23 to 112.94)
        put("厦门", 24.48 to 118.09); put("天津", 39.13 to 117.20)
        put("青岛", 36.07 to 120.38); put("大连", 38.91 to 121.60)
        put("沈阳", 41.81 to 123.43); put("哈尔滨", 45.80 to 126.53)
        put("郑州", 34.75 to 113.63); put("合肥", 31.86 to 117.28)
        put("福州", 26.07 to 119.30); put("昆明", 25.04 to 102.72)
        put("贵阳", 26.65 to 106.63); put("南昌", 28.68 to 115.86)
        put("济南", 36.65 to 117.12); put("石家庄", 38.04 to 114.51)
        put("太原", 37.87 to 112.55); put("兰州", 36.06 to 103.83)
        put("银川", 38.49 to 106.23); put("西宁", 36.62 to 101.78)
        put("乌鲁木齐", 43.83 to 87.62); put("拉萨", 29.65 to 91.14)
        put("呼和浩特", 40.84 to 111.75); put("南宁", 22.82 to 108.37)
        put("海口", 20.04 to 110.32); put("三亚", 18.25 to 109.51)
        // 中国台湾
        put("台北", 25.03 to 121.57); put("高雄", 22.63 to 120.30)
        put("台中", 24.15 to 120.68); put("台南", 23.00 to 120.21)
    }

    /**
     * 查表。大小写与首尾空白均做归一化——模型转述出的城市名大小写不稳定。
     * 未命中返回 null（调用方回退 geocoding）。
     */
    fun lookup(city: String): Pair<Double, Double>? {
        val key = city.trim().lowercase()
        if (key.isEmpty()) return null
        TABLE[key]?.let { return it }
        // 去掉常见行政区划后缀再试一次：「广州市」→「广州」、「深圳市」→「深圳」
        var stripped = key
        for (suffix in STRIPPABLE_SUFFIXES) {
            if (stripped.endsWith(suffix) && stripped.length > suffix.length) {
                stripped = stripped.removeSuffix(suffix)
                TABLE[stripped]?.let { return it }
                break
            }
        }
        return null
    }

    /**
     * 可安全剥离的行政区划后缀。
     * 注意「州」绝不在表内——广州 / 杭州 / 苏州 / 郑州 / 兰州 的「州」是地名固有部分，
     * 剥掉会得到「广」「杭」这类无意义键（参考实现踩过这个坑）。
     */
    private val STRIPPABLE_SUFFIXES = listOf(
        "特别行政区", "直辖市", "自治区", "自治州", "自治县", "新区",
        "地区", "市辖区", "市", "区", "县", "省"
    )
}

/**
 * 一次天气观测的归一化结果。
 * 所有数值字段可空——Open-Meteo 在高纬度 / 特殊站点可能不返回某些字段，
 * 缺失时展示为「?」而不是崩溃或显示 0。
 */
data class WeatherSnapshot(
    val placeName: String,
    val weatherCode: Int,
    val temperatureC: Double? = null,
    val apparentC: Double? = null,
    val humidityPercent: Int? = null,
    val windKmh: Double? = null,
    val tomorrowCode: Int? = null,
    val tomorrowMaxC: Double? = null,
    val tomorrowMinC: Double? = null,
    val tomorrowPrecipProb: Int? = null
)

/**
 * 天气快照 → 中文播报文本（纯函数，便于单测）。
 *
 * 输出形态与既有 Python 参考实现保持一致（emoji 行 + 明日预报 + 一句提示），
 * 但**不含任何称呼**：称呼由人格层在 LLM 回应里自然带出，工具回执不应该
 * 替人格说话，否则会与角色卡设定的口吻打架。
 */
object WeatherFormatter {

    fun format(snapshot: WeatherSnapshot): String {
        val sb = StringBuilder()
        sb.append("🌤️ ${snapshot.placeName}实时天气：\n")
        sb.append("☁️ ${WeatherCodes.describe(snapshot.weatherCode)}\n")
        sb.append("🌡️ 气温 ${num(snapshot.temperatureC)}°C（体感 ${num(snapshot.apparentC)}°C）\n")
        sb.append("💧 湿度 ${snapshot.humidityPercent ?: "?"}%\n")
        sb.append("💨 风速 ${num(snapshot.windKmh)} km/h")

        // 提示语优先级：降水 → 雷暴 → 高温 → 低温。互斥分支，一条即可。
        val temp = snapshot.temperatureC
        sb.append(
            when {
                WeatherCodes.isRain(snapshot.weatherCode) -> "\n\n☔ 有降水，出门记得带伞。"
                WeatherCodes.isStorm(snapshot.weatherCode) -> "\n\n⛈️ 有雷暴，注意安全。"
                temp != null && temp > 30 -> "\n\n☀️ 挺热的，记得多喝水。"
                temp != null && temp < 15 -> "\n\n🧣 有点冷，多穿一件。"
                else -> ""
            }
        )

        val tCode = snapshot.tomorrowCode
        if (tCode != null) {
            sb.append("\n\n📅 明天：${WeatherCodes.describe(tCode)}")
            if (snapshot.tomorrowMinC != null && snapshot.tomorrowMaxC != null) {
                sb.append("，${num(snapshot.tomorrowMinC)}°C ~ ${num(snapshot.tomorrowMaxC)}°C")
            }
            val rp = snapshot.tomorrowPrecipProb
            if (rp != null && rp > 50) {
                sb.append("（降水概率 $rp%）")
            }
        }
        return sb.toString()
    }

    /** 数值格式化：缺失 → "?"；有值 → 保留 1 位小数并去掉无意义的 ".0" */
    private fun num(value: Double?): String {
        if (value == null || value.isNaN() || value.isInfinite()) return "?"
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }
}
