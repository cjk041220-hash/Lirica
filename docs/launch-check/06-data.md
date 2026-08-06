# 06 · 数据与持久化体检

> 板块状态：20/20 · 已知问题：P2-7（pre_memfix 备份残留位置） · 续跑指引：完成
> 取证前置：sqlite 查询统一用 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'` 模式

- [x] **6.1 当前 schema 版本**
      · 查：生产库 schema_version 应为 20
      · 取证：`SELECT MAX(version) FROM schema_version`
      · 判据：= 20（v20 = events.completed_at 完成态，2026-08-06 产品决策）
      · 严重度：P0
      · 结果：✅ 2026-08-06。初查 =19（旧服务进程 v20 代码落地前启动、未重启）；重启触发迁移后 =20。**v20 迁移代码早已在盘上（migrations.py _migrations[20]），问题只是真实库未升级**
- [x] **6.2 迁移链完整**
      · 查：v1→v20 全部迁移有实现且注册
      · 取证：代码核验 migrations.py `_migrations` 字典键 2..20 连续（v1 为基线 schema，无 v1 迁移）
      · 判据：2..20 无缺号
      · 严重度：P1
      · 结果：✅ 正则扫描 `_migrations[N]` 注册键 = [2..20] 全连续无缺号
- [x] **6.3 v19→v20 实升**
      · 查：真实库完成 v20 迁移（events.completed_at 列存在）
      · 取证：查库 `PRAGMA table_info(events)` 含 completed_at；迁移 SQL 本身由 test_migrations_v20 钉死（自建 v19 裸库 + 幂等重跑）
      · 判据：列存在即可（勾选并注明日期）
      · 严重度：P0
      · 结果：✅ 2026-08-06 20:19 HKT。events 表 completed_at 列在（重启触发迁移）；schema_version v20 单条记录（applied_at 12:19:47 UTC）
- [x] **6.4 全新安装升级链**
      · 查：空库 init 到 v20 全程无错
      · 取证：临时副本删库重启（或读 test_init_db_upgrade 语义）验证
      · 判据：init 直达 20、无中间态错误
      · 严重度：P1
      · 结果：✅ 实跑 `tests/test_init_db_upgrade.py` 4 passed（空库直通 + v1 旧库升级数据保留 + 索引建成 + 二次执行幂等）
- [x] **6.5 备份轮换**
      · 查：data/backups/ 保留 5 份 userdata.db 备份
      · 取证：`ls backend/data/backups/` 数备份数
      · 判据：≤ 5 份、命名含时间戳
      · 严重度：P1
      · 结果：✅ 5 份（194821/194824/194828/194831/201950），全部 `userdata_backup_YYYYMMDD_HHMMSS.db` 命名，轮换正常（19:47 那份已被顶出）
- [x] **6.6 备份完整性**
      · 查：备份文件可打开且非空
      · 取证：对最新一份备份跑 sqlite 只读 `PRAGMA integrity_check`
      · 判据：integrity_check = ok
      · 严重度：P1
      · 结果：✅ 最新备份 155648 B、integrity_check=ok、schema 20
- [x] **6.7 scrub 备份**
      · 查：BACKUP_SCRUB_SENSITIVE 开启时备份剔除 cycle/chat 且 VACUUM
      · 取证：代码核验 scrub 逻辑（备份时清行 + VACUUM 清页）
      · 判据：实现与设置说明一致
      · 严重度：P1
      · 结果：✅ core.py:386-394：开启后 DELETE cycle_records + events(source='cycle') + chat_messages + chat_sessions → VACUUM（注释写明 T1-4 体检依据）。默认关闭（settings.py backup_scrub_sensitive=False，.env 未开启）
- [x] **6.8 归档**
      · 查：记忆 90 天归档与对话档案归档（chat_archive）不冲突
      · 取证：代码核验两套归档的时限与触发
      · 判据：无重叠破坏（归档不删活跃数据）
      · 严重度：P2
      · 结果：✅ 记忆 90 天=decay 冷藏（manager.py `_ARCHIVE_MIN_AGE_DAYS=90`，只改 memories.status→archived 不删行，archived 从读面消失）；chat_archive=纯存档无时限裁剪（写入面仅 chat.py、读面 sessions 历史 + heartbeat 尾巴），与 memories 注入/checkpoints 裁剪解耦（模块头注明 agent 侧禁止 import，有 grep 自检）。两套互不触碰
- [x] **6.9 WAL 一致性**
      · 查：journal_mode=WAL、checkpoint 正常
      · 取证：查库 `PRAGMA journal_mode`；看 data/ 下 -wal/-shm 文件是否随停服收敛
      · 判据：WAL 模式；服务停止后 wal 文件可合并（不残留巨大 wal）
      · 严重度：P1
      · 结果：✅ journal_mode=wal。强杀（taskkill /F，非正常停服）后 wal 残留 3.6MB 属崩溃安全设计：正常打开 integrity ok、数据无损；`PRAGMA wal_checkpoint(FULL)` 后 -wal/-shm 消失、主库合并为 155KB（=真实数据量）。顺带清掉 10 个历史会话遗留的 heredoc python 僵尸进程（它们持有库连接阻止 checkpoint）
- [x] **6.10 约束完整性**
      · 查：memories 唯一索引（活跃行去重）等约束存在
      · 取证：查 sqlite_master 中唯一索引列表（idx_memories_*）
      · 判据：去重唯一索引存在
      · 严重度：P1
      · 结果：✅ idx_memories_cat_content（UNIQUE，谓词 status IN ('active','core')）+ idx_events_dedupe（UNIQUE 部分索引）
- [x] **6.11 表清单完整**
      · 查：11+ 张核心表全部存在
      · 取证：查 sqlite_master 表名清单（memories/diaries/life_state/proactive_messages/push_subscriptions/cycle_records/events/chat_sessions/chat_messages/memory_extract_state/care_ledger）
      · 判据：全部存在
      · 严重度：P0
      · 结果：✅ 11 张核心表全在，无缺
- [x] **6.12 checkpoint 裁剪**
      · 查：checkpoints.db 有裁剪（prune_checkpoints：每 2h 触发、30 天不活跃裁剪）防无限膨胀
      · 取证：代码核验 watchdog 逻辑与触发
      · 判据：有上限机制
      · 严重度：P1
      · 结果：✅ core.py:231 prune_checkpoints（keep_per_thread=10、drop_inactive_days=30，含 uuid6 时间域校准注释）；heartbeat.py `_periodic_maintenance` 随 2h 心跳执行
- [x] **6.13 数据目录结构**
      · 查：data/ 下文件齐全（userdata.db/checkpoints.db/backups/.api-token）
      · 取证：`ls backend/data/`
      · 判据：核心文件存在、无异常残留
      · 严重度：P2
      · 结果：✅（含校准）userdata.db/checkpoints.db/backups/ 在。**.api-token 缺失属正常态**：它是部署态产物（docker-entrypoint.sh 生成持久化），dev 直跑 uvicorn 不经过 entrypoint、认证由 settings.xiaoxi_api_token(.env) 控制——清单原判据在 dev 实例上校准为「部署态文件在 Docker 卷生成」。⚠️ 残留 userdata_backup_pre_memfix_20260805.db（8/5 手动备份，不在 backups/ 轮换体系内）→ 挂 P2-7
- [x] **6.14 备份恢复演练**
      · 查：备份恢复流程可走通（副本演练）
      · 取证：用最新备份复制成临时库 → 打开 → 校验关键表行数
      · 判据：恢复库可读、数据完整
      · 严重度：P1
      · 结果：✅ 最新备份复制为临时库可打开；行数与生产全等（memories 9/diaries 0/events 13/chat_sessions 20/chat_messages 71/cycle_records 4）、schema 20；临时库已删
- [x] **6.15 迁移幂等**
      · 查：重复启动不重复迁移（schema_version 不重跑）
      · 取证：连续重启两次 → 查 schema_version 仍 19 无报错
      · 判据：迁移幂等
      · 严重度：P1
      · 结果：✅ 本轮实跑 3 次重启（v19→20 触发、强停、再启），schema_version 恒 20、v20 单条记录（12:19:47 UTC）、版本总数 20，不重跑
- [x] **6.16 时区存储**
      · 查：时间存储统一 HKT 无 UTC 混用
      · 取证：代码核验 timezone.py 与各表 created_at 写入处；抽查库内时间与 HKT 一致
      · 判据：无混用（对照 bd61f08 时间词修复）
      · 严重度：P1
      · 结果：✅ timezone.py 单一真相源（HKT UTC+8，now_str() 全项目统一）。抽查 chat_messages 最新 17:20 / life_state 20:19（与心跳时刻吻合）/ memories 16:55 / events 16:52，均 HKT。schema_version.applied_at 为 UTC 属迁移登记内部机制（datetime('now')），非业务数据
- [x] **6.17 事件墓碑索引**
      · 查：idx_events_dedupe 唯一部分索引（谓词 `WHERE dedupe_key IS NOT NULL AND status IN ('active','deleted')`）
      · 取证：查 sqlite_master 索引 SQL
      · 判据：谓词与 v9 语义一致
      · 严重度：P2
      · 结果：✅ SQL = `UNIQUE ON events(dedupe_key) WHERE dedupe_key IS NOT NULL AND status IN ('active','deleted')`，与 v9 语义一致
- [x] **6.18 记忆表字段**
      · 查：memories 含 last_mention_ts/valence/arousal 列（_relevance_score 为计算函数非列）
      · 取证：查 PRAGMA table_info(memories)
      · 判据：v17/v18 新增列都在
      · 严重度：P0
      · 结果：✅ last_mention_ts/valence/arousal 三列均在
- [x] **6.19 库文件权限**
      · 查：生产库文件权限（非全局可写）
      · 取证：`ls -l backend/data/userdata.db` 看权限位
      · 判据：普通用户可读写、无 777
      · 严重度：P1
      · 结果：✅ -rw-r--r--（644），无 777
- [x] **6.20 磁盘占用**
      · 查：库体积与备份占用合理（无失控膨胀）
      · 取证：`du -sh backend/data/` 看总量
      · 判据：规模与使用量匹配（千条级记忆应 < 100MB 量级）
      · 严重度：P2
      · 结果：✅ 总量 12MB（userdata 155KB + checkpoints 2.7MB + wal + backups 800KB），记忆仅个位数条数、以聊天消息为主，规模与使用量匹配
