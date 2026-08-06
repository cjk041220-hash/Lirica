# 08 · 部署与运维体检

> 板块状态：20/20 ✅ · 发现 4 处问题全部当场修复（P1-2 容器无 TZ；.env.example 缺样例；main.py 过时提示；runbook SW 版本漂移）· 全量 pytest 774 passed 收尾
> 依据：deploy/README.md runbook + docker-compose.yml + Dockerfile
> 取证姿势：本机无 Docker（`docker` 命令不存在），8.1 走清单预留的「核对 Dockerfile 合理性」分支；运行时等效判据走 :8001 净化副本

- [x] **8.1 镜像构建**
      · 查：docker build 成功
      · 取证：`docker compose build` 看退出码（本机有 Docker 时；无则核对 Dockerfile 阶段合理性）
      · 判据：构建成功、多阶段合理（Python 3.12-slim）
      · 严重度：P0
      · 结果：✅（核对分支，本机无 Docker）。三阶段合理：① node:20-slim 构建前端（先 COPY package.json+package-lock.json 再 npm ci，层缓存正确；lock 文件在案 161KB）② python:3.12-slim builder `pip install --prefix=/install -r requirements.txt`（08-05 拆分后 requirements.txt 纯生产依赖，pytest 不进镜像）③ runtime python:3.12-slim：`COPY --from=builder /install /usr/local` 标准做法、后端源码、entrypoint chmod+CRLF 强制转 LF（Windows 构建上下文坑，有注释）、`COPY --from=web /web/dist /app/web/dist`（P1-4 修复点在）、mkdir data + 非 root 用户 xiaoxi + chown、VOLUME、EXPOSE 8000、CMD `--workers 1` 并有 APScheduler 单例注释。`.dockerignore` 核对：排除 backend/data/（数据走卷）、backend/config/.env（密钥不进镜像）、web/node_modules+web/dist（镜像内重建）——均正确，无 P1-4 回归。实机构建留待部署工单（服务器有 Docker 时跑一遍收口）
- [x] **8.2 compose 回环绑定**
      · 查：ports 为 127.0.0.1:8000:8000（非 0.0.0.0）
      · 取证：读 docker-compose.yml
      · 判据：回环绑定
      · 严重度：P0
      · 结果：✅ `"127.0.0.1:8000:8000"`，注释说明公网需前置反代或确认 token 后再放开——与 fail-closed 启动校验（utils/security.py）双保险
- [x] **8.3 容器加固**
      · 查：cap_drop ALL + no-new-privileges
      · 取证：读 docker-compose.yml security 段
      · 判据：两项都在
      · 严重度：P1
      · 结果：✅ `cap_drop: [ALL]` + `security_opt: [no-new-privileges:true]`，有理由注释（Python web 无需任何 capability）。资源上限注释留白并注记「按 docker stats 实测填」，不拍脑袋——合理
- [x] **8.4 入口脚本**
      · 查：docker-entrypoint.sh token 逻辑（.env → data/.api-token → 生成）
      · 取证：读 backend/docker-entrypoint.sh 三分支
      · 判据：与 runbook §1 描述一致
      · 严重度：P1
      · 结果：✅ 三分支与 runbook §1 逐字吻合：`.env` 有 XIAOXI_API_TOKEN 用它 → 否则读 data/.api-token（重启复用，避免换 token 陷 401 死循环）→ 都没有且 XIAOXI_ALLOW_INSECURE≠true 则 `secrets.token_hex(32)` 生成、umask 077 落盘、打印一次引导文案；写盘失败有明确告警指路。`exec "$@"` 交接 PID 1 正确。细节：shell 侧 ALLOW_INSECURE 只认字符串 "true"，pydantic 侧认 true/1/yes——分歧方向是「更保守」（entrypoint 仍生成 token），无害
- [x] **8.5 卷持久化**
      · 查：xiaoxi-data 卷挂载 /app/backend/data
      · 取证：读 compose volumes 段
      · 判据：数据卷挂载正确、重启不丢
      · 严重度：P0
      · 结果：✅ named volume `xiaoxi-data` → /app/backend/data，与 Dockerfile `VOLUME ["/app/backend/data"]`、`mkdir -p data` + chown 对齐（首挂空卷继承镜像内目录属主 xiaoxi，可写）。named volume 在 `docker compose down`（不带 -v）后保留——重启/重建不丢数据；DB_PATH 相对源码解析为 backend/data/，落点正确（交叉 6.x 数据板块）
- [x] **8.6 healthcheck**
      · 查：compose healthcheck 配置（间隔/超时/重试）
      · 取证：读 compose healthcheck 段
      · 判据：参数合理、探 /api/health
      · 严重度：P1
      · 结果：✅ `python -c urllib.request urlopen /api/health`，interval 30s / timeout 5s / start_period 15s / retries 3——参数合理。交叉核验：/api/health 在 auth.py `_PUBLIC_PATHS`（免 token，探针无凭证可过）；health 限流 120/分钟 ≫ 探针 2/分钟，不会自伤；镜像内 `python` 可执行（python:3.12-slim 自带）。P2-8 修复后 HEAD 亦放行，外部监控（UptimeRobot 默认 HEAD）与内部探针各走各的都通
- [x] **8.7 Caddyfile 示例**
      · 查：Caddyfile.example 正确（域名占位/反代/HSTS/SSE 不压缩）
      · 取证：读 deploy/Caddyfile.example
      · 判据：配置正确、注释清晰
      · 严重度：P1
      · 结果：✅ 域名占位 xiaoxi.example.com、reverse_proxy 127.0.0.1:8000、HSTS(max-age=1y+includeSubDomains)+nosniff 头；关键决策有注释：**不默认开 gzip/zstd**，防止压缩中间件缓冲 text/event-stream 破坏打字机效果（与 08-03 部署意向「反代必须不缓冲 SSE」一致）；文件头写明用法（改域名、caddy run / systemd、compose 端口保持回环）
- [x] **8.8 nginx 示例**
      · 查：nginx.example.conf 正确（SSE proxy_buffering off、XFF）
      · 取证：读 deploy/nginx.example.conf
      · 判据：SSE 关缓冲、XFF 传递正确
      · 严重度：P1
      · 结果：✅ `proxy_buffering off` + `proxy_cache off` + `proxy_read_timeout 24h`（SSE 空闲看门狗 300s 的 288 倍余量）、`proxy_http_version 1.1`、Host/X-Real-IP/XFF/X-Forwarded-Proto 四头齐全；TLS1.2/1.3、HSTS 带 always、http2 on。文末注记 TRUST_PROXY=true 与限流 IP 归因的联动（直连必须 false）——与 auth.py `_check_rate` 的 XFF 最右值逻辑对应
- [x] **8.9 启动脚本**
      · 查：start.bat/start.sh 逻辑正确（建 venv/装依赖/起服务）
      · 取证：读两个脚本
      · 判据：路径/命令正确、无过时引用
      · 严重度：P2
      · 结果：✅（顺带修 1 处过时提示）。两脚本对等：建 venv → `pip install -r requirements-dev.txt`（与 08-05 拆分一致）→ config/.env 检查（指路 .env.example）→ 缺 dist 时构建前端（缺 npm/构建失败均 WARN 降级 API-only = 用户已拍板策略）→ 端口占用处理（bat 提示后 taskkill；sh 提示后退出，保守但合理）→ `PYTHONIOENCODING=utf-8`（GBK 坑）→ `main.py`（实测绑 127.0.0.1:8000，fail-closed 回环放行）。引用核对：main.py / requirements-dev.txt / config/.env / web/dist / 「:3000 dev UI」（vite.config.js port=3000 实测吻合）/「xiaoxi 5.0」banner（与 version.py 一致）——全部存活。过时提示 1 处已修：main.py 启动失败引导仍写 `install -r requirements.txt`，拆分后本地装的是 requirements-dev.txt，已改
- [x] **8.10 环境变量清单**
      · 查：.env.example 与 settings.py 字段一一对应（无缺漏/无幽灵）
      · 取证：对照 settings.py 全部字段 vs .env.example
      · 判据：全字段有样例
      · 严重度：P1
      · 结果：✅（补 3 个游离 env 后过）。settings.py 13 字段 vs .env.example 逐项对照：deepseek_api_key/base_url/model、amap_api_key、tavily_api_key、xiaoxi_api_token、trust_proxy、xiaoxi_allow_insecure、backup_scrub_sensitive、vapid×3、cors_origins——**全覆盖、无幽灵键**。但全仓 env 扫除（grep os.environ/getenv）发现 3 个 settings 之外直读的选项原本无文档出口：① LOG_FILE/LOG_LEVEL（utils/logger.py，8.20 的轮转兜底靠它启用，不写样例则部署者无法操作）② XIAOXI_FIRST_MEET（migrations.py v7 相识日种子，幂等单向，README/runbook 均无提及）——已补进 .env.example「可选：运维」段（注释含容器示例路径与幂等语义）。XIAOXI_BIND_HOST/UVICORN_HOST/UVICORN_WORKERS/WEB_CONCURRENCY 属 fail-closed 探测的内部变量（标准 uvicorn 约定），不入样例
- [x] **8.11 日志可观测**
      · 查：容器 stdout 日志（docker logs）含关键事件、无 GBK 乱码墙
      · 取证：docker logs 样本观察（部署后）
      · 判据：日志可读、关键事件有记录
      · 严重度：P2
      · 结果：✅（本机无 Docker，以 :8001 净化副本实跑日志等效取证）。启动全程日志样本核验：关键事件齐全——公网部署自检行（bind/token 掩码显示 chec…00ab/allow_insecure/trust_proxy/cors）、WAL 初始化、记忆加载、23 工具、模型客户端、checkpoint 库、APScheduler 5 job 逐个注册 + Scheduler started、心跳启动、2h 备份落盘、记忆健康多指标遥测（count/提取率/归档占比）、每请求访问行（GET/HEAD/401 全记）。格式统一 `HH:MM:SS [LEVEL] module | msg`，中文无乱码（logger 落 stderr，Linux 容器默认 UTF-8；Windows GBK 由 _GbkSafeStreamHandler 兜底）。细节 1 处（不记账）：唤醒横幅硬编码 `http://127.0.0.1:8000`，非标端口运行时显示与实际不符——纯展示、部署态恒 :8000，不动
- [x] **8.12 重启恢复**
      · 查：restart: unless-stopped + 崩溃自愈
      · 取证：读 compose restart 段
      · 判据：策略正确
      · 严重度：P1
      · 结果：✅ `restart: unless-stopped`——崩溃自动拉起、手动 stop 不复活、宿主机重启后自启，单用户常驻服务的正确策略。配合 entrypoint 的 token 持久化（8.4），重启不会因换 token 陷崩溃循环；heartbeat cron 均带 misfire_grace_time（3600s/600s），重启窗口内错过的任务补跑
- [x] **8.13 容器时区**
      · 查：容器 TZ=Asia/Hong_Kong（HKT 一致）
      · 取证：读 Dockerfile/compose 的 TZ 配置
      · 判据：TZ 正确（与 6.16 一致）
      · 严重度：P1
      · 结果：⚠️→✅ 发现问题当场修。原状：Dockerfile 与 compose 均无 TZ → 容器 UTC。影响面核验（修前）：业务时间无损——全库无裸 `datetime.now()`（grep 零命中），时间存储统一 timezone.py now_str()（6.16 已验），3 个 cron job（早安 8:00/前夜 23:00/日记 22:30）逐个显式 `timezone="Asia/Hong_Kong"`、2 个 interval job 与时区无关；**真实影响**=日志时间戳走系统本地（logger asctime）差 8 小时误导排障 + APScheduler 默认时区 UTC 是未来新增 job 的隐性坑。修：Dockerfile 运行阶段 `apt-get install tzdata + ENV TZ=Asia/Hong_Kong`（glibc 解析 TZ 需系统 zoneinfo，python:3.12-slim 不带 tzdata；PyPI tzdata 只喂 ZoneInfo 不喂 TZ 环境变量），注释写明业务时间已显式 HKT、此举为日志/默认时区收口。本机无 Docker 无法实机构建验证，属配置层修复，部署工单实机构建时顺带确认
- [x] **8.14 启动迁移**
      · 查：容器启动时 lifespan 执行迁移（到 v19）
      · 取证：代码核验 server.py lifespan 迁移调用；部署日志确认「迁移到 schema v19」
      · 判据：启动即迁移、幂等
      · 严重度：P1
      · 结果：✅（清单漂移校准：现行 SCHEMA_VERSION=**20**，非 v19；v19=care_ledger、v20=events.completed_at）。:8001 净化副本双路径实跑：① **空库冷启**（副本不带 data/）→ lifespan `init_db` 逐条执行 v1→v20 全 19 步迁移日志，库核验 schema_version=20 / 13 表；② **存量 v20 库**（副本带生产库拷贝）→ 启动零迁移日志，直接可用——幂等确认。容器等效：Dockerfile CMD 直起 uvicorn → 同一 lifespan 路径，卷挂载保证库跨容器重建留存。零污染：生产 userdata.db/checkpoints.db sha256 前后逐字节一致（a750…bc8f / 6773…ddce），副本已销毁
- [x] **8.15 静态资源托管**
      · 查：dist 构建产物被打进镜像并由 FastAPI 托管
      · 取证：读 Dockerfile 拷贝 dist 与挂载逻辑；交叉 5.2
      · 判据：构建产物入镜像、托管正确
      · 严重度：P1
      · 结果：✅ 双层核验：① 镜像侧——Dockerfile web 阶段 `npm ci + npm run build` 产 dist，`COPY --from=web /web/dist /app/web/dist`；.dockerignore 排除本地 web/dist 强制镜像内重建（不会带进宿主机旧产物）② 托管侧——server.py `app.mount("/", StaticFiles(directory=../web/dist, html=True))`（backend 的相对上级=镜像内 /app/web/dist，路径吻合）；副本实跑 GET / → 200 返回 index.html（含 iOS apple-touch-icon 注释等现行构建特征）。交叉 5.2 已验前端产物本体，此处验的是「入镜像 + 挂载链路」
- [x] **8.16 runbook 一致性**
      · 查：deploy/README.md 与实际文件/命令一致（无死命令）
      · 取证：逐条核对 runbook 的路径与文件名
      · 判据：无过时引用（如旧文件名）
      · 严重度：P2
      · 结果：⚠️→✅（修 1 处漂移）。逐条核对：§0 PRIV-01 引用属实（migrations.py:248-251 v4 种子仍含 2026-04-22/05-20/06-15/07-18 真实日期、test_cycle_tracker.py 40 行日期——仓库私有，警示语「曾/将公开按泄露处理」准确）；「轮换四把密钥」与 settings 密钥面一致（DeepSeek/高德/Tavily/VAPID 私钥）。§1 命令与 entrypoint 三分支逐字吻合（交叉 8.4）。§2 两个反代示例文件路径在案。§3 CORS_ORIGINS/BACKUP_SCRUB_SENSITIVE 均 settings 实字段。§5 备份保留 5 份（core.py `_MAX_BACKUPS=5` 吻合）、JSON 备份轮换（manager.py D-1 逻辑在）、checkpoints.db 不备份为文档化有意设计、v9 回退 SQL 的 idx_events_dedupe 索引名与迁移 v9 吻合。**漂移 1 处已修**：§4「现 v6」→ v7（sw.js CACHE_NAME='xiaoxi-room-v7'，06 板块 bump 后 runbook 未跟上）
- [x] **8.17 版本一致性**
      · 查：version.py 与 README 版本号一致
      · 取证：`cat backend/version.py` vs README 版本标注
      · 判据：一致
      · 严重度：P2
      · 结果：✅ version.py `__version__ = "5.0"`（注释声明唯一定义处）vs README「版本」节 **5.0**——一致；start.bat/start.sh banner「xiaoxi 5.0」亦同步（交叉 8.9）
- [x] **8.18 SW 版本**
      · 查：sw.js CACHE_NAME 版本与部署节奏匹配（交叉 5.19）
      · 取证：读 web/public/sw.js
      · 判据：版本号明确
      · 严重度：P2
      · 结果：✅ `CACHE_NAME='xiaoxi-room-v7'`，头部注释完整记录 bump 理由（06 板块：立绘/图标/清单纳入 install 预缓存，首访防闪图；上一版 v6=字体自托管）；activate 整体退役旧缓存、skipWaiting+clients.claim、预缓存 addAll 失败有 catch 兜底不拖垮 install。runbook §4 的版本引用已在 8.16 同步到 v7
- [x] **8.19 上线前置清单**
      · 查：域名解析/DNS/证书申请路径清晰
      · 取证：核对 runbook §2 步骤完整性
      · 判据：步骤可执行（域名替换点明确）
      · 严重度：P1
      · 结果：✅ §0-§4 全链路可按步执行，域名替换点全部明确：Caddyfile.example 站点地址 1 处（自动证书，替换即跑）；nginx.example.conf server_name + letsencrypt 证书路径 2 处（自备 certbot）；.env `CORS_ORIGINS=https://域名`（§3，精确域名纪律在注释）；验证步 `curl -I https://域名` 看证书 + HSTS。反代后 compose 保持回环的纪律在 §2 明文。域名/服务器本身待用户提供（部署工单主线），runbook 侧无缺口
- [x] **8.20 监控与告警**
      · 查：至少 healthcheck 探活 + 日志轮转有兜底（LOG_FILE 10MB×7）
      · 取证：代码核验 logger RotatingFileHandler 配置
      · 判据：落盘时轮转正确
      · 严重度：P2
      · 结果：✅ 探活：compose healthcheck 30s/5s/15s/3retries 探 /api/health（8.6 已验，免 token + 限流 120/min 不自伤；P2-8 后 HEAD 探针亦通，外部监控可直连）。轮转：utils/logger.py `RotatingFileHandler(maxBytes=10MB, backupCount=7, encoding=utf-8)`，仅 LOG_FILE 设置时启用（默认 stderr → docker logs 即观测面）、落盘失败仅告警不阻断启动、目录自动创建。闭环补全：LOG_FILE/LOG_LEVEL 样例已随 8.10 进 .env.example（容器内指到卷路径即持久化），「兜底」从代码存在变为部署者可操作。告警（TOKEN_COST_WARNING 账单类）属部署工单待办，非本板块判据
