# 设计文档：小汐的日历（第四块面板）+ 地图双引擎

**日期**：2026-07-29 **状态**：已定稿，待实现计划
**视觉基准**：`docs/mockups/calendar-panel-v2.html`（可交互，已通过用户验收）
**关联**：第二轮体检 `docs/CHECKUP_2026-07-29_round2.md`——本设计顺带收敛其 4 项前端 🟠（memo/focus-visible/reduced-motion）与 2 项文档漂移

---

## 0. 决策记录（brainstorming 已拍板，不再讨论）

| # | 决策 | 理由 |
|---|------|------|
| D1 | 顶栏第四个按钮 → 完整月历面板 | 用户指定；复用三面板的 dialog/焦点架构 |
| D2 | 日历只上「日程类」事件；台风/回声/档案等叙事类留在聊天 | 保持日历干净，叙事是内心戏不是行程 |
| D3 | 双向编辑：冷冷走面板 CRUD，小汐走 LLM 工具，同库同规则 | 一本账，两个人 |
| D4 | 不加提醒设置项：前一晚推送 + 早安提 + 重要事项当天再说 | 符合人设——她记得，而不是她是个日历 |
| D5 | 同步不做实时通道：打开面板时 + 小汐回复后重新拉取 | 事件低频，WebSocket 是过度设计 |
| D6 | 地图按坐标自动选引擎：内地高德、其余谷歌 | 用户深港往返频繁；确定性切换，无双重延迟 |
| D7 | 日历视觉完全取自本体 `@theme`（暖纸/茶墨/木色/疗愈绿） | 与房间本体同一套衣服，mockup v2 已验收 |
| D8 | 手写月历网格，不引日历库 | 依赖洁癖 + 本体美学不匹配货架组件 |

## 1. 数据层：零迁移

`events` 表字段已齐（`event_type`/`event_date`/`end_date`/`severity`/`source`/`memo`/`dedupe_key`/`status`）。**本设计不新增表、不新增列、不写迁移。**

**日历显示过滤规则**（唯一权威定义，前后端共用）：
```
可见 = event_type IN ('exam','trip','appointment','health','general','birthday','anniversary')
且 status IN ('active','archived')          -- 过去月份仍能回看（archived 是心跳自动翻篇的）
且 source IN ('llm','ui') OR event_type IN ('birthday','anniversary')
```
即：检测器生成的 weather/memory_echo 等类型天然被第一行排除；纪念日/生日虽由检测器生成但对两人可见、**只读**（UI 不渲染编辑/删除按钮）。

检测器 source 现状已核实（无需新增 source 分类）：档案=`profile`、天气=`weather`、回声=`memory`，LLM 创建=`llm`；本设计新增 `ui`。**配套一行**：`utils/events.py` 的 `_SOURCE_NOTES` 增加 `'ui': '冷冷在日历上记的'`，让小汐读日程上下文时知道这条是谁记的。

**软删除**：`DELETE` 置 `status='deleted'`，不参与去重、不出现在任何读取面。

## 2. 后端契约

### 2.1 日历 REST（新文件 `backend/api/routes/events.py`）

| 路由 | 语义 | 备注 |
|------|------|------|
| `GET /api/events?from=YYYY-MM-DD&to=YYYY-MM-DD` | 月范围查询 | 按 `event_date`/`end_date` 与区间相交过滤；返回完整行；缺参 422 |
| `POST /api/events` | 冷冷创建 | `source='ui'`；复用 `events_mgr.create_event` 的校验与 `dedupe_key` 推断——与小汐撞车时返回 `{created:false, event}` 200（前端 toast「这件小汐已经记过啦」） |
| `PATCH /api/events/{id}` | 改 title/event_date/end_date/event_type/severity/memo | 同一套校验；把 archived 行日期改到未来 → 自动复活为 active；`birthday`/`anniversary` 型返回 403（只读） |
| `DELETE /api/events/{id}` | 软删 | 同上，只读型 403 |

- 全部路由注册进 `api/middleware/auth.py` 受保护前缀（`test_auth_middleware.py` 的路由登记元测试会自动钉住，漏注册必红）。
- 过去日期创建：后端 422 + 前端 date 控件 `min=today` 双保险（与 `create_event` 的「小汐没办法回到过去」规则一致）。

### 2.2 LLM 工具（扩展 `backend/tools/events.py`）

```python
@tool update_event(event_id, title="", event_date="", event_type="", severity="", memo="")
@tool delete_event(event_id)
```
- 空串参数 = 不改该字段；校验逻辑与 `create_event` 提取为共享函数（顺手 DRY）。
- 工具返回文案保持小汐口吻；`list_events` 输出已含 ID，小汐可自查自改。
- 提示词（soul.py）补一句：冷冷要求改/删日程时用这两个工具。

### 2.3 提醒（改 `backend/heartbeat.py`，不新增机制）

- 挂钩点已核实：heartbeat 为整点触发结构（`_try_proactive_message(mood, daily_event, hour)`，带 `_MIN_INTERVAL_HOURS` 与安静时段守卫）。**前一晚提醒**挂在 EVENING 时段分支：检测明日有日程 → 生成关心文案 → 走 `_store_and_push_proactive` 统一流水线（自动继承「存库失败不推送」铁律与 4 态回归测试姿势）。
- `severity='high'`：当天早安上下文（8:00 job 的 `get_events_context` 已覆盖）之外，当天 DAYTIME 时段分支再触发一次关心。
- 纪念日/生日：现有 `detect_anniversary_events` 不动。

## 3. 地图双引擎（与日历完全解耦，可独立上线）

### 3.1 引擎选择（新文件逻辑置于 `tools/map_tools.py`）

```
_mainland = 73°E≤lon≤135°E 且 18°N≤lat≤54°N
_hkmo     = 113.8°E≤lon≤114.5°E 且 22.15°N≤lat≤22.57°N   # 港澳 excl.
engine = amap if (_mainland and not _hkmo) else google
```
深圳 → 高德；港岛/九龙/新界 → 谷歌；海外 → 谷歌。边界城市按几何判定，不做行政区精修（YAGNI）。

### 3.2 谷歌侧（新文件 `backend/tools/google_places.py`）

- `nearby_search(location, keyword, radius)` + `text_search(query)`，Places API（New）REST 直调，**复用 `set_http_client` 注入的共享客户端**（顺带终结本线 httpx 碎片化）。
- key 缺失/额度错误 → `_GoogleKeyMissing`，小汐口吻文案，模式完全复刻 `_AmapKeyMissing`（可被 LLM 正确归因，不空转）。
- 归一化：两引擎输出统一 `{name, address, location, rating?, distance_m?, types?}`，`search_nearby`/路线工具签名不变，LLM 无感。
- 引擎自身故障各自报错降级（固定文案），**不做跨引擎重试**（语义歧义，记录为将来项）。

### 3.3 配置与文档同步（一批做完，防止再漂移）

- `settings.py` + `.env.example` + `.env` 模板：`GOOGLE_MAPS_API_KEY`（第四把 → 第五把密钥）。
- `deploy/README.md` 轮换清单、根 `README.md` 环境变量表（顺带补齐体检 DOC-03 漏的 5 个变量 + 文档索引 DOC-02 + `fix_sleeping_sprite` DOC-04）。

## 4. 前端（基准 = mockup v2，逐像素对照实现）

- **组件**：`components/CalendarPanel.jsx`（月历网格 + 月标题 + 图例 + 详情/填写两 sheet）、`api/events.js` 客户端、`hooks/useEvents.js`（范围拉取 + 失效重拉）。
- **挂载**：App 顶栏第四按钮，复用 `useFocusTrap` + dialog 语义（Esc 关闭、焦点归还——与三面板同构）。
- **刷新时机（D5）**：面板打开时；`useChat` 每条助手消息完成后调 `useEvents.invalidate()`。
- **视觉**：六色全部取自 `@theme`（琥珀 `#D9A45B`、陶土玫瑰 `#C9828C` 为本色加深一档的新 token，加入 `@theme` 管理）；动效全部本体曲线（`fadeInUp` 的 `.16,1,.3,1` + 弹性 sheet + 级联 6ms）。
- **体检 🟠 顺带收敛（与面板同批落地）**：
  - 立绘/天空子树 `React.memo` + `usePush` setUnread bail-out（消掉流式期全树空转）；
  - 全局 `:focus-visible` 疗愈绿基线（日历输入框率先受益）；
  - 全局 `prefers-reduced-motion` 媒体查询（含日历在内的全部常驻动画静止化）。
- **a11y**：格子 `role=button` + 键盘可达（mockup 已实现该模式）；sheet 焦点圈定；触摸目标 ≥40px。
- **多天事件**：`end_date` 渲染为跨日横条（mockup 已有样式）。

## 5. 错误处理与降级

| 场景 | 行为 |
|------|------|
| 面板 CRUD 网络失败 | 沿用 api 层角色化兜底文案；不静默吞 |
| 与小汐记录撞车（dedupe 命中） | 200 + `created:false`，toast 小汐口吻，不报错 |
| 谷歌 key 缺失 | 地图工具回小汐口吻「地图功能还没开启」，聊天不中断（复刻高德模式） |
| 月范围查询无事件 | 空状态文案「这一天还空着，要不要和小汐约点什么？」 |
| 编辑只读纪念日 | 403 + 前端不渲染编辑入口（双保险） |

## 6. 测试策略

- **后端 pytest（绿闸口，每阶段门）**：
  - `test_events_routes.py`：CRUD × 校验（坏日期 422 / 过去日期 422 / 只读型 403）、dedupe 撞车 200、软删不可见、月范围含 archived 回看；
  - 路由登记元测试自动覆盖 auth 注册；
  - `test_events_tools.py` 扩：update/delete 工具 + 与 llm source 的交叉 dedupe；
  - `test_map_engine.py`：`_pick_engine` 三分支（深圳/尖沙咀/伦敦）+ 归一化 + 双 key 缺失语义；
  - `test_proactive_pipeline.py` 姿势复用：前夜提醒 4 态（存库成功推/存库失败不推/无日程静默/high 当天追加）。
- **E2E**：`scripts/e2e/ui_test.cjs` 追加——开日历 → 手动加事件 → 断言 pill 出现 → 切月 → 详情弹层（沿用现有截图 + exit(1) 机制）。
- **前端单测**：仍无设施（记录在案，随 D 档长线）。

## 7. 落地顺序（每步 pytest 绿闸口，独立可提交）

1. **P1 后端日历面**：routes + 共享校验提取 + update/delete 工具 + 测试
2. **P2 前端日历面板 + 收敛批**：面板组件/hook/api + memo/focus-visible/reduced-motion 四项 + build 验证
3. **P3 提醒管线**：前夜 beat + high 当天追加 + 4 态回归
4. **P4 地图双引擎**：pick_engine + google_places + 配置同步（密钥文档五件套）
5. **P5 收尾**：E2E 追加 + README/docs 漂移修复 + CHANGELOG 式摘要

## 8. 明确不做（YAGNI）

重复事件（每周一…）、逐事件提醒时间配置、实时同步通道、日历库依赖、叙事类事件上日历、跨引擎故障重试、地图行政区精修。

## 9. 风险

| 风险 | 缓解 |
|------|------|
| 谷歌需绑账单账号 | 每月 $200 免费额度覆盖个人用量；runbook 写明开户步骤 |
| 港澳几何 excl. 框误伤珠海沿岸 | 框取保守值（22.15°N 下界）；边界误差 = 引擎换用，POI 仍可用 |
| events 表被检测器高频写入 | 日历查询走 `(status,event_date)` 既有索引；叙事类被类型过滤天然挡掉 |
| mockup 与实现漂移 | 实现逐像素对照 v2；验收以 mockup 为基准 |
