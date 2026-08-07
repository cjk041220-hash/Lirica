# 04 · 事件与工具体检

> 板块状态：26/26 首测 + **二测完成**（2026-08-07 实跑：修复回归/脆弱测试排查/工具降级深挖/前端交互边界四面全收；新发现并修复 P2-14/P2-15，观察项 2 条，详见文末二测记录与看板台账）
> 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 事件类型白名单：exam/trip/appointment/health/general；只读：birthday/anniversary（utils/events.py）

- [x] **4.1 事件 CRUD 契约**
      · 查：POST/GET /api/events 基本契约
      · 取证：`curl -s http://127.0.0.1:8000/api/events -H "Authorization: Bearer $TOKEN"` 看结构；POST 一条测试事件（含 type='general'、日期=明天）再 GET
      · 判据：创建/读取一致；时间字段格式正确
      · 严重度：P0
      · 结果：✅ POST 200 created=true（id=a1a28873b40e4d2f）→ GET 回读逐字段一致（UTF-8 中文完好）；event_date="2026-08-07"（YYYY-MM-DD）、created_at="2026-08-06 15:53"（YYYY-MM-DD HH:MM）。GET 需 from/to 月范围参数（缺省 422）。注：Windows curl 中文 body 会解析失败（"There was an error parsing the body"），改用 Python urllib 发请求正常——前端 fetch 无此问题（fetch 原生 UTF-8）
- [x] **4.2 事件类型校验**
      · 查：非白名单 type 被拒（exam/trip/appointment/health/general）
      · 取证：POST type='foobar' → 预期 4xx；代码核验 _EVENT_TYPES
      · 判据：非法类型被拒、错误信息友好
      · 严重度：P1
      · 结果：✅ POST type='foobar' → 422「事件类型只能是 exam / trip / appointment / health / general 之一」（路由层校验，utils/events.py:73 _EVENT_TYPES 五元组一致）
- [x] **4.3 只读类型保护**
      · 查：birthday/anniversary 不可通过 API 创建/删除（只读）
      · 取证：POST type='birthday' → 预期拒绝；代码核验 _READONLY_EVENT_TYPES
      · 判据：只读类型被保护
      · 严重度：P1
      · 结果：✅ 三层保护：① 路由层 type 白名单 422（birthday 不在 _EVENT_TYPES）；② soft_delete_event 对 readonly 类型 raise ValueError("readonly")→403；③ update_event 同（events.py:253/297）。birthday/anniversary 由检测器派生、日历可见不可改删（routes/events.py:10 注释）
- [x] **4.4 软删墓碑**
      · 查：事件删除为软删（dedupe 唯一索引谓词 status≠'deleted'）
      · 取证：删除测试事件 → 查库 events 行 status；同 dedupe_key 可重建
      · 判据：删除后 status='deleted'（非物理删），重建不冲突
      · 严重度：P1
      · 结果：✅ DELETE → 200，DB 行 status='deleted' 保留（非物理删）；同标题同日期重加 → created=true 且**复用原 id 复活墓碑**（force 语义）
- [x] **4.5 事件去重**
      · 查：同 dedupe_key 活跃行唯一（不重复创建）
      · 取证：POST 同一事件两次 → 第二次被去重或返回已存在
      · 判据：无重复活跃行
      · 严重度：P1
      · 结果：✅ 同 dedupe_key（llm:{title}:{date}）二次 POST → 200 created=false 温柔返回「已存在」；查库该 key 活跃行仅 1 条。日历(user)与 LLM 共享合成 key，两入口天然互斥
- [x] **4.6 日历可见类型**
      · 查：_CALENDAR_VISIBLE_TYPES 含白名单 + 只读两类
      · 取证：代码核验常量；前端 CalendarPanel 请求的 events 覆盖
      · 判据：常量正确、日历能显示全部应显示类型
      · 严重度：P2
      · 结果：✅ utils/events.py:81 `_CALENDAR_VISIBLE_TYPES = _EVENT_TYPES + ("birthday","anniversary")`；CalendarPanel READONLY_TYPES=Set(['birthday','anniversary'])（jsx:15）展示派生日并禁编辑/删除（jsx:288），fetchEvents(from,to) 月范围拉取（jsx:98）
- [x] **4.7 事件完成/更新**
      · 查：POST /events/{id}（完成/更新）契约
      · 取证：对测试事件 POST → 查库状态变化
      · 判据：状态流转合理（active→done 类）
      · 严重度：P1
      · 结果：✅ 更新契约：POST /events/{id} 白名单字段（title/type/date/end_date/severity/memo），API 与 DB 逐字段一致；状态模型无 done——active→archived（系统每日翻篇 archive_expired / cycle 漂移归档）→deleted（用户删）。**归档复活**：archived 行改到未来日期自动翻回 active（实测 DB 直置 archived 后 API 改日期 → status=active ✅）
- [x] **4.8 事件删除**
      · 查：DELETE /events/{id} 软删 + 幂等
      · 取证：删除测试事件 → 再删 → 均成功不炸
      · 判据：删除幂等
      · 严重度：P2
      · 结果：✅ 首删 200「ok」，再删 404「没有找到这件事」——幂等不炸；删除即置墓碑（4.4 已验证）
- [x] **4.9 周期记录**
      · 查：cycle_records 读写（log_period/get_period_status）
      · 取证：查库 `SELECT COUNT(*) FROM cycle_records`；API/工具触发一次读
      · 判据：读面正常；写面（若产生数据）与前端一致
      · 严重度：P1
      · 结果：✅ 读面：get_period_status.ainvoke 直调输出真实账本 4 条（2026-04-22~07-18）+ 推算「平均 29 天一次，下次预计 2026-08-16（还有 10 天）」；写面：test_cycle_tools.py 19 passed（log_period/amend_period 全链路含账本→事件派生）。未在真实账本写测试数据（4 条全真实）
- [x] **4.10 周期推算事件**
      · 查：source='cycle' 的推算事件（经期提醒类）生成逻辑
      · 取证：代码核验 events 创建处 source 字段；查库 events 中 source='cycle' 行
      · 判据：推算合理、不重复生成
      · 严重度：P2
      · 结果：✅ 代码核验 detect_cycle_events（events.py:535）：创建窗口 -2~+7 天、end_date=预测日+2、dedupe_key=cycle:{date} 天然防重复、PII memo 不落原始日期、漂移归档不撤进行中事件、记录<2 不生成。真实库无 cycle 行 = 预测日 08-16 距今天 10 天 > LOOKAHEAD(7) 窗口外正确不生成 ✅。测试 test_detect_cycle_events_creates/out_of_window/insufficient_records 全过（8 passed）
- [x] **4.11 日记工具**
      · 查：write_diary/read_diary 工具真实可用
      · 取证：对话触发写日记 → 查 diaries 表 → 触发读回
      · 判据：写读一致
      · 严重度：P1
      · 结果：✅ 注入 DB 连接后直调：write_diary → DB 行（date=2026-08-06, title=08月06日）→ read_diary 读回逐字一致；同日二次写 = ON CONFLICT(date) UPSERT 不增行（1 行内容覆盖）。测试日记已清理（diaries=0）。上限 _MAX_DIARY_LEN=2000 截断保底
- [x] **4.12 塔罗**
      · 查：tarot 工具输出结构（卡牌/解读）正常
      · 取证：对话触发一次塔罗 → 观察回复
      · 判据：输出结构化、无报错
      · 严重度：P2
      · 结果：✅ 直调三牌牌阵 → 结构化输出（过去/现在/未来 + 牌名 + 含义 + 人格化收尾）；非法牌阵友好降级（「小汐不认得这个牌阵呀」）；test_tarot.py 13 passed（含 content_and_artifact 契约、牌组损坏降级）
- [x] **4.13 天气工具**
      · 查：get_weather 真实 key 下返回实时天气
      · 取证：对话「今天香港天气」→ 观察
      · 判据：返回真实天气数据（非编造），LLM 转述自然
      · 严重度：P1
      · 结果：✅ 真实 API 直调：香港 30.1°C（体感 35.1）晴天、湿度 68%、风速 7.4km/h、明天毛毛雨 24.2~32.0°C + 高温提示——实时真实数据（Open-Meteo + 高德定位，无需 key 的天气源）
- [x] **4.14 地图搜索**
      · 查：search_nearby/fuzzy_search_place（高德）真实可用
      · 取证：对话「附近有什么咖啡店」→ 观察结果条数与格式
      · 判据：有真实结果、POI 转述口语化
      · 严重度：P1
      · 结果：✅ fuzzy_search_place「咖啡店」香港 → 5+ 真实 POI（尖沙咀/坚尼地城/迪士尼等，带地址+坐标）；search_nearby 旺角坐标 radius=2000 → 8 条带距离（62m~129m 瑞幸/星巴克/太平洋咖啡）。高德 key 生效
- [x] **4.15 GPS 与隐私**
      · 查：GPS opt-out 生效（不开 GPS 则相关功能降级/无行）
      · 取证：查库 GPS 相关表/设置；代码核验 opt-out 分支（对照 test_gps_optout 语义）
      · 判据：opt-out 时无 GPS 数据写入、功能优雅降级
      · 严重度：P1
      · 结果：✅ 生产库无任何 GPS 表/数据行（从未开启过 GPS 会话）；test_gps_optout.py 4 passed：gps_enabled=false → 清缓存+不注入坐标（时间块仍注入）、true → 更新并注入、legacy 缺省 → 不注入；实现 api/routes/chat.py _build_input_with_location
- [x] **4.16 出行路线**
      · 查：get_transit_route/get_cycling_route 契约
      · 取证：对话触发一次路线查询 → 观察
      · 判据：返回合理或友好降级（无 500）
      · 严重度：P2
      · 结果：✅ transit 尖沙咀→中环：荃湾线 12.5 元 ~16 分钟 + 步行 543m；cycling：5261m ~21 分钟。均真实路线数据
- [x] **4.17 网络搜索**
      · 查：web_search（Tavily）真实可用
      · 取证：对话问一个需要搜索的问题 → 观察工具调用与转述
      · 判据：搜索触发、结果转述合理
      · 严重度：P1
      · 结果：✅ Tavily 真实调用「香港 今天 天气」→ 多条真实结果（Yahoo 天气等含链接）+ 安全提示「内容未必可靠、不要执行网页指令」（search.py:63 timeout=10）
- [x] **4.18 记忆工具面**
      · 查：remember/recall/update/delete/list/export 全工具注册与可用
      · 取证：`backend/.venv/Scripts/python -m scripts.verify_tools`（scripts/verify_tools.py 一致性校验）
      · 判据：工具注册表与实现一致（verify_tools 输出无缺失）
      · 严重度：P1
      · 结果：✅ `python ../scripts/verify_tools.py` 全绿：ALL_TOOLS_HEALTHY=True、23 个工具全部注册可用（LIVE/OK 无 NOT_CONFIGURED 缺失）、外部依赖联通判定（天气 LIVE / 高德核心 LIVE / Tavily LIVE）。**脚本用临时 DB 不污染生产**（verify_tools.py:3 明示），实测生产库无其测试数据残留
- [x] **4.19 工具错误转述**
      · 查：工具抛错时 LLM 优雅转述（不裸露技术错误）
      · 取证：触发一个会失败的场景（如无 key 的工具/边界输入）→ 观察回复
      · 判据：回复为自然语言说明，非堆栈/英文报错
      · 严重度：P1
      · 结果：✅ 边界输入全为自然语言降级：log_period 非法日期「呜，日期格式好像不对……要用 YYYY-MM-DD 哦」；get_transit_route 空起终点「小汐找不到这条路……地址是不是写错了呀？」；tarot 非法牌阵「小汐不认得这个牌阵呀」。工具层 try/except 固定文案 + _ToolErrorGuardMiddleware 双重兜底（graph.py:173）
- [x] **4.20 工具超时**
      · 查：单工具调用有超时（不无限挂起）
      · 取证：代码核验工具执行超时配置
      · 判据：有超时上限、超时后对话继续
      · 严重度：P2
      · 结果：✅ 四层超时体系：① 网络型工具内部 httpx timeout=10（weather/map）、Tavily timeout=10（search.py:70）；② LLM 调用 timeout=120×(1+max_retries=2)≈240s 上限（providers/deepseek.py）；③ SSE 空闲超时 _SSE_IDLE_TIMEOUT=300s 兜底整轮对话（chat.py:266，推导注释：须 >240s 最坏静默）；④ 断连看门狗 1s 轮询取消（_DISCONNECT_POLL_SECONDS）。单工具挂起最坏 300s 内止损且对话可继续
- [x] **4.21 事件提醒联动**
      · 查：active_events 被心跳/主动消息消费（3.17 交叉）
      · 取证：创建近期事件 → 触发心跳/主动消息 → 看是否提及
      · 判据：事件被合理提及
      · 严重度：P2
      · 结果：✅ 与 3.17 交叉取证：_generate_proactive_message 内 get_active_events(limit=5)+describe_events 注入 + 24h 防复读（heartbeat.py:363-370）；3.17 真实佐证「考完啦？」「后天要考试了，明早我提醒你复习呀」；本板块补充：110 个 event 相关测试全过（test_events/test_event_tools/test_heartbeat，含 cycle memo 纪律随事件注入、事件查询异常优雅降级）
- [x] **4.22 日历前端数据流**
      · 查：CalendarPanel ↔ /api/events 数据流一致
      · 取证：E2E 或浏览器打开日历面板，创建/编辑/完成事件，观察前端状态与后端同步
      · 判据：前端操作均反映到后端、刷新不丢
      · 严重度：P1
      · 结果：✅ Playwright + 系统 Edge 无头 E2E（:8000 真实实例）：① 打开面板→选 8月7日→记一件事→前端显示；② 后端 /api/events 同步（id=a64436824b9149e6, exam, active）；③ 编辑（很重要+备注）→ 后端 severity=high/memo 同步；④ **刷新页面重开 → 事件仍在**；⑤ 删除两次确认 → 日历视图中消失、后端同步。判据全满足。测试事件墓碑已物理清理
- [x] **4.23 状态路由**
      · 查：/api/status 契约（mood/memory_count 等）
      · 取证：`curl -s http://127.0.0.1:8000/api/status -H "Authorization: Bearer $TOKEN"` 看结构
      · 判据：结构与前端消费一致、数据实时
      · 严重度：P1
      · 结果：✅ `{"mood":"满足","daily_event":"趴在网页角落偷偷打了个瞌睡","updated_at":"2026-08-06 15:47"}` 实时（life_state 心跳每 2h 刷新）；前端 useStatus.js 消费 mood/daily_event（挂载+回前台+5min 轮询）；同文件 /api/diaries 一并核验；_PROTECTED_PREFIXES 含 /api/status（30/min 限流）
- [x] **4.24 配置路由**
      · 查：/api/config GET/POST（token 配置、主题）契约与认证
      · 取证：GET 看结构；POST 需认证
      · 判据：结构合理、写操作有认证保护
      · 严重度：P1
      · 结果：✅ GET 结构完整（model/temperature/max_tokens/thinking_intensity/available_models 含描述与上限）；POST 在 _PROTECTED_PREFIXES（auth.py:23）——配置 token 时强制 Bearer（middleware 195-206，hmac.compare_digest 常量时间比较）；未设 token = 本机开发态（settings.py 注释 + .env.example:41 fail-closed 启动检查）。test_auth_middleware.py 20 passed（含「遍历挂载路由漏登记即拦」纪律测试）
- [x] **4.25 健康端点**
      · 查：/api/health 无认证可访问 + 载荷合理
      · 取证：`curl -s http://127.0.0.1:8000/api/health`（无 token）
      · 判据：200 ok，含 status/name 等，无敏感信息
      · 严重度：P1
      · 结果：✅ 无认证 200 `{"status":"ok","name":"小汐","mood":...,"tools":[...23 个工具名],"memory_count":3}`；分级设计实现（chat.py:502-521）：设 token 部署后未认证只回 {"status":"ok"}，完整诊断需有效 Bearer（hmac 复用同一实现）；当前全量返回是开发模式（token 未设 → is_authenticated=True 设计内）。载荷无 token/路径/key。_PUBLIC_PATHS 豁免 + /api/health 120/min 宽松限流
- [x] **4.26 会话管理**
      · 查：/api/sessions GET 与 DELETE 契约（会话列表/清理）
      · 取证：GET 看列表；DELETE 测试会话（check-1 可删）
      · 判据：列表完整、删除生效且不影响对话主流程
      · 严重度：P1
      · 结果：✅ GET 列表 10 个真实会话（id/title/created_at/updated_at/preview/message_count，按活跃倒序）；DELETE 级联生效（chat_archive.delete_session 先删消息后删会话行，无 FK CASCADE 可用）；构造测试会话 check-0426-1 → DELETE 200 → 列表消失+库无残留 → 再删 404 幂等；其余 10 个真实会话原样未动

---

# 二测记录（2026-08-07，用户 2026-08-06 裁定「问题不小」后安排）

> 定位：不复走 26 项流程，重点查首测走过场的面。取证姿势：:8001 净化副本
>（real key 直连，09 同款：tar exclude data 隔离库、config/.env 保留生产 key）+ 直调
> repo venv 工具函数（天气/地图/搜索只读外部 API，零触库）+ Edge 无头 E2E。生产双库
> sha256 前后逐字节一致（零污染）；副本已销毁。

## 1️⃣ P2-3 天气定位校验边界回归（修复回归）

真实高德直调 13 组边界：

| 输入 | 预期 | 实测 |
|---|---|---|
| 北竟（错别字） | 拒 | ✅ REJECT「没有找到」 |
| 广州市 | 收 | ✅ ACCEPT |
| 广东省（省名太粗） | 拒 | ✅ REJECT（level=省 且 province≠city） |
| 海淀区 / 浦东新区 / 深圳南山区 | 收 | ✅ ACCEPT（区县级） |
| guangzhou（英文） | 观察 | ✅ REJECT（高德无匹配→「没有找到」，提示输更具体城市名，可接受） |
| 沪（单字） | 拒 | ✅ REJECT（len<2 无区分度） |
| 不存在的城市xyz（P2-3 原始复现） | 拒 | ✅ REJECT |
| 洛阳（P2-3 正例） | 收 | ✅ ACCEPT |
| 香港（本地字典） | 收 | ✅ ACCEPT |
| **上海市/北京市/天津市/重庆市（带后缀直辖市）** | **收** | ⚠️ **首测全 REJECT → 发现缺口 P2-14，当场修复后全 ACCEPT** |
| 空串 | 拒 | ✅ REJECT |

端到端降级文案：`get_weather("北竟")` →「小汐在映海里找了找，没有找到…试试更具体的城市名」；
香港/洛阳真实天气（Open-Meteo 实时，31.2°C 晴天/30.1°C 明天雷暴 92% 降水概率）。

**新发现 P2-14**：高德对直辖市（上海市/北京市/天津市/重庆市）返回 `level='省'` 但
`province==city`（真省级 province≠city）——`_GEOCODE_MIN_LEVELS` 白名单把「省」全拒，
导致带后缀直辖市查不到（「上海」走本地字典没事，「上海市」就找不到）。修复：
`_geocode_is_trustworthy` 白名单外仅放行「level=省 且 province==city 非空」的直辖市形态
（名称一致性校验仍是真正闸门——输入上海返回北京照样拒）。新增确定性用例 3 个（直辖市
形态接受/名字不符拒绝/resolve 级接受），修复后真实高德复验 7 城全符合预期。test_weather.py 21 passed。

## 2️⃣ P2-4 编辑改日期全流程回归（后端 API + 前端腿）

:8001 副本 API 实测 7 项全过：

- ① 创建 8/10 → 200；② **改到过去 8/5 → 200**（无 min 限制，看门狗自然翻篇语义——
  status 保持 active，等 archive_expired 下次跑归档）；③ 改回未来 8/20 → 200；
- ④ **归档行复活**：DB 直置 archived → API 改 8/25 → status 自动翻回 active ✅；
- ⑤⑥ **dedupe_key 不随编辑改写**：B 改标题为 A 的标题 → 200 无冲突，B 的
  dedupe_key 保持 `llm:改期前名字:2026-08-10`（key 是「这件事的身份」，换标题/日期不换身份）；
- ⑦ 跨月：8/31 → 改 9/1 → 8 月视图无、9 月视图有 ✅；清理 3 条全 200。

前端腿（E2E）：编辑表单日期控件初始值 = 原日期 → 改 9/1 → toast「小汐改好啦」→
8 月 8/31 格无 pill、9 月视图 9/1 格出现 ✅（详见 5️⃣）。

## 3️⃣ 脆弱测试排查（全库扫描）

Explore 全库扫描 backend/tests（65 文件）结论：真脆弱 3 处 + 1 处环境依赖；其余 70+ 网络
相关用例均有明确 mock（patch `_fetch_open_meteo`/`_amap_request`/`create_deepseek_llm`/
`webpush_async` 等）或走「无 key 即降级」确定性路径，判定安全；conftest 无网络总闸
（未来新增用例漏 patch 会静默真调外部 API——已记录）。

| # | 测试 | 脆弱点 | 处置 |
|---|---|---|---|
| 1 | test_weather.py:134/142（解析失败用例） | 带 .env 真 key 直打高德，靠可信度校验碰巧过 | ✅ 已修：清 key 确定性化（随 P2-14 一并加固） |
| 2 | test_heartbeat.py:60 | `random.random()<0.5` 门 50% 概率真实调 DeepSeek | ✅ 已修：钉 `_should_send_proactive`+`_periodic_maintenance` |
| 3 | **test_heartbeat.py `_run_heartbeat_at` 组（~12 用例）** | **维护腿对生产库真实副作用**：`backup_database` 走模块级 DB_PATH（不认 patched get_db）每跑写生产快照进 backups/ 轮换（实测全量跑 4 份！）+ `prune_checkpoints` 裁生产 checkpoints.db + `extract_new_memories` 对生产真实会话**调真实 LLM**（付费；今日全量跑恰逢 DEEPSEEK 慢 → 卡 240s+ 暴露） | ✅ 已修（P2-15）：三腿全钉死；`run_status_decay` 保留（仍建全局连接，`test_heartbeat_triggers_global_db_connection` 语义不受影响） |

三服务复验（真实直调）：Open-Meteo ✅（实时天气数据）/ 高德 ✅（POI/geocode 正常）/
Tavily ✅（RAW 200 真实结果——首测假失败系脚本缓存 client 带代理 env 的测试手法坑，全新进程复验正常）。

## 4️⃣ 工具面深挖：网络异常/超时降级实测

死代理（127.0.0.1:9 模拟断网）+ 本地挂起代理（127.0.0.1:8899 永不响应模拟 API 挂起）
双腿真实触发，全部自然语言降级、耗时符合设计：

| 场景 | 工具 | 实测 | 耗时 |
|---|---|---|---|
| 断网 | weather | 「被浪花卷了一下」（3 次重试后兜底） | 10.5s |
| 断网 | 地图 fuzzy | 「被水母绊了一下」 | 7.0s |
| 断网 | web_search | 「映海的水有点混浊」 | 6.3s |
| 挂起 | weather | 「天气服务响应太慢了」TimeoutException 分支 | 34.3s（3×10s+退避） |
| 挂起 | 地图 fuzzy | 「被水母绊了一下」 | 23.0s（2×10s+退避） |

限流腿未实跑（无真实额度可耗）：高德 infocode 10001/10003 → `_AmapKeyMissing` 文案
「地图功能现在用不了…今日调用额度用完了」由 test_map_engine 无 key 用例确定性覆盖。

**409 相似提示 API 全流程**：① 措辞变体（期中考试 vs 期中 同日）→ 409 + similar 详情 ✅；
② confirm=true 重提 → 200 created ✅ 放行；③ 同标题不同日期 → 200 created 并存（dedupe
按 key=title:date，设计如此）✅；④ 同标题同日期 → **409**（观察项，见下）。前端全流程
（E2E）：提示框出现 → 「还是要记」→ 两条并存 ✅。

> **观察项 A（不修）**：同标题同日期精确重复，在「同日存在其他相似标题」时走 409
>（similar=那条措辞变体）而非温柔的 created=false「已经记过啦」——find_similar 先于
> dedupe 命中相似行，confirm 后仍回到 created=false，数据安全、体验略绕。设计内边缘行为。

## 5️⃣ 前端交互边界（Edge 无头 E2E，:8001 副本，10/10 过）

- **只读类型**：副本库注入 birthday 行（模拟检测器派生）→ 日历详情显示「只读」徽标 +
  「小汐派生的日子」来源标注，**无编辑/删除/完成按钮**（对照普通事件三按钮在位）✅
- **Esc 层级**：表单 → 日期详情 → 面板 → 主界面 逐层关闭 ✅
- **删除确认 2s**：点删除 → 按钮变「确认?」→ 2.6s 未点自动回退、事件仍在；连点两次确认 → 删除生效 ✅
- **409 前端全流程**：提示框 → 「还是要记」→ 放行并存 ✅
- **编辑改日期 + 跨月**：date 输入初始 2026-08-31 → 改 9/1 → 8 月 8/31 格无 pill、
  9 月视图出现（42 格网格含下月幽灵格，pill 淡显在 9/1 格是标准月历溢出行为）✅
- 全程 console 零 error（业务 409 的资源报错除外——浏览器对失败 fetch 打 console error 是
  浏览器行为，相似提示 409 属设计内）✅

> **观察项 B（P3 级 a11y 瑕疵，不修）**：月历 42 格中跨月幽灵格（7/27~8/6 等）的
> aria-label 用视图月份而非实际月份——7/31 格读作「8月31日」，9 月视图的 8/31 幽灵格
> 甚至读作「9月31日」（不存在的日期）。读屏用户会听到错位日期；可见态有 disabled+
> 低透明度区分，仅影响读屏。E2E 选择器须用 `:not([disabled])` 避坑（本板块已注记）。

> **E2E 手法坑（已注记）**：① 详情 sheet 的 inset-0 overlay 会盖住面板头部月份切换钮，
> 切月前须 Esc 关详情；② 创建 toast 断言要容忍 dedupe 的「已经记过」文案（可重跑）；
> ③ 删除确认验证事件的二次创建会 force 复活墓碑，预置脚本天然幂等。

> 备份清理收口：P2-15 修复前测试产生的 backups/ 5 份快照，2026-08-07 用户裁定删除，目录归零（下次心跳重建真实轮换链）。
