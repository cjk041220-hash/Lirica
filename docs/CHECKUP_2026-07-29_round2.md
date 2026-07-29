# 小汐 sonetto 第二轮复查报告（体检后复检）

**复查日期**：2026-07-29 **基线**：HEAD `c11323e`（工作树干净）
**方式**：5 路独立审查（后端架构 / 安全 / 测试 / 前端 / 部署与文档），互不信任上一轮结论，边找新问题边复核 §8 治疗回执
**关联**：本报告是 `CHECKUP_2026-07-29.md`（首轮体检 + §8 治疗）的复检轮

---

## §0 一句话总评

**首轮 §8 的 9 项治疗经独立取证全部为真，无一项纸面糊弄；代码侧评分从 3.6 抬到 ~4.0（A−），发布阻断项全部收敛到代码之外的两个运维动作：PII 处置（已升级）与密钥轮换。**

## §1 两处对首轮报告的更正（本轮实证推翻）

1. **「8 提交未 push」已过期**：`git ls-remote origin master` = `c11323e` = 本地 HEAD，全部加固提交**已在 Gitee**。CHECKUP §8:338 的陈述需更正。
2. **PRIV-01 升级**：真实生理期数据不止在 git 历史，**当前 HEAD 源码就有**——`backend/database/migrations.py:185-190`（4 条真实日期 + 健康备注）与 `backend/tests/test_cycle_tracker.py`（同组日期 15+ 处）。既然历史已推送，filter-repo 之外**必须同时占位化源码**。
3. （正面更正）`soul.py:152-156` 的硬编码档案属产品形态决策项；**凭据泄漏面已证伪**：`git log -S "-----BEGIN"` 零命中、`git log -S "sk-"` 5 命中全为 package-lock 哈希与 `.env.example` 占位符、`git ls-files` 无 `.env`/`*.db` 被追踪。

## §2 更新评分卡（首轮 → 本轮）

| # | 维度 | 首轮 | 本轮 | 变化主因 |
|---|------|----|----|--------|
| 1 | 架构健康度 | 4 | 4 | god module 951→765；残留层反转 heartbeat→routes.config（🟠） |
| 2 | 代码质量 | 4 | 4 | DRY 三处零残留；新发现 4 处死代码（🟡 级清理） |
| 3 | 安全 | 5 | **5（独立复核背书）** | fail-closed/恒定时比对/SSRF 白名单/CSP 逐行复验为真；新发现全在 🟡 以下 |
| 4 | 性能 | 3 | 3.5 | 主包 gzip 122→76KB、SW v4；扣分：立绘/天空子树零 memo，流式期逐 token 空转 |
| 5 | 可靠性/容错 | 4 | 4.5 | X-Request-Id + 500 兜底（不误吞 HTTPException）+ LOG_FILE 落盘 |
| 6 | 可维护性 | 4 | 4 | 注释文化依旧；README 索引与环境变量表漂移 |
| 7 | 前端体验 | 3 | **4** | a11y 三硬伤全闭环（viewport/键盘立绘/焦点圈定），SW 策略分层正确 |
| 8 | 部署就绪度 | 2 | **4** | 反代示例 + 容器加固 + token bootstrap 逐项为真；阻断清除，剩 runbook 打磨 |
| 9 | 隐私合规 | 3 | 3.5 | 凭据泄漏面证伪（正面）；PII 源码占位化未完成且历史已推送（负面） |
| 10 | 测试工程 | 4 | 4 | 337 个测试函数静态核实 = 336 pass + 1 skip（Windows chmod）；缺口仍在 |

**综合 ≈ 4.0 / 5（首轮 3.6 → A−）**。代码层 GO；发布 NO-GO 仅剩 §5 运维清单。

## §3 治疗验证：9/9 全部属实

| 提交 | 声称 | 独立取证 |
|------|------|--------|
| 19329fd heartbeat 拆分 | 951→799，纯逻辑外移 | ✅ `git show` 行数逐版核实（现 765）；heartbeat_moods.py 189 行纯只读数据+近纯函数，diff 纯删除，测试零修改 |
| e739be6 三处 DRY | rows_to_dicts/坐标/Bearer 收敛 | ✅ 三处均为单一实现 + 零私有副本残留；compare_digest 仅存 security.py:32 |
| 9baf4de a11y | 立绘键盘可达 + 焦点圈定 | ✅ CharacterSprite.jsx:138-149 role/tabIndex/Enter+Space；useFocusTrap.js capture 循环+条件归还；三面板 dialog 语义齐全 |
| 3c1440f 日志落盘 | LOG_FILE 轮转 + LOG_LEVEL | ✅ logger.py:64-77 RotatingFileHandler 10MB×7，落盘失败仅 warning |
| 7d272d1 rid+500 | X-Request-Id + 异常兜底 | ✅ request_id.py 纯 ASGI 包装不坏 SSE；负面测试钉死 HTTPException 不误吞、异常文本不外泄 |
| 5d7a831 推送流水线 | 统一 + 存库失败不推送 | ✅ heartbeat.py:298-346 双路共用，return 先于 send_push；4 态回归测试齐 |
| 672c45c 懒加载 | 主包 gzip 122→76KB | ✅ dist 实证独立 chunk `Markdown-*.js` 153.8KB raw，主包 −38% raw |
| 7a59357 SW v4 | SWR 免 bump | ✅ 三层策略 + `/api/` 前置排除 + 仅 ok 入缓存 + skipWaiting/claim/清旧桶，API 误缓存风险静态推演为零 |
| cfb3ec4 部署三件套 | 反代示例 + 加固 + bootstrap | ✅ compose `127.0.0.1:8000` + cap_drop ALL；entrypoint token 优先级链 + umask 077；LF 三重保险 |

## §4 本轮新发现（按视角，均已去重）

### 后端（🟠×1，🟡×7）
- 🟠 **N1 层反转仍在**：heartbeat.py:128/262/545/647 惰性 import `api.routes.config` 的运行时配置。**下一刀最高杠杆**：`_runtime_config` 下沉到 `agent/runtime_config.py`，路由与心跳同为消费方。
- 🟡 两处 `except: pass` 零日志黑洞（heartbeat.py:236/256）；4 处死代码（heartbeat.py:204/157、events.py:23、migrations.py:42 `callable` 误作注解）；迁移无逐条事务包裹 + v2 UTC 回填（migrations.py:477/92）；httpx 5 处临时客户端的廉价折中（调用点传 `app.state.http_client`）；chat.py /memories 段可平移独立路由（−60 行零风险）；429 无 Retry-After（auth.py:175）；LOG_LEVEL 解析应白名单化。
- **正面**：全库零模块级循环依赖、零同步阻塞进事件循环、SQL 面全参数化（唯一 f-string SQL 为白名单表名探测）。

### 安全（🟠×1，🟡×10，维持 5/5）
- 🟠 **O-1 TRUST_PROXY=true 依赖「反代唯一入口」不变量**：auth.py:85-89 取 XFF 最右值；若防火墙放行 8000 直连则限流可被任意 IP 归因架空。compose 默认回环绑定已天然满足，清单里钉死即可。
- 🟡 要点：OPTIONS 预检计入限流桶（auth.py:170-173）；`/api/memories/clear` 回显服务器备份路径（chat.py:439）；CSP 缺 `upgrade-insecure-requests`；镜像基础 tag 无 digest 钉死；pytest 进了运行时 requirements 且 cryptography 等传递依赖浮动。
- **正面实证**：恒定时比对单一实现、路由登记有元测试防绕过、推送 host 白名单可挡仿域、token bootstrap umask 077、CORS 未开 credentials。

### 测试（静态核实，实跑被环境拦截）
- **336/1 属实**：337 个测试函数（28 文件）= 336 pass + 1 skip（test_backup_permissions.py:23 Windows chmod skipif），与声称精确吻合。
- 隔离安全：conftest.py 全走 `tmp_path`，test_integration.py:109 注释明示 mock 掉 get_db 不碰生产库。
- 缺口：`memory/location_store.py`、`tools/search.py`、`utils/retry.py`、`utils/logger.py`、`api/middleware/websocket_reject.py`、`security_headers.py` 无专项测试（后者仅经 integration 覆盖）；providers/deepseek 永远被 mock 无单测；前端零单测；无 CI。
- **正面**：gps_test.cjs 的 exit-0 缺陷已修（:94/:97 显式 exit(1)）；回归测试真钉历史事故（fail-closed 15 例含等号写法/多 worker）。
- ⚠️ 本轮 pytest 实跑被本机安全分类器故障拦截，两轮计时未复核（依赖首检当日的双轮记录）。

### 前端（🔴×0，🟠×4，🟡×14，3→4）
- 🟠 立绘/天空子树零 `React.memo`，SSE 每 token 触发整棵天空（含 46 星）+ 立绘重算；usePush 每 60s 无条件换新引用强制全树重渲染（两行修复消掉聊天全程最大无效渲染源）；文本输入 `outline-none` 无 focus-visible 补偿（WCAG 2.4.7）；live region 被流式 token 轰炸。
- 🟡 要点：无 `prefers-reduced-motion`（常驻动画密度高）；戳一戳气泡对读屏静默；5 个低频姿态首切闪白（仅预载 4 态）；SWR 全量回源 ≈2MB 隐性流量（已知取舍）；全局 select-none 挡死复制；天空无自持时钟靠轮询顺带刷新。
- **正面**：useChat 的 epoch 纪元隔离 / 早断检测 / buffer flush 是同类项目罕见的通信层质量；四项治疗均超「达标线」（viewport 附带 touch-action 防回潮、useFocusTrap 单一钩子而非三份拷贝）。

### 部署与文档（🔴×1 已升格运维项，🟠×5，🟡×10，2→4）
- 🟠 **CONF-01 runbook 缺第一步**：compose `env_file: backend/config/.env` 是硬依赖，全新 clone 无此文件容器根本不起（entrypoint bootstrap 没机会生效）；deploy/README 与根 README 都假设 .env 已存在。
- 🟠 nginx 示例无 :80 server 块（与 Caddy 行为不一致）+ `http2 on` 需 nginx≥1.25.1 无版本注记；占位 DEEPSEEK_API_KEY 能过启动校验、对话才 401，runbook 未警告；README 文档索引漏 4 项、环境变量表漏 5 个（LOG_FILE/LOG_LEVEL 等）；CHECKUP §8 未 push 陈述过期。
- 🟡 要点：.gitignore 未覆盖根级 `.env`；tests/ 进生产镜像（bloat）；compose 无日志上限；start.bat taskkill :8000 与 start.sh 行为不一致。
- **正面实证**：README 12 项声明抽查 10 项为真；DB 路径以 `Path(__file__)` 锚定（memory 里「硬编码相对路径」的担忧在容器语境不成立）；deploy/README 与代码零漂移；四密钥清单与 settings.py:12/17/20/42 逐一对上。

## §5 公网发布清单（GO/NO-GO：代码 GO，下列运维项完成前 NO-GO）

1. **立即确认 Gitee 可见性**（历史已推送，此项从「上线前」提前到「现在」）。若曾公开 → 按已泄露处置：`migrations.py:185-190` + `test_cycle_tracker.py` 日期占位化、soul.py:152-156 档案打码 → `git filter-repo` 清 7fc9882/cd392e7/37ec00c → force-push。
2. **轮换四密钥**（DeepSeek / 高德 / Tavily / VAPID 私钥；VAPID 一换需在页面重开一次推送订阅）。
3. 服务器：`cp backend/config/.env.example backend/config/.env` → 填**真实** key（勿留占位）+ `XIAOXI_API_TOKEN=$(python -c "import secrets;print(secrets.token_hex(32))")` → `chmod 600`。
4. 反代 HTTPS + compose 端口**保持** `127.0.0.1:8000`（O-1 前提）；nginx 路线手动补 80→443 块；`CORS_ORIGINS=https://域名`（禁 `*`）；用反代则 `TRUST_PROXY=true`。
5. 上线后验证：无 token → 401 矩阵、`/docs` → 404、`curl -I` 见 HSTS、SSE 不被缓冲、改一张立绘硬刷新两次验 SW v4 过渡。

## §6 建议治疗顺序（性价比）

1. **半小时批**：前端两 memo + setUnread bail-out + 全局 focus-visible + reduced-motion（🟠×4 一锅端）。
2. **15 分钟批**：runbook 补 `cp .env.example` + 占位 key 警告、nginx 补 :80 块、429 加 Retry-After + OPTIONS 跳过计数、memories/clear 不回显路径、CSP 加 upgrade-insecure-requests。
3. **下一刀重构**：N1 运行时配置下沉（同时解开层反转 + heartbeat 4 处惰性依赖）。
4. **文档同步**：README 索引/环境变量表、CHECKUP §8 push 状态更正、旧产品报告移 `docs/archive/`。
5. **长线**：最小 CI（Gitee Go）、前端关键 hook 单测、pytest 拆 dev 依赖 + 传递依赖钉版、镜像 digest。

---

*本报告由 5 路独立审查（3 subagent + 主线程 2 域）汇总；所有 file:line 经一手取证。局限：pytest 实跑与 npm build 复测被本机分类器临时故障拦截，测试数以静态计数 + 首检双轮记录佐证，bundle 尺寸引自治疗提交自述 + dist raw 实测。*
