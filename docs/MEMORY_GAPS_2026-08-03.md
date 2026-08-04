# 记忆库诊断记录（2026-08-03）

> 来源：2026-08-03 系统性优化中的记忆库通读 + 业界调研（Generative Agents /
> mem0 / Zep-Graphiti / MemGPT-Letta / LangMem / A-MEM / MemoryBank）。
> 结论：**8-01 评审的 P0/P1 已全部落地，记忆库无阻塞缺陷**。以下为
> 「架构权衡 / 与业界差距」级别的观察项，按其影响排序。
>
> **状态（2026-08-03 晚）：O1 / O2 / I1 / O3 已作为 schema v17 全部落地**
> （实施计划见 `docs/superpowers/plans/2026-08-03-memory-schema-v17.md`，
> 全量 pytest 633 passed）：
> - O1 → `227156d`：`last_mention_ts` 统一提及时钟，`created_at` 不可变
> - I1 → `fa6d74c`：提取 LLM 打 1-10 分进 `_relevance_score`（权重 0.3 档）
> - O2 → `f108782`：talked 信号改读 `chat_messages` 真实对话时间
> - O3 → `c9ecb68`：superseded 链上限 5 代 + `export_json` 导出开关
> - 情绪联动 → schema v18（valence/arousal 标签 + mood-congruent 加权浮现 +
>   情绪核心记忆「只存不浮现」，2026-08-04，全量 pytest 663 passed）
> 尚未实施的：O4（记录不修）、健康检查多指标。

## 核心判断

小汐记忆库的 freshness 行为与 Generative Agents（0.995^小时数衰减 + 访问
重置时钟）和 MemoryBank（Ebbinghaus 曲线 + 访问续命）**同构，属合格水平**。
与业界主流的两大差距：

1. **时间戳语义纪律**（created_at 兼任 last_access——双时钟混用）；
2. **缺 importance 因子**（有 recency + frequency，无 LLM 打分的重要性）。

## 观察项

| # | 观察 | 证据 | 业界对照 | 建议 | 状态 |
|---|---|---|---|---|---|
| O1 | `add` 去重刷新 `created_at`，`update_content` 保留——**同一衰减体系两套矛盾时钟**：被反复 add 的记忆续命、被反复 update 但未重复 add 的记忆老化 | `manager.py`（add 的 ON CONFLICT 曾刷新 created_at）vs update_content 保留 | mem0 三时间戳范式：`created_at` 不可变 + `last_accessed_at` 刷新；Generative Agents / MemoryBank 也以「访问/提及」重置时钟而非创建时间 | 拆 `create_ts`（不可变）/ `last_mention_ts`（add/update/hit 统一刷新），衰减与 90 天归档统一基于 `last_mention_ts`（schema v17） | ✅ `227156d` |
| I1 | 排序缺 **importance 因子**（LLM 打分）——「第一次约会」与「今天吃了饭」同权衰减 | `manager.py` `_relevance_score`（原四因子） | Generative Agents：`0.5·recency + 0.3·importance + 0.2·relevance`；mem0 三分等权 | 提取时 LLM 顺带打 1-10 分，存 importance 列，进 _relevance_score（权重 0.3 档） | ✅ `fa6d74c` |
| O2 | `_choose_mood` 的 `talked` 信号读 `recent(10)` **记忆**而非**对话**——「近 24h 有记忆活动」≈「近 24h 有值得记住的对话」，纯闲聊不触发 | `heartbeat_moods.py`（2026-08-03 重构后仍在） | 无直接对应 | 改从 chat_messages 按 created_at 判断真实对话时间 | ✅ `f108782` |
| O3 | superseded 历史链**无上限、不入 JSON 导出**：`export_json(include_archived=True)` 谓词只含 active/core/archived，历史链仅存于整库快照备份 | `manager.py` export 谓词 | Graphiti 时间图无限保留 + valid_at 时间旅行；mem0 覆盖不留链；LangMem/A-MEM 压缩折叠 | 每记忆保留 N 代（如 5），更旧合并为一行 digest；`export_json` 加可选开关 | ✅ `c9ecb68` |
| O4 | 提取批次「有界不回看」：单批超 50 条/4000 字从旧端丢弃，水位线推进到批尾，丢弃消息永久跳过 | `extract.py` | 设计有意（提取价值随对话变旧衰减） | 记录，不修 | — |

## 情绪联动（前沿空白，可做差异化）✅ schema v18（2026-08-04）

- ✅ 记忆带 valence/arousal 标签（提取时 LLM 顺带打分，A-MEM note tags 先例）
  ——`memories.valence/arousal` 两列 + 迁移 `_migrate_v18`；提取 prompt 规则 8
  （-1/0/1 三档锚点，只对 add 生效）→ `_dispatch` 透传；写入钳制
  `manager._coerce_valence/_coerce_arousal`（缺失/非法/NaN → 0.0 中性）；
- ✅ 浮现抽样按当前情绪效价加权（mood-congruent，Bower 1981 经典心理学）——
  `_relevance_score` 第六因子 `_mood_affinity`（亲和度 1−|Δv|/2，权重 0.15 软
  调制）**加权而非硬过滤**，防情绪固化回路；中性记忆免疫（亲和度恒 0.5，
  等式测试钉死）；效价来源 `get_mood_valence()`（life_state.mood →
  MOOD_VECTORS），narrative.build 查一次传给 recall（graph.py 零改动）；
- ✅ 把「当前情绪」本身存成一条核心记忆（固定 id `mood_current`，status
  'core'），换词走 `update_content` 保留 superseded 历史（继承 5 代上限）
  → 情绪可回放（history / export_json）——`manager.upsert_mood_memory`；
  **只存不浮现**：`_MOOD_ROW_FILTER` 排除全部浮现/计数读面，仅作回放账本
  与调制信号来源；心跳换词（最小停留/早晨锚定放行后）接线落库。

## 健康检查（无业界标准，当前做法合理）

「N 条对话却 0 条记忆 → 告警」是合理自定义启发式，无先例可抄。建议后续补
多指标（提取率 / 记忆增长率 / 归档率）防单指标误报——注意默认动作
（「今天没事发生」）会天然压低提取率，需先建立基线。

---
*证据：代码通读 + 业界文献对照（2026-08-03）。本轮未实施任何代码改动。*