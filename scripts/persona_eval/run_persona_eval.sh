#!/usr/bin/env bash
# 人格探针实跑（批⑤ Task 11）：起 :8001 临时副本（真 key）→ probe.py → 收尾零污染校验。
# 用法：bash scripts/persona_eval/run_persona_eval.sh
# 真 key 来源：backend/config/.env 原样复制进副本——脚本本身不写任何 key 字面量。
# 骨架 = smoke-batch4.sh（tar 整目录副本 + 绝对路径 sha + wmic 清场 + MSYS2 内联赋值）。
set -u
cd /e/小汐sonetto
TMP=$(mktemp -d /tmp/xiaoxi-eval.XXXXXX)
echo "TMP=$TMP"

cleanup() {
  kill "${PID:-}" 2>/dev/null || true
  wmic process where "CommandLine like '%port 8001%'" call terminate >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

# 0. 清场：旧的 8001 残留（已知坑「旧进程占端口」）
wmic process where "CommandLine like '%port 8001%'" call terminate >/dev/null 2>&1 || true
sleep 1

# 1. 拷贝副本（排除 .venv/__pycache__/测试产物，服务用仓库 venv + PYTHONPATH）
tar --exclude='.venv' --exclude='__pycache__' --exclude='out.txt' --exclude='out_proof.txt' -cf - backend | (cd "$TMP" && tar -xf -) || { echo "COPY FAIL"; exit 1; }

# 2. 真 key env：生产 .env 原样复制进副本（无假 key 覆盖；探针要真实 LLM 回复）
cp backend/config/.env "$TMP/backend/config/.env" || { echo "ENV COPY FAIL（backend/config/.env 不存在？）"; exit 1; }

# 3. 生产库指纹（零污染判据，绝对路径）
sha256sum "E:/小汐sonetto/backend/data/userdata.db" > "$TMP/sha_before"
echo "=== sha_before ==="; cat "$TMP/sha_before"

# 4. 提取 API token（探针认证用；去 CRLF 坑）。本地回环开发模式
#    （.env 未配 token）时为空——auth.py:195 对空 token 不强制认证，
#    探针不带 Authorization 头照常可用。
TOKEN=$(grep '^XIAOXI_API_TOKEN=' backend/config/.env | head -1 | cut -d= -f2- | tr -d '\r' || true)
echo "token: $([ -n "$TOKEN" ] && echo 已配置 || echo 未配置（本地回环开发模式，免认证）)"

# 5. 起服务（后台；真 key → 启动即真实 build_system_prompt）
cd "$TMP/backend"
PYTHONPATH="$TMP/backend" E:/小汐sonetto/backend/.venv/Scripts/python -m uvicorn api.server:app --host 127.0.0.1 --port 8001 > "$TMP/server.log" 2>&1 &
PID=$!
echo "server pid=$PID"

# 6. 轮询 /api/health 就绪（真 key 下健康检查过认证即服务已起；60s 兜底）。
#    未配 token 时 health 返回公开态 {"status": "ok"}，不带头照样可探。
cd /e/小汐sonetto
READY=""
for i in $(seq 1 12); do
  if [ -n "$TOKEN" ]; then
    READY_BODY=$(curl -s -m 5 -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8001/api/health 2>/dev/null)
  else
    READY_BODY=$(curl -s -m 5 http://127.0.0.1:8001/api/health 2>/dev/null)
  fi
  if echo "$READY_BODY" | grep -q "ok"; then READY="yes"; echo "== 服务就绪（第 ${i}x5s）=="; break; fi
  if grep -q "Traceback" "$TMP/server.log" 2>/dev/null; then echo "== 服务崩溃 =="; break; fi
  sleep 5
done
[ -n "$READY" ] && echo "SERVER-READY: PASS" || { echo "SERVER-READY: FAIL（60s 未就绪）"; tail -5 "$TMP/server.log"; exit 1; }

# 7. 实跑探针（真 key 逐场对话；退出码 0 = 全 PASS）。
#    PYTHONIOENCODING=utf-8：Windows 控制台 GBK 会把中文回复转码成乱码
#    （内存教训：verify_tools 同款坑）；报告落盘 SDD 目录供人工逐条判断
REPORT=.superpowers/sdd/2026-08-11-persona-deep-optimization/probe-report-2026-08-11.txt
PYTHONIOENCODING=utf-8 E:/小汐sonetto/backend/.venv/Scripts/python -u scripts/persona_eval/probe.py --base http://127.0.0.1:8001 --token "$TOKEN" 2>&1 | tee "$TMP/probe.out" "$REPORT"
RC=${PIPESTATUS[0]}

# 8. 收尾：停服务 + 生产库指纹比对（绝对路径；探针副作用只落在副本，生产库必须原样）
kill "$PID" 2>/dev/null || true
wmic process where "CommandLine like '%port 8001%'" call terminate >/dev/null 2>&1 || true
sleep 3
echo "=== sha_after（生产库）==="
sha256sum "E:/小汐sonetto/backend/data/userdata.db"
diff <(cat "$TMP/sha_before") <(sha256sum "E:/小汐sonetto/backend/data/userdata.db") >/dev/null && echo "ZERO-POLLUTION: PASS" || echo "ZERO-POLLUTION: FAIL"
exit $RC
