# 部署 runbook（小汐 sonetto）

把本服务从「本机 :8000」搬到「域名 + HTTPS」的最小步骤。所有命令在仓库根执行。

## 0. 上线前安全前置（必读）
- **确认 Gitee 仓库可见性**：`backend/database/migrations.py` 的 v4 迁移种子与 `backend/tests/test_cycle_tracker.py` 含真实生理期日期，已在 git 历史里。若仓库曾/将公开，按已泄露处理（源码占位化 + `git filter-repo` 清历史 + force-push），并**轮换四把密钥**（DeepSeek / 高德 / Tavily / VAPID 私钥）。VAPID 私钥泄露 = 他人可冒充小汐向你设备推通知。
- 在 `backend/config/.env` 设强 `XIAOXI_API_TOKEN`（`python -c "import secrets;print(secrets.token_hex(32))"`）；或留空交给入口脚本自动生成（见 §1）。

## 1. 构建并启动容器
```bash
docker compose up -d --build
docker compose logs -f xiaoxi     # 首次启动看入口打印的 token
```
- 入口脚本 `backend/docker-entrypoint.sh` 逻辑：`.env` 有 token 用它；否则读 `data/.api-token`（持久化，重启不变）；都没有且未开 `XIAOXI_ALLOW_INSECURE` 则**生成一个强随机 token** 写入 `data/.api-token` 并打印一次——把它填进浏览器「设置 → 访问令牌」。
- 这样规避了「容器内 `--host 0.0.0.0` + 无 token → fail-closed 崩溃循环」的旧陷阱。
- 崩溃循环排障：若仍循环，多是入口写不了 `.api-token`（卷权限）→ 改在 `.env` 显式设 `XIAOXI_API_TOKEN`。

## 2. 前置反向代理（HTTPS）
- **Caddy（推荐，自动证书）**：域名解析到服务器后，复制 `deploy/Caddyfile.example` 改域名，`caddy run --config Caddyfile`。证书自动申请/续签。
- **Nginx**：复制 `deploy/nginx.example.conf` 改域名与证书路径；已为 SSE 关闭 `proxy_buffering`。
- 反代后 `docker-compose.yml` 端口**保持** `127.0.0.1:8000`（不对外直暴露）。
- 用 Nginx 且要按真实 IP 限流：`.env` 设 `TRUST_PROXY=true`（详见 nginx 示例末尾注记）；Caddy 同理（默认透传 XFF 时再开）。

## 3. .env 收尾
- `CORS_ORIGINS=https://你的域名`（精确域名，勿用 `*`）。
- 可选 `BACKUP_SCRUB_SENSITIVE=true`：让应用内置备份剔除生理期 + 对话档案（分享/演示前更安心）。

## 4. 验证
- `curl -I https://你的域名` → 见有效证书 + `Strict-Transport-Security`。
- 浏览器打开能正常聊天；`/api/health` 返回 ok。
- **SW 版本过渡人工复核**（现 v7，CACHE_NAME 见 web/public/sw.js）：改一张立绘重新部署后，硬刷新页面两次，确认老缓存被 stale-while-revalidate 更新到位（此过渡难以全自动覆盖）。

## 5. 数据与备份
- 数据在 named volume `xiaoxi-data`（即 `userdata.db` + `checkpoints.db`）。可 `docker compose down` 不丢数据；要导出：`docker cp xiaoxi:/app/backend/data ./backup`。
- 应用自身每 2h 用 SQLite Online Backup API 备份 **userdata.db** 到 `data/backups/`（保留 5 份，淘汰时同删 -wal/-shm 兄弟文件；开 BACKUP_SCRUB_SENSITIVE 时脱敏 + VACUUM 清页）；清空记忆的 JSON 备份也保留 5 份。
- **checkpoints.db（LangGraph 对话活上下文）不在备份范围**——已知缺口：用备份恢复后会话上下文从头开始，记忆/档案/日历本身不受影响。如需更长窗口见体检报告 REL-03。
- **迁移 v9 紧急回退**（仅「回代码不回库」时需要：把带墓碑机制的新代码退回旧版，而库已升到 v9）：
  ```bash
  sqlite3 userdata.db "DROP INDEX idx_events_dedupe; \
    CREATE UNIQUE INDEX idx_events_dedupe ON events(dedupe_key) \
    WHERE dedupe_key IS NOT NULL AND status = 'active';"
  ```
  正常升级/回滚整个镜像无需此步（代码与迁移始终同批上线）。

## 6. 存量数据迁移（把本地记忆库搬上服务器）
- 一条命令（本机 Git Bash，仓库根）：
  ```bash
  scripts/upload_data_to_server.sh user@服务器IP [服务器仓库目录,缺省~/xiao-xi] [本地数据目录,缺省backend/data]
  ```
- 流程：本地 Online Backup API 一致性快照 → scp 加密上传 → 停容器 → **以应用用户 xiaoxi 身份**复制进 `xiaoxi-data` 卷（属主天然正确）→ 启动（若库 schema 落后于代码，迁移自动升级，幂等）→ 清临时目录。`.api-token` 不受影响。
- 搬快照而非当前库：第三参指向快照目录即可，如 `E:/xiaoxi-pre-handoff-backup-20260807`。
- 反向（服务器 → 本地留底）手工做：`ssh 服务器 "docker cp xiaoxi:/app/backend/data -" > data.tar` 或先 `scp`。

## 7. 云端备份（异地第二层）
应用内每 2h 快照轮换 5 份是**第一层**（防误删/库损坏）；但它和主库在同一块卷上，服务器没了就全没了。云端备份是**第二层**：

1. 服务器装 rclone：`curl https://rclone.org/install.sh | sudo bash`
2. `rclone config`：先配一个云存储 remote（OSS/COS/WebDAV/OneDrive 任选），再建一个 **crypt 加密 overlay remote**（例名 `xiaoxicrypt`）指向它——**库含生理期/对话隐私，明文上云不可接受，必须走 crypt**。首次运行 `rclone/rclone` 镜像会拉取镜像（~几十 MB）。
3. 挂定时任务（库仅几百 KB，间隔可以很密）：
   ```
   */30 * * * * cd ~/xiao-xi && scripts/cloud_backup_server.sh xiaoxicrypt:xiaoxi >> /var/log/xiaoxi-cloud-backup.log 2>&1
   ```
   脚本流程：以应用镜像打 Online Backup API 新鲜快照（原子替换，云端永不出现半截文件）→ rclone sync 上传 userdata.db + checkpoints.db。
4. **从云端恢复**（服务器重建/卷丢失后）：
   ```bash
   mkdir -p /tmp/xiaoxi-restore && chmod 700 /tmp/xiaoxi-restore
   docker run --rm -v /tmp/xiaoxi-restore:/dst -v "$HOME/.config/rclone":/config/rclone:ro \
     rclone/rclone:latest copy xiaoxicrypt:xiaoxi /dst --config /config/rclone/rclone.conf
   cd ~/xiao-xi && docker compose stop xiaoxi
   docker compose run --rm --no-deps -v /tmp/xiaoxi-restore:/src:ro --entrypoint sh xiaoxi -c \
     'cp /src/userdata.db /app/backend/data/userdata.db && \
      cp /src/checkpoints.db /app/backend/data/checkpoints.db && \
      rm -f /app/backend/data/*.db-wal /app/backend/data/*.db-shm'
   docker compose start xiaoxi && rm -rf /tmp/xiaoxi-restore
   ```
   （若服务器代码比快照新，启动时迁移自动升级；.api-token 不受影响。）

### OSS/COS 配置要点（目的地已定：阿里云 OSS 或腾讯云 COS）
- **bucket 建私有读写**（切勿公共读），与服务器**同地域**——内网传输免流量费且快。
- 用 RAM/CAM **子账号** AccessKey，只授权该 bucket 的读写（不用主账号 key；泄露时损失限于这个桶）。
- `rclone config`：阿里云选 provider `Alibaba`（填 endpoint 如 `oss-cn-shenzhen.aliyuncs.com`），腾讯云选 `TencentCOS`；配好命名如 `ossbox`。
- 再加 crypt overlay：`rclone config` → crypt → remote 填 `ossbox:xiaoxi-backup` → 设密码 → 命名 `xiaoxicrypt`。cron 目的地就用 `xiaoxicrypt:xiaoxi`。

## 容器加固说明
`docker-compose.yml` 已 `cap_drop: [ALL]` + `no-new-privileges`（Python web 无需任何 capability，收敛逃逸面）。资源上限 `mem_limit/cpus` 默认**注释**——请按 `docker stats` 实测 RSS 后填，过低会 OOMKill 致中断，故不替你用拍脑袋的数字。
