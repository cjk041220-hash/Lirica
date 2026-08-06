# 07 · 安全与隐私体检（公网暴露面档）

> 板块状态：0/26 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 依据：deploy/README.md §0 上线前安全前置 + settings.py 全部开关

- [ ] **7.1 认证覆盖**
      · 查：除 /api/health 外全部 API 强制 Bearer（交叉 1.2）
      · 取证：`curl -s -o /dev/null -w '%{http_code}'` 无 token 逐个打 25 个端点
      · 判据：业务端点全 401、仅 health 200
      · 严重度：P0
      · 结果：
- [ ] **7.2 token 强度**
      · 查：生产 token 为强随机（≥32 hex）
      · 取证：读 `backend/config/.env` 长度探针（只查长度不打印值）：`awk -F= '/^XIAOXI_API_TOKEN=/{print length($2)}' backend/config/.env`
      · 判据：≥32 字符随机值（或入口自动生成逻辑存在）
      · 严重度：P0
      · 结果：
- [ ] **7.3 fail-closed**
      · 查：无 token + 非回环绑定 → 拒绝启动（XIAOXI_ALLOW_INSECURE 未开时）
      · 取证：代码核验启动校验分支（settings.py 注释语义）
      · 判据：fail-closed 逻辑存在且正确
      · 严重度：P0
      · 结果：
- [ ] **7.4 速率限制**
      · 查：API 限流存在（桶参数合理）
      · 取证：代码核验限流实现（桶容量/速率）；无 token 快速打 10 次观察是否触发限流
      · 判据：有限流、参数合理（防暴力）
      · 严重度：P1
      · 结果：
- [ ] **7.5 TRUST_PROXY 纪律**
      · 查：直连部署时 TRUST_PROXY 未开启（防伪造 XFF 绕过限流）
      · 取证：读 .env 配置与部署拓扑
      · 判据：仅反代后开启；直连时关闭
      · 严重度：P0
      · 结果：
- [ ] **7.6 CORS 精确域名**
      · 查：CORS_ORIGINS 为精确域名非 `*`
      · 取证：读 settings 默认值与 .env 覆盖值
      · 判据：公网配置为 https://域名（非 *、非 localhost 残留）
      · 严重度：P0
      · 结果：
- [ ] **7.7 安全响应头**
      · 查：HSTS/Content-Type-Options/X-Frame-Options 等由中间件/反代设置
      · 取证：`curl -sI http://127.0.0.1:8000/` 看响应头；核对 SecurityHeadersMiddleware 与反代 header 配置
      · 判据：关键安全头存在（反代层 HSTS 等）
      · 严重度：P1
      · 结果：
- [ ] **7.8 密钥轮换清单**
      · 查：上线前四把密钥（DeepSeek/高德/Tavily/VAPID 私钥）确认轮换/隔离
      · 取证：核对 deploy/README.md §0 清单与实际 .env 密钥状态（是否存在生产密钥）
      · 判据：有轮换计划或已轮换；无测试密钥残留
      · 严重度：P0
      · 结果：
- [ ] **7.9 仓库敏感数据**
      · 查：git 历史含真实生理期日期（v4 种子/test_cycle_tracker）——按泄露处理决策
      · 取证：`grep -r '2026\|cycle' backend/tests/test_cycle_tracker.py backend/database/migrations.py` 评估；确认仓库可见性
      · 判据：仓库私有（可见性确认）或已按 runbook 处理；决策记录到台账
      · 严重度：P0
      · 结果：
- [ ] **7.10 scrub 开关**
      · 查：BACKUP_SCRUB_SENSITIVE 语义正确（备份剔除 cycle/chat）
      · 取证：代码核验 + 若开启验证备份内容（交叉 6.7）
      · 判据：实现与文档一致
      · 严重度：P1
      · 结果：
- [ ] **7.11 对话不进 checkpoint**
      · 查：对话档案（chat_messages）与记忆不入 checkpoint（防注入纵深）
      · 取证：代码核验 llm_input_messages 注入 vs checkpoint 写入分离
      · 判据：注入读面与 checkpoint 写面分离（graph.py）
      · 严重度：P1
      · 结果：
- [ ] **7.12 记忆防注入**
      · 查：记忆内容为数据非指令（边界标记清晰）（交叉 2.23）
      · 取证：代码核验注入块边界
      · 判据：无指令语义、有边界
      · 严重度：P0
      · 结果：
- [ ] **7.13 WebSocket 拒绝**
      · 查：RejectWebSocketMiddleware 拒绝非预期 WS 连接
      · 取证：`curl -s -o /dev/null -w '%{http_code}'` 尝试 ws 升级 → 预期拒绝；代码核验中间件
      · 判据：WS 被拒、无功能依赖 WS
      · 严重度：P1
      · 结果：
- [ ] **7.14 VAPID 私钥保护**
      · 查：VAPID 私钥只存 .env 不进 git
      · 取证：`git ls-files | grep -i env` 确认 .env 未入库；.gitignore 覆盖
      · 判据：.env 不入库
      · 严重度：P0
      · 结果：
- [ ] **7.15 日志不泄密**
      · 查：token/API key 不出现在日志
      · 取证：grep 日志样本（LOG_FILE 开启时）找 token/key 模式
      · 判据：无密钥类输出
      · 严重度：P1
      · 结果：
- [ ] **7.16 端口暴露面**
      · 查：仅 127.0.0.1:8000 回环绑定（公网由反代入口）
      · 取证：`netstat -ano | grep :8000` 看监听地址；docker-compose ports 配置
      · 判据：回环绑定
      · 严重度：P0
      · 结果：
- [ ] **7.17 HTTPS 终止**
      · 查：反代配置 HTTPS（证书自动/手动）与 HSTS
      · 取证：部署环境验证 `curl -I https://域名` 证书与 HSTS；未部署时核对 Caddyfile/nginx 配置正确性
      · 判据：证书有效 + HSTS 头
      · 严重度：P0
      · 结果：
- [ ] **7.18 请求体上限**
      · 查：超大请求体被拒（防内存耗尽）
      · 取证：代码核验 body 大小限制（中间件/框架配置）
      · 判据：有限制且合理
      · 严重度：P1
      · 结果：
- [ ] **7.19 路径穿越**
      · 查：静态托管无目录穿越
      · 取证：`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8000/../backend/config/.env` 与 `..%2f` 变体
      · 判据：返回 4xx，不泄露文件
      · 严重度：P0
      · 结果：
- [ ] **7.20 错误不泄堆栈**
      · 查：500 响应不含堆栈/Python 路径（交叉 1.13/1.14）
      · 取证：触发错误看响应体；代码核验错误处理器
      · 判据：友好文案、无堆栈
      · 严重度：P1
      · 结果：
- [ ] **7.21 数据出境清单**
      · 查：上传给第三方的内容清单明确（DeepSeek 对话、高德地址、Tavily 查询词）
      · 取证：梳理 providers 上传内容；确认 PRIVACY.md 已披露
      · 判据：文档披露与实际上传一致
      · 严重度：P1
      · 结果：
- [ ] **7.22 PRIVACY 一致性**
      · 查：PRIVACY.md 与实际行为一致（备份/存储/上传）
      · 取证：对照 docs/PRIVACY.md 与实现
      · 判据：无矛盾陈述
      · 严重度：P1
      · 结果：
- [ ] **7.23 比较安全**
      · 查：token 校验使用安全比较（恒定时间）
      · 取证：代码核验 token 比对实现
      · 判据：用 hmac.compare_digest 类安全比较
      · 严重度：P1
      · 结果：
- [ ] **7.24 .gitignore**
      · 查：.env/.api-token/data 不入 git
      · 取证：`cat .gitignore` + `git ls-files` 抽查
      · 判据：敏感路径全被忽略
      · 严重度：P0
      · 结果：
- [ ] **7.25 依赖风险**
      · 查：关键依赖无已知高危（手工核对主要依赖版本）
      · 取证：`pip-audit`（venv 有则跑）；无则核对 requirements.txt 主要包（fastapi/uvicorn/aiosqlite/langgraph）版本
      · 判据：无高危公告或记录豁免理由
      · 严重度：P2
      · 结果：
- [ ] **7.26 认证旁路检查**
      · 查：静态页面/资源不泄露受保护数据
      · 取证：无 token 访问 /、`/assets/*`、`/api/*` 对比
      · 判据：静态可访问但不含业务数据（业务数据全在认证 API 后）
      · 严重度：P1
      · 结果：
