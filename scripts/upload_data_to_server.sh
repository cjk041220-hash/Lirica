#!/bin/sh
# ─────────────────────────────────────────────────────────────
# 小汐记忆库迁移：本地 DB → 服务器 xiaoxi-data 卷
#
# 用法（仓库根目录，Git Bash）：
#   scripts/upload_data_to_server.sh <user@host> [服务器仓库目录] [本地数据目录]
#     服务器仓库目录  缺省 ~/xiao-xi（服务器上 git clone 的位置）
#     本地数据目录    缺省 backend/data（当前库）；
#                     也可指向快照，如 E:/xiaoxi-pre-handoff-backup-20260807
#
# 前提：
#   - 服务器上已 git pull + docker compose up -d --build 至少跑过一次（卷已建好）
#   - 本机 OpenSSH 自带 scp/ssh 可用
#
# 安全：本地先走 Online Backup API 一致性快照再传输（不直接拷活文件）；
#       服务器侧临时目录 chmod 700，传完即删；以应用用户 xiaoxi 身份
#       复制进卷，文件属主天然正确；.api-token 不受影响（只覆盖两个 .db）。
#       若上传的库 schema 落后于服务器代码，启动时迁移自动升级（幂等）。
# ─────────────────────────────────────────────────────────────
set -eu

REMOTE="${1:?用法: upload_data_to_server.sh <user@host> [服务器仓库目录,缺省~/xiao-xi] [本地数据目录,缺省backend/data]}"
REMOTE_DIR="${2:-~/xiao-xi}"
SRC_DIR="${3:-backend/data}"

command -v scp >/dev/null 2>&1 || { echo "错误：找不到 scp（需要 OpenSSH）" >&2; exit 1; }
[ -f "$SRC_DIR/userdata.db" ]     || { echo "错误：找不到 $SRC_DIR/userdata.db" >&2; exit 1; }
[ -f "$SRC_DIR/checkpoints.db" ]  || { echo "错误：找不到 $SRC_DIR/checkpoints.db" >&2; exit 1; }

PY="backend/.venv/Scripts/python.exe"
[ -x "$PY" ] || PY="backend/.venv/bin/python"
[ -x "$PY" ] || { echo "错误：找不到 backend venv 的 python" >&2; exit 1; }

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT
# Windows python 读不懂 /tmp 风格路径，转混合路径（非 Windows 下 cygpath 不存在则原样）
TMPD_WIN=$(cygpath -m "$TMPD" 2>/dev/null || echo "$TMPD")
SRC_ABS=$(cygpath -m "$SRC_DIR" 2>/dev/null || echo "$SRC_DIR")

echo "== 1/4 本地一致性快照（Online Backup API）=="
cat > "$TMPD/snap.py" <<'PYEOF'
import sqlite3, sys, os
src_dir, dst_dir = sys.argv[1], sys.argv[2]
for name in ("userdata.db", "checkpoints.db"):
    src = sqlite3.connect(f"file:{src_dir}/{name}?mode=ro", uri=True)
    dst = sqlite3.connect(os.path.join(dst_dir, name))
    with dst:
        src.backup(dst)
    ok = dst.execute("PRAGMA integrity_check").fetchone()[0]
    dst.close()
    src.close()
    assert ok == "ok", f"{name} 快照完整性校验失败"
    print(f"  {name} 快照 ok ({os.path.getsize(os.path.join(dst_dir, name))} bytes)")
PYEOF
"$PY" "$TMPD/snap.py" "$SRC_ABS" "$TMPD_WIN"

echo "== 2/4 加密通道上传（scp）=="
ssh "$REMOTE" "mkdir -p /tmp/xiaoxi-migrate && chmod 700 /tmp/xiaoxi-migrate"
scp -q "$TMPD/userdata.db" "$TMPD/checkpoints.db" "$REMOTE:/tmp/xiaoxi-migrate/"

echo "== 3/4 服务器：停容器 → 以应用用户换库 → 启容器 =="
ssh "$REMOTE" "cd $REMOTE_DIR && \
  docker compose stop xiaoxi && \
  docker compose run --rm --no-deps -v /tmp/xiaoxi-migrate:/src:ro --entrypoint sh xiaoxi -c \
    'cp /src/userdata.db /app/backend/data/userdata.db && \
     cp /src/checkpoints.db /app/backend/data/checkpoints.db && \
     rm -f /app/backend/data/*.db-wal /app/backend/data/*.db-shm && \
     ls -la /app/backend/data' && \
  docker compose start xiaoxi && \
  rm -rf /tmp/xiaoxi-migrate"

echo "== 4/4 核验 =="
sleep 6
ssh "$REMOTE" "docker inspect --format='health: {{.State.Health.Status}}' xiaoxi 2>/dev/null; docker ps --filter name=xiaoxi --format 'table {{.Names}}\t{{.Status}}'"
echo ""
echo "完成。打开页面验一句话；如需旧库兜底，先 docker cp xiaoxi:/app/backend/data 出来留底。"
