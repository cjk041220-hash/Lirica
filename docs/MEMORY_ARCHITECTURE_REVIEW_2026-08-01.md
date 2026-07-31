# 记忆架构评审 + 优化方案（2026-08-01）

> 评审方式：通读全部记忆相关代码 + **直接取证真实 userdata.db 与 5 份备份** +
> 研究 5 个业界记忆框架（MemGPT/Letta、mem0、Zep/Graphiti、A-MEM、LangMem）的架构与已知缺陷。
> 结论：**代码质量 9 分、实盘 3 分** —— 架构设计优秀，但写路径在真实使用中是「死」的。

---

## 1. 当前架构一图流

```
写路径（纯 agentic）     读路径（被动注入）             遗忘路径
LLM 调用 remember/        NarrativeBuilder hybrid      apply_status_decay (2h)
update_memory →           (3 相关 + recent 补足 +      active→core (≥5次 & ≥30天)
manager.add/update         1 core 保底, ≤5条×200字)     active→archived (≤1次 & ≥90天)
  └─ (category,content)   → pre_model_hook 经         记忆回声 (90天+随机, 7天
     部分唯一索引 +         llm_input_messages 注入      限频, 一生一次)
     ON CONFLICT 原子去重   (不进 checkpoint,
     档案 COLD_INFO 静态     「数据不是指令」包裹)
     双通道并行注入
```

存储：SQLite `memories` 表（v10）：id / content / category / created_at / last_used_at /
access_count / status(active/core/archived)。备份 = 整库快照轮转 5 份 + 700/600 权限 + 可脱敏。

## 2. 真实数据取证（本评审独有证据）

对 `backend/data/userdata.db`（schema v10，7-31 迁移）+ 5 份备份逐表核查：

| 表 | 行数 | 含义 |
|---|---|---|
| memories | **0** | 长期记忆库全空 |
| chat_sessions / chat_messages | 10 / 31 | 真实对话发生过（7-27~7-29，含「我好像喜欢上一个人」这类应记内容） |
| events | 8（全 weather） | 检测器路径工作正常 |
| events source=llm/user/memory | **0** | agentic 写路径从未触发 |
| diaries / cycle 派生事件 | 0 / 0 | 同上 |

**结论：记忆系统代码质量高，但写路径在实盘是「死」的** —— 31 条真实消息
（含 TOOL_GUIDELINES 明确写了「冷冷告诉你她对谁动了心 → 用 remember 记进
『重要的事』」的内容）没有触发过一次 remember；create_event / log_period
同样零调用。7 天的 2h 衰减 job 跑在空表上；记忆回声无候选（0 条 ≥90 天记忆）。

根因分析（按可能性）：
1. **纯 agentic 写入依赖 LLM 在对话中自觉调用工具** —— DeepSeek 对 23 个工具的
   长列表调用决策保守；对照业界：mem0 / LangMem / Zep **全部**采用「对话后异步
   后台提取」兜底，主对话工具调用只是加速通道；
2. 提示词里「档案与记忆的边界」强调「档案里已有的不要重复记」，LLM 可能把新信息
   误判为「档案已有」而整体抑制了 remember；
3. 无任何「写入健康检查」，系统跑了 7 天 0 写入，无人知晓、无日志告警。

## 3. 评分（10 分制，加权总评 **6.0/10**）

| 维度 | 权重 | 得分 | 依据（代码证据） |
|---|---|---|---|
| 存储与持久化 | 12% | **9.0** | v5/v10 唯一部分索引 + ON CONFLICT 原子去重；备份轮转+脱敏+权限；WAL+单连接纪律 |
| 写路径可靠性 | 14% | **3.0** | 纯 agentic、无后台兜底；**实盘 0 触发**（0/31 消息被记住） |
| 召回质量 | 14% | **6.5** | 二段排序+四因子+passive 阻断正反馈（超同龄项目）；无语义检索、query 只取末条消息、无召回评估数据 |
| 遗忘与生命周期 | 10% | **7.0** | 分层衰减+回声限频纪律优秀；无过期机制（事件有 archive_expired，记忆没有）、archived 不可读回、热度信号单一 |
| 更新与矛盾处理 | 10% | **6.5** | agentic 矛盾引导与 mem0 的 ADD/UPDATE 哲学同款；覆盖无版本、无 updated_at、created_at 被覆盖刷新致时间衰减语义错乱 |
| 注入面与安全 | 10% | **8.0** | 「数据不是指令」降级+截断+不进 checkpoint+备份脱敏，纵深防御好；无来源追踪、content 无长度上限 |
| 可观测与运维 | 10% | **4.0** | 仅 token 遥测；无召回质量打点、无审计、**无健康检查（0 条记忆跑一周无告警）** |
| 测试覆盖 | 8% | **8.0** | 60+ 专项（460 全量）；工具层 update/delete/list/export 零测试、千条级/崩溃恢复无覆盖 |
| 实盘有效性 | 12% | **3.0** | 真实 DB：memories=0、llm 事件=0、echo 无候选 |

加权 = 1.08+0.42+0.91+0.70+0.65+0.80+0.40+0.64+0.36 ≈ **6.0/10**

**与 CHECKUP_2026-07-31（4.2/5 ≈ 8.4/10）的差异说明**：CHECKUP 评的是代码质量与
修复闭环（记忆动力学 MEM-01~05 确实修得漂亮，本评审认可）；本评审引入「实盘数据 +
业界架构对照」两个新锚点 —— 代码 9 分、实盘 3 分，架构完备度被写路径短板拖累。

## 4. 缺陷清单

| # | 缺陷 | 证据 | 优先级 |
|---|---|---|---|
| D1 | 写路径纯 agentic，实盘 0 触发 | 真实 DB 取证 | P0（已修） |
| D2 | 无后台提取兜底（mem0/LangMem/Zep 均标配） | 业界对照 | P0（已修） |
| D3 | 无写入健康检查，0 记忆跑一周无告警 | 真实 DB + 日志无记忆条目 | P0（已修） |
| D4 | content 无长度上限，LLM 可写入任意长文本 | manager.add 无校验 | P0（已修） |
| D5 | 覆盖刷新 created_at（时间衰减被重置）；无 updated_at | manager.update_content | P1 |
| D6 | 记忆无来源追踪（哪次会话/何时提取不可追溯） | schema 无 source 字段 | P1 |
| D7 | 阶段性事实（考试/搬家）无过期机制；事件有记忆没有 | utils/events.py 有 archive_expired | P1 |
| D8 | archived 冷库不可读回，export/recall 全排除 | manager 谓词 | P1 |
| D9 | 工具层 update/delete/list/export 零测试；千条级无覆盖 | 测试盘点 | P1 |
| D10 | 召回 query 只取末条 human 消息，多轮上下文丢失 | graph.py pre_model_hook | P1 |
| D11 | 档案 COLD_INFO 静态 vs 记忆动态双通道冲突（控糖例） | soul.py「关于冷冷」 | P2 |
| D12 | access_count 信号单一（CHECKUP MEM-03 遗留） | manager 评分公式 | P2 |

## 5. 优化方案

### P0（本轮已实施，2026-08-01）

**① 会话后台提取（D1/D2）——核心修复**
- 新模块 `backend/memory/extract.py`：水位线（memory_extract_state 表，迁移 v11）+
  提取流水线。心跳 `_periodic_maintenance` 每 2h 运行：取水位线后的新对话（限
  50 条 / 4000 字）→ 连同现有记忆 top-10 作冲突上下文 → 轻量 LLM 决策
  add / update / noop → dispatch 到 manager（复用原子去重）→ 整批成功才推进水位线；
  LLM 失败/输出不可解析不推进（下次重试，去重保证无害）。
- 成本护栏：单次最多 5 add / 3 update；无新消息时纯读近零开销。
- 首启自动回填全部历史对话（含 7-27~7-29 那 31 条）。

**② 记忆健康检查（D3）**
- `manager.check_memory_health()` 纯指标 + `extract.check_and_report_memory_health()`
  节流告警（24h 内不重复）：有 ≥10 条对话却零记忆 → WARNING「写路径疑似失灵」；
  距上次写入 ≥7 天 → WARNING「记忆停滞」；正常时 INFO 遥测。

**③ content 长度上限（D4）**
- `manager.add()` / `update_content()` 超 500 字截断（不拒绝）+ log warning；
  顺带修复：空内容覆盖 update_content 返回 False（原会写入空记忆）。

**测试**：+24 专项（`test_memory_extract.py` 21 例 + `test_migrations_v11.py` 3 例），
全量 **484 passed, 1 skipped**。心跳维护测试同步隔离新段（防测试写穿生产库）。

### P1（下轮）
- schema v12：`updated_at`（覆盖只刷 updated_at，时间衰减仍按 created_at）+
  `source`（session_id 快照）+ 覆盖前旧文本快照（D5/D6）；
- export 提供 `include_archived` 选项 + 面板回看 archived（D8）；
- 工具层 4 个零测试补齐 + 千条级性能冒烟（D9）；
- query 扩展为最近 1~2 轮 human 消息拼接（D10）；
- 事件归档时可选联动提示清理关联记忆（D7，设计先行、观察）。

### P2（远期）
- **档案可演化（D11）**：COLD_INFO 拆「身份事实（静态）」与「偏好/经历（动态）」——
  偏好子集渲染为 profile 记忆块，由 update_memory 维护，系统提示词从库渲染；
  身份事实保持静态防退化。
- 语义检索（embedding，千条后启用，与 bigram 混合）；记忆审计表（write 操作留痕，
  服务「你记错了」纠错）；多信号热度（D12）。

### 不做清单（防过度工程）
知识图谱（Zep 式，单用户不值得）；引入向量库（现状记忆量极小）；A-MEM 链接图；
多租户 scope；全量 episodic 会话摘要。

## 6. 业界对照（GitHub 项目经验）

| 框架 | 架构 | 可借鉴点 | 小汐现状 |
|---|---|---|---|
| MemGPT/Letta | 三层记忆层级（core/recall/archival）+ agent 自我管理 | core 块可编辑 → 档案可演化（P2）；sleeptime 后台 agent → 本轮的会话提取 | 读路径分级已有（narrative core 保底） |
| mem0 | 两阶段 LLM 提取（Extract → Categorize ADD/UPDATE/NOOP） | 提取+整合决策管线 → 本轮 extract.py 同构；已知缺陷：无 ground truth、无置信度 | 矛盾处理（remember 回显引导）比 mem0 更细 |
| Zep/Graphiti | 双时态知识图谱，矛盾软失效不删除 | 覆盖留痕（旧事实可查）→ P1 prev_content 快照 | 记忆内容合并写法（「以前……最近……」）是轻量版 |
| A-MEM | Zettelkasten 笔记 + 链接 + 记忆演化 | 新记忆触发旧记忆上下文更新 —— 单用户陪伴场景价值中等 | 暂不做 |
| LangMem | semantic/episodic/procedural 三分 + background 模式 | 后台提取模式 → 本轮同款；procedural 记忆（agent 自改指令）远超需求 | 暂不做 |
| FutureAGI 四维评估 | recall / freshness / contradiction / forgetting | 「Recall 是地板」：评估要看更新/矛盾/遗忘四维 → 本评审的 D5~D8 即 freshness/contradiction 缺口 | 打分已按四维展开 |

**关键共识**：对话后异步提取 + 去重 + 矛盾整合是全业界标配写路径；遗忘/生命周期
（Zep 的软失效、小汐的分层衰减+回声）是最难、也最值得做的部分 —— 小汐在此处
已超出多数同类项目。

---

## 7. 实施状态更新（2026-08-01 同日）

**P0 已落地**：会话后台提取（v11 水位线）+ 健康检查 + content 长度上限。全量 484 tests 绿。

**P1 已落地**：
- schema v12：`updated_at`（覆盖只刷 updated_at，时间衰减仍按 created_at，D5）、
  `source`（chat:{session_id} 来源追踪，D6）、`prev_content`（覆盖留痕快照，可回看旧说法）；
- archived 可回看（D8）：`/api/memories?include_archived=1` + export 同款参数 +
  记忆面板「已归档」开关；
- 工具层 update/delete/list/export 测试补齐 + 千条级召回冒烟（D9）；
- pre_model_hook query 扩展为最近 1~2 条 human 消息拼接（D10，回指找回话题）。
- **工具调用取证结论（修正「DeepSeek 懒调用」假设）**：解码 checkpoints.db 全量
  状态——68 条 human 消息、18 次工具调用（search_nearby 7 / get_weather 4 / tarot 2
  / geocode 1 / export 1 / delete 1 / transit 1 / list_events 1），**remember 0 次**。
  模型并不懒：用户明确要求时（查天气/搜附近）调用积极；**不主动写记忆**才是病根。
  修复：remember 工具描述与 TOOL_GUIDELINES 触发言语行为化（低门槛、宁多勿少），
  并在每轮对话打点 `telemetry: session_tools=[...]` 供前后量化对比。

全量 **501 passed, 1 skipped**；前端构建通过。测试数据已按用户授权清空
（31 条测试对话等），下次重启后 v11/v12 迁移在干净库上执行。

---

*评审人：Claude Code（2026-08-01）。证据：代码通读 + userdata.db/checkpoints.db 取证 + 业界文献对照。*
*本评审的 P0+P1 修复已随同日提交落地。*
