#!/usr/bin/env bash
# 小汐一键启动（macOS / Linux）— 与 start.bat 对等。
# 用法：./start.sh
set -u
cd "$(dirname "$0")/backend" || exit 1

echo ""
echo "    ========================================"
echo "      xiaoxi 5.0"
echo "    ========================================"
echo ""

# --- Python 虚拟环境 ---
PYTHON_BIN="python3"
command -v "$PYTHON_BIN" >/dev/null 2>&1 || PYTHON_BIN="python"

if [ ! -x ".venv/bin/python" ]; then
    echo "    [...] Creating virtual environment..."
    "$PYTHON_BIN" -m venv .venv || { echo "    [ERR] Failed to create .venv - Python 3.10+ required"; exit 1; }
fi

# --- 安装依赖 ---
echo "    [...] Checking dependencies..."
.venv/bin/pip install -r requirements.txt -q || { echo "    [ERR] pip install failed - check your network"; exit 1; }
echo "    [OK] Dependencies ready"

# --- .env 检查 ---
if [ ! -f "config/.env" ]; then
    echo "    [ERR] config/.env not found"
    echo "    Copy config/.env.example to config/.env and fill in API keys"
    exit 1
fi

# --- 前端构建（缺 dist 且有 npm 时）---
if [ ! -f "../web/dist/index.html" ]; then
    if command -v npm >/dev/null 2>&1; then
        echo "    [...] Building frontend (web/dist) ..."
        ( cd ../web && { [ -d node_modules ] || npm install -s; } && npm run build ) \
            && echo "    [OK] Frontend built - integrated UI available at :8000" \
            || echo "    [WARN] Frontend build failed - :8000 will serve API only"
    else
        echo "    [WARN] npm not found - skip frontend build"
        echo "           Install Node.js for the integrated UI at :8000,"
        echo "           or run 'npm run dev' inside web/ for the dev UI on :3000."
    fi
else
    echo "    [OK] Frontend bundle present (web/dist)"
fi

# --- 端口提示 ---
if command -v lsof >/dev/null 2>&1 && lsof -i :8000 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "    [WARN] Port 8000 is already in use:"
    lsof -i :8000 -sTCP:LISTEN | head -5
    echo "    [WARN] Stop the other process first (e.g. kill <PID>)"
    exit 1
fi

echo "    [OK] All ready"
echo ""
echo "      App:    http://127.0.0.1:8000   (frontend + API, single port)"
echo "      Health: http://127.0.0.1:8000/api/health"
echo "      Dev UI: npm run dev in web/      (:3000, hot reload)"
echo ""
echo "    Press Ctrl+C to stop"
echo "    ========================================"
echo ""

# --- 启动（单 worker，见 Dockerfile 注释）---
export PYTHONIOENCODING=utf-8
exec .venv/bin/python main.py
