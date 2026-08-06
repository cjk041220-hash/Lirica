# 03 · 主动陪伴体检

> 板块状态：22/22 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 取证前置：export TOKEN=$(grep XIAOXI_API_TOKEN backend/config/.env | cut -d= -f2)；sqlite 只读查询统一用 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'` 模式

- [x] **3.1 心跳调度时序**
      · 查：心跳调度器每 2h 触发（间隔正确、不漂移）
      · 取证：读 `backend/heartbeat.py` 调度循环；LOG_FILE 开启时观察日志心跳打点间隔
      · 判据：间隔配置 2h（7200s）且循环实现无累积漂移
      · 严重度：P1
      · 结果：✅ 代码核验：register_jobs 注册 `check_and_proactive_message` interval hours=2 + misfire_grace_time=600 + next_run_time=now 启动脉冲（heartbeat.py:1017-1028）。隔离副本实跑日志：`Job "check_and_proactive_message (trigger: interval[2:00:00], next run at: ...17:44:43)"` —— APScheduler interval 模式无累积漂移。真实实例佐证：08-06 14:43 主动消息 + 15:47 重启脉冲，链路真实在跑。
- [x] **3.2 心跳更新心情**
      · 查：心跳刷新 life_state.mood 与 daily_event
      · 取证：查库 `SELECT mood, daily_event, mood_changed_at FROM life_state` 与心跳日志
      · 判据：mood_changed_at 有近期更新记录（真实实例运行过心跳）
      · 严重度：P1
      · 结果：✅ 真实库：mood=满足 / daily_event=趴在网页角落偷偷打了个瞌睡 / mood_changed_at=2026-08-05 14:36 / updated_at=2026-08-06 14:55（今天心跳跑过）。隔离副本日志打点：`heartbeat | 15:00 -> 心情：开心 | 日常：刚刚醒来，映海还很安静`。代码：UPSERT id=1（mood/daily_event/mood_changed_at/updated_at 同查同写，heartbeat.py:125-175）。
- [x] **3.3 心情保持纪律**
      · 查：_MIN_MOOD_HOLD_HOURS=6 最小停留，早醒窗口 6-8 点才允许换词
      · 取证：代码核验 heartbeat.py 换词条件（_mood_hold 与 _MORNING_RESET_HOURS）
      · 判据：条件与常量一致（6h 停留 + 早晨锚定）
      · 严重度：P2
      · 结果：✅ _MIN_MOOD_HOLD_HOURS=6 + _MORNING_RESET_HOURS=(6,7,8)；_should_hold_mood 纯函数（词未变/早晨锚定/无历史/时间戳异常均放行）与 _is_morning_reset 一致。真实库佐证：mood_changed_at 自 08-05 14:36 未变——6h 停留 + 数值游走不换词，行为与设计吻合。测试 test_min_hold_blocks_intraday_mood_change / test_morning_reset_allows_mood_change / test_morning_already_reset_skips 全过。
- [x] **3.4 早安注入**
      · 查：早安消息在 6-8 点时段生成并注入对话尾巴
      · 取证：代码核验 daily_morning_greeting 触发窗口；读 proactive 注入链路（Spec A）
      · 判据：窗口与实现一致；消息走对话历史尾巴注入
      · 严重度：P1
      · 结果：✅ cron 8:00 Asia/Hong_Kong（heartbeat.py:1030-1039，misfire 宽容 1h）；历史尾巴（Spec A）经 recent_messages/format_recent_messages 注入（heartbeat.py:725-737，带时间戳「昨晚 23:42 还僵着」破冰语义）；LLM 生成后走统一 _store_and_push_proactive 流水线。测试 test_morning_greeting_malformed_daily_keeps_current_weather / test_morning_prompt_mentions_weekend 过。
- [x] **3.5 主动消息触发条件**
      · 查：非深夜 + 距上次 ≥4h + 50% 概率 + 有内容
      · 取证：代码核验 heartbeat.py 常量 _QUIET_END/_MIN_INTERVAL_HOURS/_PROACTIVE_PROBABILITY 与判定逻辑
      · 判据：四条件与常量一致；无内容时不发空消息
      · 严重度：P1
      · 结果：✅ _QUIET_END=7 / _MIN_INTERVAL_HOURS=4 / _PROACTIVE_PROBABILITY=0.5；_should_send_proactive 顺序：深夜抑制 → 距上次间隔检查（查库 LIMIT 1）→ 概率（heartbeat.py:294-323）。空消息防线：_generate_proactive_message 尾部过滤 <5 字回退「冷冷，小汐在想你~」、>200 截断。测试 test_should_send_respects_min_interval / test_should_send_after_min_interval / test_should_send_quiet_hours 过。
- [x] **3.6 主动消息落库**
      · 查：主动消息写入 proactive_messages 表
      · 取证：查库 `SELECT COUNT(*), MAX(created_at) FROM proactive_messages`；有真实记录则抽查内容
      · 判据：表有近期记录（真实实例在跑）或写入路径代码正确
      · 严重度：P2
      · 结果：✅ 真实库 2 条：08-06 14:43 delivered「考完啦？（顿了顿）今天外面热得很，你先歇一会儿，别急着跑远。」+ 08-05 16:24 read。写入路径：_store_and_push_proactive「先存 pending → 推送 → delivered/stored 落库」+ 启动清扫 sweep_orphan_proactive_messages（server.py:107）防崩溃孤儿 pending。
- [x] **3.7 主动消息尾部注入**
      · 查：主动消息作为对话尾巴注入历史（吵架后递台阶/聊一半跟进）
      · 取证：代码核验 heartbeat 与 chat_archive 的注入接点；日志看最近主动消息生成
      · 判据：注入语义与 Spec A 一致（历史尾巴读取面 recent_messages）
      · 严重度：P1
      · 结果：✅ _generate_proactive_message 注入历史尾巴（heartbeat.py:383-398：聊一半跟进/闹别扭递台阶语义，失败只省略不阻断）。白名单纪律：heartbeat.py 头部注释确认本文件是对话档案唯一获准消费方（只走 recent_messages/format_recent_messages 两个专用读函数）。真实佐证：14:43 主动消息「考完啦？」自然接住了考试话题的历史尾巴。
- [x] **3.8 深夜检测**
      · 查：late_night 检测窗口（23:00-05:00 类）与提示注入
      · 取证：代码核验 care.py LATE_NIGHT 常量与条件；对照 spec B 深夜窗口
      · 判据：窗口正确、提示文案符合人格劝睡纪律
      · 严重度：P1
      · 结果：✅ Spec B（2026-08-05-anomaly-care-design.md:123）深夜窗口 = **00:00/04:59 触发、05:00 不触发**（清单「23:00-05:00 类」为近似描述，以 spec 为准）；care.py _LATE_NIGHT_START=0/_LATE_NIGHT_END=5 与 spec 逐字一致。纯函数实测 02:14 注入：「冷冷现在还没睡。近 7 天里这是第 2 个熬夜的夜晚」。注入链路：chat.py _resolve_user_content → collect_care_lines → _system_info_block 时间行永远在场。测试 test_late_night_window_boundaries 过。
- [x] **3.9 考前熬夜检测**
      · 查：exam 事件 + 深夜组合触发考前提示（_COMBO_EVENT_TYPES=exam）
      · 取证：代码核验 care.py 事件组合逻辑与 events 白名单联动
      · 判据：逻辑与常量一致；已知偏差（只认 exam）在代码注释留痕
      · 严重度：P1
      · 结果：✅ _COMBO_EVENT_TYPES=("exam",)（care.py:34），偏差留痕注释：interview 不在 utils.events._EVENT_TYPES 白名单故第一期只认 exam。24h 窗口（今天/明天交集）+ combo 吞并 late_night（更具体理由优先）。纯函数实测：明天有「高数考试」→「明天有「高数考试」，她还没睡」。测试 test_event_combo_absorbs_late_night / test_event_combo_negative_cases 过。
- [x] **3.10 回归检测**
      · 查：消失后回来（returned）检测——取最近一条用户消息对比
      · 取证：代码核验 care.py RETURNED 触发条件与取数（LIMIT 1 无 7 天窗口的已知偏差）
      · 判据：条件合理；偏差在注释留痕
      · 严重度：P2
      · 结果：✅ last_user_message_time() 无窗口取最近一条（care.py:139-147，注释留痕「消失 30 天也要能算出间隔」——无窗口是有意设计而非偏差）；gap ≥ 5 天（_RETURNED_GAP_DAYS=5）触发。纯函数实测：6 天间隔 →「她已经 6 天没来了，这是回来了」。returned 不记台账（间隔条件天然单次）。测试 test_returned_gap_thresholds / test_returned_not_fired_for_empty_history 过。
- [x] **3.11 冷却台账**
      · 查：care_ledger 注入即记账、同类型冷却内抑制
      · 取证：查库 `SELECT * FROM care_ledger` 看行与 last_at；代码核验冷却窗口常量
      · 判据：有记账（真实触发过）或写入路径正确；防唠叨兜底存在
      · 严重度：P1
      · 结果：✅ 真实库台账为空（从未触发过深夜/考试 combo——符合预期，非缺陷）；写入路径核验：注入即记账（record_hint 在 filter_cooled 放行时写，LLM 没提也算冷却开始）、UPSERT 幂等、失败降级为不滤（宁可多关心不丢感知）。冷却窗口：late_night=72h / event_combo=24h。测试 test_ledger_record_then_load_upserts / test_filter_cooled_blocks_within_cooldown / test_filter_cooled_bad_timestamp_passes / test_load_ledger_failure_degrades_to_no_filter 全过。
- [x] **3.12 时间块注入路径**
      · 查：请求路径注入当前时间块（她总知道现在几点）
      · 取证：代码核验 chat 请求路径时间块注入点（Spec B 地基提交 d5bfea8）
      · 判据：注入点存在且内容含日期时段；与 1.9 交叉验证
      · 严重度：P2
      · 结果：✅ chat.py _time_line：`现在是 2026-08-06 周四 15:47，下午。`（格式与设计文档逐字一致，chat.py:152-162）；_system_info_block 时间行永远在场（关 GPS 关不掉时钟）；时段词走 heartbeat_moods.period_label 与心跳同一口径。与 1.9（01 板块已过）交叉验证一致。
- [x] **3.13 推送订阅**
      · 查：/api/push/subscribe + vapid-key 契约（PWA 订阅）
      · 取证：`curl -s http://127.0.0.1:8000/api/push/vapid-key -H "Authorization: Bearer $TOKEN"` 看返回；读 api 路由
      · 判据：vapid-key 正常返回公钥；subscribe 校验合理
      · 严重度：P1
      · 结果：✅ 实跑 GET /api/push/vapid-key → 200 `{"key":"BPvIuG...","enabled":true}`（公钥端点在认证白名单，前端订阅前无需 token——合理）。subscribe 校验：p256dh/auth 缺失 → 400（不再 200 假成功）、endpoint 白名单 _PUSH_HOST_SUFFIXES 7 个受信推送域名（FCM/Mozilla/Apple/WNS，防持 token 者注册任意 URL 的 SSRF+外泄原语）；save_subscription 原子 UPSERT 去重。真实库 3 条 Edge（WNS）订阅。DELETE 端点支持 query/body 两种取 endpoint。
- [x] **3.14 推送发送**
      · 查：VAPID 签名推送可达（真实订阅端收得到）
      · 取证：浏览器/前端订阅后触发一次推送（心跳事件或手工）观察
      · 判据：推送成功送达（或错误为可诊断原因如无订阅，非代码缺陷）
      · 严重度：P1
      · 结果：✅ 真实强证据：08-06 14:43 主动消息 status=**delivered**（代码语义：send_push 返回 >0 才置 delivered，heartbeat.py:493-499）→ VAPID 签名推送真实送达至少 1 台订阅设备（库中 3 条真实 WNS 订阅）。send_push 实现：webpush_async（aiohttp 异步）+ 显式 10s 超时 + TTL=86400（防 WNS 400）+ asyncio.gather 并发单设备失败不影响其他。
- [x] **3.15 未读读面**
      · 查：/api/push/unread 与 /api/push/read 契约
      · 取证：curl 两个端点；读路由代码确认未读语义
      · 判据：端点行为与前端 useChat/通知逻辑一致
      · 严重度：P2
      · 结果：✅ 实跑 unread → 返回 14:43 那条未读（`{"messages":[{"id":"f5cc1983…","content":"考完啦？…"}],"count":1}`，read_at IS NULL + delivered/stored 双态）。read 端点：空列表 → updated:0；插入临时消息实测 → updated:1 + 状态变 read（测完即删，真实 2 条原样）。前端核对：usePush.js checkUnread/markRead 与后端契约逐字段匹配，useChat.js:520 注入主动消息读面。MarkReadRequest 上限 200 条防 DoS 拼长 IN 列表。
- [x] **3.16 推送失败降级**
      · 查：订阅失效（410/404）时清理订阅不炸
      · 取证：代码核验 web_push.py 错误处理（失效订阅移除）
      · 判据：失效订阅被清理、日志有记录、推送循环继续
      · 严重度：P1
      · 结果：✅ web_push.py _is_stale_subscription：优先异常携带的 response.status（410/404）；字符串兜底收紧为完整特征串（防 endpoint URL 含 "410"/"404" 数字的误杀）。失效订阅收集后统一 DELETE + log.info + 循环继续（gather 内单设备失败不外抛）；超时/网络错误走 error 分支不误删。双层兜底：推送失败降级 stored（_mark_stored 防静默丢消息）+ 启动清扫孤儿 pending。观察点（非缺陷）：失败日志 endpoint[:50] 截断，WNS 下会含订阅 token 参数前几个字符——订阅标识非认证密钥，可接受。
- [x] **3.17 事件提醒联动**
      · 查：active_events 参与心跳消息内容生成
      · 取证：代码核验 heartbeat 中 get_active_events 调用与内容模板
      · 判据：有事件时消息内容提及（不轰炸、最多 N 条）
      · 严重度：P2
      · 结果：✅ _generate_proactive_message：get_active_events(limit=5) + describe_events 注入（围绕事件说或不围绕，heartbeat.py:363-370）+ get_recent_proactive_texts(24h,3) 防复读（同样的关心不许说第二遍）。前夜提醒 daily_evening_reminder（23:00 cron，只提明日日历事件，无日程静默）。真实佐证：14:43「考完啦？」接住考试事件上下文、08-05 16:24「后天要考试了，明早我提醒你复习呀」呼应前夜提醒。测试 test_evening_reminder 4 个全过。
- [x] **3.18 深夜静默**
      · 查：7 点前（_QUIET_END=7）不主动打扰
      · 取证：代码核验主动消息触发的前置时段判断
      · 判据：深夜时段主动消息被抑制
      · 严重度：P1
      · 结果：✅ _should_send_proactive 第一步即 `hour < _QUIET_END → False`（heartbeat.py:303-304），深夜不发主动消息；与 care 的劝睡提示（请求路径）职责分离——深夜不打扰，但冷冷来聊天时关心。测试 test_should_send_quiet_hours 过。
- [x] **3.19 重启恢复**
      · 查：服务重启后心跳调度器恢复且不重复触发
      · 取证：重启实例（用户在场时）→ 日志看调度器恢复；无重复消息
      · 判据：重启后正常调度、无一次性补偿触发
      · 严重度：P1
      · 结果：✅ 真实实例重启（本次体检期间 8000 实例恢复，15:47）→ 日志：调度器启动脉冲仅执行一次心跳（备份+健康检查+`executed successfully`）、next run 正确排到 17:47（2h 后）、无补偿触发。代码：replace_existing=True 幂等注册 + scheduler.running 防重入（server.py:184）+ misfire_grace_time=600（事件循环繁忙 10 分钟内补跑，防静默跳过）。测试 test_registered_jobs_are_awaitable（钉死注册形态，v0.4.4 lambda 吞协程教训的回归防线）+ test_scheduler_actually_runs_proactive_chain 过。
- [x] **3.20 频率上限**
      · 查：主动消息频率有硬上限（4h 间隔兜底 + 冷却）
      · 取证：代码核验 _MIN_INTERVAL_HOURS 检查与 care_ledger 双保险
      · 判据：即使概率触发也受间隔约束，不会高频轰炸
      · 严重度：P1
      · 结果：✅ _should_send_proactive 间隔检查先于概率（4h 硬闸门，DB 查最近一条）——概率触发也越不过间隔；关怀类提示另有 care_ledger 冷却（72h/24h）双保险。真实佐证：08-05 16:24 → 08-06 14:43 实际间隔 22h。测试 test_should_send_respects_min_interval / test_should_send_after_min_interval 过。
- [x] **3.21 日志打点**
      · 查：心跳/主动消息/关怀提示有 INFO 级日志（可诊断）
      · 取证：LOG_FILE 开启跑一段，grep heartbeat/proactive/care 相关行
      · 判据：关键动作有日志、无敏感信息（token/key 不进日志）
      · 严重度：P2
      · 结果：✅ 隔离副本实跑（临时库 + LOG_FILE，patch database.core 路径防写穿生产）→ 打点齐全：`heartbeat | 15:00 -> 心情：开心 | 日常：…`、`主动消息已生成 [56ab6469]: （伸了个懒腰）醒啦…`（前缀截断 50 字符）、备份/记忆健康/调度器执行行。敏感信息扫描：deepseek_api_key/vapid_private/api_key 计数 0。注：无订阅时推送段静默（send_push debug 级）——合理，不刷日志。
- [x] **3.22 劝睡纪律**
      · 查：深夜回复中人格会劝睡（言行一致）
      · 取证：深夜时段真实聊一句 → 品读回复；代码核验 care 提示文案
      · 判据：深夜回复自然带关怀劝睡、不硬教育
      · 严重度：P1
      · 结果：✅ 三层证据：① BASE_PERSONA 含「劝睡可以，轻轻的，一句就够，别每次都提」（test_persona_has_late_night_care_discipline 过，防唠叨模型侧另一半）；② care 提示文案自然关怀非硬教育——纯函数实测 02:14 注入「冷冷现在还没睡。近 7 天里这是第 2 个熬夜的夜晚」；③ 模块纪律（care.py docstring）：提示只是感知与许可，说不说怎么说留给 LLM 按人格发挥。深夜时段真实对话验证留待 10 板块用户旅程回归（白天无法伪造服务器时钟）。
