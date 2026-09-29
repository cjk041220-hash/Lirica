package com.lirica.android.life

import com.lirica.android.automation.agent.LifeTool
import com.lirica.android.automation.agent.ToolExecutionResult
import com.lirica.android.network.model.ToolDefinition
import com.lirica.android.network.model.ToolFunction
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 天气查询结果。`isSuccess=false` 时 [text] 仍是**给大模型看的中文说明**
 * （而非异常栈）——工具失败也要让模型能自然地把这件事说出口，
 * 这是"绝不击穿对话主链路"纪律在生活工具上的延续。
 */
data class WeatherOutcome(val isSuccess: Boolean, val text: String)

/**
 * Open-Meteo 天气查询服务。
 *
 * 为什么选 Open-Meteo：**完全免 Key**。本项目要分发给朋友使用，
 * 任何需要申请 Key 的数据源都会变成"每个朋友先去注册一个账号"的安装门槛；
 * 天气这类高频小工具体验不该背这个成本。
 *
 * 坐标解析三级回退：
 *   1. 调用方直传经纬度（未来接 GPS 时的路径）
 *   2. 本地常用城市表 [CityCoords]（零网络开销）
 *   3. Open-Meteo 官方 geocoding API（同样免 Key）
 */
class OpenMeteoWeatherService(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    companion object {
        private const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODING_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search"
        private const val HTTP_TIMEOUT_SECONDS = 10L

        fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    /**
     * 查询并格式化为中文播报文本。
     * 本方法**不抛异常**：所有失败路径都转成 `isSuccess=false` + 中文说明。
     */
    suspend fun query(city: String?, latitude: Double?, longitude: Double?): WeatherOutcome {
        val requestedCity = city?.trim().orEmpty()

        // ── 1. 坐标解析 ─────────────────────────────────────────
        val resolved: ResolvedLocation = when {
            latitude != null && longitude != null ->
                ResolvedLocation(latitude, longitude, requestedCity.ifBlank { "当前位置" })

            requestedCity.isBlank() ->
                return WeatherOutcome(false, "还不知道要查哪里的天气，告诉我一个城市名就行。")

            else -> CityCoords.lookup(requestedCity)
                ?.let { (lat, lon) -> ResolvedLocation(lat, lon, requestedCity) }
                ?: geocode(requestedCity)
                ?: return WeatherOutcome(
                    false,
                    "没有找到「$requestedCity」的位置。换个更具体的城市名试试，比如「广州」「上海」。"
                )
        }

        // ── 2. 拉取预报 ─────────────────────────────────────────
        val body = runCatching {
            val url = buildString {
                append(FORECAST_ENDPOINT)
                append("?latitude=").append(resolved.lat)
                append("&longitude=").append(resolved.lon)
                append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m")
                append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                // timezone=auto：按坐标自动取当地时区。参考实现在此硬编码 Asia/Hong_Kong，
                // 分发给不同地区的朋友时「今天/明天」会算错。
                append("&timezone=auto")
                append("&forecast_days=2")
            }
            httpClient.get(url).bodyAsText()
        }.getOrElse { e ->
            return WeatherOutcome(false, "天气服务没连上（${e.javaClass.simpleName}），稍后再问一次试试。")
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return WeatherOutcome(false, "天气服务返回的内容读不懂，稍后再试试。")

        val snapshot = parseSnapshot(root, resolved.placeName)
            ?: return WeatherOutcome(false, "拿到了天气数据但字段缺失，稍后再试试。")

        return WeatherOutcome(true, WeatherFormatter.format(snapshot))
    }

    // ── geocoding 回退 ─────────────────────────────────────────

    private suspend fun geocode(city: String): ResolvedLocation? {
        val encoded = runCatching { URLEncoder.encode(city, "UTF-8") }.getOrNull() ?: return null
        val body = runCatching {
            httpClient.get("$GEOCODING_ENDPOINT?name=$encoded&count=1&language=zh&format=json").bodyAsText()
        }.getOrNull() ?: return null

        val first = runCatching {
            json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return null

        val lat = first.double("latitude") ?: return null
        val lon = first.double("longitude") ?: return null
        val name = first.string("name")?.takeIf { it.isNotBlank() } ?: city
        return ResolvedLocation(lat, lon, name)
    }

    // ── 响应解析（全部走"缺字段就返回 null"，绝不抛） ──────────

    private fun parseSnapshot(root: JsonObject, placeName: String): WeatherSnapshot? {
        val current = root["current"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return null
        val code = current.int("weather_code") ?: return null

        val daily = root["daily"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val tomorrowCode = daily?.arrayInt("weather_code", TIME_INDEX_TOMORROW)
        // 各数组分别守卫长度：Open-Meteo 偶发返回不等长字段，
        // 只查其中一个会让其余越界（参考实现踩过这个坑，此处按字段独立取值）
        val tomorrowMax = daily?.arrayDouble("temperature_2m_max", TIME_INDEX_TOMORROW)
        val tomorrowMin = daily?.arrayDouble("temperature_2m_min", TIME_INDEX_TOMORROW)
        val tomorrowPrecip = daily?.arrayInt("precipitation_probability_max", TIME_INDEX_TOMORROW)

        return WeatherSnapshot(
            placeName = placeName,
            weatherCode = code,
            temperatureC = current.double("temperature_2m"),
            apparentC = current.double("apparent_temperature"),
            humidityPercent = current.double("relative_humidity_2m")?.toInt(),
            windKmh = current.double("wind_speed_10m"),
            tomorrowCode = tomorrowCode,
            tomorrowMaxC = tomorrowMax,
            tomorrowMinC = tomorrowMin,
            tomorrowPrecipProb = tomorrowPrecip
        )
    }

    private fun JsonObject.double(key: String): Double? =
        runCatching { this[key]?.jsonPrimitive?.doubleOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.arrayAt(key: String): JsonArray? =
        runCatching { this[key]?.jsonArray }.getOrNull()

    private fun JsonObject.arrayDouble(key: String, index: Int): Double? =
        runCatching { arrayAt(key)?.getOrNull(index)?.jsonPrimitive?.doubleOrNull }.getOrNull()

    private fun JsonObject.arrayInt(key: String, index: Int): Int? =
        runCatching { arrayAt(key)?.getOrNull(index)?.jsonPrimitive?.intOrNull }.getOrNull()

    private data class ResolvedLocation(val lat: Double, val lon: Double, val placeName: String)
}

/**
 * 天气生活工具：把 [OpenMeteoWeatherService] 挂到大模型的扁平工具清单上。
 */
class WeatherLifeTool(
    private val service: OpenMeteoWeatherService = OpenMeteoWeatherService()
) : LifeTool {

    companion object {
        const val TOOL_NAME = "get_weather"

        /**
         * 工具说明刻意写明"用户提到出门 / 冷热 / 带伞时也应主动调用"，
         * 而不只是"询问天气时"——生活工具的价值在于**被想起来**，
         * 若只在明确的"天气"关键词上触发，她就不会在你深夜说冷的时候去查一下。
         */
        private const val DESCRIPTION =
            "查询指定城市或坐标的实时天气与明日预报。用户问天气、提到出门/通勤/行程、" +
                "说冷说热、或深夜还在外面时都应主动调用。数据源为 Open-Meteo，无需 API Key。"
    }

    override val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            type = "function",
            function = ToolFunction(
                name = TOOL_NAME,
                description = DESCRIPTION,
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("city") {
                            put("type", "string")
                            put("description", "城市名称，例如「佛山」「广州」「上海」。用户未指明地点时可省略，改用坐标。")
                        }
                        putJsonObject("latitude") {
                            put("type", "number")
                            put("description", "纬度（可选，优先于 city）。已知用户 GPS 坐标时传入。")
                        }
                        putJsonObject("longitude") {
                            put("type", "number")
                            put("description", "经度（可选，优先于 city）。")
                        }
                    }
                    // 三个参数全可选：模型可以只给城市，也可以只给坐标。
                    // 二者都缺时由服务层返回一句"告诉我城市名"的友好说明，而不是参数校验报错。
                    putJsonArray("required") {}
                }
            )
        )
    )

    override suspend fun execute(name: String, args: JsonObject, toolCallId: String): ToolExecutionResult? {
        if (name != TOOL_NAME) return null

        val city = args["city"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val lat = args["latitude"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
        val lon = args["longitude"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }

        // 只给了一个坐标分量时按"未提供"处理，避免用半个坐标去查出一座无关城市
        val hasCoords = lat != null && lon != null

        return try {
            val outcome = service.query(
                city = city,
                latitude = if (hasCoords) lat else null,
                longitude = if (hasCoords) lon else null
            )
            ToolExecutionResult(
                toolCallId = toolCallId,
                isSuccess = outcome.isSuccess,
                output = outcome.text
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult(
                toolCallId = toolCallId,
                isSuccess = false,
                output = "查天气的时候出了点问题（${e.javaClass.simpleName}），稍后再试。"
            )
        }
    }
}

/** 明日预报在 daily 数组中的下标（forecast_days=2 → [0]=今天、[1]=明天） */
private const val TIME_INDEX_TOMORROW = 1
