# 08 · 部署与运维体检

> 板块状态：0/20 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 依据：deploy/README.md runbook + docker-compose.yml + Dockerfile

- [ ] **8.1 镜像构建**
      · 查：docker build 成功
      · 取证：`docker compose build` 看退出码（本机有 Docker 时；无则核对 Dockerfile 阶段合理性）
      · 判据：构建成功、多阶段合理（Python 3.12-slim）
      · 严重度：P0
      · 结果：
- [ ] **8.2 compose 回环绑定**
      · 查：ports 为 127.0.0.1:8000:8000（非 0.0.0.0）
      · 取证：读 docker-compose.yml
      · 判据：回环绑定
      · 严重度：P0
      · 结果：
- [ ] **8.3 容器加固**
      · 查：cap_drop ALL + no-new-privileges
      · 取证：读 docker-compose.yml security 段
      · 判据：两项都在
      · 严重度：P1
      · 结果：
- [ ] **8.4 入口脚本**
      · 查：docker-entrypoint.sh token 逻辑（.env → data/.api-token → 生成）
      · 取证：读 backend/docker-entrypoint.sh 三分支
      · 判据：与 runbook §1 描述一致
      · 严重度：P1
      · 结果：
- [ ] **8.5 卷持久化**
      · 查：xiaoxi-data 卷挂载 /app/backend/data
      · 取证：读 compose volumes 段
      · 判据：数据卷挂载正确、重启不丢
      · 严重度：P0
      · 结果：
- [ ] **8.6 healthcheck**
      · 查：compose healthcheck 配置（间隔/超时/重试）
      · 取证：读 compose healthcheck 段
      · 判据：参数合理、探 /api/health
      · 严重度：P1
      · 结果：
- [ ] **8.7 Caddyfile 示例**
      · 查：Caddyfile.example 正确（域名占位/反代/HSTS/SSE 不压缩）
      · 取证：读 deploy/Caddyfile.example
      · 判据：配置正确、注释清晰
      · 严重度：P1
      · 结果：
- [ ] **8.8 nginx 示例**
      · 查：nginx.example.conf 正确（SSE proxy_buffering off、XFF）
      · 取证：读 deploy/nginx.example.conf
      · 判据：SSE 关缓冲、XFF 传递正确
      · 严重度：P1
      · 结果：
- [ ] **8.9 启动脚本**
      · 查：start.bat/start.sh 逻辑正确（建 venv/装依赖/起服务）
      · 取证：读两个脚本
      · 判据：路径/命令正确、无过时引用
      · 严重度：P2
      · 结果：
- [ ] **8.10 环境变量清单**
      · 查：.env.example 与 settings.py 字段一一对应（无缺漏/无幽灵）
      · 取证：对照 settings.py 全部字段 vs .env.example
      · 判据：全字段有样例
      · 严重度：P1
      · 结果：
- [ ] **8.11 日志可观测**
      · 查：容器 stdout 日志（docker logs）含关键事件、无 GBK 乱码墙
      · 取证：docker logs 样本观察（部署后）
      · 判据：日志可读、关键事件有记录
      · 严重度：P2
      · 结果：
- [ ] **8.12 重启恢复**
      · 查：restart: unless-stopped + 崩溃自愈
      · 取证：读 compose restart 段
      · 判据：策略正确
      · 严重度：P1
      · 结果：
- [ ] **8.13 容器时区**
      · 查：容器 TZ=Asia/Hong_Kong（HKT 一致）
      · 取证：读 Dockerfile/compose 的 TZ 配置
      · 判据：TZ 正确（与 6.16 一致）
      · 严重度：P1
      · 结果：
- [ ] **8.14 启动迁移**
      · 查：容器启动时 lifespan 执行迁移（到 v19）
      · 取证：代码核验 server.py lifespan 迁移调用；部署日志确认「迁移到 schema v19」
      · 判据：启动即迁移、幂等
      · 严重度：P1
      · 结果：
- [ ] **8.15 静态资源托管**
      · 查：dist 构建产物被打进镜像并由 FastAPI 托管
      · 取证：读 Dockerfile 拷贝 dist 与挂载逻辑；交叉 5.2
      · 判据：构建产物入镜像、托管正确
      · 严重度：P1
      · 结果：
- [ ] **8.16 runbook 一致性**
      · 查：deploy/README.md 与实际文件/命令一致（无死命令）
      · 取证：逐条核对 runbook 的路径与文件名
      · 判据：无过时引用（如旧文件名）
      · 严重度：P2
      · 结果：
- [ ] **8.17 版本一致性**
      · 查：version.py 与 README 版本号一致
      · 取证：`cat backend/version.py` vs README 版本标注
      · 判据：一致
      · 严重度：P2
      · 结果：
- [ ] **8.18 SW 版本**
      · 查：sw.js CACHE_NAME 版本与部署节奏匹配（交叉 5.19）
      · 取证：读 web/public/sw.js
      · 判据：版本号明确
      · 严重度：P2
      · 结果：
- [ ] **8.19 上线前置清单**
      · 查：域名解析/DNS/证书申请路径清晰
      · 取证：核对 runbook §2 步骤完整性
      · 判据：步骤可执行（域名替换点明确）
      · 严重度：P1
      · 结果：
- [ ] **8.20 监控与告警**
      · 查：至少 healthcheck 探活 + 日志轮转有兜底（LOG_FILE 10MB×7）
      · 取证：代码核验 logger RotatingFileHandler 配置
      · 判据：落盘时轮转正确
      · 严重度：P2
      · 结果：
