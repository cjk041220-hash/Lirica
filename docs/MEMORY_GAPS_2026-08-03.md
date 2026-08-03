# 记忆库诊断记录（2026-08-03）— 本轮不动代码，作为后续工作项

> 来源：2026-08-03 系统性优化中的记忆库通读 + 业界调研（Generative Agents /
> mem0 / Zep-Graphiti / MemGPT-Letta / LangMem / A-MEM / MemoryBank）。
> 结论：**8-01 评审的 P0/P1 已全部落地，记忆库无阻塞缺陷**。以下为
> 「架构权衡 / 与业界差距」级别的观察项，按其影响排序。均未实施。

## 核心判断

小汐记忆库的 freshness 行为与 Generative Agents（0.995^小时数衰减 + 访问
重置时钟）和 MemoryBank（Ebbinghaus 曲线 + 访问续命）**同构，属合格水平**。
与业界主流的两大差距：

1. **时间戳语义纪律**（created_at 兼任 last_access——双时钟混用）；
2. **缺 importance 因子**（有 recency + frequency，无 LLM 打分的重要性）。

## 观察项

| # | 观察 | 证据 | 业界对照 | 建议 |
|---|---|---|---|---|
| O1 | `add` 去重刷新 `created_at`，`update_content` 保留——**同一衰减体系两套矛盾时钟**：被反复 add 的记忆续命、被反复 update 但未重复 add 的记忆老化 | `manager.py:214-225`（add 的 ON CONFLICT 刷新 created_at）vs `manager.py:238-286`（update 保留 created_at） | mem0 三时间戳范式：`created_at` 不可变 + `last_accessed_at` 刷新；Generative Agents / MemoryBank 也以「访问/提及」重置时钟而非创建时间 | 拆 `create_ts`（不可变）/ `last_mention_ts`（add/update/hit 统一刷新），衰减与 90 天归档统一基于 `last_mention_ts`（schema v17） |
| I1 | 排序缺 **importance 因子**（LLM 打分）——「第一次约会」与「今天吃了饭」同权衰减 | `manager.py:97-132`（_relevance_score 四因子：recency/access/usage/topic） | Generative Agents：`0.5·recency + 0.3·importance + 0.2·relevance`；mem0 三分等权 | 提取时 LLM 顺带打 1-10 分，存 importance 列，进 _relevance_score（权重 0.3 档） |
| O2 | `_choose_mood` 的 `talked` 信号读 `recent(10)` **记忆**而非**对话**——「近 24h 有记忆活动」≈「近 24h 有值得记住的对话」，纯闲聊不触发 | `heartbeat_moods.py:171-175`（2026-08-03 重构后仍在） | 无直接对应 | 2026-08-03 心情重构后顺带评估：改从 chat_messages 按 created_at 判断真实对话时间 |
| O3 | superseded 历史链**无上限、不入 JSON 导出**：`export_json(include_archived=True)` 谓词只含 active/core/archived，历史链仅存于整库快照备份 | `manager.py:377-378`（export 谓词） | Graphiti 时间图无限保留 + valid_at 时间旅行；mem0 覆盖不留链；LangMem/A-MEM 压缩折叠 | 每记忆保留 N 代（如 5），更旧合并为一行 digest；`export_json` 加可选开关 |
| O4 | 提取批次「有界不回看」：单批超 50 条/4000 字从旧端丢弃，水位线推进到批尾，丢弃消息永久跳过 | `extract.py:163-184` | 设计有意（提取价值随对话变旧衰减） | 记录，不修 |

## 情绪联动（前沿空白，可做差异化）

- 记忆带 valence/arousal 标签（提取时 LLM 顺带打分，A-MEM note tags 先例）；
- 浮现抽样按当前情绪效价加权（mood-congruent，Bower 1981 经典心理学）——
  **加权而非硬过滤**，防情绪固化回路（只想起同色记忆 → 情绪更固化）；
- 把「当前情绪」本身存成一条核心记忆，UPDATE 保留 superseded 历史 → 情绪
  可回放、可作检索调制信号。

## 健康检查（无业界标准，当前做法合理）

「N 条对话却 0 条记忆 → 告警」是合理自定义启发式，无先例可抄。建议后续补
多指标（提取率 / 记忆增长率 / 归档率）防单指标误报——注意默认动作
（「今天没事发生」）会天然压低提取率，需先建立基线。

---
*证据：代码通读 + 业界文献对照（2026-08-03）。本轮未实施任何代码改动。*