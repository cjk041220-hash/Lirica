# 小汐移动端更新防失联与会话自愈治理计划

**Goal:** 彻底杜绝 iPad / iPhone（iOS Safari PWA）因发版更新或 WebKit ITP 机制回收本地存储导致用户“失去所有聊天记录、日记、日历和记忆”的体验断层，建立多层容灾持久化与会话自愈对齐机制。

**Date:** 2026-09-07  
**Status:** Completed & Verified

---

## 根因与背景分析

1. **苹果 WebKit ITP 清理冲击**：iOS Safari / PWA 在应用更新、SW 升级、存储压力或 7 天未打开时，会静默清空 `localStorage`；
2. **凭证单点失联**：`xiaoxi-token` 仅保存在 `localStorage`，丢失后所有 API 触发 401，四大面板静默以空数组 `[]` 渲染，造成“被删库”的视觉假象；
3. **会话盲目新建**：`useChat.js` 在缺少 `sessionId` 时盲目通过 `Date.now()` 生成全新的空会话，而真实历史完整的旧会话被闲置，用户被直接抛入空白房间。

---

## 三大核心治理模块

### 模块 1：三轨容灾凭证系统 (Cookie + IndexedDB + localStorage)
- **后端**：在 `auth.py` 中支持从安全 Cookie（`xiaoxi_token`）读取令牌，并在验证/设置成功时提供 Cookie 种植，支持长效 `Max-Age=31536000`；
- **前端**：新增轻量 `web/src/utils/storage.js`，实现 `localStorage` + `IndexedDB` 双写与回读容灾；
- **自愈机制**：页面启动时，若 `localStorage` 为空，自动尝试从 Cookie 或 IndexedDB 唤醒并回填。

### 模块 2：智能会话对齐与历史自愈
- **改造 `useChat.js` 初始化流程**：
  1. 本地存在有效的 `sessionId` 且拉取到消息，正常渲染；
  2. 若本地无 `sessionId`，或拉取结果为 0 条消息，**不立即展示空房间**，而是并发向 `/api/status` 或 `/api/sessions?limit=5` 请求全库最新活跃会话；
  3. 若服务器存在非空的历史活跃会话（如 `e-mtir883f-1`），自动无缝将当前会话切回该活跃会话，瞬间展现全部历史；
  4. 仅当服务器全库真为空库时，才初始化新会话与欢迎语。

### 模块 3：401 友好锁定遮罩与防恐慌引导
- 改造 `MemoriesPanel`、`CalendarPanel`、`ConfigPanel` 等组件：
  - 严格区分“服务器数据为空”与“401 鉴权未通过”；
  - 遇到 401 严禁将界面刷成空日记/空记忆，而是呈现温和的房间锁门卡片：
    > *“小汐还在房间里等你哦~ 请输入暗号唤醒小汐”*
  - 用户解锁后立即触发数据刷新，杜绝造成“小汐失忆了”的焦虑。

---

## 实施任务分解 (SDD Tasks)

- [x] **Task 1: 前端双轨存储与后端 Cookie 凭证支持**
  - 后端 `backend/api/middleware/auth.py` 支持读取 Cookie `xiaoxi_token` 与 1 年长效 Cookie 种植；
  - 前端 `web/src/utils/storage.js` / `web/src/api/client.js` 增加 IndexedDB 镜像存储与启动自动回填。
- [x] **Task 2: `useChat.js` 会话自愈与全库最新会话对齐**
  - 移除盲目 `Date.now()` 开空会话逻辑；
  - 增加空会话回退向 `/api/sessions` 寻址并自动接驳能力。
- [x] **Task 3: 401 拦截友好门锁 UI 与防恐慌提示**
  - 前端各面板组件增加 401 拦截门禁与状态保护；
  - 增加输入暗号后自动重试与全局数据刷新广播。
- [x] **Task 4: 全量回归测试与 iOS PWA 离线模拟验证**
  - 模拟 `localStorage.clear()` 场景下刷新页面，验证历史会话与数据 100% 自动秒级恢复。
