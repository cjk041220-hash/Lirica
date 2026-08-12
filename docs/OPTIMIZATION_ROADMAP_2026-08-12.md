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

- [x] **P0-1. A/B 验证 mood→文风映射遵循度** ✅（2026-08-12，脚本
      `scripts/persona_eval/ab_mood_style.{sh,py}`，报告
      `.superpowers/sdd/2026-08-12-batch1/ab-report-2026-08-12/`）：
      12 剧本 × 2 组（:8001 注入 / :8002 patch 不注入，mood 钉死「开心」）实跑，
      **量化 0/4 项过、无方向一致性**（长度比 1.01、长于比例 0.50、语气词密度
      -30% 反向、句数 -11.5% 反向）→ 文风句对 Flash 输出影响低于可测门槛。
      用户拍板：**4 词保底仍扩**（非 10 词放量）——「细腻表达」动机证伪，
      只留「减少重复」价值。
- [x] **P0-2. mood 词表 + 事件池文本扩容** ✅（2026-08-12，同批提交）：
      - 词 10→**14**：MOOD_VECTORS + _MOOD_STYLE_HINTS 同步补 平静/安心/轻松/想念
        （距离核算 ≥0.22 无冗余；想念距深夜池 0.70 死词不放）；时段池补引用
      - 事件 37→**61**（每时段 +6，含「回忆冷冷」类 8 条，禁 JK/八卦 ✅）
      - 前端 `web/src/App.jsx` moodToSprite/moodToLight 同步 4 词（想念归 idle+cool）
      - 新建 3 测试（键集一致性【原为承诺非现状，本批补实】/ 新词几何 ≥0.22 /
        新词池内可达 ≤0.65）；test_heartbeat.py 采样断言经蒙特卡洛复验安全
        （EVENING 池扩词后泄漏计数 0.27→0.13，分母稀释）
- [ ] **P0-3. 事件池联动机制**（与 P0-2 文本扩容拆开）：天气/季节联动需新机制（心跳现无正常
      天气读取；异常天气检测是另一条通道）。约束：早晨锚定日级刷新（heartbeat.py L162）。
      **语义已拍板（2026-08-12）：日内不随天气变化 = 可接受**——事件是「今天的心情基调」
      而非实时天气播报，故只需在早晨锚定时联动天气/季节（日级刷新足够），不做日内联动机制。
- [x] **P0-4. 系统文案润色** ✅（2026-08-12）：auth.py 401 →「想和小汐说话，得先对上暗号哦~」
      （对齐「浪花家族」语气；test_auth_middleware 只断言状态码、前端只广播不渲染 → 零风险）；
      `heartbeat_moods.py` arXiv:2504.14706 误读注释已修正（条件化表达 ≠ 情绪改变文风，
      并附 A/B 实测佐证）。
      注：「系统文案段迁前端」**已完成**（PROMPT_GUIDE:104），不重做。

## P1 人格化主线（观察收口后动工，污染面已标注）

- [x] **P1-1. 跨会话情绪基调延续** ✅（2026-08-13，批次二）：
      - `heartbeat_moods.py` `_SIGNAL_OFFSETS` 加第五信号 `carryover`（-0.12,
        +0.04——幅度刻意小于 care 的 -0.15：惦记是「还没散去的」不是
        「正在发生的」）+ `_CARRYOVER_SAD_LEXICON`（12 个高精准负面词）/
        `_CARRYOVER_WINDOW_HOURS`（24h）/ `_CARRYOVER_TAIL_LIMIT`（6 条）
      - `MemoryManager.has_sad_carryover()`：排除当前活跃会话与 era（独处
        时间线只有小汐自己说话），扫上一段非 era 会话尾部 user 消息，
        命中词表且 24h 内 → True；任何失败 → False 不推偏（同 talked
        降级纪律）
      - **设计约束**：情绪倾向**确定性可算**（静态词表，禁 LLM 判定）✅；
        `_apply_signal_offsets` 四参数签名测试钉死，第五信号默认 False
        保测试绿 ✅；失败语义沿用「保持当前词」✅
      - 新增 6 测试（has_sad_carryover 命中/未命中/超窗/era 排除/无
        life_state 降级 + _choose_mood 接线钉值）；全量 pytest 892 passed
- [ ] **P1-2. 补 BASE_PERSONA 真缺口**（前置现状 diff！）：PROMPT_GUIDE:236-239 的
      「情绪反应模式/对话深度」已被「态度」段 + 22 组示例解决，大概率只剩
      「更多示例（尤其难过时）」真缺。连锁：改锚同步 + 探针锚点措辞被钉死
      + CHANGELOG 过渡说明 + 污染吃瓜观察基线（放 0a 收口后）。
- [x] **P1-3. TOOL_GUIDELINES 补「什么时候不要用工具」** ✅（2026-08-13，批次二）：
      - soul.py TOOL_GUIDELINES 加「什么时候不要用工具」段（倾诉时别查工具 /
        她没问别主动查 / 情绪话题不套工具模板 / 记住的分寸不变）——
        纯提示词层，PERSONA_ANCHOR 零改动（锚是压缩版不承载工具指引）
      - 新增 2 探针（倾诉不查工具 / 没问不主动查）钉承诺锚点；
        :8001 副本冒烟实测「我最近好难过……」→ 陪伴路径无工具调用 ✓
- [ ] **P1-4. 画像节流调整**（挪到画像现网质量评估之后）：拆独立 _PROFILE_THROTTLE_HOURS 或
      24h→12h。成本：LLM 调用翻倍（每次 40 条摘录）+ 测试改造（test_consolidate.py L205-233
      钉死同窗口语义 + conftest 复核）——非迁移 v23 必需（memory_extract_state 表支持多行）。
      **批次二裁决（2026-08-13）**：评估未做 + 12h 会翻倍 LLM 调用并改变 0a-0c 观察基线，
      本批不动，放观察收口后与 0a 结论一起排。

## P2 工程债（择机，各独立）

- [x] **P2-1. TOKEN_COST_WARNING 实现** ✅（2026-08-12，批次三）：settings 加
      `token_cost_warning`（近似输入 token/日阈值，0=关闭）+ 新模块 `utils/token_budget.py`
      （cl100k 近似累计、HKT 日归零、阈值倍数递增 WARNING、进程内累计重启清零）+
      graph.py 遥测块放宽接线（默认全关零成本，B8 语义不变）。只累计 chat agent 输入
      （成本大头，含工具循环）；后台任务不累计。7 测试（test_token_budget.py）。
- [x] **P2-2. 探针实跑层负断言补强** ✅（2026-08-12，批次三）：test_persona_eval.py 加
      `REPLY_NEGATIVES`（回复级负断言，单独承载——不进 PROBES.negatives，避免被单元层
      「规则文本不含」误杀），覆盖 吃瓜劝选/不适即收/减肥拒答 3 场景高精准违规子串；
      新增「减肥拒答」探针；probe.py 接线 `.get(name, [])`。卫生测试钉键集防拼写漂移。
      边界不变：单元层负断言留空是设计使然，语义正确性人工抽查兜底。
- [x] **P2-3. CI 死配置** ✅（2026-08-12，批次三）：删 `.github/workflows/ci.yml`
      （Gitee 不跑 GitHub Actions，用户拍板删除）；check_sw_version.py 发版检查保留，
      改写为 deploy/README §4 的手动发版步骤。
- [x] **P2-4. deploy/README.md「现 v7」→v8 修正** ✅（2026-08-12，批次三）。
- [x] **P2-5. CHANGELOG 数字修正** ✅（2026-08-12，批次三）：872→875，注明 3 例差额
      来源 = 收尾批次迁移 v22 的 test_migrations_v22.py（3 测试）。
- [x] **P2-6. provider 抽象层** ✅（2026-08-13，批次二顺带）：
      - `providers/deepseek.py` 工厂消费模型元数据：`get_model_info()`（能力
        查询 supports_thinking / max_output_tokens / context_window，为「关键
        场景升级 Pro」铺路）+ `_MODEL_META` 索引（字典推导式自动跟随
        AVAILABLE_MODELS）+ 未知模型名回退默认 + max_tokens 越界钳制
      - **纪律**：零行为变化——默认 flash / temperature 0.8 / max_tokens 8192 /
        timeout 120 / retries 2 一律不动；thinking 参数**未**新增（DeepSeek
        深度推理由模型内部控制，config.py 注释已定，不发明未验证的 API 参数）
      - 新建 5 测试（test_deepseek_provider.py）；全量 pytest 897 passed

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
| 一 | P0-1 A/B → P0-2 词表/事件池（含前端映射）→ P0-4 文案 | ✅ 2026-08-12 已入库（b6c07f3） |
| 二 | P1-1 跨会话情绪基调（carryover 信号）→ P1-3 工具时机段 | ✅ 2026-08-13 已入库（15d2c38，P1-2 改锚与 P1-4 节流留收口后） |
| 二附 | P2-6 provider 抽象层（顺带）+ P1-2 只读 diff 结论 | ✅ 2026-08-13 已入库（P2-6 零行为变化；P1-2 产出报告不落码） |
| 三 | P2-1..P2-5 工程债 | ✅ 2026-08-12 已入库（236a5d0） |
| 四 | 0a-0c 观察收口（若恢复观察期）→ P1-2 补难过示例（可选）→ P1-4 → P3 运营 | 条件触发 |

## 验证方式

- 每批走 SDD/TDD 循环（台账 .superpowers/sdd/…）；后台 agent 不可靠，直接手做
- 全量 pytest（基线 875 passed/1 skip）+ 前端 vitest + build + lint
- :8001 临时副本冒烟 + 生产库 userdata.db/checkpoints.db sha256 零污染
- 人格层改动前后跑 scripts/persona_eval 探针回归
- 观察收口核验：生产 proactive_messages/chat_messages 抽样 + era 时间线抽查
