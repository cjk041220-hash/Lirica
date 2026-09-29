package com.lirica.android.automation.agent

import com.lirica.android.network.model.ToolDefinition
import kotlinx.serialization.json.JsonObject

/**
 * 生活工具扩展契约 (Life Tool)。
 *
 * 设计意图：`NativeToolAgent` 原本是"手机操控"agent，工具集围绕设备代行
 * （open_app / click_element / input_text / swipe …）。但大模型只看到一份
 * **扁平的工具清单**，所以需要一个不与设备操控耦合的挂载点，把"她能为用户
 * 做点什么"这类纯网络 / 纯本地能力接进来（天气、日程、搜索…）。
 *
 * 为什么不直接往 NativeToolAgent 的 when 里加分支：
 * 1. agent 的 when 会随工具数线性膨胀，最终变成一团难以定位的巨型分支
 * 2. 生活工具本可脱离 HelixGuiCoordinator / AccessibilityService 单测，
 *    塞进 agent 后会被迫带上整套设备操控依赖
 * 3. 后续扩工具只需新增实现类 + 在装配处追加一行，不动 agent 主体
 *
 * 契约约定：
 * - `execute` 返回 `null` 表示"这不是我的工具"，由 NativeToolAgent 继续向下匹配；
 *   绝不能对不属于自己的 name 抛异常，否则会打断其它工具的派发。
 * - 实现内部必须自兜异常，把失败转成 `ToolExecutionResult(isSuccess = false)`，
 *   与既有工具"绝不击穿对话主链路"的纪律一致。
 */
interface LifeTool {

    /** 本工具向大模型声明的函数定义（可实现多个） */
    val definitions: List<ToolDefinition>

    /**
     * 执行一次工具调用。
     *
     * @param name 工具名（与 [definitions] 中声明的一致）
     * @param args 已解析的参数对象（调用方保证非 null；实现仍需对缺字段做防御）
     * @param toolCallId 用于构造 tool 回执消息的 ID
     * @return 执行结果；`null` 表示该 name 不属于本工具
     */
    suspend fun execute(name: String, args: JsonObject, toolCallId: String): ToolExecutionResult?
}
