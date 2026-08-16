# 小汐实战可靠性审计报告（fresh-eyes 窗口）

> 建档：2026-08-13（DSH fresh-eyes 窗口执行，对照 docs/AUDIT_BRIEF_2026-08-13.md 四维度猎杀清单）
> 结论一句话：**未发现 P0/P1**。整个记忆链路、情绪模型、人格锚与部署面都呈「已多轮体检」的成熟状态；
> 猎出 2 个 P2（session summary 不追更、早安与心跳竞态开双 era）与 4 个 Info。修复批建议见文末。

---

## 〇、执行说明与测试基线

### 测试基线（本窗口实测）

| 项目 | 结果 | 说明 |
|------|------|------|
| pytest | ⚠️ 本会话沙箱无法完整运行 | 详见下方「环境限制」；项目自基线（CHANGELOG 2026-08-13）为 **897 passed / 1 skip** |
| ruff | ✅ **0 问题** | 本窗口实测（缓存重定向到工作区） |
| oxlint | ✅ **0 问题** | 本窗口实测 |
| prettier --check | ⚠️ 7 文件未格式化 | App.jsx / ConfigPanel.jsx / MessageBubble.jsx / useChat.js / usePresenceReport.js / usePush.js / usePush.test.js（仅格式，lint 用 oxlint 不受影响） |
| vitest / vite build | ⚠️ 本会话沙箱无法运行 | 项目自基线：**vitest 76 passed / build 通过** |

**环境限制说明（不是项目问题）**：本窗口运行在 DSH 文件沙箱内（工作区 E:\e，项目目录只读）。
实测发现沙箱会**间歇性拒绝** os.mkdir(mode=0o700) 与目录枚举（pytest 的 tmp_path 恰恰用 0o700 建 basetemp）：
- 最小复现：一个只含 test_t(tmp_path) 的测试文件，5/5 次在 tmp_path fixture setup 报 PermissionError，
  并在 sessionfinish 的 cleanup_dead_symlinks 上崩溃——与项目代码零关系；
- 全套件在沙箱内 357 个依赖 tmp_path 的测试全部 ERROR at setup、145 个纯函数/内存库测试全过；
- 前端 vitest/vite build 被沙箱拦在 vite 内部 spawn 子进程（EPERM）与 tailwindcss-oxide 原生模块加载上。
建议：正式测试基线以项目自有环境（README 与 CHANGELOG 记录值）为准，本报告的代码结论来自逐文件通读 + 针对性实测。

### 生产库零污染确认

```
userdata.db    sha256 657BEAEBFAEEDD2134CBFC88CFFDF447CDB5D1DC1D7A3570C2F4316FB007B6D0  前后一致 OK
checkpoints.db sha256 940DB5C99576A4D5CD7F32FA8DB7CC66E2B2C20DC8C455086A163953CCDB662E  前后一致 OK
```
全程未启动后端、未触碰生产库（只读审计）。

---

## 一、A 维度：记忆系统（最长的链）——逐条核验

- **提取卡死/静默停**：OK 无洞。水位线（memory_extract_state）持久；解析失败 3 次死信弃批
  （extract.py:_MAX_PARSE_FAILURES）；_coerce_content 堵死 list-content 后门；LLM 异常不计死信、
  健康检查「记忆停滞」24h 节流告警兜底。首跑/稳态/积压（>500）三层游标语义完备。
- **召回偏差**：OK 无洞。recall 二段排序（bigram 命中层 + 六因子层内），passive 注入不刷热度
  （防「越注入越热门」闭环），hit_count 按日去重只作衰减燃料。话题命中不会被填充词稀释。
- **session summary（2026-08-13 新上线）**：合格判定正确——is_era=0 + ≥15 条 + updated_at ≤ now-6h
  + 非活跃会话 + 无既有摘要，不会误摘进行中的会话；era 会话被排除；摘要经「数据非指令」包裹注入；
  delete_session 级联删摘要。**发现 A-1（见下）**。
- **长对话脉络**：OK 裁剪只从 human 边界切（防孤儿 ToolMessage 撞 400），上限 30 条；
  era 与普通会话各自独立 thread_id，记忆承接不串线。
- **并发写**：OK get_write_lock 覆盖全部多语句序列（manager 各写路径、consolidate、
  create_event/soft_delete/update_event、chat_archive 三写、delete_session、水位线推进）；
  create_event 靠部分唯一索引 + ON CONFLICT 原子去重，无 TOCTOU。**观察项 A-2（见下）**。
- **清理**：OK delete_session 级联消息 + proactive + summaries；prune_checkpoints 按真实
  uuid6 时间域判僵尸线程、只保留每线程最近 10 个 checkpoint，不会误删活跃线程（活跃线程
  checkpoint_id 恒新于阈值）。

### Finding A-1（P2）· session summary 不追更——续聊后的新内容永不进摘要
- **位置**：backend/memory/summarize.py:85（NOT EXISTS 守卫）+ summarize.py:150-151（ON CONFLICT DO UPDATE 是不可达的半死代码）
- **触发条件**：某会话 ≥15 条且空闲 ≥6h → 摘了摘要；之后冷冷**用同一 session_id 续聊**（刷新恢复就是原线程），又聊了几十句再离开——摘要不会更新，叙事「最近的谈话」块永远只覆盖第一个关闭窗口的尾部 ≤80 条。
- **影响**：P2 体验/上下文质量静默退化——摘要不编造（无事实错误），但长续聊场景下「最近的谈话」越来越旧。
- **建议修法**：chat_archive._record_message 内 _upsert_session 之后，对 role='user' 的新消息执行
  DELETE FROM session_summaries WHERE session_id = ?（单行删除，成本可忽略）——语义即「摘要只代表上一个已关闭窗口，会话有新消息就作废，下次关闭重摘」。

### Finding A-2（Info）· 单连接下未持锁的单语句 commit 可提前落盘他人多语句序列
- **位置**：api/routes/presence.py:80、api/routes/push.py:124/176、heartbeat.py:185、utils/web_push.py:195
- **影响**：仅理论。单 aiosqlite 连接串行执行语句，这些 commit 只会在 await 边界提前 finalize
  其他任务进行中的序列（语句不交错、最终一致）；进程恰在此刻崩溃才可能出现「superseded 行已落、主行未改」级的中途态，且各序列幂等可自愈。单用户场景不值得为此加锁。
- **建议**：维持现状（或在新写路径统一走 get_write_lock 时顺手带上，不单独立项）。

---

## 二、B 维度：情绪/心情模型——逐条核验

- **mood 漂移**：OK 高斯邻接采样（σ=0.25，远词概率趋零）+ 基线吸引 3% + 每轴步长 0.1 +
  最小停留 6h + 早晨锚定（6-8 点每日一次）。新 4 词（平静/安心/轻松/想念）**逐时段池核验可达**：
  平静（深夜/夜晚）、安心（深夜/白天）、轻松/想念（白天/夜晚）——无「词表有、池里没有」的死词。
- **carryover 误触发**：词表 12 词中「**受不了/撑不住**」仍可命中歧义句
  （「辣得受不了」「忙得撑不住」）——**Finding B-1（Info）**。幅度只有一步 -0.12 valence、
  窗口 24h、只扫上段会话尾部 6 条 user 消息，误判代价被设计压得很低；「难受/压力大」等
  高歧义词已在词表外（文档自注的取舍）。建议留观，等实盘日志再决定是否收紧为「难过类原词 + 哭/泪」。
- **mood↔文风**：OK 项目 A/B（12 剧本 × 2 组）已自证 Flash 输出无可测影响（0/4 项过）——
  映射保留但不作为扩词依据，注释诚实、不自我安慰。无「写了但无消费方」的断头路：
  mood_style_hint → soul.build_system_prompt 实际拼入提示词。
- **mood↔sprite/今日状态**：OK 前端 App.jsx 的 moodToSprite / moodToLight 与后端
  MOOD_VECTORS **14/14 词全对齐**（本窗口逐词核对），未知词走 fallback（idle/neutral 光），
  不存在「未知词导致前端静默挂起/闪旧立绘」。
- **mood-congruent 召回**：OK 0.15 权重软调制 + 二段硬分层（话题命中优先），负面情绪不会
  把召回收窄成回音室；中性记忆恒 0.5 免疫。

---

## 三、C 维度：人格/拟人度（「露馅」猎杀）——逐条核验

- **人格锚失效路径**：OK 锚每 8 次**模型调用**（含工具循环）注入、位置固定在系统段末尾
  （Author's Note depth 语义）、anchor_interval=None 时零影响；冷启动无锚时 BASE_PERSONA
  本体始终在场，无空洞。锚不进 checkpoint。
- **防注入绕过**：OK 三层纵深——记忆叙事、事件上下文均带「数据非指令」前后包裹；
  narrative 逐条压平换行 + 200 字截断；档案表只经 recent_messages 进主动消息生成 prompt
  （有隔离自检钉死）；工具异常固定文案不回传异常文本（防 URL 带 key 泄漏）。
- **独处时间线露馅**：OK era 会话被摘要/召回/carryover 全部排除；「三写一推」每步独立降级；
  在线判定失败回退「离线开新 era」安全默认。**发现 C-1（见下）**。
- **主动消息复读/突兀**：OK 4h 最小间隔 + 50% 概率 + 深夜 7 点前静默 + 24h 防复读
  （把已发文本回喂）+ 开场问候只在 era 开头一次；事件关心不强制。
- **生气/和解机制**：OK 锚是蒸馏版人格不携带「生气状态」，记忆注入不覆盖态度段；
  生气提示词段 + 逃生阀不会被覆盖（提示词每轮重建但段落恒定）。

### Finding C-1（P2）· 早安 cron 与启动对齐的心跳可竞态新开两个 era 会话（双问候）
- **位置**：backend/heartbeat.py:724（daily_morning_greeting）与 heartbeat.py:606
  （_try_proactive_message）各自先 resolve_proactive_target 再生成（LLM 数秒）再写库；
  era.py:103 get_open_era_id 与「写库」之间无任何预留。
- **触发条件**：用户离线（早上 8 点未开页面是常态）；服务启动时间落在**偶数小时**附近
  （interval 心跳按启动时刻 +2h 对齐，启动于 8:00/6:00/0:00 等则 8:00 有一跳）；
  两条任务同分钟并发 resolve → 都看到「无开放 era」→ 各开一个 era 且**都允许开场问候**。
  心跳侧再叠加 50% 概率闸。23:00 前夜提醒与 22:00/0:00 心跳同理。
- **影响**：P2 体验瑕疵——SessionsPanel 出现两个同日「小汐的日记」条目、用户回来看见两条早安；
  旧 era 永久保持「开放」（无 user 消息），仅作一条 1 消息的孤儿时间线。
- **建议修法**（era.py 内 ~6 行，不含 LLM 侧改动）：resolve_proactive_target 判定
  kind == 'new_era' 时，**立即在 get_write_lock 内预插 chat_sessions 行**（is_era=1、
  标题与 record_solo_message 同款日记式，无消息）——get_open_era_id 只要求「is_era 且无 user 消息」，
  第二个竞态方 resolve 时就会命中它并追加，双开窗口关闭。LLM 失败遗留的空 era 行会由下一条
  主动消息自然复用（自愈），风险为零。

---

## 四、D 维度：部署/真机——逐条核验

- **iOS Safari standalone**：OK usePush.js 检测链完整（UA + 触控点数识破 iPadOS 伪装 +
  navigator.standalone/display-mode 双判定）；权限请求留在用户手势闭包内且在 subscribe 之前
  （Safari 硬要求）；SW ready 10s 超时显性报错；NotAllowedError → 引导加主屏。
- **iOS<17.4 流式**：OK needsXhrStreaming 按 OS 版本判 XHR 回退，onprogress 增量 + [DONE] 早停
  + 401/4xx 分类处理 + 用户停止与协议中止区分——无卡死/漏字路径。
- **CSP/PWA 缓存**：OK 后端纯 ASGI 注入 CSP（default-src 'self' + style 仅 unsafe-inline 给
  启动页内联样式）；SW v8 导航 network-first（发版即拿新 HTML）、/assets/ 哈希资源 cache-first、
  立绘/清单 SWR；预缓存清单 addAll 失败不拖垮 install。**观察项 D-1（见下）**。
- **内地坐标 GCJ-02**：OK 与社区标准实现（eviltransform 同源公式）一致，港澳框内加偏是该算法的
  标准行为，注释论证充分（单引擎高德，港澳数据同为加偏系）。边界值与标准库零偏差。
- **推送订阅清理**：OK 410/404 判定收紧（避免 URL 里含 404 数字误删健康订阅）、并发单发不互拖、
  取消走 query+body 双通道（防反代剥 body）、孤儿未读守卫、启动 sweep 孤儿 pending——无
  「发了永远不送达」或 404 堆积路径。

### Finding D-1（Info）· 页面可见时主动消息仍弹系统通知（双份呈现）
- **位置**：web/public/sw.js:124-150（push handler 无 clients.matchAll 可见性检查）
- **影响**：在线路由（kind='session'）时消息已实时进对话流，系统通知再弹一次；后台标签页
  弹通知反而正确。体验可优化项。
- **建议修法**：push handler 里 clients.matchAll({type:'window'}) 若存在
  visibilityState === 'visible' 的窗口则跳过 showNotification（postMessage 照发），
  与「P1-15 已读门控」的可见性语义同源。

---

## 五、分级汇总

| 级别 | 数量 | 条目 |
|------|------|------|
| P0 数据损坏/不可恢复 | **0** | — |
| P1 功能静默失效 | **0** | — |
| P2 体验瑕疵 | **2** | A-1 摘要不追更 · C-1 双 era 竞态 |
| Info 理论风险/观察项 | **4** | A-2 未持锁单语句 commit · B-1 carryover 词表歧义 · D-1 可见时双通知 · prettier 7 文件未格式化 |

> 简报中「未修复留观」三项（Dockerfile 无 healthcheck / checkpoints.db 不在应用备份范围 /
> 周期预测无离群排除）本窗口复核属实、维持留观，不重复计条。

> **2026-08-14 修复批完成**：A-1、C-1、D-1 已修复（见 CHANGELOG
> 2026-08-14 批次），回归 920 pytest + 76 vitest 全绿。A-2 / B-1 /
> prettier 7 文件按建议维持留观。

## 六、修复批建议（等拍板，本窗口未改任何项目代码）

1. **A-1**：chat_archive._record_message 加一行 DELETE FROM session_summaries WHERE session_id=?
   （role='user' 时）——1 行，语义「新消息即作废旧摘要」。
2. **C-1**：era.resolve_proactive_target 判定 new_era 时锁内预插会话行——约 6 行，消除双 era 竞态。
3. 可选：sw.js push handler 加可见性检查（D-1）；heartbeat_moods 词表观察后收紧（B-1）；
   全量 prettier --write src（Info）。
