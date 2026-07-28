# 小汐 v5.0 全项目检查 · 产品报告

**日期**：2026-07-26
**检查对象**：`E:\小汐sonetto`（HEAD `00d4747` + 未提交工作区改动）
**检查方法**：5 路并行专项审查（后端架构 / API 契约 / Agent 工具 / 前端 / 死代码）+ 3 轮全量测试 + 关键发现人工复核（含 langgraph 源码级验证）

---

## 一、执行摘要

| 维度 | 结论 |
|------|------|
| 全量测试 | ✅ **3 轮全绿**：234 通过 / 1 跳过（Windows 下 posix 权限测试，合理跳过）/ 0 失败，耗时 26.5s / 22.4s / 22.4s，**无 flaky** |
| 架构合理性 | ✅ 优秀。单进程单连接假设自洽且 fail-closed 闭环，分层清晰；唯 `heartbeat.py`（786 行）有 god module 倾向 |
| 前后端契约 | ✅ 12 个实调端点逐一核对全部匹配，SSE 协议双端一致，**无硬断链** |
| Agent 工具 | ✅ 实际注册 **18 个**（README 写 15 已过时），schema 质量普遍良好，失败路径几乎都有角色化文案 |
| 功能可实现性 | ⚠️ 除**立绘资源断链（当前工作区小汐不可见）**外，聊天/工具/记忆/日记/心跳/推送/塔罗各链路代码层面均可跑通 |
| 死代码 | ✅ 很干净，约 30 行可删（1 个死模型 + 10 个死 import + 4 条死 CSS）；漂移问题（文档/脚本）比死代码本身多 |

**一句话总评**：这是一个防御性自觉远超其体量的代码库——几乎每处微妙设计都有注释解释为什么，每处历史缺陷都有回归测试钉死。没有数据损坏或正常路径安全击穿类严重缺陷；但存在 1 个正在生效的视觉层生产事故（立绘 404）和若干"防线看起来在、实际不在"的边界问题，建议在公网部署前修完 §5 的 P0/P1 清单。

---

## 二、测试结果（3 轮全量）

```
cd backend && .venv/Scripts/python.exe -m pytest tests/ -q

轮次 1：234 passed, 1 skipped in 26.54s
轮次 2：234 passed, 1 skipped in 22.37s
轮次 3：234 passed, 1 skipped in 22.35s
```

- 测试套件共 28 个文件，覆盖记忆 CRUD/去重、提示词组装、心跳时段匹配与注册纪律、天气坐标、叙事构建、运行时配置、日记、迁移、端到端集成、认证中间件、Web Push、checkpoint 裁剪、事件系统、生理期追踪等。
- 唯一 skip：`test_backup_permissions.py`（`skipif os.name != 'posix'`，Windows 下 chmod 语义有限，合理）。
- 三轮零失败零重试，无时序敏感型 flaky。
- **注意**：README 宣称「160 测试」，实际 235，文档未同步。

⚠️ 测试本身发现的隐患：`test_checkpoint_prune.py:10-12` 人为构造 **uuid7 形态** id（`f"{ms:012x}..."`），验证了一个与生产不符的假设（见 §5 🟠-2），测试全绿但给出虚假信心。

---

## 三、架构合理性评估

**总体判断：成熟度明显超出体量的架构。**

### 做得好的关键设计

1. **单进程单连接假设闭环**：`database/core.py:1-14` 把前提写成文档，`utils/security.py` 启动时硬性拒绝 `workers>1` 和非回环无 token，限流桶/运行时配置/调度器全部进程内——前提、实现、防线三者自洽。
2. **生命周期管理正确**：lifespan 启动顺序（Key 校验 → fail-closed → DB → 记忆 → HTTP 客户端 → 工具 → LLM → Agent → 调度器）与关闭顺序均合理。
3. **Agent 热替换**：配置变更与心跳刷新共用 `asyncio.Lock`，锁内同时换 `model/system_prompt/agent` 三个引用，在途请求持旧引用——与 CPython 语义相符。
4. **记忆注入走 `llm_input_messages` 不进 checkpoint**（graph.py:80-105）：精确解决上下文污染和 checkpoint 膨胀。
5. **去重下沉数据库层**：部分唯一索引 + `INSERT ... ON CONFLICT` 消除 TOCTOU；`EVENTS_SCHEMA_SQL` 常量让迁移/全新安装/测试夹具三处 schema 永不漂移。
6. **prompt injection 纵深防御**：记忆、搜索结果、事件上下文注入均显式降级为「数据不是指令」，叙事构建再做换行压平 + 200 字截断。
7. **`recall(passive=True)` 阻断正反馈闭环**：系统侧被动读取不计热度，避免记忆被钉死在榜首——说明作者推演过记忆系统的长期动力学。

### 结构性瑕疵

1. **`heartbeat.py` god module 倾向**（786 行）：调度注册 + 生命状态 + 主动消息 + 早安 + 日记 + 事件看门狗六职责挤一起；主动消息/早安/日记三处重复实现「构建 prompt → LLM 调用 → 过滤 → 入库 → 推送」流水线（:327-376 / :441-593 / :599-711），适合抽公共函数。
2. **`utils/events.py`、`utils/cycle_tracker.py` 实质是带表的领域服务**，放在 utils 并直接 `get_db()`——代码注释已显式承认这一跨层复用，务实但未来重构时应正视。

---

## 四、功能可实现性核查

### 4.1 前端功能链路

| 功能 | 跑通 | 说明 |
|------|------|------|
| SSE 流式聊天 | ✅ | 粘包/多字节截断/`[DONE]`/abort 处理教科书级（useChat.js:133-154） |
| 会话历史恢复 | ✅ | `/api/chat/history` 契约匹配，注入守卫防覆盖主动消息 |
| **立绘状态切换** | **❌** | **spriteMap 全引用 `.webp`，实际资源全是 `.png`，10 张立绘全 404（见 §5 🔴-1）** |
| 状态归并优先级 | ✅ | 聊天流程态 > 深夜唤醒 > 犯困 > mood 映射，逻辑自洽 |
| 配置面板 | ✅ | 部分字段 POST、seq 防过期响应、250ms 防抖对齐后端限流 |
| 记忆/日记面板 | ✅ | 只读展示（删除/导出/清空仅 API 无 UI，属有意设计） |
| Web Push 订阅/未读闭环 | ⚠️ | 链路通；已读同步失败后永不重试（🟡-12），多条未读注入倒序（🟡-11） |
| PWA 离线 | ⚠️ | 框架完整；dev 模式也注册 SW（🟠-9）、导航 cache-first 发版吃旧包（🟠-10） |
| 构建产物 ↔ FastAPI | ✅ | `base:'/'` + dist 挂载 `/`，/api 优先，同源无需 CORS |

### 4.2 后端功能链路

| 功能 | 结论 | 备注 |
|------|------|------|
| 心跳（2h 心情+事件+维护） | ✅ | 调度注册纪律有两条回归测试钉死 v0.4.4 血泪教训 |
| 早安/晚间日记/主动消息 | ⚠️ | 可跑通；早安天气字段 None 时整体丢弃且日志误报（🟠-5）；晚间补记覆盖白天手记标题（🟡-7） |
| 记忆系统 | ✅ **最佳实践级** | remember→展示旧记忆→update/delete 的 agentic 闭环，去重原子化 |
| 日记系统 | ✅ | 原子 UPSERT；`read_diary` 日期格式错误误报为「没写日记」（🟡-4） |
| 塔罗 | ✅ | 78 张牌 + 3 牌阵 + 损坏降级；随机性用 OS 熵无可复现 bug；但系统提示词零提及，LLM 大概率只在用户明说时才调用 |
| 事件「惦记」 | ✅ | 去重 + 归档健全；接受过去日期/非法 event_type（🟡-3） |
| 天气 | ✅ | 50 城字典零依赖；字典外需高德 Key，失败优雅（0.0°C 恰好 falsy 跳过提示，🟡-6） |
| 地图/导航 | ⚠️ | GPS 注入格式与工具契约相反（🟠-4）；Key 缺失伪装成「没找到」（🟠-5 工具侧） |
| 网络搜索 | ✅ | Key 检查显式；Tavily 客户端每调用新建不关闭（🟡-5） |
| 备份/裁剪 | ⚠️ | 每 thread 保留 10 个 checkpoint 有效；**僵尸 thread 整体删除恒为假**（🟠-2）；备份窗口仅约 10h（🟡-8） |
| 认证/限流 | ✅ | 白名单登记有回归测试；CORS 顺序缺陷仅影响跨域部署（🟠-7） |

### 4.3 Agent 工具注册清单

实际注册 **18 个**（`tools/__init__.py:44-63`，README 的「15 个」漏写 `update_memory`、`create_event`、`list_events`）：

get_weather / search_nearby / geocode_address / fuzzy_search_place / get_transit_route / get_cycling_route / web_search / remember / update_memory / recall / list_memories / delete_memory / export_memories / write_diary / read_diary / tarot / create_event / list_events

**无定义了没注册、也无注册了没实现的工具。** schema docstring 普遍写明调用时机与冲突处理流程。缺口：`soul.py` 的 TOOL_GUIDELINES 只覆盖 14 个，**tarot / read_diary / list_memories 零提及**（🟡-1）。

---

## 五、问题清单（按优先级）

> 标注「✅ 已人工复核」的条目已由本次检查直接读源码/资源确认，非仅 agent 结论。

### 🔴 P0 — 正在生效的生产事故（1 项）

**🔴-1 立绘资源扩展名全错，当前工作区下小汐在页面上不可见** ✅ 已复核
- `web/src/components/CharacterSprite.jsx:4-13` 引用 `/sprites/*.webp`（10 个状态）；`web/public/sprites/` 与 `web/dist/sprites/` 实际只有 10 个 `.png`。
- git 状态确认：9 个 webp 在工作区被删（未提交的 ` D`）、10 个 png 未跟踪（`??`），组件仍引用 webp → 全部 404。HEAD 提交态可显示 9 张（但 HEAD 也无 `halfawake.webp`，该状态回落 idle）。
- 影响：产品最核心的「活着」的视觉层完全不可见；`<img>` 无 `onError` 回退，静默失败。
- 修法：`spriteMap` 统一改 `.png` 并把 png 纳入 git；或跑 `scripts/gen_assets.py` 重新生成 webp（注意该脚本会删 png）。给 `<img>` 加 `onError` 降级到 idle；E2E 增加 `naturalWidth > 0` 断言。

### 🟠 P1 — 公网部署前建议修复（11 项）

**🟠-1 启动 fail-closed 可被 `--host=0.0.0.0` 等号写法绕过** ✅ 已复核
- `utils/security.py:31` 只识别两 token 的 `--host VALUE`；单 token `--host=0.0.0.0`（uvicorn/click 支持）或 `UVICORN_HOST` 环境变量会回落默认回环 → 无 token 校验被放行。
- 已复核：Dockerfile CMD 用空格写法、main.py 硬编码 127.0.0.1，容器与一键启动安全；风险仅限手工启动暴露场景——恰是该防线存在的意义。
- 修法：解析 `--host=VALUE` 前缀形式 + `UVICORN_HOST`/`HOST` 环境变量；或反转逻辑「无法确信回环时按非回环处理」。

**🟠-2 checkpoint 僵尸线程清理基于 uuid7 假设，实际 langgraph 用 uuid6 → 该删除恒为假** ✅ 已复核
- `database/core.py:216-233` 注释称 checkpoint_id 前 12 位 hex = unix 毫秒；已读 `.venv/.../langgraph/checkpoint/base/id.py:79-109` 核实为 **uuid6**（Gregorian 100ns epoch >> 12）。2026 年真实前缀约 `1f1b…`，unix-ms 阈值约 `019e…`，`MAX(...) < 阈值` 永远为假。
- 影响：`drop_inactive_days` 僵尸 thread 清理从未删除过任何行（每 thread 10 行的裁剪仍有效，故非数据损坏，仅 thread 数无界增长）；`test_checkpoint_prune.py:10-12` 构造假 uuid7 数据，测试全绿但验证错误假设。
- 修法：阈值在 uuid6 时间域计算（`ts = unix_ns//100 + 0x01B21DD213814000`，取 `(ts>>12)&0xFFFFFFFFFFFF`），测试改用真实 `uuid6()` 生成数据。

**🟠-3 pre_model_hook 裁剪在找不到 human 边界时可能留下孤儿 ToolMessage → 整轮 400**
- `agent/graph.py:60-70`：`recursion_limit=25` 允许单轮约 50 条工具链；若长工具链恰好是对话尾部，裁剪点落在链中间 → DeepSeek 对无主 ToolMessage 返回 400。用户下一条消息可自愈，属瞬时故障。
- 修法：找不到 human 边界时放弃本轮裁剪，补「单轮超长工具链」回归测试。

**🟠-4 GPS 注入坐标格式与工具参数格式相反，把 LLM 引向错误路径** ✅ 已复核
- `api/routes/chat.py:123` 与 `:194` 注入「坐标为 `(lat, lon)`…作为 location 参数」（纬度在前）；`tools/map_tools.py:60` 要求 `location` 为「经度,纬度」。LLM 若照注入文本传 `22.30,114.17` → 高德 INVALID_PARAMS → 软失败「没找到附近有什么」。讽刺的是 LLM **不传** location 才安全（工具内部回退正确拼 `lon,lat`）。
- `tests/test_integration.py:238` 还把错误格式写进了断言。
- 修法：注入文本改为经度在前并给出可复制的串，或删注入完全依赖工具内部回退；同步修测试。

**🟠-5 高德 Key 缺失/无效时地图工具以「零结果」话术软失败，LLM 无法区分配置问题与真实无结果**
- `tools/map_tools.py:39` 空 key 照发；高德返回 INVALID_USER_KEY 后四个工具分别回「没找到」「好像没有这个地方」「找不到这条路，地址是不是写错了呀？」——LLM 会反复换参空转，最终自信地告诉用户「附近没有咖啡店」。对照 `web_search` 有显式 key 检查。
- 修法：`_amap_request` 开头加 key 空检查返回「地图功能未开启」类可辨识文案。

**🟠-6 工具异常总闸错误消息未脱敏，潜在 Key 泄漏三面暴露**
- `agent/graph.py:146`：`f"工具执行出错：{type(exc).__name__}: {exc}"` 原样嵌入异常文本。高德 Key 走 URL query，httpx 异常 str 含完整 URL。目前各工具自带 try/except 使其不可达，但总闸的全部意义就是接住漏网异常——一旦某新工具忘写兜底，Key 同时进 LLM 上下文、checkpoint、SSE `tool_end` 事件。
- 修法：总闸改固定文案，细节走已有的 `log_tool_error`（errors.py 有现成脱敏正则）。

**🟠-7 中间件顺序使 401/429 响应绕过 CORS，跨域部署时认证流程整体退化**
- `api/server.py:184-199` 先 add CORS 后 add Auth（Starlette 头插法 → Auth 最外层）；Auth 短路返回的 401/429 不带 `Access-Control-Allow-Origin`。当前同源部署无感；一旦前端跨域（README 提供 `CORS_ORIGINS` 即暗示支持），所有无 token/超频请求在浏览器侧表现为不透明网络错误，前端 401 引导全部失效。
- 修法：Auth 移到 CORS 之前 add（CORS 最外层），或在 401/429 响应手动补 CORS 头。

**🟠-8 `/docs`、`/openapi.json` 在启用 token 后仍完全公开**
- `server.py:171-176` 未设 `docs_url=None`；`_PROTECTED_PREFIXES` 只含 `/api/*`。公网部署时完整 API schema（含 `/api/memories/clear` 参数说明）任人查看。
- 修法：生产构建关闭 docs，或中间件对 `/docs` 同样要求 token。

**🟠-9 开发模式也注册 Service Worker，cache-first 会冻住 HMR**
- `web/src/main.jsx:6-10` 无条件注册 sw.js；dev 模块被缓存后「改了不生效」，且注册错误被 `.catch(() => {})` 吞掉。
- 修法：`if (import.meta.env.PROD)` 再注册。

**🟠-10 sw.js 对导航请求 cache-first，发版后老用户持续吃旧包**
- `web/public/sw.js:16-32` 更新唯一依赖人工 bump `CACHE_NAME`（'xiaoxi-room-v2'）；忘 bump = 静默回滚。
- 修法：导航请求（`request.mode === 'navigate'`）改 network-first、失败回退缓存；缓存版本构建期注入。

**🟠-11 sw.js push 事件 JSON 解析无兜底，坏载荷 = 静默丢推送**
- `sw.js:35-53`：`event.data.json()` 对非 JSON 抛异常 → `showNotification` 不执行，零通知零感知。
- 修法：`try { data = event.data?.json() ?? {} } catch {}`。

### 🟡 P2 — 体验与健壮性（20 项，摘要）

| # | 问题 | 位置 |
|---|------|------|
| 1 | `soul.py` TOOL_GUIDELINES 漏 tarot/read_diary/list_memories | agent/soul.py:166-202 |
| 2 | `sendMessage` 防重发守卫是死代码（空依赖数组 stale closure，被 UI 层守卫掩盖） | web/src/hooks/useChat.js:73-75 |
| 3 | `create_event` 不校验 event_type 白名单、接受过去日期 | backend/tools/events.py:42-52 |
| 4 | `read_diary` 日期格式错误误报为「这一页是空白的」 | backend/tools/diary.py:81-87 |
| 5 | Tavily 客户端每调用新建不关闭；TavilyError 不在重试白名单 | backend/tools/search.py:27 |
| 6 | weather 恰好 0.0°C 时 falsy 跳过「有点冷」提示；timezone 硬编码 HKT | backend/tools/weather.py:82,176 |
| 7 | 自动日记晚间补记无条件覆盖白天手记标题 | backend/heartbeat.py:699-707 |
| 8 | 备份每 2h × 保留 5 份 → 可恢复窗口仅约 10h；memories JSON 备份不在轮转内 | backend/database/core.py:294-353 |
| 9 | `update_config` 先改 `_runtime_config` 再重建，重建失败则配置与 Agent 永久漂移 | backend/api/routes/config.py:163-187 |
| 10 | 早安：今日预报字段为 None/空数组时丢弃已构建的当前天气，日志误报 API 失败 | backend/heartbeat.py:490-505 |
| 11 | 多条未读主动消息注入聊天时倒序出现 | web/src/App.jsx:87-93 + push.py:104 |
| 12 | `markRead` 失败后 `injectedRef` 不回滚，消息永久挂未读挤占名额 | web/src/hooks/usePush.js:81-93 |
| 13 | 流内错误伪装成成功：出错/未就绪时立绘演「happy」（前端 succeeded 判定只看流读完） | chat.py:178-183/237-240 + useChat.js:229 |
| 14 | SSE `{"type":"state"}` 是空承诺：前端已实现消费、README 已宣称、后端从不发送 | chat.py:213-231 |
| 15 | `push/subscribe` 业务错误用 HTTP 200 返回，前端只查 `res.ok` 误判成功 | push.py:55-56 + usePush.js:47-52 |
| 16 | 输入框无 maxLength（后端限 2000 字 → 422 通用文案）；429 无 Retry-After | ChatInput.jsx:32-42 + auth.py:175-179 |
| 17 | 时区口径不一致：天空背景用本地时，深夜判定用 HKT | App.jsx:15-18 vs SkyBackground.jsx:3-9 |
| 18 | 生理期 `add_record` 不校验日期格式，一条坏数据静默禁用整个关心功能 | utils/cycle_tracker.py:24-46 |
| 19 | `recall`/`recall_by_category` LIKE 未转义通配符；`remember` 不校验 category 非空 | memory/manager.py:213-259 |
| 20 | 无 ErrorBoundary（渲染异常整页白屏）；禁缩放 + 无 aria-live 等可访问性缺口 | web/src/main.jsx:12-16, index.html:7 |

---

## 六、死代码扫描结论

**维护得很干净**——v0.4→v0.5 大重构没有留下成块死函数/死端点/死工具；`get_all_tools` / `include_router` / `scheduler.register_jobs` 三张注册表与实际定义完全一致；requirements.txt 与 package.json 无冗余项（疑似项逐一验证均为传递依赖）。

### 确认可删（约 30 行，零风险）

| 位置 | 类型 | 证据 |
|------|------|------|
| `backend/api/routes/chat.py:100-101` `DeleteMemoryRequest` | 死 pydantic 模型 | 全库仅定义处 1 次匹配；DELETE 端点用路径参数 |
| `tools/diary.py:3`、`memory/manager.py:15`、`database/core.py:18`、`heartbeat.py:22` | 未使用的 `timezone, timedelta` import | grep 零使用 |
| `utils/web_push.py:18` | 未使用的 `timezone, timedelta`（datetime 在用） | 同上 |
| `utils/cycle_tracker.py:7`、`utils/events.py:22` | 未使用的 `timezone` | 同上 |
| `web/src/index.css:7,11,12,123-125` | 4 条死 CSS（`.input-glow` 等） | 全 src 零引用 |

### 疑似死代码（需人工确认后删）

- `utils/cycle_tracker.py:138` `seed_initial_records`：生产零调用（初始记录已改由迁移 v4 写入），仅测试用。
- `memory/__init__.py:2-5` 再导出：所有使用方都从子模块直接导入，零引用。
- `CharacterSprite.jsx:11-12` `wave`/`reading` 状态表项 + 2 张对应立绘：无任何代码把状态设为这两个值（后端从不发 `state` 事件）。
- `web/package.json` `@types/react`/`@types/react-dom`：项目无任何 TypeScript 文件。
- `utils/web_push.py:90` `"badge": "/badge-72.png"`：public 无此文件，悬空引用（sw.js 已自行改用 icon-192）。

### 比死代码更值得处理的漂移

1. **README 幽灵条目**：结构树列了 `docs/PRODUCT_REPORT.md`、`docs/ACCEPTANCE_REPORT_2026-07-23.md`、`docs/XIAOXI_GUIDE.md`，工作区均已删除（本报告文件名为新产物，不冲突）。
2. **README 陈旧**：「15 个工具」→ 实为 18；「160 测试」→ 235；「9 张立绘（WebP）」→ 10 张 PNG；API 表缺 `/api/chat/history`、`/api/status`、`/api/diaries`；tools/utils/routes/测试树均缺项。
3. **`scripts/verify_tools.py:116` 已损坏**：`recall.ainvoke({"query": "测试"})`，但 recall 签名是 `recall(category="")`，LangChain 参数校验直接报错，该用例永远判 EMPTY；且未覆盖 5 个新工具。
4. **`scripts/gen_assets.py`** 依赖 Pillow 但未写入 requirements.txt；`trim_sprites.py` 只 glob webp，当前目录跑什么都不做。
5. 根 `.gitignore` 5 条遗留条目指向已不存在的文件（base.py / prompts.py / scoring.py / storage.py / mem0-research-report.md）；Dockerfile 与 compose 重复定义 healthcheck。

---

## 七、修复优先级建议

**今天就修（10 分钟内）**
1. 🔴-1 立绘断链：spriteMap 改 `.png` + 把 png 纳入 git（或重生成 webp），加 `onError` 回退。不修则页面没有小汐。

**公网部署前修（半天）**
2. 🟠-8 `/docs` 生产关闭；🟠-7 CORS 中间件顺序（跨域部署前提）。
3. 🟠-1 `--host=` 等号与环境变量解析；🟠-6 工具总闸脱敏。
4. 🟠-9/10/11 SW 三件套（PROD 才注册、导航 network-first、push 解析兜底）——否则每次发版都是静默回滚赌博。

**近期迭代修（体验直接相关）**
5. 🟠-4 GPS 格式矛盾 + 🟠-5 高德 Key 缺失可辨识文案：这两个会让小汐在配置/坐标边界上**自信地说错话**，对陪伴型人设最伤信任。
6. 🟠-2 checkpoint uuid6 阈值修正 + 测试数据换真实 uuid6。
7. 🟠-3 裁剪边界兜底。

**顺手清理（一次提交）**
8. 死代码 30 行 + README 同步（18 工具/235 测试/API 表补 3 条/删幽灵条目）+ 修复 verify_tools.py 的 recall 调用。

---

## 八、亮点记录（值得保持的工程文化）

1. **缺陷固化防御**：v0.4.4「lambda 包裹协程导致心跳静默」写进 docstring + 两条回归测试分别钉死注册形态与真实执行。
2. **错误脱敏纪律**（utils/errors.py）：意识到 httpx 异常 str 会携带 URL 里的 API Key，对 LLM 返回固定文案、日志侧遮罩——堵死「密钥经异常文本泄漏到对话/checkpoint」的隐蔽路径。（本次发现的 🟠-6 是这条纪律唯一的漏网点。）
3. **web_push 实战细节密度**：显式 timeout 防半死端点挂死事件循环、gather 隔离单设备失败、`ttl=86400` 绕过 WNS 冲突、每次新建 claims 字典（pywebpush 原地修改）、UPSERT 原子订阅——每一条都是踩过坑的样子。
4. **SSE 解析与 AbortController 时序**：controller 创建提前到 GPS await 之前（6 秒定位窗口内点停止也生效）、`streamDone` 修复史注释、`tool_end` 反向扫描挂回并发工具提示。
5. **前后端约束联动**：useConfig 防抖注释直接关联后端 `/api/config` 30 次/分钟限流与 Agent 重建成本。
6. **备份用 SQLite Online Backup API** 而非文件拷贝：写入中也能得到一致性快照，配合权限收紧 700/600。
7. **注释诚实**：全库注释普遍记录「为什么这样做」和「上次怎样坏的」，而非复述代码。

---

## 九、结论

小汐 v5.0 的代码质量对个人项目而言属于罕见的高水准：测试稳定全绿、契约零断链、架构假设自洽且有防线、死代码极少。产品功能在代码层面全部可实现，**唯一阻断性问题是工作区立绘资源与引用不一致导致的视觉层 404**，属于部署事故而非设计缺陷，分钟级可修。

剩余风险集中在三类边界衔接处：①「防线看起来在、实际不在」（uuid6 清理、`--host=` 绕过、工具总闸脱敏）；② 失败语义模糊（高德软失败、流内错误演 happy、订阅 200 报错）；③ PWA 发版机制（SW cache-first）。按 §7 的优先级处理完 P0/P1 后，这个项目就具备了记忆中「部署服务器 + 域名」计划所需的安全与可靠性底线。

---

*本报告由 5 路并行专项审查（后端架构 116k tokens / 工具审查 84k / API 契约 73k / 前端 77k / 死代码 193k）汇总，关键条目（🔴-1、🟠-1、🟠-2、🟠-4）经人工读源码/资源逐行复核。*

---

## 十、修复落实（2026-07-26 当日执行）

按 `C:\Users\Lenovo\.claude\plans\kind-orbiting-conway.md` 的 11 个单元全部执行完毕，**测试从基线 234 通过增至 259 通过 / 1 跳过（净增 25 个回归测试）**，oxlint 零告警，生产构建通过。

| 单元 | 内容 | 验证 |
|------|------|------|
| U0 🔴 立绘 | 10 张 PNG→WebP（23MB→2MB，原 PNG 备份于仓库外）；spriteMap 补 `exploring` 键（活泼/好奇心情不再静默回落 idle）；dist 重建 | curl 全部 200 image/webp；E2E 断言对应的 HTTP 级验证通过 |
| U1 加固 | security 支持 `--host=V` 等号写法 + UVICORN_HOST/UVICORN_WORKERS；CORS 移到最外层（401/429 带 CORS 头）；设 token 时 /docs 自动关闭；工具总闸固定文案 + 脱敏日志；高德 Key 缺失/失效可辨识文案 | 新增 6 例（含真实中间件栈 401+CORS、docs 关闭回归） |
| U2 GPS | `_location_suffix` 统一注入：纬度/经度带标签（天气分参）+ 经度在前整串（地图 location），消除格式矛盾 | 单测 + 集成夹具同步更新 |
| U3 错误语义 | SSE 新增 `{"type":"error"}` 事件（向后兼容）；前端收到后 succeeded=false、立绘不演 happy | 2 例 SSE 事件测试 |
| U4 uuid6 | 僵尸清理阈值改 uuid6 时间域；测试夹具按官方位布局手拼 + **漂移哨兵**（真实 uuid6 绝不误删 + 公式自洽） | 5 例全绿 |
| U5 config | 副本校验→发布→rebuild 失败回滚 + 500；删死变量 | 2 例（回滚 / 校验不触写） |
| U6 heartbeat | 双 now() 合并；早安天气分段降级；晚间日记保白天标题；三处 LLM 补 model 维度；scheduler 重入守卫（调用侧） | 3 例新增，钉死行为原样绿 |
| U7 小 bug | cycle/diary 日期校验；event_type 白名单 + 拒过去日期；Tavily 单例 + lifespan 关闭；0°C falsy；LIKE 转义 + category 校验；web_push badge/stale 收紧；migrations pragma + 版本读取收紧；subscribe 400 | 每条配回归，共 10 例 |
| U8 前端 | SW 三件套（PROD 门控 / 导航 network-first / push JSON 兜底，v3）；死守卫改 abortRef 真锁；error 事件消费；未读升序 + markRead 失败回滚；HKT 时区统一（utils/time.js）；maxLength 2000；ErrorBoundary；坏 GPS 缓存自愈；a11y（role=log/dialog + Esc）；DELETE 退订走 query | lint 零告警 + build 通过 |
| U9 死代码 | DeleteMemoryRequest、7 文件 10 个死 import、4 条死 CSS、memory/__init__ 再导出、@types 双 devDep 删除；seed_initial_records 与 wave/reading 键保留并注释说明 | 全量测试绿 |
| U10 收尾 | verify_tools recall 参数修复 + 补 5 工具；cleanup 移除误删 E2E 数据项；README 全面同步（18 工具 / 实测测试数 / 10 张立绘 / API 表补 3 条 / docs 树修正）；.gitignore 遗留清理 | 见脚本与文档 diff |

**未执行（明确 defer）**：焦点圈定（a11y 进阶）、vite preview 代理、heartbeat DRY 重构（结论：病根不在结构，见 §5 末节论证）。

**遗留环境事项**：浏览器级 E2E（`scripts/e2e/ui_test.cjs`）因本机 playwright 不可用未跑；立绘的 E2E 断言（`naturalWidth>0` + webp src）已由 HTTP 级验证覆盖。
