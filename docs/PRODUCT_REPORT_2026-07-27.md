# 小汐 v5.0 全量测试产品报告

**日期**：2026-07-27　**版本**：v5.0（commit `00d4747` + 本次修复，未提交）
**测试轮次**：3 轮（静态评审 → 运行时实测 → 修复后复测）
**环境**：Windows 本机 / Python venv / Node + 系统 Edge（headless）/ 空数据库状态

---

## 一、测试方法与覆盖

| 轮次 | 内容 | 方法 | 结果 |
|---|---|---|---|
| 第 1 轮 | 全量单元测试 | `pytest tests/` | 259 通过 / 1 跳过 |
| 第 1 轮 | 架构评审 | 只读代码审查 | 19 项发现 |
| 第 1 轮 | 后端逻辑评审 | 只读代码审查 | 9 项发现（含 2 严重） |
| 第 1 轮 | 前端评审 | 只读代码审查 | 24 项发现（含 1 严重） |
| 第 1 轮 | 安全审查 | 只读代码审查（按公网暴露标准） | P1×2 / P2×3 / P3×6 |
| 第 2 轮 | API 接口实测 | httpx 黑盒，33 项 | 31 通过 / 2 待分析（均非缺陷） |
| 第 2 轮 | 前端 E2E | Playwright + 系统 Edge，18 交互点 | 17 通过 / 1 环境依赖 |
| 第 2 轮 | GPS 链路 E2E | 注入尖沙咀坐标 | 2/2 通过，截图证实高德真实 POI 回填 |
| 第 3 轮 | 修复后全量单测 | `pytest tests/` | **261 通过 / 1 跳过** |
| 第 3 轮 | API 回归验证 | 针对改动点 6 项 | **6/6 通过** |
| 第 3 轮 | E2E 复测 | 重建 dist 后重跑 | 17/18（剩余 1 项为环境依赖） |

---

## 二、功能验证矩阵

| 功能 | 验证方式 | 状态 |
|---|---|---|
| 流式聊天（SSE） | API 实测 + E2E | ✅ 帧格式、`[DONE]` 收尾、停止按钮、气泡渲染均正常 |
| 非流式聊天 | API 实测 | ✅ 正常 200；空消息/超长/非法 JSON 全部正确 422 |
| 记忆系统（增删查导出） | 单测 32 项 + API 实测 | ✅ 去重（DB 唯一索引）、转义、导出正常 |
| 记忆相关性召回 | 单测 + 代码审查 | ⚠️→✅ **发现并修复**：候选池被 `created_at` 预筛死（见下） |
| 日记系统 | 单测 + API | ✅ |
| 事件系统「惦记」 | 单测 + 审查 | ✅（归档测试日期依赖问题已修） |
| 生理期关怀 | 单测 + 审查 | ⚠️→✅ **发现并修复**：单条脏数据静默禁用整个功能 |
| 主动消息 / 心跳 | 单测 + 审查 | ⚠️→✅ **发现并修复**：推送异常时消息永久滞留 `pending` |
| Web Push 订阅 | API 实测 | ⚠️ 功能正常，但 endpoint 无校验（遗留 P2，见下） |
| GPS → 地图工具链 | E2E（注入坐标） | ✅ 端到端打通，本地化 POI 回复真实生效 |
| 天气 / 搜索 / 塔罗工具 | 单测 + 审查 | ✅（明天预报数组越界守卫已加固） |
| 运行时配置 | API 实测 + E2E | ✅ 模型/温度/思考强度修改、校验、回滚链路正常 |
| 鉴权与限流 | API 实测 + 审查 | ✅ 限流在 LLM 之前生效（刷桶零成本）；发现 history 挤占 chat 桶（遗留） |
| 前端托管 / SPA | API 实测 | ✅ dist 由 FastAPI 正常托管（应用无路由，无需 fallback） |

---

## 三、本次已修复的问题（12 项源码 + 5 项测试）

### 严重（2）

1. **非流式聊天的 503 优雅降级被吞成 500** — `api/routes/chat.py`
   `HTTPException(503)` 继承自 `Exception`，被外层 `except Exception` 兜底改写为 500——启动窗口期的「小汐还在睡觉」永远不生效。修复：检查移到 try 之外，与流式路径对齐。已加回归测试。
2. **记忆「相关性召回」名存实亡** — `memory/manager.py`
   候选池是 `ORDER BY created_at DESC LIMIT limit*2`，`recall(limit=3)` 只在**最新 6 条**里打分——access 热度因子对旧记忆完全失效，「重要但较旧」的核心偏好永远被流水账挤出系统提示词，与 narrative 宣称的 hybrid 注入直接矛盾。修复：无类别分支改全表评分（单用户量级完全可承受）。已加回归测试（旧的高频记忆必须能进 top-3）。

### 一般（5）

3. **推送异常 → 主动消息永久丢失** — `heartbeat.py` ×2（主动消息 + 早安）
   `send_push` 抛异常/ImportError 时消息滞留 `pending`，而 `/api/push/unread` 只捞 `delivered/stored`。修复：新增 `_mark_stored()` 兜底，异常时降级为 `stored`（页面打开仍可拉取）。
4. **一条脏数据静默禁用生理期关心** — `utils/cycle_tracker.py`
   `get_cycle_stats` 对全部记录 strptime，迁移回填的存量数据无格式约束。修复：逐条 try/except 跳过坏行并告警。
5. **markRead 无条数上限** — `api/routes/push.py`
   可构造超长 IN 列表撞 SQLite 绑定变量上限。修复：`Field(max_length=200)`（复测：1500 条 → 422）。
6. **消息裁剪可能切断工具调用链** — `agent/graph.py`
   找不到 human 边界时仍硬切，可能留孤儿 ToolMessage 在队首 → DeepSeek 400。修复：无边界时放弃本轮裁剪（宁多留，勿切链）。
7. **流式断连被静默截断**（前端唯一严重项）— `web/src/hooks/useChat.js`
   连接中途切断时 `done:true` 直接收尾，半截回复被判定为「聊成功」，立绘还演 6 秒开心——违背代码自己写下的 invariant。上服务器（后端重启变常态）后会高频暴露。修复：未收到 `[DONE]` 的收尾判定为早断，气泡追加「（连接中断了，小汐的话没说完……）」且 `succeeded=false`；catch 分支同样改为追加提示。

### 加固/体验（3）

8. **天气明天预报数组越界** — `tools/weather.py`：各字段数组分别守卫长度。
9. **apiFetch 无超时** — `web/src/api/client.js`：默认 15s 超时（流式聊天自带 signal 不叠加）。
10. **前端 dist 已用修复后代码重建**。

### 测试基建（5）

11. **新增 2 个回归测试**：503 降级、旧高频记忆浮现。
12. **修复 2 个日期/时间依赖的坏测试**（本次翻红的真凶，与功能改动无关）：
    - `test_events.py`：硬编码「2026-07-26 = 明天」，日期推移后必然翻红 → 改为相对今天的日期；
    - `test_proactive.py`：docstring 自认「只要此刻不是深夜必定通过」，凌晨跑必挂 → mock 为固定 14:00。

---

## 四、遗留问题（需排期，按优先级）

### 🔴 需要主人亲自决策（涉及隐私与凭证）

1. **真实生理期数据进了 git 历史并已推送远程** — `database/migrations.py:178-183`（v4 迁移种子数据含真实日期+健康备注）、`tests/test_cycle_tracker.py` 同组日期。代码审查确认 `.env` 从未入库、远端无密钥泄漏，但**种子健康数据在 git 历史中永久存在**。
   - **行动**：确认 Gitee 仓库 `oscarjk/xiao-xi` 可见性；若曾公开/将公开，按已泄露处理（`git filter-repo` 清历史 + force push），种子数据改占位日期。此项不代劳，等你决定。
2. **公网部署前轮换全部四把真实密钥**（DeepSeek / Tavily / 高德 / VAPID）——尤其 VAPID 私钥泄漏 = 攻击者可以以小汐身份向你的设备推送任意通知。

### 🟠 公网部署阻断项（安全审查 P1）

3. **无 TLS**：uvicorn 裸 HTTP，Bearer token 明文过境。必须 Caddy/Nginx 前置 HTTPS，uvicorn 退回回环。
4. **Docker 默认路径自相矛盾**：容器内固定 `--host 0.0.0.0` + 空 token → fail-closed 拒绝启动 → `restart: unless-stopped` 崩溃循环。fail-closed 是对的，但 compose 需显式注入 `XIAOXI_ALLOW_INSECURE=true`（127.0.0.1 映射下威胁模型成立）或改为要求 token 的模板。

### 🟡 建议排期（P2 / 一般）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 5 | push subscribe endpoint 无校验（受限 SSRF + 内容外泄原语，需 token 才可利用） | `api/routes/push.py:49` | https + 推送服务 host 白名单 |
| 6 | `/api/chat/history` 挤占聊天 10次/分 限流桶（本轮 API 实测现场复现：5 次 history GET 后 GET /api/chat 即 429） | `api/middleware/auth.py:35` | history 单列更高档位 |
| 7 | 层反转：heartbeat（领域层）依赖 `api.routes.config`（路由层）持有 agent 重建逻辑 | `heartbeat.py:187,315…` | 抽 `agent/runtime.py` |
| 8 | 表 schema 双处定义，漂移只靠注释约束 | `database/core.py` vs `migrations.py` | 提共享常量（v6 events 已有先例） |
| 9 | 备份保留窗口仅约 10 小时（2h×5） | `core.py:299` | 分层：2h×5 + daily×7 + weekly×4 |
| 10 | 前端：流无空闲看门狗（hang 死只能手动停） | `useChat.js:144` | 30-45s chunk 超时 abort |
| 11 | 前端：SW 对无 hash 的立绘永久 cache-first（换立绘老用户看不到） | `public/sw.js:43` | stale-while-revalidate |
| 12 | 前端：拖拽把手轻点误吸、config GET 竞态、推送订阅失败静默、3 项无障碍 | 见前端评审 #5-#10 | 逐条排期 |
| 13 | E2E：gps_test 断言失败仍 exit 0；ui_test 种子数据硬断言（本次空库翻红即此） | `scripts/e2e/*.cjs` | failures 计数退出；种子断言放宽 |
| 14 | 迁移无事务保护（v2 DROP/RENAME 间崩溃窗口）；v2 回填用 UTC 与全项目 HKT 差 8 小时 | `migrations.py:85,99` | 残留探测续做 + 显式事务 |

### 安全加固 checklist（公网开放前，P3）

- [ ] `CORS_ORIGINS` 精确域名，绝不 `*` + credentials 同开
- [ ] `TRUST_PROXY` 仅在代理正确覆写 XFF 时开启（默认 False 正确）
- [ ] Docker：`mem_limit` / `read_only` / `cap_drop: ALL` / `no-new-privileges`
- [ ] `data/` 目录与主库收 `700/600`（备份已收权，主库未收）
- [ ] 静态站补最小 CSP 响应头
- [ ] 守住前端渲染基线：未来引入 `rehype-raw` 必须同时 `rehype-sanitize`

---

## 五、工程质量评估

**结论：这是一个工程素养明显高于平均水准的个人项目，已达到「可放心进入公网部署阶段」的质量基线。**

值得专门记录的亮点（审查三方交叉确认属实）：

- **fail-closed 安全体系成建制**：非回环+空 token 拒绝启动、workers>1 拒绝、设 token 自动关 `/docs`、token 常量时间比较、XFF 仅显式信任取最右值——且**用遍历全路由的回归测试钉死鉴权登记纪律**（曾拦下 `/api/diaries` 漏登记）。代码层无 P0 可利用漏洞。
- **把事故沉淀为制度**：v0.4.4 lambda 注册事故、checkpoint「恒假阈值+假绿灯测试」、TOCTOU 去重、falsy 0.0 判断——每个历史坑都有显式修复 + 注释 + 回归测试。本次新修复也按同样纪律加了测试。
- **DB 运维细节**：WAL+busy_timeout、SQLite Online Backup API、备份收权、uuid6 时间域换算带完整推导、langgraph 新旧 schema 自适应。
- **async 与注入防御**：会话锁 `async with` 消除取消死锁、工具异常固定文案（堵 URL/key 泄漏到 LLM/checkpoint/SSE 三面）、记忆注入「数据不是指令」降级包裹；工具集无任何外发/shell/文件能力，被污染数据无外泄通道。
- **前端**：API 层 100% 收口 `apiFetch` + 401 全局广播；无 `dangerouslySetInnerHTML`、markdown 链接规范处理，XSS 面干净；E2E 用持久化上下文绕开隐身 Push 限制等做法务实且文档化。

**核心短板**只有两处：隐私种子数据进了 git 历史（待你决策），以及部署三件套的默认路径自相矛盾（半小时可修）。其余均为可排期的重构项与公网加固清单。

---

## 六、本次改动清单（未提交）

```
backend/api/routes/chat.py        503 降级修复
backend/memory/manager.py         相关性召回候选池修复
backend/heartbeat.py              _mark_stored 兜底（主动消息+早安）
backend/utils/cycle_tracker.py    脏日期容错
backend/tools/weather.py          明天预报数组守卫
backend/api/routes/push.py        markRead 条数上限
backend/agent/graph.py            裁剪边界安全修复
backend/tests/test_integration.py +1 回归测试
backend/tests/test_memory_manager.py +1 回归测试
backend/tests/test_events.py      日期依赖测试修复
backend/tests/test_proactive.py   深夜依赖测试修复
web/src/hooks/useChat.js          流断检测与提示
web/src/api/client.js             默认 15s 超时
web/dist/                         已重建
```

**最终测试状态**：单测 261 通过 / 1 跳过；API 回归 6/6；E2E 17/18（1 项为空库环境依赖）；GPS E2E 2/2。
