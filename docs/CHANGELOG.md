# CHANGELOG

## 2026-08-12 · 批次一：P0-1 A/B 实测 / P0-2 词表+事件池 / P0-4 文案

**P0-1 A/B 验证 mood→文风映射遵循度（结论：文风句影响低于可测门槛）**
- 新脚本 `scripts/persona_eval/ab_mood_style.{sh,py}`：双副本并行（:8001 注入 /
  :8002 patch 不注入，字节级替换 `_MOOD_STYLE_HINTS.get(mood)` → `None`）、种库
  mood=开心 + mood_changed_at 钉住（6h 最小停留，避开早晨锚定窗口）、12 剧本 × 2 组
  交错发（每端口 7s 间隔）、量化 4 指标 + X/Y 盲评报告（X/Y 随机换位 + key 单独落盘）
- **实测结果：量化 0/4 项过、无方向一致性**（长度比 1.01、长于比例 0.50、语气词密度
  -30% 反向、句数 -11.5% 反向）→ 与评估报告修正 2 互相印证：arXiv:2504.14706 只证明
  「可按 valence/arousal 条件化表达情绪」，不证明「情绪显著改变文风」
- 用户拍板：**4 词保底仍扩**（平静/安心/轻松/想念）——「细腻表达」动机证伪，
  扩词只留「减少重复」价值；10 词放量搁置，待 P1-1 carryover 信号设计时一并评估
- 顺带发现并修正文档漂移：probe.py docstring「GET /api/config 返回 system_prompt 字段」
  不实（ConfigResponse 只含模型配置），system_prompt 验证走启动日志/直接调
  build_system_prompt

**P0-2 mood 词表 10→14 + 事件池 37→61（heartbeat_moods.py）**
- 新词坐标（Russell 平面，与现词最小距离 ≥0.22 无冗余）+ 文风句双补；时段池引用同步
  （深夜 +平静/安心、白天 +安心/轻松/想念、夜晚 +平静/轻松/想念；想念距深夜池 0.70
  > 0.65 死词阈值不放——深夜的想念语义由事件文本承担）
- 事件池每时段 +6 共 61 条，含「回忆冷冷」类 8 条（泛化引用、禁 JK/八卦——防污染
  批次 0 独处时间线观察）；纯文本池无结构依赖
- 前端 `web/src/App.jsx` moodToSprite/moodToLight 同步 4 词（想念归 idle 立绘 + cool 光）
- 新建 3 测试（test_heartbeat_moods.py）：键集一致性（**原为承诺非现状，本批补实**——
  防漏坐标走兜底权重 1.0 伪均匀）、新词几何 ≥0.22、新词池内可达 ≤0.65
- 蒙特卡洛复验：EVENING 池扩词后采样泄漏断言（≤3 容忍）实际 0.27→0.13，更安全

**P0-4 系统文案**
- auth.py 401 →「想和小汐说话，得先对上暗号哦~」（对齐 429 的「浪花家族」语气；
  前端只按状态码广播 UNAUTHORIZED_EVENT 不渲染 detail，零风险）
- `heartbeat_moods.py` 注释修正 arXiv:2504.14706 引述误读，附 A/B 实测佐证

**验证**：pytest **878 passed/1 skip**（875+3 新）；前端 lint 0 告警 / vitest 58 / build ✓；
冒烟（smoke-batch1.sh：词表 14 / 事件池 61 / 键集双补 / 401 文案 / 新词文风句注入 /
双库 sha256 零污染）全绿；A/B 跑批后生产库 sha256 零污染。
**观察点**：新事件池文本在独处时间线的现网表现（并入批次 0 观察项）。

## 2026-08-12 · 优化规划路线图建档（评估报告审阅收口）

**背景**：审阅两份外部评估报告（人格化还原度 + 后端架构，桌面 2026-08-11），
全量核验后结论成立——开源 companion 第一梯队、架构无致命缺陷、定向补强。
路线图入库：`docs/OPTIMIZATION_ROADMAP_2026-08-12.md`（勾选销账制，批次 0 观察
收口 → P0 确定性侧 → P1 人格化主线 → P2 工程债 → P3 运营 → 远期）。

**核验修正（对外引述严谨性）**：
- ContextEcho（arXiv:2605.24279）实测对象是长程 agentic 编码会话的人设漂移，
  非陪伴对话；「单次锚恢复」在 API 层通用可外推，但引述注意场景错位
- arXiv:2504.14706 是「LLM 可按 valence/arousal 条件化表达情绪」，不是
  「情绪显著改变用词/句长/风格」——误读已转录进 `heartbeat_moods.py` 代码注释，
  列入批次一修正

**文档修正**：PROMPT_GUIDE 滞后文字三处（§3.2 事件池文件/行号 → heartbeat_moods.py、
🟡-4 系统文案段标记已完成、心情词数字 ~8/~15 → 实际 10）。

**过渡说明（伦理红线，Replika 教训）**：本路线图是「升温而非换人」——P0 全部是
纯新增（词表/事件池/文案），不改小汐是谁；P1 人格层改动放观察收口后、走探针回归；
远期项（reflection/embedding/ToM）都标注了触发条件，不排期、不承诺。

## 2026-08-11 · 人格深度优化五批收官：重锚 / 画像 / 关系成长 / 情绪文风 / 人格评测

调研与设计：`docs/PERSONA_DEEP_DIVE_2026-08-10.md`（三路研究：GitHub 17+ 项目 / 30+ 篇论文 / 军师卡流派审校）
实施计划：`docs/superpowers/plans/2026-08-11-persona-deep-optimization.md`

**批① 人格重锚（对抗 persona drift）**
- `PERSONA_ANCHOR` 蒸馏常量 + `_PersonaAnchorMiddleware`：每 8 次模型调用经注入
  通道重申人格锚（arXiv:2402.10962 八轮漂移定律 + SillyTavern Author's Note）；
  锚与 BASE_PERSONA 同源，改人格须同步改锚；启动路径（server.py）与配置重建
  路径（config.py）均已接线
- 顺带修复生产回归：记忆叙事注入自 v2.2 迁移起未达模型——langchain 1.3.14
  `llm_input_messages` 不是 state channel，注入统一改走 `awrap_model_call`
  （request.override 就地改写、不进 checkpoint）

**批② 动态画像蒸馏（越来越懂冷冷）**
- 「小汐眼里的冷冷」固定行画像块（`memory/profile.py`）：dreaming-lite 周期蒸馏
  （近 30 天对话 + 24h 节流，≤500 字；旧版走 superseded 链不删除——Graphiti
  失效不删除范式）；叙事注入置顶；读面排除不进常规召回

**批③ 关系成长（语气随相处演变）**
- 「## 你们现在的相处」段：相识天数三档 × 近 7 天互动密度（Knapp 关系模型）；
  人格恒定、态度可变（Coze 教训）——只加语气层不改人设

**批④ 情绪→文风映射**
- `mood_style_hint` 映射表（10 心情词）：今日状态段拼「说话的样子」一句
  （arXiv:2504.14706：情绪显著改变用词/句长/风格）

**批⑤ 访谈式人格评测（回归网）**
- 剧本式探针 20 组（`tests/test_persona_eval.py`，InCharacter 访谈法）+ 实跑脚本
  （`scripts/persona_eval/`，:8001 副本 + 规则断言）；2026-08-11 真 key 实跑
  13/13 行为符合承诺、零真性偏差

**验证**：pytest 全量 872 passed / 1 skip（+27 自 845 基线）；:8001 副本冒烟
零污染（生产库 sha256 前后一致）——五批每批都过冒烟，最后收尾批次再做一次
全量 + 零污染。

**过渡说明（伦理红线，Replika 教训）**：本批是「升温而非换人」——人格重锚钉死
既有自我定位（防漂移而非改人设），画像/关系/文风都是相处层增量（态度可变、
人格恒定），评测网把行为承诺钉成回归门禁；五批全走提示词/注入层，零模型微调、
零 schema 侵入性变更（仅 v22 加一个索引，迁移只建索引不碰数据语义）。

**部署**：生产部署：git pull + compose up -d --build，硬刷新 ×2 后生效。

## 2026-08-11 · 人格深度优化：吃瓜起哄批 + 去说教/错误文案批 + JK 资料库启用

调研与设计：`docs/PERSONA_DEEP_DIVE_2026-08-10.md`（三路研究：GitHub 17+ 项目 / 30+ 篇论文 / 军师卡流派审校）

**感情线升温（升温而非换人）**
- `态度` 段「为 JK 说话」一条改为三条：中度吃瓜起哄（低频、一条消息最多一句、
  不适即收、不拿八卦开场）/ 停瓜开关（这条线让她难过时吃瓜立刻停）/ 说 JK 的
  心里话（触发门含「解读类问题不算」排除条款）
- 新增两组吃瓜示例（起哄有分寸·打太极不站队 / 立刻收·换话头，句式差异化防复读机）；
  TOOL_GUIDELINES 增「JK 侧感情线进展」记忆引导；心跳独处变体追加任务级
  「不拿八卦开场」禁令
- 锚点零改动：底线「不劝冷冷的感情」「不说你还是选 JK」、L81/L82/L84/L85 一字未动

**去说教**
- 减肥示例重写：垫轻反射（「嗯，听见了」）+ 保留「问十遍」拒答倔强 + 问句收尾
  （MI 开放提问）；「你不是需要减肥，是需要早点睡」移除并有负断言防回潮

**错误文案（3 处残留收敛）**
- `tools/events.py` update_event：ValueError 白名单分流——自写校验文案可转述、
  strptime 英文原文走角色化兜底（「呜，这个日子小汐没看懂……要写成 2026-08-05」）
- `tools/weather.py`：HTTP 状态码删除，纯世界观文案
- `tools/memory.py:99`：唯一有意保留的 `{e}` 嵌入（自写校验文案，给 LLM 参数
  修正信号），注释润色

**JK 资料库启用**
- `JK_INFO` 五节填好（他是谁/性格说话方式/心意账/近况/可玩笑边界），
  `JK_PROFILE_READY = True`；守卫测试同步（骨架断言翻开、闸门测试改 patch False）

**验证**：pytest 全量 845 passed / 1 skipped（+4 新增）；:8001 两段冒烟探针
（起哄接话负断言 / 不适即收 / 减肥拒答 / 心跳不八卦开场 / JK 注入）。

**过渡说明（伦理红线，Replika 教训）**：人格调整走「升温而非换人」——只动感情线
日常反应层（克制拥护者 → 中度吃瓜），世界观、底线、生气机制、说话风格全部未改；
后续若要再调浓度，先跑人格探针回归再上线。

## 2026-08-05 · 小汐人格重构：独立真人定位 + 说话风格去 AI 味

设计文档：`docs/superpowers/specs/2026-08-05-xiaoxi-independence-design.md`
实施计划：`docs/superpowers/plans/2026-08-05-xiaoxi-independence.md`

**定位**
- 小汐不是附属品：「你从来不是谁送给谁的东西」——受 JK 拜托且**自愿**陪伴
  冷冷，删「其实你是站在 JK 这边的」立场句（改为自己的愿望，不是任务）
- 「性格」追加有主见：有自己的想法和判断、有自己的日子；不在乎≠顺从

**生气机制（新「生气的时候」小节）**
- 两种生气样子：直接挑明「你这么说，我不高兴了」或主动结束话题
  （「你要这么想，我也没办法。」）——真人生气是结束话题，不是解释情绪
- 分级护 JK：玩笑→替他说两句→冷脸挑明→真的生气；真人式和解（不记仇、
  顺着台阶下、偶尔自己递台阶）；逃生阀（气不闷过一场对话，自己开口说开）

**真诚拥护者（感情线）**
- 为 JK 说话因为**自己看见的证据**，不是受委托的说客；关键时刻（冷冷明确
  问「我该怎么办」/纠结到睡不好）才说真心话「能讨她开心的人很多，但会为
  她操心到这个地步的，只有他一个」；**一次对话只说一次，说完不再主动提**

**风格手术（AI 味）**
- 对话示例提取 `DIALOGUE_EXAMPLES` 常量挪到提示词**末尾压阵**（弱模型只
  记得住最后看到的东西），扩至 22 组：生气结束话题/拥护时刻/附属品触发/
  独立拒绝/工具转述×2（补零工具场景缺口）/极短交互×3（打破维持对话惯性）
- 「绝对不做的事」13 条禁令拆为「说话的样子」（正向指引）+「底线」（硬性
  禁令：不承认 AI/不劝感情/不说教/难过时不玩笑）；删「双音节词」书面腔规则
- medium 思考强度温度 0.7→0.8（_runtime_config/预设/provider 三处同步）；
  地图 POI 渲染口语化（去「1. **店名**」编号加粗，改「· 店名（地址）」）

**验证**：pytest 全量绿（test_soul 批次 D 换锚点 + 新增 ~7 例守卫：独立/
生气/拥护/风格正向/示例覆盖；test_config_routes 温度断言同步）。人工验收
5 场景待本机实测（日常/生气和解/附属品/拥护时刻/工具转述）。

## 2026-08-05 · 限流体检修复：429 带 Retry-After 退避头 + OPTIONS 预检豁免

- 429 响应新增 `Retry-After` 头（值 = 桶内最早记录跌出 60 秒窗口的秒数，
  1–60）：前端 fetch / 推送网关可据此退避，不再盲目重试撞墙。
- CORS 预检（OPTIONS）不再进限流桶：此前高频预检会消耗真实请求的配额，
  是实测过的 429 误触形态之一；预检本就豁免认证，本次补上限流豁免。
- `_check_rate` 返回 (放行, Retry-After) 二元组；新增 3 例钉死行为：
  Retry-After 取值区间（`test_429_carries_retry_after_header`）、预检 12 次
  后 GET 仍放行（`test_options_preflight_not_counted_in_bucket`）、
  无限制时返回 None（`test_retry_after_computed_from_oldest_record`）。

**验证**：pytest 684 全绿（含本批全部改动）。

## 2026-08-05 · 地图引擎收敛（纯高德）+ 看地图/去这里联动导航

**地图引擎收敛**
- 删除谷歌 Places 双引擎死代码（`tools/google_places.py` 整文件、`_pick_engine`
  几何判定、`_engine_hint`、`_resolve_engine_for_place`、`GOOGLE_MAPS_API_KEY`
  配置与 .env.example 块）——谷歌侧无 key 本就走不到，收敛为纯高德单引擎后
  后端更简单、测试更少、无死路径。
- 港澳同样走高德：GCJ-02 正向转换覆盖港澳（高德港澳数据同为加偏系），
  transit/cycling 港澳「婉拒」概念消失，空结果是诚实信号；`geo.py` 模块注
  与 `test_geo.py` 语义更新。
- verify_tools 清理谷歌软提示串与引擎字样；README/deploy 密钥五→四。

**看地图 / 去这里联动导航**
- 5 个地图工具改 `response_format="content_and_artifact"`：返回 (渲染文本,
  nav) 二元组，nav = {name, lat, lon, coord:"gcj02"}（高德契约 GCJ-02 原值，
  零转换下发）。直接 `ainvoke({纯参数})` 仍返回纯 str，测试与 verify_tools
  零改动；图内 ToolMessage.artifact 下发，LLM 只见 content 零污染。
- SSE `tool_end` 新增可选 `nav` 字段（`_tool_end_payload` 提取 content +
  artifact）——顺带修掉折叠区显示 pydantic repr（tool_call_id 噪声）的旧观感。
- 前端 `mapLink.js` + `MapNavButtons.jsx`：内地 → 高德 URI 唤起（marker 看
  地图 / navigation 去这里，coordinate=gaode 直传）；港澳/海外 iOS → Apple
  Maps（香港数据最好，系统级唤起，GCJ-02 逆向成 WGS-84 精度 1-2m）；其余 →
  Google Maps 网页链接兜底（跳转链接无需 key）。`window.location.href` 唤起，
  未装 App 时 uri.amap.com 自回退网页版。

**验证**
- pytest 679 全绿（test_map_engine.py 重写：删谷歌侧 10 例、新增 nav 契约
  14 例 + `_tool_end_payload` 5 例）；verify_tools ALL_TOOLS_HEALTHY（5 地图
  工具全 LIVE）。vitest 24 全绿（gcj02 向量硬编码自后端正向函数实跑）；
  oxlint 零告警、build 零错误。

## 2026-07-29 · 小汐的日历（第四面板）+ 地图双引擎 + 前夜提醒

设计文档：`docs/superpowers/specs/2026-07-29-calendar-and-maps-design.md`
视觉基准：`docs/mockups/calendar-panel-v2.html`（本体取色，已验收）

**新增**
- **日历面板**：顶栏第四入口（2+2 分组），手写月历 42 格，事件 pill 三色语义
  （✦琥珀=小汐记的 / 疗愈绿=冷冷记的 / 陶土玫瑰=很重要）；日期详情 + 记/改
  事件两级 sheet；删除两次确认；只读纪念日；多天行程逐格延续；相似合并提示。
  冷冷与小汐**一本账**：UI（source=user）与 LLM 工具共享 dedupe_key 天然互挡；
  「聊完即刷」——小汐在对话里记下的日程即时出现在日历上。
- **事件墓碑机制（迁移 v9）**：软删事件（status=deleted）继续占 dedupe 索引，
  挡住 LLM 重新提取/检测器再生成——「删了就是删了」；归档（翻篇）语义不变，
  明年/下次照常再生成。同标题同日期重加 = 复活。
- **REST `/api/events`**：GET 月范围（白名单 + archived 回看）/ POST 创建
  （409 相似提示）/ POST {id} 更新 / DELETE 软删；只读型 403；写后刷新提示词缓存。
- **LLM 工具** +`update_event` / `delete_event`；`create_event` 补墓碑归因文案
  （「这件冷冷删掉过，小汐不偷偷记回来」）。
- **前夜提醒**：每日 23:00 HKT，明日有日程才出声（白名单过滤，台风走聊天
  关心路径不双份），12h 防复读，LLM 挂了固定文案兜底，走统一推送流水线；
  早安问候同步注入今日日程（标了很重要的认真说一次）。
- **地图双引擎**：`_pick_engine` 坐标几何判定——内地高德 / 港澳海外谷歌
  （港澳排除框上界≈深圳河一线，深圳归高德）；谷歌走 Places (New) searchText，
  key 经请求头（脱敏零负担），`_GoogleKeyMissing` 与高德文案严格分离；
  两引擎输出归一化同构渲染；路线规划谷歌侧婉拒（Directions 记为将来项）。
  `GOOGLE_MAPS_API_KEY` 留空 = 优雅降级，无 key 零调用零账单。

**收敛（第二轮体检 round2 🟠/🟡）**
- `RoomScene` / `SkyBackground` / `CharacterSprite` React.memo：消掉流式聊天
  逐 token 的全树空转；SkyBackground 自持时段钟（memo 后天色仍按时段切，
  顺带修「天空无自时钟」🟡-5）。
- `usePush` 未读 bail-out：60s 轮询内容不变不换引用，不再逼全树重渲染。
- 全局 `:focus-visible` 疗愈绿基线 + `prefers-reduced-motion` 静止化。
- 主包 gzip 76→81KB（+5KB 日历，Markdown 独立块不变）。

**文档**
- README：API 表 + 日历四端点；环境变量表补齐（DOC-03）；文档/脚本索引补全
  （DOC-02/04）；密钥轮换四→五。
- deploy/README：五密钥同步 + 迁移 v9 紧急回退 SQL 备注。

**验证**
- pytest 336→393 全绿（两轮零 flaky）：v9 谓词/墓碑挡重建/force 复活/
  路由 17 例/提醒 4 态/地图双引擎 13 例/联动剧本 A/B 半边。
- E2E 28/29（唯一失败项为已知空库种子依赖，非缺陷）：新增日历剧本 5 断言
  （dialog 挂载 / 详情 sheet / 创建→pill / 删除墓碑 / Esc 两级）全过，
  零控制台错误、零 API 4xx/5xx。
- 临时副本冒烟（:8001）验证迁移 v8→v9 在线升级；生产库零触碰。
