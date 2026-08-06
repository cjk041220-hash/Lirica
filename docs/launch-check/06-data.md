# 06 · 数据与持久化体检

> 板块状态：0/20 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 取证前置：sqlite 查询统一用 `backend/.venv/Scripts/python - backend/data/userdata.db <<'PY'` 模式

- [ ] **6.1 当前 schema 版本**
      · 查：生产库 schema_version 应为 20
      · 取证：`SELECT MAX(version) FROM schema_version`
      · 判据：= 20（v20 = events.completed_at 完成态，2026-08-06 产品决策）
      · 严重度：P0
      · 结果：
- [ ] **6.2 迁移链完整**
      · 查：v1→v20 全部迁移有实现且注册
      · 取证：代码核验 migrations.py `_migrations` 字典键 2..20 连续（v1 为基线 schema，无 v1 迁移）
      · 判据：2..20 无缺号
      · 严重度：P1
      · 结果：
- [ ] **6.3 v19→v20 实升**
      · 查：真实库完成 v20 迁移（events.completed_at 列存在）
      · 取证：查库 `PRAGMA table_info(events)` 含 completed_at；迁移 SQL 本身由 test_migrations_v20 钉死（自建 v19 裸库 + 幂等重跑）
      · 判据：列存在即可（勾选并注明日期）
      · 严重度：P0
      · 结果：
- [ ] **6.4 全新安装升级链**
      · 查：空库 init 到 v20 全程无错
      · 取证：临时副本删库重启（或读 test_init_db_upgrade 语义）验证
      · 判据：init 直达 20、无中间态错误
      · 严重度：P1
      · 结果：
- [ ] **6.5 备份轮换**
      · 查：data/backups/ 保留 5 份 userdata.db 备份
      · 取证：`ls backend/data/backups/` 数备份数
      · 判据：≤ 5 份、命名含时间戳
      · 严重度：P1
      · 结果：
- [ ] **6.6 备份完整性**
      · 查：备份文件可打开且非空
      · 取证：对最新一份备份跑 sqlite 只读 `PRAGMA integrity_check`
      · 判据：integrity_check = ok
      · 严重度：P1
      · 结果：
- [ ] **6.7 scrub 备份**
      · 查：BACKUP_SCRUB_SENSITIVE 开启时备份剔除 cycle/chat 且 VACUUM
      · 取证：代码核验 scrub 逻辑（备份时清行 + VACUUM 清页）
      · 判据：实现与设置说明一致
      · 严重度：P1
      · 结果：
- [ ] **6.8 归档**
      · 查：记忆 90 天归档与对话档案归档（chat_archive）不冲突
      · 取证：代码核验两套归档的时限与触发
      · 判据：无重叠破坏（归档不删活跃数据）
      · 严重度：P2
      · 结果：
- [ ] **6.9 WAL 一致性**
      · 查：journal_mode=WAL、checkpoint 正常
      · 取证：查库 `PRAGMA journal_mode`；看 data/ 下 -wal/-shm 文件是否随停服收敛
      · 判据：WAL 模式；服务停止后 wal 文件可合并（不残留巨大 wal）
      · 严重度：P1
      · 结果：
- [ ] **6.10 约束完整性**
      · 查：memories 唯一索引（活跃行去重）等约束存在
      · 取证：查 sqlite_master 中唯一索引列表（idx_memories_*）
      · 判据：去重唯一索引存在
      · 严重度：P1
      · 结果：
- [ ] **6.11 表清单完整**
      · 查：11+ 张核心表全部存在
      · 取证：查 sqlite_master 表名清单（memories/diaries/life_state/proactive_messages/push_subscriptions/cycle_records/events/chat_sessions/chat_messages/memory_extract_state/care_ledger）
      · 判据：全部存在
      · 严重度：P0
      · 结果：
- [ ] **6.12 checkpoint 裁剪**
      · 查：checkpoints.db 有裁剪（prune_checkpoints：每 2h 触发、30 天不活跃裁剪）防无限膨胀
      · 取证：代码核验 watchdog 逻辑与触发
      · 判据：有上限机制
      · 严重度：P1
      · 结果：
- [ ] **6.13 数据目录结构**
      · 查：data/ 下文件齐全（userdata.db/checkpoints.db/backups/.api-token）
      · 取证：`ls backend/data/`
      · 判据：核心文件存在、无异常残留
      · 严重度：P2
      · 结果：
- [ ] **6.14 备份恢复演练**
      · 查：备份恢复流程可走通（副本演练）
      · 取证：用最新备份复制成临时库 → 打开 → 校验关键表行数
      · 判据：恢复库可读、数据完整
      · 严重度：P1
      · 结果：
- [ ] **6.15 迁移幂等**
      · 查：重复启动不重复迁移（schema_version 不重跑）
      · 取证：连续重启两次 → 查 schema_version 仍 19 无报错
      · 判据：迁移幂等
      · 严重度：P1
      · 结果：
- [ ] **6.16 时区存储**
      · 查：时间存储统一 HKT 无 UTC 混用
      · 取证：代码核验 timezone.py 与各表 created_at 写入处；抽查库内时间与 HKT 一致
      · 判据：无混用（对照 bd61f08 时间词修复）
      · 严重度：P1
      · 结果：
- [ ] **6.17 事件墓碑索引**
      · 查：idx_events_dedupe 唯一部分索引（谓词 `WHERE dedupe_key IS NOT NULL AND status IN ('active','deleted')`）
      · 取证：查 sqlite_master 索引 SQL
      · 判据：谓词与 v9 语义一致
      · 严重度：P2
      · 结果：
- [ ] **6.18 记忆表字段**
      · 查：memories 含 last_mention_ts/valence/arousal 列（_relevance_score 为计算函数非列）
      · 取证：查 PRAGMA table_info(memories)
      · 判据：v17/v18 新增列都在
      · 严重度：P0
      · 结果：
- [ ] **6.19 库文件权限**
      · 查：生产库文件权限（非全局可写）
      · 取证：`ls -l backend/data/userdata.db` 看权限位
      · 判据：普通用户可读写、无 777
      · 严重度：P1
      · 结果：
- [ ] **6.20 磁盘占用**
      · 查：库体积与备份占用合理（无失控膨胀）
      · 取证：`du -sh backend/data/` 看总量
      · 判据：规模与使用量匹配（千条级记忆应 < 100MB 量级）
      · 严重度：P2
      · 结果：
