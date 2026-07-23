# 小汐的房间 · 前端

Vite 8 + React 19 + Tailwind CSS 4 的单屏移动端 PWA。立绘按角色状态切换（WebP），天空随时辰变化，聊天面板可拖拽，支持 Web Push 主动消息。

## 开发

```bash
npm install
npm run dev      # http://localhost:3000，/api 自动代理到 http://localhost:8000
```

开发时后端（FastAPI）需同时在 `127.0.0.1:8000` 运行。

## 构建

```bash
npm run build    # 产出 dist/
```

`dist/` 由后端 `backend/api/server.py` 挂载到根路径 `:8000`，生产环境只需访问后端即可拿到前后端一体的服务。

## 后端接口约定

所有请求经 `/api` 前缀，详见后端路由：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | SSE 流式对话，请求 `{message, session_id}` |
| GET/POST | `/api/config` | 模型 / 思考强度（thinking_intensity）/ 温度 |
| GET | `/api/memories` | 记忆列表 |
| GET | `/api/push/vapid-key` | VAPID 公钥 |
| GET | `/api/push/unread` | 未读主动消息 |
| POST/DELETE | `/api/push/subscribe` | 注册 / 取消推送订阅 |
| POST | `/api/push/read` | 标记已读 `{message_ids}` |

SSE 流协议：每行 `data: <json>\n\n`，JSON 形如 `{"content":"增量文本"}` / `{"type":"tool_start",...}` / `{"type":"state","state":"happy"}`，结尾 `data: [DONE]\n\n` 后关流。

## 目录

- `src/hooks/` — `useChat`（对话状态机 + SSE）、`useConfig`（设置）、`usePush`（Web Push）
- `src/components/` — `RoomScene`（场景）、`CharacterSprite`（立绘）、`ChatPanel`/`ChatInput`、`ConfigPanel`、`MemoriesPanel`、`MessageBubble`
- `public/sprites/` — 9 张角色立绘（WebP，已压缩）
- `public/sw.js` — Service Worker：cache-first（跳过 `/api`）+ 推送通知
- `scripts/gen_assets.py` — 生成 PWA 图标 / 立绘 PNG→WebP 的工具
