package com.xiaoxi.android.life

import com.xiaoxi.android.automation.agent.LifeTool
import com.xiaoxi.android.automation.agent.ToolExecutionResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气工具的网络路径与派发契约单测（Ktor MockEngine，不出网）。
 */
class WeatherToolTest {

    companion object {
        /** forecast_days=2 的真实响应结构（字段名与 Open-Meteo 一致） */
        private const val FORECAST_JSON =
            """{"current":{"temperature_2m":26.3,"relative_humidity_2m":78,"apparent_temperature":28.1,""" +
                """"weather_code":61,"wind_speed_10m":12.4},""" +
                """"daily":{"weather_code":[61,63],"temperature_2m_max":[29.0,31.0],""" +
                """"temperature_2m_min":[22.0,24.0],"precipitation_probability_max":[80,60]}}"""

        private const val GEOCODING_JSON =
            """{"results":[{"name":"亚特兰大","latitude":33.75,"longitude":-84.39,"country":"美国"}]}"""

        private fun clientFor(handler: (url: String) -> Pair<HttpStatusCode, String>): HttpClient {
            val engine = MockEngine { request ->
                val (status, body) = handler(request.url.toString())
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            return HttpClient(engine)
        }
    }

    // ── 服务层：解析与坐标回退 ────────────────────────────────

    @Test
    fun query_tableCity_skipsGeocodingAndFormats() = runTest {
        val requested = mutableListOf<String>()
        val client = clientFor { url ->
            requested.add(url)
            HttpStatusCode.OK to FORECAST_JSON
        }
        val outcome = OpenMeteoWeatherService(client).query(city = "佛山", latitude = null, longitude = null)

        assertTrue(outcome.isSuccess)
        assertTrue(outcome.text.contains("佛山实时天气"))
        assertTrue(outcome.text.contains("小雨"))
        assertTrue(outcome.text.contains("26.3"))
        assertTrue(outcome.text.contains("带伞"))
        assertTrue(outcome.text.contains("明天"))
        // 佛山在本地坐标表内 → 不应触发 geocoding 请求
        assertTrue(requested.single().startsWith("https://api.open-meteo.com"))
    }

    @Test
    fun query_unknownCity_fallsBackToGeocodingThenForecasts() = runTest {
        val client = clientFor { url ->
            when {
                url.contains("geocoding-api") -> HttpStatusCode.OK to GEOCODING_JSON
                else -> HttpStatusCode.OK to FORECAST_JSON
            }
        }
        val outcome = OpenMeteoWeatherService(client).query(city = "亚特兰大", latitude = null, longitude = null)

        assertTrue(outcome.isSuccess)
        // 地名取 geocoding 返回的 name，而不是调用方原始输入
        assertTrue(outcome.text.contains("亚特兰大实时天气"))
    }

    @Test
    fun query_unknownCityAndGeocodingEmpty_returnsFriendlyNotFound() = runTest {
        val client = clientFor { url ->
            when {
                url.contains("geocoding-api") -> HttpStatusCode.OK to """{"results":[]}"""
                else -> HttpStatusCode.OK to FORECAST_JSON
            }
        }
        val outcome = OpenMeteoWeatherService(client).query(city = "不存在的城市xyz", latitude = null, longitude = null)

        // 参考实现的教训：对不存在/错别字的地名模糊匹配会报成另一座城市，
        // 所以这里必须"没找到"降级，宁可不给也不给错
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.text.contains("没有找到"))
        assertFalse(outcome.text.contains("实时天气"))
    }

    @Test
    fun query_blankCity_returnsFriendlyPrompt() = runTest {
        val client = clientFor { HttpStatusCode.OK to FORECAST_JSON }
        val outcome = OpenMeteoWeatherService(client).query(city = "  ", latitude = null, longitude = null)
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.text.contains("城市名"))
    }

    @Test
    fun query_coordinatesOnly_skipCityResolution() = runTest {
        val requested = mutableListOf<String>()
        val client = clientFor { url ->
            requested.add(url)
            HttpStatusCode.OK to FORECAST_JSON
        }
        val outcome = OpenMeteoWeatherService(client).query(city = null, latitude = 23.02, longitude = 113.12)

        assertTrue(outcome.isSuccess)
        assertTrue(outcome.text.contains("当前位置"))
        assertTrue(requested.single().startsWith("https://api.open-meteo.com"))
    }

    @Test
    fun query_httpError_doesNotThrow_returnsFailure() = runTest {
        val client = clientFor { HttpStatusCode.InternalServerError to "" }
        val outcome = OpenMeteoWeatherService(client).query(city = "佛山", latitude = null, longitude = null)

        // 契约：失败也要给模型一句能自然说出口的中文，绝不把异常抛回对话主链路
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.text.isNotBlank())
    }

    @Test
    fun query_malformedJson_doesNotThrow_returnsFailure() = runTest {
        val client = clientFor { HttpStatusCode.OK to "不是 JSON 的内容" }
        val outcome = OpenMeteoWeatherService(client).query(city = "佛山", latitude = null, longitude = null)
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.text.isNotBlank())
    }

    @Test
    fun query_unequalDailyArrays_doesNotCrash() = runTest {
        // 参考实现踩过的坑：Open-Meteo 偶发返回不等长字段，
        // 只查其中一个数组会让其余越界，把本可展示的天气整体降级
        val ragged =
            """{"current":{"temperature_2m":20.0,"relative_humidity_2m":60,"apparent_temperature":19.0,""" +
                """"weather_code":0,"wind_speed_10m":5.0},""" +
                """"daily":{"weather_code":[0,1],"temperature_2m_max":[25.0],"temperature_2m_min":[15.0,16.0]}}"""
        val client = clientFor { HttpStatusCode.OK to ragged }
        val outcome = OpenMeteoWeatherService(client).query(city = "佛山", latitude = null, longitude = null)

        assertTrue(outcome.isSuccess)
        assertTrue(outcome.text.contains("🌤️"))
        // weather_code 数组有 2 个元素 → 明日段仍在
        assertTrue(outcome.text.contains("明天："))
        // 但 temperature_2m_max 只有 1 个元素 → 温度区间整体缺失而不是越界崩溃
        assertFalse(outcome.text.contains("~"))
    }

    @Test
    fun query_missingCurrentBlock_returnsFailure() = runTest {
        val client = clientFor { HttpStatusCode.OK to """{"daily":{}}""" }
        val outcome = OpenMeteoWeatherService(client).query(city = "佛山", latitude = null, longitude = null)
        assertFalse(outcome.isSuccess)
    }

    // ── LifeTool 契约 ────────────────────────────────────────

    @Test
    fun tool_declaresWeatherTool() {
        val tool = WeatherLifeTool(OpenMeteoWeatherService(HttpClient(MockEngine { respond("", HttpStatusCode.OK, headersOf()) })))
        assertEquals(1, tool.definitions.size)
        assertEquals("get_weather", tool.definitions[0].function.name)
    }

    @Test
    fun tool_executesWeatherAndReturnsResult() = runTest {
        val client = clientFor { HttpStatusCode.OK to FORECAST_JSON }
        val tool = WeatherLifeTool(OpenMeteoWeatherService(client))

        val result = requireNotNull(
            tool.execute(
                name = "get_weather",
                args = buildJsonObject { put("city", "佛山") },
                toolCallId = "call_1"
            )
        ) { "get_weather 应由天气工具接住" }
        assertTrue(result.isSuccess)
        assertEquals("call_1", result.toolCallId)
        assertTrue(result.output.contains("佛山实时天气"))
    }

    @Test
    fun tool_returnsNullForNamesItDoesNotOwn() = runTest {
        // 契约：不是自己的工具必须返回 null（而不是抛异常），
        // 否则会打断 NativeToolAgent 对其它工具的派发
        val tool = WeatherLifeTool(OpenMeteoWeatherService(HttpClient(MockEngine { respond("", HttpStatusCode.OK, headersOf()) })))
        for (foreign in listOf("open_app", "click_element", "schedule_wake", "Get_Weather")) {
            assertNull(
                "name=$foreign 不应由天气工具接走",
                tool.execute(foreign, buildJsonObject { put("city", "佛山") }, "call_2")
            )
        }
    }

    @Test
    fun tool_singleCoordinateComponent_treatedAsMissing() = runTest {
        val client = clientFor { HttpStatusCode.OK to FORECAST_JSON }
        val tool = WeatherLifeTool(OpenMeteoWeatherService(client))

        // 只给纬度：半个坐标会查出一座无关城市，必须按"未提供"处理
        val result = requireNotNull(
            tool.execute(
                name = "get_weather",
                args = buildJsonObject { put("latitude", 23.02) },
                toolCallId = "call_3"
            )
        ) { "半个坐标也应由天气工具接住并降级" }
        assertFalse(result.isSuccess)
        assertTrue(result.output.contains("城市名"))
    }
}
