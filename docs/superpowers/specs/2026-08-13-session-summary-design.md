# 设计：session summary 层（P1-a）

> 建档日期：2026-08-13
> 依据：桌面《小汐sonetto人格化还原度与架构评估报告》P1-a + 全量代码核验 + 会话/记忆链路调研
> 状态：设计定稿（用户授权代拍板，2026-08-13）
> 定位：补 ZifaMem L2「session summaries」——长对话后只有硬裁剪 + recent 记忆，缺"这段聊了什么"的语义压缩

## 一、背景与目标

**现状缺口**：长对话在 `graph.py:62-83` 按 max 30 条硬裁剪（找 HumanMessage 边界切）。被裁掉的部分里，**事实**已由 `extract.py` 抽进 memories（含 valence/arousal），但**谈话的脉络与情绪弧**（"这次从考试焦虑聊到了家庭压力"）没有被任何层捕获。跨会话连续性因此缺一块：小汐记得"冷冷考试焦虑"这个事实，但记不得"那场对话的走向和她的状态"。

**目标**：对"已结束且够长"的普通会话，用 LLM 生成一句话脉络摘要，存入独立新表，叙事注入时在"画像之后、常规记忆之前"呈现，让小汐在每轮对话前想起最近几场深聊的走向。

**成功判据**：长会话结束后，后续对话中小汐能自然承接其脉络（如"上次你说压力大"），而非只浮现孤立记忆。

## 二、决策记录（本次调研改变的点）

1. **触发位置**：挂 `_periodic_maintenance` 新独立段（2h 心跳），与 `consolidate_memories`/`distill_cold_profile` 同款 try/except 隔离。**不做**会话结束即时触发（侵入 chat 路由、时序敏感、难测）。
2. **era 不纳入**：独处时间线是日记式（标题已带日期、内容自带时间戳），非连续性缺口。
3. **成本口径**：会话 ≥15 条消息 + 空闲 ≥6h（`updated_at ≤ now−6h`）+ 无既有摘要 + 非当前活跃会话（`life_state.active_session_id`，离线即清空已验证）+ 每轮 ≤3 个。单用户稳态每天 1-3 次 LLM 调用。
4. **隔离纪律零改动（关键调研结论）**：初稿曾设想"为摘要生成开第二个 `chat_archive` 档案例外"，查证后**不需要**——`extract.py:196` 早已直接 SQL 读 `chat_messages` 喂 LLM 做后台提取，是 `memory/` 处理模块的既有先例。隔离自检（`test_archive_isolation_from_llm_context_paths`）只禁 `narrative.py` + `agent/*` 引用 `chat_archive`，本模块照 `extract.py` 先例直接读 `chat_messages`，**不碰 `chat_archive`、不改任何自检测试**。

## 三、数据模型（迁移 v23）

新表 `session_summaries`，`SCHEMA_VERSION 22→23`：

| 列 | 类型 | 说明 |
|---|---|---|
| `session_id` | TEXT PRIMARY KEY | 对应 `chat_sessions.id` |
| `summary` | TEXT NOT NULL | LLM 产出的一句话脉络摘要 |
| `msg_count` | INTEGER NOT NULL | 摘时的消息数快照（排障） |
| `ended_at` | TEXT NOT NULL | 会话最后消息时间快照（`chat_sessions.updated_at`），叙事按谈话新鲜度排序，非摘要生成时间 |
| `created_at` | TEXT NOT NULL | 摘要生成时间（HKT） |

要点：
- 独立表 → 天然不落 `memories` 任何读面（记忆面板/recall/健康检查），**零 `_READ_EXCLUSIONS` 改动**。
- 本库无 FK 纪律：`chat_archive.delete_session` 补一条 `DELETE FROM session_summaries WHERE session_id=?`（与 `proactive_messages` 同款手动级联）。
- 备份：`backup_database` 整库备份自动含本表；敏感度与 `chat_messages` 同级（对话内容本就已入库备份），scrub 无需扩展。

## 四、生成模块 `memory/summarize.py`

### 入口
```python
async def summarize_closed_sessions(conn=None) -> dict
```
挂 `_periodic_maintenance` 新段（consolidate 之后），返回值供日志/测试：`{"summarized": 实际生成数, "skipped": 跳过数}`。

### 合格判定（一条 SQL）
```sql
SELECT s.id, s.updated_at, COUNT(m.id) AS n
FROM chat_sessions s
JOIN chat_messages m ON m.session_id = s.id
WHERE s.is_era = 0
  AND (:active_id IS NULL OR s.id != :active_id)  -- 非当前活跃会话（active 离线即清空；
                                                  --   NULL 判参数自身，离线时不排除）
  AND NOT EXISTS (SELECT 1 FROM session_summaries ss
                  WHERE ss.session_id = s.id)   -- 无既有摘要（幂等）
GROUP BY s.id
HAVING COUNT(m.id) >= 15                        -- 够长
   AND MAX(m.created_at) <= :cutoff             -- 空闲 ≥6h（等价 s.updated_at）
ORDER BY s.updated_at ASC                       -- 最旧优先，排旧账
LIMIT 3                                         -- 每轮上限
```

### 流程
1. 合格会话 → 直接 SQL 读 `chat_messages` 尾部 ≤80 条（时间升序；`extract.py` 先例，`_SUMMARY_MESSAGE_CAP=80` 控 token）。
2. `memory.extract._create_llm()`（与 consolidate 同通道，默认 flash）低 temperature 调用。
3. `_SUMMARIZE_SYSTEM` 提示词（见下）→ 输出一句话纯文本。
4. `get_write_lock` + `INSERT OR REPLACE`（幂等）。
5. 失败语义：任何异常 `log.warning` + 跳过不写（下轮重试）；空/超长输出跳过。

### Prompt 纪律（确定性可算的 LLM 侧镜像）
- 只总结对话里**实际出现**的主题与情绪，**禁止编造**未提话题/事实；
- 一句话、≤80 字、过去时；
- **不写日期/时间**（时间锚由代码侧从 `ended_at` 渲染——对齐 time_words 纪律：绝对时间由代码保证，LLM 不写相对时间）；
- 侧重冷冷的状态与情绪弧，而非流水账罗列。

## 五、叙事注入 `narrative.py`

新增 `_summaries_block(limit=3)`：`SELECT session_id, summary, ended_at FROM session_summaries ORDER BY ended_at DESC LIMIT 3`，渲染在 **profile 段之后、"以下是小汐记住的..."之前**（画像 > 摘要 > 常规记忆，对齐报告）：

```
## 最近的谈话
- 8月11日晚：从考试焦虑聊到家庭压力，她哭了一场，你一直陪着。
```

要点：
- 每行 ≤160 字截断 + 压平换行（防伪造结构，同 profile 纪律）；无摘要返回 `""`（原语义零影响）。
- **独立块，不占 `max_items` 记忆席位**（约 +200 tokens/次调用，对 11.7k 系统提示词 ~1.7%）。
- 每次模型调用多 1 条 PK 索引查询，可忽略。
- 时间锚：`MM月D日 + 时段词`（晚/夜/午/早），由代码从 `ended_at` 推出。

## 六、数据流

```
chat.py 写消息 → chat_archive 刷新 chat_sessions.updated_at
   ↓ 心跳 2h
_periodic_maintenance → 新段 summarize_closed_sessions()
   ↓ 合格会话（≥15 条 / 空闲 6h / 非活跃 / 无摘要 / ≤3）
直接 SQL 读 chat_messages 尾部 ≤80 条 → _create_llm 一句话摘要
   ↓ get_write_lock
INSERT session_summaries
   ↓ 每次模型调用
pre_model_hook → narrative.build() 渲染摘要块（画像后、常规记忆前）
```

## 七、错误处理与降级

- 生成失败：`log.warning` + 跳过不写，下轮重试，不阻塞心跳其他段。
- 注入失败：摘要块置空（narrative 已有 try/except 包裹），对话照常。
- 幻觉防护：Prompt 禁编造 + 叙事按"数据非指令"包裹注入（纵深第一层现成）。
- 超长会话：只喂尾部 80 条，摘要反映近期走向即可，不烧 token。

## 八、测试（约 9-11 个）

1. 迁移 v23：建表 + 幂等 + SCHEMA_VERSION 更新（`test_migrations_v23.py`）
2. 生成合格判定：合格被摘 / <15 跳过 / era 跳过 / 当前活跃跳过 / 未空闲跳过 / 已摘过跳过 / 每轮 ≤3 上限 / LLM 异常跳过不标记
3. 注入：摘要块位置（画像后、常规记忆前）/ ≤3 / 超长截断 / 无摘要零输出
4. 级联：`delete_session` 补删 session_summaries 行
5. 接线：`_periodic_maintenance` 含新段（mock consolidate 或直接调）

## 九、文档同步与观察基线扰动

- `OPTIMIZATION_ROADMAP_2026-08-12.md` 勾选 P1-a（含量级与决策摘要）。
- `CHANGELOG.md` 加过渡说明条目（人格/记忆链路改动留痕，Replika 纪律）。
- **已知扰动**：摘要注入改变每轮 LLM 上下文 → 轻微扰动吃瓜/初识档观察基线（0a-0c）。新库早期合格会话少，影响先小后积；观察收口评估时注意此变量。

## 十、明确不做（防回潮）

- 不绕 `chat_archive` 静默开洞（本模块直接 SQL 读 `chat_messages` 是 extract.py 先例，**不是**新增注入路径）。
- 不做会话结束即时触发（YAGNI，心跳 2h 延迟可接受）。
- 不做 era 会话摘要（日记式，非缺口）。
- 不做摘要的 LLM 定期刷新（首版生成即固化，会话续聊后如需重摘属远期）。
- 不做摘要的 UI 展示（纯叙事静默层；记忆面板不显示）。
