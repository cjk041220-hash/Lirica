#!/bin/sh
# ─────────────────────────────────────────────────────────────
# 小汐云端备份（服务器端跑）：新鲜快照 → rclone 上传
#
# 用法：scripts/cloud_backup_server.sh <rclone目的地>
#   例：scripts/cloud_backup_server.sh xiaoxicrypt:xiaoxi
#
# 定时（cron 每 30 分钟；库只有几百 KB，再密也行）：
#   */30 * * * * cd ~/xiao-xi && scripts/cloud_backup_server.sh xiaoxicrypt:xiaoxi >> /var/log/xiaoxi-cloud-backup.log 2>&1
#
# ⚠️ 目的地必须是 rclone crypt 加密 remote（库含生理期/对话等隐私，
#    明文上云不可接受）——配置见 deploy/README.md §7。
# 快照用 Online Backup API（一致性副本），原子替换防半截文件；
# userdata.db + checkpoints.db 都传（后者可恢复对话活上下文）。
# ─────────────────────────────────────────────────────────────
set -eu

DEST="${1:?用法: cloud_backup_server.sh <rclone目的地, 例 xiaoxicrypt:xiaoxi>}"

# 在仓库根执行（找得到 docker-compose.yml）
[ -f docker-compose.yml ] || cd "$(dirname "$0")/.."
[ -f docker-compose.yml ] || { echo "错误：找不到 docker-compose.yml" >&2; exit 1; }

RCLONE_CONF_DIR="${RCLONE_CONF_DIR:-$HOME/.config/rclone}"
[ -f "$RCLONE_CONF_DIR/rclone.conf" ] || {
  echo "错误：找不到 $RCLONE_CONF_DIR/rclone.conf（先 rclone config 配置，见 runbook §7）" >&2
  exit 1
}

# ── 1) 以应用镜像打新鲜快照到中转卷 ────────────────────────
# 容器内 USER xiaoxi 对活库只读权限天然正确；.tmp 写完原子改名，
# 云端永远不会收到半个文件。
docker volume create xiaoxi-stage >/dev/null
docker compose run --rm --no-deps -T \
  -v xiaoxi-stage:/stage \
  --entrypoint python xiaoxi -c '
import sqlite3, os
for n in ("userdata.db", "checkpoints.db"):
    src = sqlite3.connect(f"file:/app/backend/data/{n}?mode=ro", uri=True)
    dst = sqlite3.connect(f"/stage/{n}.tmp")
    with dst:
        src.backup(dst)
    dst.close(); src.close()
    os.replace(f"/stage/{n}.tmp", f"/stage/{n}")
print("snapshot ok")'

# ── 2) rclone 上传（中转卷只读挂载；rclone 容器 root 可读 uid-1000 文件）──
docker run --rm \
  -v xiaoxi-stage:/data:ro \
  -v "$RCLONE_CONF_DIR":/config/rclone:ro \
  rclone/rclone:latest sync /data "$DEST" \
  --config /config/rclone/rclone.conf --quiet

echo "$(date '+%F %T') 云端备份 ok -> $DEST"
