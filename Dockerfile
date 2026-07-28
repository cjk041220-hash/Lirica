# 多阶段构建：web 构建前端 → builder 安装 Python 依赖 → runtime 只复制必要文件
# （修复 P1-4：旧镜像不含 web/dist，纯容器部署只有 API 没有房间 UI）

# ── 前端构建阶段 ──────────────────────────────────────────────
FROM node:20-slim AS web

WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ── Python 依赖阶段 ──────────────────────────────────────────
FROM python:3.12-slim AS builder

WORKDIR /build

# 先复制依赖文件（利用 Docker layer caching）
COPY backend/requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

# ── 运行阶段 ──────────────────────────────────────────────────
FROM python:3.12-slim

WORKDIR /app/backend

# 从 builder 复制已安装的 Python 包
COPY --from=builder /install /usr/local

# 复制后端源码
COPY backend/ .

# 复制前端构建产物（server.py 会挂载 ../web/dist 到 :8000 根路径）
COPY --from=web /web/dist /app/web/dist

# 创建数据目录
RUN mkdir -p data

# 非 root 用户运行
RUN useradd --create-home xiaoxi && chown -R xiaoxi:xiaoxi /app
USER xiaoxi

# 数据持久化
VOLUME ["/app/backend/data"]

EXPOSE 8000

# 健康检查统一定义在 docker-compose.yml（此处移除，避免与 compose 重复且 start-period/retries 参数不一致）

# ⚠️ 必须 --workers 1：APScheduler 是模块级单例，多 Worker 会导致心跳任务重复执行。
# 5.0 起后端启动时会主动校验，workers>1 直接拒绝启动。
# 如果需要水平扩展，应将调度器拆到独立进程或使用分布式锁。
CMD ["uvicorn", "api.server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
