# 多阶段构建：builder 安装依赖 → runtime 只复制必要文件
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

# 创建数据目录
RUN mkdir -p data

# 非 root 用户运行
RUN useradd --create-home xiaoxi && chown -R xiaoxi:xiaoxi /app
USER xiaoxi

# 数据持久化
VOLUME ["/app/backend/data"]

EXPOSE 8000

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/api/health')" || exit 1

# ⚠️ 必须 --workers 1：APScheduler 是模块级单例，多 Worker 会导致心跳任务重复执行。
# 如果需要水平扩展，应将调度器拆到独立进程或使用分布式锁。
CMD ["uvicorn", "api.server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
