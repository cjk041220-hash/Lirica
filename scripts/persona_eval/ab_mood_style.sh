#!/usr/bin/env bash
# A/B 验证：mood 文风句注入 vs 不注入（批次一 P0-1，2026-08-12）。
# 双副本并行起 :8001（注入组，原样）/ :8002（不注入组，patch mood_style_hint）
# → ab_mood_style.py 交错发 12 剧本 × 2 组 → 量化判定 + X/Y 盲评报告 → 双库零污染校验。
# 用法：bash scripts/persona_eval/ab_mood_style.sh
# 真 key 来源：backend/config/.env 原样复制进副本——脚本本身不写任何 key 字面量。
set -u
cd /e/小汐sonetto
TMP=$(mktemp -d /tmp/xiaoxi-ab.XXXXXX)
TMPW=$(cygpath -m "$TMP")   # MSYS 虚拟路径 → Windows 正斜杠（python -c 直读文件必须）
echo "TMP=$TMP"
REPORT_DIR=.superpowers/sdd/2026-08-12-batch1/ab-report-2026-08-12

cleanup() {
  kill "${PIDA:-}" "${PIDB:-}" 2>/dev/null || true
  wmic process where "CommandLine like '%port 8001%'" call terminate >/dev/null 2>&1 || true
  wmic process where "CommandLine like '%port 8002%'" call terminate >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

# 0. 早晨锚定窗口守卫（06:00-08:00 HKT 心跳会重置 mood，A/B 必须钉死 mood=开心）
HOUR=$(date +%H)
if [ "$HOUR" -ge 6 ] && [ "$HOUR" -lt 8 ]; then
  echo "AB-GUARD: FAIL（06:00-08:00 HKT 早晨锚定窗口，跑批会重置 mood）"; exit 1
fi

# 1. 清场 + 双副本拷贝（排除 .venv/__pycache__；服务用仓库 venv + PYTHONPATH）
wmic process where "CommandLine like '%port 8001%'" call terminate >/dev/null 2>&1 || true
wmic process where "CommandLine like '%port 8002%'" call terminate >/dev/null 2>&1 || true
sleep 1
mkdir -p "$TMP/a" "$TMP/b"
tar --exclude='.venv' --exclude='__pycache__' --exclude='out.txt' --exclude='out_proof.txt' -cf - backend | (cd "$TMP/a" && tar -xf -) || { echo "COPY A FAIL"; exit 1; }
tar --exclude='.venv' --exclude='__pycache__' --exclude='out.txt' --exclude='out_proof.txt' -cf - backend | (cd "$TMP/b" && tar -xf -) || { echo "COPY B FAIL"; exit 1; }

# 2. 真 key env：生产 .env 原样复制进两份副本（探针要真实 LLM 回复）
cp backend/config/.env "$TMP/a/backend/config/.env" || { echo "ENV COPY FAIL（backend/config/.env 不存在？）"; exit 1; }
cp backend/config/.env "$TMP/b/backend/config/.env"

# 3. 副本 B 字节 patch：_MOOD_STYLE_HINTS.get(mood) → None（ASCII 锚点全文件唯一；
#    只动这一行 → 不注入组的唯一差异 = 文风句，「今天我的心情」等其余段与 A 组逐字同构）
PYTHONIOENCODING=utf-8 E:/小汐sonetto/backend/.venv/Scripts/python -c "
p = '$TMPW/b/backend/heartbeat_moods.py'
s = open(p, encoding='utf-8').read()
old = '_MOOD_STYLE_HINTS.get(mood)'
assert s.count(old) == 1, 'patch 锚点不唯一: %d' % s.count(old)
open(p, 'w', encoding='utf-8').write(s.replace(old, 'None'))
print('PATCH-B: OK（不注入组 mood_style_hint 恒返 None）')
"

# 4. 种库：mood 钉死「开心」（mood_changed_at=now → 心跳 6h 最小停留不换词；
#    daily_event 固定同文，两组起点一致）。必须发生在起服务前（server.py
#    lifespan 一次性构建 system_prompt，改库不重启不生效）。
#    坑（2026-08-12 实锤）：life_state 无行时 UPDATE 静默 0 行，起服务后
#    init_db（database/core.py:256）种子「开心/刚刚醒来」会覆盖——必须
#    INSERT OR REPLACE 先建行（首次实跑恰好靠 init_db 默认开心撑住，
#    重跑/生产库有行时则失效，已修正）。
SEED_NOW=$(date +"%Y-%m-%d %H:%M")
PYTHONIOENCODING=utf-8 E:/小汐sonetto/backend/.venv/Scripts/python -c "
import sqlite3
for db in ('$TMPW/a/backend/data/userdata.db', '$TMPW/b/backend/data/userdata.db'):
    con = sqlite3.connect(db)
    cur = con.execute(\"INSERT OR REPLACE INTO life_state (id, mood, daily_event, mood_changed_at, updated_at) VALUES (1, '开心', '在映海里捡到一颗亮亮的贝壳', ?, ?)\", ('$SEED_NOW', '$SEED_NOW'))
    con.commit()
    assert cur.rowcount == 1
    print('SEED', db.split('/')[-1], con.execute('SELECT mood, mood_changed_at FROM life_state WHERE id=1').fetchone())
    con.close()
"

# 5. 生产库指纹（零污染判据：双库，绝对路径）
sha256sum "E:/小汐sonetto/backend/data/userdata.db" "E:/小汐sonetto/backend/data/checkpoints.db" > "$TMP/sha_before"
echo "=== sha_before ==="; cat "$TMP/sha_before"

# 6. 提取 API token（探针认证用；去 CRLF 坑）。本地回环开发模式
#    （.env 未配 token）时为空——auth.py:195 对空 token 不强制认证。
TOKEN=$(grep '^XIAOXI_API_TOKEN=' backend/config/.env | head -1 | cut -d= -f2- | tr -d '\r' || true)
echo "token: $([ -n "$TOKEN" ] && echo 已配置 || echo 未配置（本地回环开发模式，免认证）)"

# 7. 并行起双服务（各端口独立进程内限流桶，互不干扰）
cd "$TMP/a/backend"
PYTHONPATH="$TMP/a/backend" E:/小汐sonetto/backend/.venv/Scripts/python -m uvicorn api.server:app --host 127.0.0.1 --port 8001 > "$TMP/server_a.log" 2>&1 &
PIDA=$!
cd "$TMP/b/backend"
PYTHONPATH="$TMP/b/backend" E:/小汐sonetto/backend/.venv/Scripts/python -m uvicorn api.server:app --host 127.0.0.1 --port 8002 > "$TMP/server_b.log" 2>&1 &
PIDB=$!
echo "server A(注入) pid=$PIDA :8001 ; server B(不注入) pid=$PIDB :8002"
cd /e/小汐sonetto

# 8. 双端口就绪轮询（health 认证/公开态，60s 兜底；崩溃即停）
ready() {
  local port=$1 token=$2 i body
  for i in $(seq 1 12); do
    if [ -n "$token" ]; then
      body=$(curl -s -m 5 -H "Authorization: Bearer $token" "http://127.0.0.1:$port/api/health" 2>/dev/null)
    else
      body=$(curl -s -m 5 "http://127.0.0.1:$port/api/health" 2>/dev/null)
    fi
    if echo "$body" | grep -q "ok"; then echo "port $port READY（第 ${i}x5s）"; return 0; fi
    sleep 5
  done
  echo "port $port NOT-READY（60s 超时）"; return 1
}
ready 8001 "$TOKEN" || { tail -5 "$TMP/server_a.log"; exit 1; }
ready 8002 "$TOKEN" || { tail -5 "$TMP/server_b.log"; exit 1; }

# 9. 实跑 A/B（12 剧本 × 2 组交错发；报告落盘 SDD 目录供人工盲评）
mkdir -p "$REPORT_DIR"
PYTHONIOENCODING=utf-8 E:/小汐sonetto/backend/.venv/Scripts/python -u scripts/persona_eval/ab_mood_style.py --base-a http://127.0.0.1:8001 --base-b http://127.0.0.1:8002 --token "$TOKEN" --report "$REPORT_DIR" 2>&1 | tee "$TMP/ab.out"
RC=${PIPESTATUS[0]}

# 10. 收尾：停服务 + 生产库指纹比对（探针副作用只落在副本，生产库必须原样）
kill "$PIDA" "$PIDB" 2>/dev/null || true
wmic process where "CommandLine like '%port 8001%'" call terminate >/dev/null 2>&1 || true
wmic process where "CommandLine like '%port 8002%'" call terminate >/dev/null 2>&1 || true
sleep 3
echo "=== sha_after（生产库）==="
sha256sum "E:/小汐sonetto/backend/data/userdata.db" "E:/小汐sonetto/backend/data/checkpoints.db"
diff <(cat "$TMP/sha_before") <(sha256sum "E:/小汐sonetto/backend/data/userdata.db" "E:/小汐sonetto/backend/data/checkpoints.db") >/dev/null && echo "ZERO-POLLUTION: PASS" || echo "ZERO-POLLUTION: FAIL"
exit $RC
