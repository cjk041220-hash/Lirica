# 小汐优化规划路线图

> 建档日期：2026-08-12
> 依据：两份外部评估报告（桌面《小汐sonetto_人格化评估报告》《小汐sonetto_后端架构评估报告》，2026-08-11）+ 全量代码核验 + 论文溯源
> 状态：勾选销账制 —— 完成的项 `- [x]` 并附验证结论

## 核验结论（本路线图的依据）

代码级断言**全部吻合**（soul.py 46KB / 人格锚每 8 调用 / MOOD_VECTORS 10 词 / 23 工具 / 画像 24h 节流经 consolidate 共享窗口）。报告结论成立：**开源 companion 第一梯队、架构无致命缺陷、定向补强而非推倒重来**。

两处引用修正（影响对外引述的严谨性）：
1. ContextEcho（arXiv:2605.24279）实测对象是**长程 agentic 编码会话**的人设漂移，非陪伴对话；「单次锚恢复」在 API 层通用可外推，但引述时注意场景错位
2. arXiv:2504.14706 证明的是「LLM 可按 valence/arousal 条件化表达情绪」，**不是**「情绪显著改变用词/句长/风格」——此误读已转录进 `heartbeat_moods.py` 代码注释，批次一顺手修正

## 永久纪律（任何批次不得违反）

- **改 BASE_PERSONA 必须同步改锚**（PERSONA_ANCHOR 是蒸馏常量，单测钉死逐字节一致）
- **新心情词必须双补**：MOOD_VECTORS 坐标 + _MOOD_STYLE_HINTS 文风句（键集一致性有测试）
- **模型升级前必跑** `scripts/persona_eval` 探针回归（Replika 教训：模型升级=人格突变风险）
- **人格调整走「升温而非换人」** + CHANGELOG 留过渡说明
- 验证纪律：全量 pytest + :8001 副本冒烟 + 生产库 sha256 零污染 + 人格改动后探针回归

---

## 批次 0：观察收口（最高优先，两条时间线：吃瓜 08-18 / 初识档 08-22）

- [ ] **0a. 一周观察收口**：吃瓜频率 / JK 资料库使用 / 独处时间线八卦开场 / 初识档现网表现。
      核验法：生产 proactive_messages + chat_messages 抽样、era 时间线抽查。
      产出：「是否走 D19 降温杠杆（加示例 > 改 INTERACTION_RULES > 降温）」结论。
- [ ] **0b. 人工验收 5 场景实测**（CHANGELOG 2026-08-05 遗留，最久悬案）：日常 / 生气和解 /
      附属品 / 拥护时刻 / 工具转述 → 实测后在 CHANGELOG 结案。
- [ ] **0c. launch-check 10.14 一周陪伴叙事品评**（唯一挂起项，用户主观确认）——与 0b 同天排期。
- [ ] **0d. 记忆叙事格式观察项**（PROMPT_GUIDE 🟡-5，条件触发：LLM 对记忆利用不好时调
      `narrative.py` 结构）。

## P0 确定性侧（观察期可并行，零模型风险，纯新增）

- [ ] **P0-1. A/B 验证 mood→文风映射遵循度**（先行！结论决定 P0-2 投入上限）：
      :8001 副本 + 探针模式（7s 间隔 / 429 退避已有），同剧本跑「注入 vs 不注入文风句」两组，
      比对输出风格差异。差异显著 → 词表扩充值得；微弱 → 词表扩充降级为保底。
- [ ] **P0-2. mood 词表 + 事件池文本扩容**（A/B 后放量，同批提交）：
      - 词 10→20-25+：`heartbeat_moods.py` MOOD_VECTORS(L173-184) + _MOOD_STYLE_HINTS(L190-201)
        同步补；时段池 TIME_PERIODS(L52-125) 补引用
      - 事件 37→60+：TIME_PERIODS events 文本扩容，加「回忆冷冷」类
      - **依赖**：`web/src/App.jsx` moodToSprite(L27)/moodToLight(L43) 前端映射同步
        （否则新词走 fallback）；test_heartbeat_moods.py 高斯采样几何断言回归
      - **观察纪律**：新事件文本禁用 JK/八卦题材（防污染独处时间线观察）
- [ ] **P0-3. 事件池联动机制**（与 P0-2 文本扩容拆开）：天气/季节联动需新机制（心跳现无正常
      天气读取；异常天气检测是另一条通道）。约束：早晨锚定日级刷新（heartbeat.py L162），
      联动事件日内不随天气变化——先拍板可接受语义还是缺陷。
- [ ] **P0-4. 系统文案润色**：auth.py 无认证文案「需要有效的 API Token…」（PROMPT_GUIDE:228）
      ← 僵硬；顺手修正 `heartbeat_moods.py` 的 arXiv:2504.14706 误读注释。
      注：「系统文案段迁前端」**已完成**（PROMPT_GUIDE:104），不重做。

## P1 人格化主线（观察收口后动工，污染面已标注）

- [ ] **P1-1. 跨会话情绪基调延续**（评估报告 P0-1）：
      - `heartbeat_moods.py` _SIGNAL_OFFSETS(L234-239) 加第五信号 carryover
      - 定位上一段会话：chat_sessions + life_state.active_session_id（v21）；素材：chat_messages 尾部
      - **设计约束**：情绪倾向必须**确定性可算**（静态词表/规则，禁 LLM 判定）；
        `_apply_signal_offsets` 四参数签名被测试钉死（test_heartbeat.py L738-752），
        第五信号默认 False 保测试绿；失败语义沿用「保持当前词」（_choose_mood L304-305）
      - **措辞纪律**：「惦记」不「复盘」
- [ ] **P1-2. 补 BASE_PERSONA 真缺口**（前置现状 diff！）：PROMPT_GUIDE:236-239 的
      「情绪反应模式/对话深度」已被「态度」段 + 22 组示例解决，大概率只剩
      「更多示例（尤其难过时）」真缺。连锁：改锚同步 + 探针锚点措辞被钉死
      + CHANGELOG 过渡说明 + 污染吃瓜观察基线（放 0a 收口后）。
- [ ] **P1-3. TOOL_GUIDELINES 补「什么时候不要用工具」**（PROMPT_GUIDE 🟢-6）：
      倾诉时别查天气搜周边。
- [ ] **P1-4. 画像节流调整**（挪到画像现网质量评估之后）：拆独立 _PROFILE_THROTTLE_HOURS 或
      24h→12h。成本：LLM 调用翻倍（每次 40 条摘录）+ 测试改造（test_consolidate.py L205-233
      钉死同窗口语义 + conftest 复核）——非迁移 v23 必需（memory_extract_state 表支持多行）。

## P2 工程债（择机，各独立）

- [ ] **P2-1. TOKEN_COST_WARNING 实现**（SERVER_ADMIN §11，三处文档点名，零实现）：
      本地 token 遥测累计（soul+graph 已有 DEBUG 埋点）→ 阈值告警。
- [ ] **P2-2. 探针实跑层负断言补强**：`scripts/persona_eval/probe.py` 回复级负断言
      （核心场景：吃瓜频率 / 不适即收 / 减肥拒答）。**边界**：单元层负断言留空是设计使然
      （禁令句含被禁词，test_persona_eval.py L22-24），不动；语义类探针自动化只能到
      「提示词注入层」，行为正确性人工抽查兜底。
- [ ] **P2-3. CI 死配置**：.github/workflows/ci.yml 在 Gitee 不跑 → 配 Gitee Go 或删除，
      **必须保留 check_sw_version.py 发版检查**（防 sw.js 漂移复发）。
- [ ] **P2-4. deploy/README.md「现 v7」→v8 修正**（SERVER_ADMIN §11 点名）。
- [ ] **P2-5. CHANGELOG 数字修正** 872→875（注明 3 例差额来源，防下轮再漂）。
- [ ] **P2-6. provider 抽象层**（低优先）：providers/deepseek.py 57 行单模型，
      AVAILABLE_MODELS 有 flash+pro 但工厂不消费 pro 元数据；为「关键场景升级 Pro」
      铺路（评估报告 P2-3 依赖此）。

## P3 运营（多数等外部条件）

- [ ] 云端备份 rclone 配置（等用户建 OSS/COS 桶；runbook §7 已完备）
- [ ] iOS Safari standalone 真机清单（用户真机执行；08-03 清单未跑项）
- [ ] PRIV-01 filter-repo（转公开前；仓库私有已降级备忘）
- [ ] 密钥轮换（**口径**：DeepSeek/高德/Tavily/VAPID 四把 + XIAOXI_API_TOKEN 第五把，
      SERVER_ADMIN §8 与 deploy/README §0 对齐）

## 远期（不排期，条件触发）

- **reflection**：先「统计信号版」（consolidate 周期从账本算高频主题/情绪趋势/时段活跃度，
  确定性模板，防幻觉洞察——评估报告 P0-3 的降级版），LLM 洞察版等 embedding 落地
- **embedding 语义检索**（千级记忆后触发，2026-08-02 复核定调维持）、量化漂移监控
  （复用探针实跑层为最小载体）
- 主动消息频率自适应（按时段/参与度）、关系成长维度扩展（自我暴露深度）、
  提取实时化（PRODUCT_REPORT 六·P1）
- 记忆过期机制 / 记忆审计表 / 档案可演化 / 召回评估集 / 多信号热度（PRODUCT_REPORT 六·P1/P2）
- 伦理簇（一组）：健康边界负面清单成文化 / 情绪危机转介话术 / 补偿性使用信号 / 鼓励现实社交话术
- dreaming-lite 阈值触发、小汐的成长（语气随记忆演变）、ALMA 三层显式参数化、
  ToM 心智模型、多模态情绪通道

## 明确不做（防止回潮）

- **微调模型**（Replika 教训：人格变更不可逆；「升温而非换人」依赖提示词层可回滚性）
- **多租户 / 多 worker**（单用户取舍：限流桶/GPS 缓存/心跳调度/锚计数器都是进程内状态，
  外置需架构级改造）
- **LangSmith 云端**（数据出境；本地自托管 trace 可作远期）
- **embedding 现在上**（bigram 千级中文够用，2026-08-02 复核定调）
- **谷歌地图方向**（已收敛纯高德）

## 批次节奏

| 批次 | 内容 | 时机 |
|---|---|---|
| 一 | P0-1 A/B → P0-2 词表/事件池（含前端映射）→ P0-4 文案 | 观察期并行，立即 |
| 二 | 0a-0c 观察收口 + 5 场景实测 → P1 主线（P1-1 → P1-2 diff → P1-3）→ P1-4 | 08-18/08-22 收口后 |
| 三 | P2-1..P2-6 工程债 | 择机 |
| 四 | P3 运营 / 远期 / 纪律 | 条件触发 |

## 验证方式

- 每批走 SDD/TDD 循环（台账 .superpowers/sdd/…）；后台 agent 不可靠，直接手做
- 全量 pytest（基线 875 passed/1 skip）+ 前端 vitest + build + lint
- :8001 临时副本冒烟 + 生产库 userdata.db/checkpoints.db sha256 零污染
- 人格层改动前后跑 scripts/persona_eval 探针回归
- 观察收口核验：生产 proactive_messages/chat_messages 抽样 + era 时间线抽查
