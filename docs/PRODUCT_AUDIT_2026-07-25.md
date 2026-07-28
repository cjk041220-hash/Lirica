# 小汐 (XiaoXi / Sonetto) — 全面产品审计报告

**审计日期:** 2026-07-25  
**审计范围:** 全项目（backend 27 个生产文件 + 28 个测试文件、frontend 22 个源文件、配置/文档/脚本/部署文件）  
**审计方法:** 三路并行深度审查（后端 / 前端 / 配置+文档+脚本）  
**当前版本 (version.py):** 5.0  
**Git HEAD:** 00d4747 — feat: v5.0 事件系统「惦记」+ 视觉状态化 + 自动日记与碎碎念 + 记忆系统优化  

---

## 一、总体评分

| 维度 | 评分 | 说明 |
|------|------|------|
| 后端代码质量 | **B** | 架构清晰、防御性编程出色、但存在 2 个严重 bug |
| 前端代码质量 | **B** | 状态管理优秀、清理规范、但错误处理全静默 |
| 架构设计 | **B** | 前后端分离合理、模块职责清晰、但存在全局注入耦合 |
| API 完整性 | **A** | 所有端点注册正确、认证覆盖完备、无孤立路由 |
| 测试覆盖 | **A-** | 28 个测试文件、回归测试完备、但缺少 CI 类型检查 |
| 文档质量 | **C+** | README 好但版本号混乱、技术文档严重过时 |
| 部署就绪 | **B+** | Docker 化完善、安全默认值好、但需加固公网 |
| **综合评分** | **B (良好，有已知风险)** | |

---

## 二、严重问题（Critical）

### C1. [后端] `create_agent` API 兼容性风险 — 可能导致流式响应全面崩溃

**文件:** `backend/agent/graph.py:15`, `backend/api/routes/chat.py:201-204`

代码从 `langchain.agents` 导入 `create_agent`（非 `langgraph.prebuilt.create_react_agent`），将返回类型标注为 `CompiledStateGraph`，然后调用 `astream_events(version="v2")`。`langchain.agents.create_agent` 是较新的 API，**未被集成测试实际覆盖**——`tests/test_integration.py` 使用 mock agent。

**影响:** 如果 API 契约不匹配，每次 `/api/chat/stream` 请求都会崩溃。这是单点故障。

**建议:** 编写一个真实 `create_agent` + `astream_events` 的端到端测试，或回归到已验证的 `langgraph.prebuilt.create_react_agent`。

---

### C2. [后端] 默认 temperature 错误 — `"max"` 而非 `"medium"`

**文件:** `backend/config/thinking.py:56, 65`

```python
intensity = _runtime_config.get("thinking_intensity", "max")
```

当 `_runtime_config` 字典意外丢失 `thinking_intensity` 键时，回退值是 `"max"`（temperature 1.2, 8192 max_tokens），而非文档中记录的 `"medium"`（temperature 0.7）。

**影响:** 潜在的——触发时 LLM 会静默切换至极高创造力/温度，可能产生不稳定输出。

**建议:** 将默认值改为 `"medium"`，一行修改。

---

### C3. [前端] 停止流式响应竞态条件 — 用户无法中止发送

**文件:** `web/src/hooks/useChat.js:74-86`

`setIsStreaming(true)` 在 `AbortController` 创建之前调用。其间 `await getGpsCoords()` 最多阻塞 6 秒。如果用户在此期间点击"停止"按钮：
- `abortRef.current` 为 `null`，`stopStreaming()` 静默无效
- GPS 完成后 fetch 仍然发送，用户停止意图丢失

**建议:** 将 `abortRef.current = new AbortController()` 移到 `setIsStreaming(true)` 之前。

---

### C4. [前端] 无双重发送保护

**文件:** `web/src/hooks/useChat.js:70-224`

`sendMessage` 没有防护调用期间 `isStreaming` 为 `true` 的情况。虽然 UI `ChatInput` 检查了 `isStreaming`，但 hook 内部没有守卫。如果将来有代码程序化调用 `sendMessage`，会触发双重流式请求。

**建议:** 在 `sendMessage` 开头添加 `if (isStreaming) return;`。

---

### C5. [全局] 版本号混乱 — 5 个不同版本号在项目中并存

| 文件 | 声明的版本 |
|------|-----------|
| `backend/version.py` (唯一权威来源) | **5.0** |
| `README.md` | v0.4.4 |
| `start.bat` / `start.sh` | v0.4.5 |
| `docs/PROJECT_SOURCE.md` | v0.4.2 |
| `docs/PRODUCT_REPORT.md` | v0.4.1 |
| `docs/ACCEPTANCE_REPORT_2026-07-23.md` | v0.5 |
| `docs/XIAOXI_GUIDE.md` | v0.4.4 |

**影响:** 用户和开发者无法确定当前版本。`server.py` 运行时期望 `__version__` 来自 `version.py`，与所有文档矛盾。

**建议:** 统一所有文件至 `5.0`（匹配 `version.py`），或改为 `0.5.0` 并全局同步。

---

## 三、中等问题（Medium）

### 后端

| ID | 问题 | 文件 | 说明 |
|----|------|------|------|
| M1 | 经纬度 0 值被误判为空 | `tools/weather.py:144` | `if latitude and longitude:` — 赤道/本初子午线坐标 (0,0) 是合法值 |
| M2 | 心跳模块 7 处延迟内部导入 | `heartbeat.py` | 为避免循环导入而在函数内部导入，但无测试覆盖这些路径 |
| M3 | 骑行路线使用 v4 API，其他用 v3 | `tools/map_tools.py` | 高德 API 版本不一致，v4 格式变更会只影响骑行 |
| M4 | `events.py` 在函数内部导入 httpx | `utils/events.py:265` | 增加心跳天气检测的延迟 |
| M5 | 工具响应使用 emoji | `tools/diary.py:65` | 系统提示词禁止 emoji，但工具输出含 📖 |
| M6 | `get_db` 导入后未使用 | `api/server.py:21` | 死导入 |

### 前端

| ID | 问题 | 文件 | 说明 |
|----|------|------|------|
| M7 | 工具输出截断为 300 字符无展开 | `hooks/useChat.js:38` | 用户无法查看完整工具返回数据 |
| M8 | MemoriesPanel API 错误静默显示空状态 | `components/MemoriesPanel.jsx:30-33` | 后端 500 → 用户看到"还没有记忆呢" |
| M9 | `JSON.stringify(item)` 泄露内部数据 | `MemoriesPanel.jsx:96` | 若 memory 无 content/text 字段，显示所有内部字段 |
| M10 | 配置乐观更新分歧 | `hooks/useConfig.js:50-56` | 保存失败时 UI 显示已保存，后端未更新 |
| M11 | 所有 catch 块静默（全局） | 多个文件 | 无 console.error、无错误上报，生产调试困难 |

### 配置/脚本

| ID | 问题 | 文件 | 说明 |
|----|------|------|------|
| M12 | 测试清理脚本直接写生产数据库 | `scripts/cleanup_test_residue.py` | 无 --dry-run、无确认提示 |
| M13 | verify_tools.py 泄漏临时文件 | `scripts/verify_tools.py:95` | NamedTemporaryFile(delete=False) 从不清理 |
| M14 | .gitignore 的 `main.py` 过于宽泛 | `.gitignore:47` | 匹配所有目录的 main.py |
| M15 | 文档严重过时 | `PRODUCT_REPORT.md`, `PROJECT_SOURCE.md` | 描述旧前端状态、旧架构、旧测试数量 |

---

## 四、低优先级问题（Low）

| ID | 类别 | 问题 | 文件 |
|----|------|------|------|
| L1 | 后端 | `_HKT` 时区在 7 个模块中重复定义 | 多地 |
| L2 | 后端 | 限流器过期桶清理为 O(n) | `middleware/auth.py:58` |
| L3 | 后端 | `tests/conftest.py` 硬编码时间戳会随时间过时 | `conftest.py:52` |
| L4 | 后端 | 无 mypy/pyright 类型检查 | — |
| L5 | 前端 | Markdown 链接渲染为不可点击 span | `MessageBubble.jsx:21` |
| L6 | 前端 | `pointermove` 无节流，每秒数百次定时器重置 | `usePresence.js:36` |
| L7 | 前端 | `shortLabel` 在 null name/id 时可能抛出 | `ConfigPanel.jsx:52` |
| L8 | 前端 | usePush 中 `data\|\|[]` 回退可能设置非数组 | `usePush.js:20` |
| L9 | 配置 | `.env.example` 缺少 VAPID_* 和 BACKUP_SCRUB_SENSITIVE | `.env.example` |
| L10 | 配置 | `.claude/settings.local.json` 含 205 条权限需整合 | `.claude/` |
| L11 | 脚本 | `gen_assets.py` 硬编码 Windows 字体路径 | `scripts/gen_assets.py` |
| L12 | 脚本 | `trim_sprites.py` 硬编码绝对路径 | `scripts/trim_sprites.py` |
| L13 | 文档 | `PROMPT_GUIDE.md` 行号引用极易漂移 | `docs/PROMPT_GUIDE.md` |
| L14 | 文档 | `mem0-research-report.md` 为不相关外部研究 | `docs/` |

---

## 五、死代码 / 无用文件

### 确认死代码

| 项目 | 文件 | 说明 |
|------|------|------|
| `rebuild_agent(request)` 包装函数 | `backend/api/routes/config.py:128-131` | 从未被调用，重构残留 |
| `HumanMessage`, `ToolMessage` 导入 | `backend/agent/graph.py:19-23` | 导入但未在模块中使用 |
| `get_db` 导入 | `backend/api/server.py:21` | 导入但未使用 |
| `loadHistory` 导出 | `web/src/hooks/useChat.js:251` | 从 hook 导出但无组件使用 |
| `web/out` 回退检查 | `backend/api/server.py:209-214` | Next.js 已移除，回退永远不触发 |

### 确认过时/无用文件

| 文件 | 问题 |
|------|------|
| `docs/mem0-research-report.md` (852 行) | 外部项目研究，与当前项目无关 |
| `docs/PRODUCT_REPORT.md` | 描述旧前端"静态骨架"状态，已完全过时 |
| `docs/PROJECT_SOURCE.md` | 架构描述两个版本前的状态、测试数量错误 (66 vs ~160)、文件列表不完整 |

---

## 六、架构评估

### 整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         用户浏览器                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐   │
│  │ ChatPanel │  │ConfigPanel│  │ RoomScene │  │MemoriesPanel │   │
│  └────┬──────┘  └────┬──────┘  └────┬──────┘  └───────┬───────┘   │
│       │              │              │                  │           │
│  ┌────┴──────────────┴──────────────┴──────────────────┴───────┐  │
│  │          Hooks (useChat / useConfig / usePush / useStatus)   │  │
│  └────────────────────────────┬────────────────────────────────┘  │
│                               │                                   │
│  ┌────────────────────────────┴────────────────────────────────┐  │
│  │              API Layer (client.js / location.js)              │  │
│  │     fetch() + Bearer token + 401 event + GPS abstraction     │  │
│  └────────────────────────────┬────────────────────────────────┘  │
└───────────────────────────────┼───────────────────────────────────┘
                                │  HTTP (localhost:8000)
┌───────────────────────────────┼───────────────────────────────────┐
│                          FastAPI Server                            │
│  ┌────────────────────────────┴────────────────────────────────┐  │
│  │              Middleware (Auth / RateLimit / RejectWS)         │  │
│  └────────────────────────────┬────────────────────────────────┘  │
│                               │                                    │
│  ┌──────────┐  ┌──────────┐  ┌┴─────────┐  ┌──────────┐         │
│  │  /chat   │  │ /config  │  │/memories │  │  /push   │         │
│  │ streaming│  │  GET/POST│  │  CRUD    │  │subscribe │         │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘         │
│       │             │             │              │                │
│  ┌────┴─────────────┴─────────────┴──────────────┴────┐          │
│  │              Agent (LangChain create_agent)          │          │
│  │  ┌─────────────────────────────────────────────┐    │          │
│  │  │  System Prompt (soul.py — 角色人设)          │    │          │
│  │  │  + Context Injection (memory + diary + life) │    │          │
│  │  └─────────────────────────────────────────────┘    │          │
│  └────┬───────────────────────────────────────────────┘          │
│       │                                                           │
│  ┌────┴──────────────────────────────────────┐                    │
│  │          Tools (12+ tools)                  │                    │
│  │  weather | map | diary | memory | tarot    │                    │
│  │  reminder | search | web | push | status   │                    │
│  └────┬───────────────────────────────────────┘                    │
│       │                                                           │
│  ┌────┴──────────┐  ┌──────────┐  ┌──────────────┐              │
│  │   Memory Mgr  │  │ Database │  │  Providers    │              │
│  │  (FAISS向量)  │  │(SQLite)  │  │(DeepSeek LLM) │              │
│  └───────────────┘  └──────────┘  └──────────────┘              │
│                                                                    │
│  ┌─────────────────────────────────────────────────────┐         │
│  │   Heartbeat Scheduler (APScheduler)                  │         │
│  │   周期性: 刷新agent / 维护 / 主动消息 / 事件检测      │         │
│  └─────────────────────────────────────────────────────┘         │
└───────────────────────────────────────────────────────────────────┘
```

### 架构优点

- **前后端分离清晰** — Vite/React SPA ↔ FastAPI REST，通过 Bearer token 认证
- **工具模块化** — 每个工具独立文件，统一错误处理和日志脱敏
- **防御性编程** — 提示注入护栏、fail-closed 启动、密钥脱敏、工具错误隔离
- **生命周期管理** — `lifespan()` + `register_jobs()` 管理 agent/调度器
- **迁移框架** — 数据库 schema 演化有版本管理
- **前端状态管理** — Hooks 模式 + 函数式更新器 + ref 区分可变状态，竞态处理正确

### 架构关注点

- **无连接池** — 单例 aiosqlite 连接，单用户足够但扩展受限（已知设计决策）
- **进程级全局注入** — 模块级 `set_*` 函数用于依赖注入，多 worker 不可用
- **循环导入规避模式** — 7+ 函数内部延迟导入，脆弱但有效
- **无 CI 类型检查** — 无 mypy/pyright 配置
- **前端零错误可见性** — 所有 catch 静默，生产调试困难
- **单一 sendMessage 函数** — 155 行覆盖整个聊天流程，可拆分为 reducer

---

## 七、API 端点完整审计

| 端点 | 方法 | 路径 | 认证 | 状态 |
|------|------|------|------|------|
| 聊天 | POST | `/api/chat` | ✓ | ✅ 正常 |
| 流式聊天 | POST | `/api/chat/stream` | ✓ | ⚠️ C1 风险 |
| 聊天历史 | GET | `/api/chat/history` | ✓ | ✅ 正常 |
| 健康检查 | GET | `/api/health` | 双重 | ✅ 正常 |
| 获取配置 | GET | `/api/config` | ✓ | ✅ 正常 |
| 更新配置 | POST | `/api/config` | ✓ | ✅ 正常 |
| 删除记忆 | DELETE | `/api/memories/{id}` | ✓ | ✅ 正常 |
| 导出记忆 | GET | `/api/memories/export` | ✓ | ✅ 正常 |
| 清空记忆 | POST | `/api/memories/clear` | ✓ | ✅ 正常 |
| 记忆列表 | GET | `/api/memories` | ✓ | ✅ 正常 |
| VAPID 公钥 | GET | `/api/push/vapid-key` | ✗ | ✅ 正常（公开） |
| 订阅推送 | POST | `/api/push/subscribe` | ✓ | ✅ 正常 |
| 取消订阅 | DELETE | `/api/push/subscribe` | ✓ | ✅ 正常 |
| 未读消息 | GET | `/api/push/unread` | ✓ | ✅ 正常 |
| 标记已读 | POST | `/api/push/read` | ✓ | ✅ 正常 |
| 状态 | GET | `/api/status` | ✓ | ✅ 正常 |
| 日记列表 | GET | `/api/diaries` | ✓ | ✅ 正常 |
| WebSocket | 全部 | 任意 | — | ✅ 已拒绝（中间件） |

**前端 ↔ 后端映射验证:** 12 个前端调用全部正确匹配后端端点，无遗漏、无错误。

---

## 八、功能完整性清单

| 功能 | 后端实现 | 前端界面 | 测试覆盖 | 状态 |
|------|---------|---------|---------|------|
| 流式对话 | ✅ | ✅ | ⚠️ mock | ⚠️ 见 C1 |
| 同步对话 | ✅ | — | ✅ | ✅ |
| 记忆系统（存储/检索） | ✅ | ✅ | ✅ | ✅ |
| 自动日记 | ✅ | ✅ | ✅ | ✅ |
| 碎碎念 | ✅ | ✅ | ✅ | ✅ |
| 事件系统「惦记」 | ✅ | ✅ | ✅ | ✅ |
| 天气查询 | ✅ | ✅ | ✅ | ✅ |
| 地图/路线/周边 | ✅ | — | ✅ | ✅ |
| 塔罗牌 | ✅ | — | ✅ | ✅ |
| Web Push 通知 | ✅ | ✅ | ✅ | ✅ |
| 心跳主动消息 | ✅ | ✅ | ✅ | ✅ |
| 人设状态管理 | ✅ | ✅ | ✅ | ✅ |
| 角色精灵动画 | — | ✅ | — | ✅ |
| 视觉状态化（情绪可见） | — | ✅ | — | ✅ |
| GPS 定位 | 接收 | ✅ | — | ✅ |
| 配置面板 | ✅ | ✅ | — | ✅ |
| 记忆面板 | ✅ | ✅ | — | ⚠️ 见 M8 |
| 离线 PWA | — | ✅ | — | ✅ |
| Docker 部署 | ✅ | — | — | ✅ |
| 数据库备份 | ✅ | — | — | ✅ |
| 限流保护 | ✅ | — | ✅ | ✅ |

---

## 九、修复优先级建议

### 🔴 立即修复（阻断性）

1. **C5 — 统一版本号**: 将所有文件同步至 `version.py` 的值（5.0）
2. **C2 — 修复默认 temperature**: `"max"` → `"medium"`（一行改动）
3. **C3 — 修复前端停止竞态**: AbortController 在 setIsStreaming 之前创建

### 🟡 尽快修复（影响体验/稳定性）

4. **C1 — 验证 create_agent + astream_events 路径**: 编写集成测试
5. **C4 — 添加双重发送保护**: sendMessage 入口 guard
6. **M8 — MemoriesPanel 错误状态**: 区分"空"和"加载失败"
7. **M11 — 添加错误日志**: 替换所有静默 catch 为 `console.warn()`
8. **M12 — 清理脚本添加 --dry-run**: 防止误删生产数据
9. **M15 — 更新/移除过期文档**

### 🟢 计划修复（技术债务）

10. 移除死代码（5 项确认 + 3 个无用文件）
11. 提取 `_HKT` 时区常量至共享模块
12. 修复经纬度 0 值判空 bug (M1)
13. 节流 `pointermove` (L6)
14. 整合 `.claude/settings.local.json` 权限
15. 编写 `CLAUDE.md` 项目上下文文件

---

## 十、公网部署前检查清单

| 检查项 | 状态 | 说明 |
|--------|------|------|
| `XIAOXI_API_TOKEN` 已设置 | ⚠️ 必填 | `.env.example` 中为空，部署前必须设置强密码 |
| VAPID 密钥已配置 | ⚠️ 建议 | 无则推送通知不可用 |
| Docker 端口绑定 `127.0.0.1` | ✅ | 安全默认值，生产应配合反向代理 |
| 限流中间件已启用 | ✅ | 每前缀独立配置 |
| SQLite 备份机制 | ✅ | Online Backup API |
| 密钥脱敏日志 | ✅ | `log_tool_error()` |
| CSRF 保护 | N/A | SPA + Bearer token，不适用 |
| HTTPS | ⚠️ 需配置 | 需反向代理（Nginx/Caddy）提供 TLS |
| 数据库加密 | ❌ | SQLite 无加密，`data/` 目录需文件系统保护 |
| 多用户隔离 | ❌ | 当前为单用户设计，公网开放前需加固 |

---

## 十一、总结

**小汐 (XiaoXi)** 是一个架构清晰、实现用心的个人 AI 伴侣项目。代码质量整体良好（综合 B 级），防御性编程、测试覆盖和前后端分离设计均表现出色。

**核心优势:**
- 完整的功能闭环 — 从流式对话到记忆、日记、事件系统、推送通知
- 精致的视觉设计 — 角色精灵动画、情绪可视化、日夜间天空
- 良好的安全基础 — 提示注入防护、密钥脱敏、认证全覆盖

**主要风险:**
- 2 个可能在生产环境触发运行失败的后端 bug（C1/C2）
- 2 个影响用户体验的前端竞态/保护缺失（C3/C4）
- 版本号混乱和文档过时影响可维护性
- 全静默错误处理使生产调试困难

**建议:** 在部署公网前，至少完成 🔴 立即修复列表中的 3 项。完成 🟡 尽快修复后可达 **B+ 级别**。完成全部技术债清理后可达 **A- 级别**。

---

*本报告由 Claude Code 代理团队通过三路并行深度代码审查自动生成。*
