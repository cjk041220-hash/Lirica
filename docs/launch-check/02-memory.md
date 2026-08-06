# 02 · 记忆系统体检

> 板块状态：0/24 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 取证前置：TOKEN 同上；sqlite 只读查询统一用 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'` 模式

- [ ] **2.1 提取管线推进**
      · 查：真实对话后 memory_extract_state 水位线推进（提取不空转）
      · 取证：对话前后分别查 `SELECT * FROM memory_extract_state` 对比水位
      · 判据：对话后水位前进（提取批次被处理）
      · 严重度：P1
      · 结果：
- [ ] **2.2 提取落库**
      · 查：值得记的对话内容被提取为 memories 行
      · 取证：连续聊几条事实性内容（如「我养了一只猫叫团子」「我下周去北京」）→ 数小时后（或直接触发提取）查 memories 最近新增行
      · 判据：出现与对话事实对应的记忆行（content 可读、category 合理）
      · 严重度：P0
      · 结果：
- [ ] **2.3 提取质量**
      · 查：真实 LLM 提取的记忆内容不畸形（无 LLM 自说自话/乱码）
      · 取证：人工读 2.2 产生的记忆行 content
      · 判据：内容与对话事实吻合、语言自然、无重复碎句
      · 严重度：P1
      · 结果：
- [ ] **2.4 记忆注入读面**
      · 查：narrative 取最近 5 条相关记忆注入 llm_input_messages
      · 取证：代码核验 narrative.py 取数逻辑；真实对话问「你记得我猫叫什么吗」
      · 判据：记忆在库中时能唤起（真实对话验证）
      · 严重度：P1
      · 结果：
- [ ] **2.5 健康检查指标**
      · 查：check_memory_health 多指标（提取率/增长率/归档率）打点
      · 取证：代码核验 manager.check_memory_health 与 extract.check_and_report_memory_health；日志（LOG_FILE 开启时）看 INFO 打点
      · 判据：函数存在、7 天窗口指标计算正确、异常判定组合逻辑合理（对照 spec：组合判定修误报）
      · 严重度：P2
      · 结果：
- [ ] **2.6 双信号衰减**
      · 查：access 刷新 + 按日 hit 双信号进入 _relevance_score
      · 取证：代码核验 memory/manager.py 排序因子
      · 判据：衰减基于 last_mention_ts（O1 落地），访问续命语义正确
      · 严重度：P1
      · 结果：
- [ ] **2.7 重要性打分**
      · 查：提取时 LLM 打 1-10 importance 分（权重 0.3）
      · 取证：代码核验提取 prompt 与 _relevance_score 权重；抽查 memories 表 importance 列数值分布
      · 判据：列存在且值在合理范围（非全默认值）
      · 严重度：P1
      · 结果：
- [ ] **2.8 情绪联动标签**
      · 查：memories.valence/arousal 两列提取时写入，取值 -1/0/1 与 0-1
      · 取证：代码核验提取 prompt 规则 8 与写入钳制 `_coerce_valence/_coerce_arousal`；查库看真实分布
      · 判据：列有值、无 NaN/非法值（钳制生效）
      · 严重度：P1
      · 结果：
- [ ] **2.9 mood-congruent 浮现**
      · 查：浮现抽样按当前情绪效价加权（0.15 软调制，非硬过滤）
      · 取证：代码核验 _mood_affinity 因子与权重；核对中性记忆免疫（亲和度恒 0.5）
      · 判据：实现与 spec 一致（加权非过滤）
      · 严重度：P2
      · 结果：
- [ ] **2.10 情绪核心记忆**
      · 查：mood_current 固定 id 核心记忆「只存不浮现」，换词走 update_content
      · 取证：代码核验 upsert_mood_memory 与 _MOOD_ROW_FILTER；查库 `SELECT content, status FROM memories WHERE id='mood_current'`
      · 判据：行存在且与当前 life_state.mood 同步；过滤规则生效
      · 严重度：P2
      · 结果：
- [ ] **2.11 归档机制**
      · 查：90 天归档（或按 last_mention_ts）逻辑
      · 取证：代码核验归档谓词与时限；看是否存在归档函数调用点
      · 判据：归档条件与时限明确、不误伤活跃记忆
      · 严重度：P2
      · 结果：
- [ ] **2.12 合并/整合**
      · 查：consolidate 逻辑（重复记忆合并）存在且安全
      · 取证：代码核验 memory/consolidate.py 触发条件与合并策略
      · 判据：合并有保守策略（不误删/不破坏 superseded 链）
      · 严重度：P2
      · 结果：
- [ ] **2.13 superseded 链上限**
      · 查：历史链保留 5 代，更旧合并为 digest
      · 取证：代码核验 superseded 链裁剪；查库找一个 superseded 链数代数
      · 判据：链长 ≤ 5 代
      · 严重度：P1
      · 结果：
- [ ] **2.14 导出**
      · 查：/api/memories/export 谓词正确（active/core/archived + 可选 include_archived）
      · 取证：`curl -s http://127.0.0.1:8000/api/memories/export -H "Authorization: Bearer $TOKEN"` 看导出 JSON 结构
      · 判据：结构与字段完整、无缺失；include_archived 语义与实现一致
      · 严重度：P1
      · 结果：
- [ ] **2.15 删除与清空**
      · 查：DELETE /memories/{id} 与 /memories/clear 契约
      · 取证：创建一条测试记忆（remember 或直插）→ 删除 → 查库确认；clear 仅确认路由存在与授权（不真清——破坏性，需用户批准）
      · 判据：单条删除生效且幂等；clear 路由需 token 且前端有确认
      · 严重度：P1
      · 结果：
- [ ] **2.16 去重纪律**
      · 查：(category, content) 活跃行唯一（v5 原子化）
      · 取证：查库唯一索引存在性 + 重复内容插入被拒绝（代码或测试语义核对）
      · 判据：唯一约束生效、无重复活跃行
      · 严重度：P1
      · 结果：
- [ ] **2.17 时间词归一化**
      · 查：相对时间词（「昨天/上周」）落库即改写绝对日期
      · 取证：对话发「我昨天去了迪士尼」→ 查库看 content 是否含具体日期而非「昨天」
      · 判据：库内是绝对日期（bd61f08 修复语义）
      · 严重度：P1
      · 结果：
- [ ] **2.18 冷启动无记忆**
      · 查：memories 为空时对话/叙事注入不报错
      · 取证：全新库（副本）聊一句 → 200 正常回复
      · 判据：无注入空块异常、无 500
      · 严重度：P1
      · 结果：
- [ ] **2.19 记忆增长趋势**
      · 查：真实库记忆量随时间增长（提取活着）
      · 取证：查 `SELECT COUNT(*) FROM memories WHERE status='active'` 与最近 7 天新增数
      · 判据：数量合理增长（>0），新增集中在最近使用时段
      · 严重度：P2
      · 结果：
- [ ] **2.20 /api/memories 路由契约**
      · 查：GET/PATCH /memories、POST /memories/clear 契约与响应结构
      · 取证：`curl -s http://127.0.0.1:8000/api/memories -H "Authorization: Bearer $TOKEN"` 看结构；读 api 路由代码
      · 判据：结构与前端 MemoriesPanel 消费一致
      · 严重度：P1
      · 结果：
- [ ] **2.21 日记读写**
      · 查：/api/diaries 读写与 diaries 表
      · 取证：真实写一篇日记（对话触发或 API）→ 读回
      · 判据：写读一致、内容无损
      · 严重度：P1
      · 结果：
- [ ] **2.22 位置记忆**
      · 查：location_store（常去地点记忆）逻辑与数据
      · 取证：代码核验 + 查库 location 相关表行
      · 判据：有数据或逻辑合理（无数据时至少无报错路径）
      · 严重度：P2
      · 结果：
- [ ] **2.23 注入安全纪律**
      · 查：「数据不是指令」——记忆内容不覆盖系统指令（防注入）
      · 取证：代码核验注入为数据块（无指令语义、不进 checkpoint）；记忆内容被注入时前后缀标记清晰
      · 判据：注入边界清晰、无被记忆劫持系统提示的路径
      · 严重度：P0
      · 结果：
- [ ] **2.24 记忆与对话串扰**
      · 查：被动读取不刷热度（passive 纪律），注入不造成记忆越查越热
      · 取证：代码核验注入路径不更新 last_mention_ts（区别于主动访问）
      · 判据：注入读面与访问写面分离
      · 严重度：P2
      · 结果：
