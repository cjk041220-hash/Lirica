# 小汐服务器管理手册

> 建立:2026-08-09 | 上线日:2026-08-08 | 配套文档:`deploy/README.md`(部署 runbook)
>
> 本文只存**位置与流程,不存密钥值**——所有密钥只存在于服务器 `.env` 与本机凭据管理器,不进仓库。

---

## 1. 资产总览

| 项 | 值 |
|---|---|
| 域名 | `selena0603.xyz`(含 `www`),Porkbun 注册,A 记录 @/www 直连,**未开** Porkbun proxy |
| 服务器 | 阿里云 HK 轻量应用服务器 `47.80.7.80` |
| 配置 | Ubuntu 24.04.2 LTS / 2C / 1.6G RAM + 2G swap(fstab 持久)/ 40G 盘 |
| 代码仓库 | `gitee.com/oscarjk/xiao-xi`(**私有**),服务器克隆在 `~/xiao-xi` |
| 形态 | Docker Compose 单容器 + Caddy 反代 + Let's Encrypt 自动证书 |
| 数据 | named volume `xiaoxi-data`(顶层名,**无项目前缀**)= `userdata.db` + `checkpoints.db` + `backups/` |

## 2. 访问方式

```bash
ssh xiaoxi        # 本机 Git Bash 直接登,别名已配好
```

本机 `~/.ssh/config` 条目:

```
Host xiaoxi
    HostName 47.80.7.80
    User root
    IdentityFile ~/.ssh/xiaoxi_deploy_ed25519
    StrictHostKeyChecking accept-new
```

- 密钥对:本机 `~/.ssh/xiaoxi_deploy_ed25519{,.pub}`;**私钥永不进仓库、永不贴聊天**。
- sshd **已禁密码登录**——密钥丢了进不去。
- **紧急通道**:阿里云控制台 WebShell(不依赖 ssh)。
- 防火墙:ufw 已启用,只放行 22/80/443。

## 3. 服务器上的布局

| 路径 | 内容 |
|---|---|
| `~/xiao-xi` | 仓库克隆(Gitee 拉取凭据在服务器 `~/.netrc`,remote URL 里无 token) |
| `~/xiao-xi/backend/config/.env` | **生产密钥全集**:DeepSeek / 高德 / Tavily / VAPID / `XIAOXI_API_TOKEN` / `CORS_ORIGINS=https://selena0603.xyz` / `TRUST_PROXY=true` / `XIAOXI_FIRST_MEET=2026-08-08` |
| `/etc/caddy/Caddyfile` | 反代配置(HTTPS 自动证书,HTTP→HTTPS 308) |
| volume `xiaoxi-data` | 小汐的全部数据(记忆库 + 对话上下文 + 备份轮换) |
| `data/.api-token`(卷内) | 入口脚本持久化的 API token;`.env` 显式设了 `XIAOXI_API_TOKEN` 时以 .env 为准 |

容器端口只绑 `127.0.0.1:8000`,公网一律走 Caddy。镜像由仓库 `Dockerfile` 多阶段构建(前端在镜像里 build)。

## 4. 日常更新(改完代码上线,最常用)

```bash
# 本机
git add -A && git commit -m "..." && git push origin master

# 服务器
ssh xiaoxi
cd ~/xiao-xi && git pull
docker compose up -d --build
docker compose logs -f xiaoxi     # 看启动干净:有无迁移、token 三分支、心跳启动行
```

- 重建含 npm ci + pip 全装,**首次偏慢**(几分钟;1.6G 内存有 swap 兜底,历史实测不 OOM)。
- **前端 Service Worker 变了**(sw.js 版本号 bump):上线后浏览器硬刷新两次;iPhone「添加到主屏幕」的版本重新打开一次。
- **回退**(无迁移的批次):`git reset --hard <旧commit> && docker compose up -d --build`。代码与迁移始终同批上线;「回代码不回库」的 v9 紧急 SQL 见 runbook §5。

## 5. 健康检查

```bash
# 本机直接探公网
curl https://selena0603.xyz/api/health          # → {"status":"ok"},免 token
curl -I https://selena0603.xyz                  # 看证书 + HSTS

# 服务器侧
docker ps                                        # xiaoxi 应为 Up (healthy)
docker logs xiaoxi --tail 50                     # 心跳/调度器/备份轮换日志
systemctl status caddy                           # 反代
```

## 6. 备份与灾难恢复

**第一层(已生效)**:应用内每 2h Online Backup API 快照 `userdata.db` → 卷内 `backups/`,轮换 5 份。`checkpoints.db`(对话活上下文)**不在备份范围**——恢复后会话上下文从头,记忆/日历不受影响(PRIVACY/runbook 已如实标注)。

**第二层(未配置)**:云端异地备份 `scripts/cloud_backup_server.sh` + rclone(cron 每 30 分钟)。待办:用户建**与服务器同地域**的阿里云 OSS / 腾讯云 COS 私有 bucket + RAM/CAM 子账号 AccessKey,必须走 rclone crypt 加密 remote(约定名 `xiaoxicrypt`)。配置全流程在 runbook §7。

**手工把卷拉回本机留底**:

```bash
ssh xiaoxi "docker cp xiaoxi:/app/backend/data -" > xiaoxi-data-$(date +%Y%m%d).tar
```

**把本地记忆库搬上服务器**(一条命令,本机仓库根):

```bash
scripts/upload_data_to_server.sh user@host [服务器仓库目录,缺省~/xiao-xi] [本地数据目录,缺省backend/data]
```

**整机重建后从云端恢复**:runbook §7 第 4 步。

## 7. 重置「新家」(清空数据重来)

```bash
ssh xiaoxi
cd ~/xiao-xi
docker compose stop
docker run --rm -v xiaoxi-data:/data alpine sh -c \
  'rm -f /data/userdata.db* /data/checkpoints.db* && rm -rf /data/backups'
docker compose up -d
```

- 保留 `data/.api-token` 则访问令牌不变;想换令牌就连它一起删(或 .env 显式设新值)。
- 重启后迁移自动建库;`.env` 的 `XIAOXI_FIRST_MEET` 会重新种入相识日。
- **动手前务必先按 §6 拉一份卷快照**。

## 8. 密钥台账(只记位置,不记值)

| 密钥 | 位置 | 备注 |
|---|---|---|
| `XIAOXI_API_TOKEN` | 服务器 `.env`;浏览器「设置 → 访问令牌」填同一个 | 2026-08-08 上线时新生成 |
| DeepSeek / 高德 / Tavily / VAPID | 服务器 `.env`(上线时复用本机四组,仓库私有故未轮换) | 转公开或泄露疑云时**四把全换** |
| ssh 部署密钥 | 本机 `~/.ssh/xiaoxi_deploy_ed25519` | 丢了走阿里云 WebShell 重新植入 |
| Gitee 拉取凭据(服务器) | 服务器 `~/.netrc` | 换 Gitee 密码后同步 |
| Let's Encrypt 证书 | Caddy 自动申请/续签 | 当前有效期 2026-08-07 ~ **2026-11-05**,续签全自动 |

**仓库转公开前必做**(PRIV-01 备忘):源码占位化(migrations.py v4 种子与 test_cycle_tracker.py 含真实生理期日期)+ `git filter-repo` 清历史 + 轮换上述全部密钥。VAPID 私钥泄露 = 他人可冒充小汐推通知。

## 9. 坑备忘(历史实锤)

1. **Caddy**:Ubuntu apt 装 caddy 会用包内默认配置自动启动;改完 `/etc/caddy/Caddyfile` 必须 `systemctl reload caddy`(`enable --now` 是 no-op)。
2. **DNS 验证**:本机 nslookup 对 Porkbun 旧停放记录(198.18.0.34)有缓存污染,验证解析用 DoH(dns.google/resolve)或服务器直查权威 NS。
3. **构建内存**:1.6G RAM,大构建靠 2G swap 兜底;若日后删了 swap 先加回来再 build。
4. **SW 过渡**:版本号 bump 后旧缓存不会自己干净,硬刷新两次人工复核(runbook §4)。
5. **Windows 构建上下文 CRLF**:Dockerfile 已对 entrypoint 强制转 LF,别删那段。
6. **workers 必须 1**:APScheduler 模块级单例,后端启动时自检,>1 直接拒绝启动。

## 10. 故障速查

| 症状 | 先看 |
|---|---|
| 容器崩溃循环 | 多是入口写不了 `.api-token`(卷权限)→ 在 `.env` 显式设 `XIAOXI_API_TOKEN`(runbook §1) |
| 全站 401 | 浏览器设置页 token 与 `.env` 是否一致;令牌保存即验证(8caa346)后设置页会当场报错 |
| 流式回复整段到达 | 反代缓冲:Caddy 默认无碍;换 Nginx 要 `proxy_buffering off`(runbook §2) |
| 502/页面死 | `docker ps` 看 healthy;`docker logs xiaoxi --tail 100`;`systemctl status caddy` |
| 证书告警 | Caddy 自动续签;`journalctl -u caddy --since -1d` 查 ACME 报错 |
| 账单异常 | DeepSeek 控制台设账单告警(`TOKEN_COST_WARNING` 代码侧尚未实现,属待办) |

## 11. 待办(截至 2026-08-09)

- [ ] 云端备份 rclone 配置(等用户建 OSS/COS 桶 + 子账号 AK)
- [ ] DeepSeek `TOKEN_COST_WARNING` 账单告警(代码未实现)
- [ ] iPhone Safari 真机清单(iOS<17.4 流式 / standalone 推送 / 刘海避让)
- [ ] runbook `deploy/README.md` §4 的「现 v7」字样已过时(实际 sw.js 已 v8)
