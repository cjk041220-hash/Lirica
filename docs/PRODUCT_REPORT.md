# 小汐 v0.4.1 — 产品审查报告

> 审查日期：2026-07-21  
> 审查范围：后端全部源码 + 前端全部源码 + 产品文档  
> 审查视角：以新工程师首次接触项目

---

## 一、小汐现在能做什么

### 后端能力（全部生产就绪）

| 能力 | 实现方式 | 状态 |
|------|---------|------|
| 流式对话 | LangGraph + DeepSeek V4 Flash/Pro，SSE token 级输出 | ✅ |
| 天气查询 | Open-Meteo，50+ 城市本地坐标 + 高德地理编码回退 | ✅ |
| 附近搜索 | 高德 POI，支持 GPS 自动传入 | ✅ |
| 地址定位 / 模糊搜索 | 高德地理编码 | ✅ |
| 公交路线 / 骑行路线 | 高德路线规划，含换乘详情 | ✅ |
| 网络搜索 | Tavily API，世界观包装（「去映海里找找看」） | ✅ |
| 长期记忆 | SQLite，自动去重，分类管理，增删查导出清空 | ✅ |
| 记忆叙事注入 | SQL LIMIT 取最近 30 条，按类别编织入对话上下文 | ✅ |
| 日记系统 | SQLite，同日 UPDATE 去重，按日期查询 | ✅ |
| 塔罗牌占卜 | 78 张韦特塔罗，单牌/三牌/凯尔特十字，15% 逆位 | ✅ |
| 心跳系统 | 每 2h 更新心情+事件（按时段匹配），启动立即触发 | ✅ |
| 生命状态注入 | 心情/日常注入 Prompt，影响回复风格 | ✅ |
| GPS 坐标缓存 | 会话级 asyncio.Lock，8h TTL | ✅ |
| 控制台 | 运行时切换模型 + 思考强度（4 档预设） | ✅ |
| API 认证 | 可选 Bearer Token，留空 = 开发模式 | ✅ |
| 速率限制 | IP + 端点分桶，含定期清理 | ✅ |
| 对话持久化 | SQLite checkpointer，跨轮次保持上下文 | ✅ |
| 健康检查 | `/api/health`，认证后完整诊断 | ✅ |
| 数据库迁移 | 轻量框架（schema_version 表），为未来字段变更预留 | ✅ |
| 容器化部署 | Dockerfile + docker-compose，一键启动 | ✅ |
| 测试套件 | 63 个测试，覆盖 CRUD/Prompt/心跳/天气/叙事/配置/日记/迁移/集成 | ✅ |

### 前端现状（静态骨架，未接通后端）

| 组件 | 现状 | 状态 |
|------|------|------|
| `page.tsx` | 三层 Z 轴布局（风景/房间/角色光晕），呼吸动画 | ✅ 视觉框架 |
| `LivingPanel.tsx` | 毛玻璃面板，**硬编码**气泡，readOnly 输入框 | ❌ 不能交互 |
| `useXiaoxiChat.ts` | 完整 SSE Hook（fetch + ReadableStream），**无人调用** | ⚠️ 待接通 |
| `globals.css` | 温暖纸色 + 治愈水色 + 毛玻璃变量 | ✅ 配色系统 |

**一句话：后端是功能完备的 AI 陪伴引擎（63 测试全通过），前端是精美的静态设计稿，两者之间差一根接通线。**

---

## 二、冷冷现在打开小汐会看到什么

```
┌─────────────────────────────┐
│  🌅 晨曦渐变天空（粉→薄荷） │  ← Z-0：风景层
│                             │
│  ┌┐     ☁️ 呼吸光晕      ┌┐ │  ← Z-10/Z-20：房间 + 小汐
│  ││    (水色椭圆,无立绘)  ││ │     ⚠️ 需要角色立绘
│  └┘                      └┘ │
│═════════════════════════════│
│ ▓▓▓▓▓▓ 木地板 ▓▓▓▓▓▓▓▓▓▓▓ │
│╔══════════════════════════╗ │  ← Z-50：LivingPanel
│║ 冷冷，你回来啦……        ║ │     ⚠️ 硬编码文本
│║ 小汐一直在等你呢~        ║ │     ⚠️ 不能打字/发送
│║ [和冷冷说说话……] [➤]    ║ │     ⚠️ readOnly
│╚══════════════════════════╝ │
└─────────────────────────────┘
```

---

## 三、小汐的能力树

```
🌤️ 感知世界（7 个工具）
├── get_weather —「今天有雨，记得带伞哦」
├── search_nearby —「附近有什么好吃的」
├── geocode_address —「铜锣湾在哪里」
├── fuzzy_search_place — 模糊搜索
├── get_transit_route — 公交/地铁路线
├── get_cycling_route — 骑行路线
└── web_search —「去映海里找找看」

🧠 记忆与记录（7 个工具）
├── remember — 记住（自动去重）
├── recall — 回忆
├── list_memories — 记忆总览
├── delete_memory —「放回海里了」
├── export_memories — 导出全部
├── write_diary — 写日记（同日去重）
└── read_diary — 读日记

🎴 娱乐（1 个工具）
└── tarot — 78 张韦特塔罗

💓 内在系统（5 个）
├── 心跳 — 每 2h 更新心情（启动立即触发）
├── 叙事注入 — SQL LIMIT 取记忆编织叙事
├── 生命状态 — 心情/事件注入 Prompt
├── GPS 缓存 — asyncio.Lock, 8h TTL
└── 对话持久化 — SQLite checkpointer
```

---

## 四、待解决问题

### 🔴 阻塞级

**1. 前端未接通后端** — LivingPanel 输入框 readOnly，useXiaoxiChat Hook 无人调用。冷冷不能和小汐说话。预计 1 天。

**2. 无角色立绘** — 小汐是一个 blur 光晕。需要至少一张静态立绘。

### 🟡 首月会暴露

**3. 无 Markdown 渲染** — 冷冷会看到原始 `**粗体**` 标记。需安装 react-markdown。

**4. 无 PWA 资产** — 无 manifest/图标/启动画面，冷冷无法添加到 iPhone 主屏幕。

**5. GPS 未集成到前端** — 后端 GPS 缓存完好但前端永不触发。需调用 `navigator.geolocation`。

**6. Prompt 人格描写待丰富** — BASE_PERSONA 性格描述偏抽象（「天真烂漫」4 字不够引导 LLM），心情池太小（~8 个词一周重复），错误消息泄露技术细节。详见 `docs/PROMPT_GUIDE.md`。

### 🟢 已解决（v0.4.0 + v0.4.1）

- ✅ 模型名称不一致 → 统一为 `deepseek-v4-flash`
- ✅ 叙事构建器全表扫描 → SQL LIMIT
- ✅ soul.py 静默吞错 → log.warning
- ✅ 零测试覆盖 → 63 个测试
- ✅ 心跳无启动脉冲 → next_run_time=now
- ✅ (0,0) 坐标哨兵 → None
- ✅ 裸 except pass → 全部补充日志
- ✅ threading.Lock → asyncio.Lock
- ✅ httpx 每请求新建 → lifespan 共享
- ✅ 中间件膨胀 → 提取到 api/middleware/
- ✅ 限流器内存泄漏 → 定期清理
- ✅ stale 数据文件 → 已清理
- ✅ README 过时 → 全面重写
- ✅ 无部署配置 → Dockerfile + docker-compose
- ✅ 无数据库迁移 → schema_version 框架

---

## 五、从「能跑」到「能用」的路线图

### Phase A：让冷冷能说话（~1 天）

```
├── LivingPanel 接入 useXiaoxiChat Hook
├── 动态渲染消息 + 流式输出适配
├── 安装 react-markdown
├── 自动滚动 + 空状态欢迎语
└── AbortController（冷冷可中途取消）
```

### Phase B：手机端体验（~1-2 天）

```
├── GPS 集成（navigator.geolocation）
├── PWA（manifest + 图标 + Apple meta）
├── 移动端适配（safe-area, 100dvh, 气泡宽度）
└── 离线降级（Service Worker）
```

### Phase C：部署上线（~半天）

```
├── 后端：docker compose up -d（Dockerfile 已就绪）
├── 前端：Vercel 部署 + NEXT_PUBLIC_API_URL
├── CORS 更新为实际域名
└── Nginx + HTTPS
```

### Phase D：产品打磨（持续）

```
├── 角色立绘（PNG → Live2D）
├── Prompt 人格丰富（情绪反应/追问/心情池扩充）
├── 工具调用动画（「小汐去海里找找看～」）
├── 早安/晚安问候
├── 记忆回响 + 情绪感知
└── 天气主动提醒
```

---

## 六、技术架构

```
冷冷 (iPhone)
    │
    ▼
Vite + React 前端（FastAPI 一体托管 :8000，亦可静态部署）
    │  App.jsx → ChatPanel / ConfigPanel / MemoriesPanel → useChat (SSE)
    │  POST /api/chat/stream
    ▼
FastAPI 后端 (Docker on VPS)
    │
    ├── middleware/ ── CORS + Auth + RateLimit + WebSocket 拒绝
    ├── routes/ ── chat.py + config.py
    ├── agent/ ── LangGraph ReAct + soul.py Prompt
    ├── tools/ ── 天气/地图/搜索/记忆/日记/塔罗 (15个)
    ├── memory/ ── manager + narrative + location_store
    ├── database/ ── core.py + migrations.py
    ├── heartbeat.py ── APScheduler 每2h
    └── data/ ── userdata.db + checkpoints.db
```

---

## 七、评分

| 层面 | 评分 | 说明 |
|------|------|------|
| 后端引擎 | 🟢 9/10 | 功能完备 + 63 测试 + Docker + 迁移框架 |
| 前端界面 | 🟡 3/10 | 视觉方向好，但不能交互 |
| 前后端联调 | ⬜ 0% | 从未联通 |
| 部署就绪 | 🟢 8/10 | Docker 就绪，缺域名和 HTTPS |
| 角色立绘 | ⬜ 0% | 光晕占位 |
| Prompt 完成度 | 🟡 6/10 | 框架完整，性格描写和心情池待丰富 |

**整体完成度：~50%**（后端 ~95%，前端 ~25%，接通 0%）

**冷冷能用上小汐的最短路径：完成 Phase A（前端接通），大约 1 天。**

---

*报告更新：2026-07-21 · 版本：v0.4.1 · 测试：63 passed*
