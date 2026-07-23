# 小汐 数据与隐私说明

> 小汐是单用户私人陪伴服务。这份文档说清楚：她记住了什么、存在哪里、
> 备份里有什么、分享/演示前该做什么。（对应审查报告 P3-3）

## 一、她记住了什么（全部在 `backend/data/userdata.db`）

| 表 | 敏感度 | 内容 |
|----|--------|------|
| `memories` | 中 | 小汐主动记下的关于你的事（喜好、去过的地方、重要的事） |
| `diaries` | 中 | 小汐写的日记 |
| `life_state` | 低 | 当前心情与日常事件 |
| `proactive_messages` | 低 | 主动消息历史（早安、随机搭话、关心） |
| **`cycle_records`** | **高** | **生理期记录与备注** |
| `push_subscriptions` | 中 | 浏览器推送订阅凭证（endpoint + 密钥） |

> **GPS 坐标不落库**：前端上传的坐标只存在进程内存（`location_store`，
> 8 小时 TTL），服务重启即消失，不进数据库、不进备份。浏览器侧另存
> 30 分钟缓存于 localStorage（仅本机浏览器可见）。

对话内容（checkpoint）在 `backend/data/checkpoints.db`。

## 二、备份里有什么

- 心跳每 2 小时自动备份到 `backend/data/backups/`，保留最近 5 份。
- 备份是**整库快照**——默认包含上表全部内容，**含 `cycle_records`**。
- 备份目录权限自动收紧为 700/600（Linux/macOS 生效；Windows 依赖 NTFS 权限）。

### 备份脱敏选项（v0.4.5 新增）

如果你会把 `backups/` 同步到网盘、复制到别的机器，或可能让他人接触：

```ini
# backend/config/.env
BACKUP_SCRUB_SENSITIVE=true
```

开启后每份新备份生成时会剔除 `cycle_records`（生理期数据不进备份）。
代价：无法从备份恢复该表。已存在的旧备份不受影响，需手动删除。

## 三、分享/演示/送人之前

如果你要把这个项目展示给朋友，或把数据库/备份发给别人：

1. **不要直接发 `userdata.db` 或 `backups/*.db`**——里面有全部隐私数据。
2. 演示用小汐的"空状态"即可：删掉 `backend/data/` 目录重启，她会从零开始陪你。
3. 如果一定要带数据演示，先用脱敏备份，并手动清空 `memories` / `diaries` /
   `proactive_messages` 中不想展示的内容。
4. `.env` 里的 API Key 与 VAPID 私钥**永远不要**随项目发出；
   `.gitignore` 已排除 `.env`，发压缩包前再检查一遍。

## 四、记忆导出与清空

- 设置面板 / `GET /api/memories/export`：导出记忆 JSON（**不含**生理期与推送凭证）。
- `POST /api/memories/clear?confirm=true`：清空记忆前自动备份。
- 彻底删除生理期记录：目前需直接操作数据库
  （`DELETE FROM cycle_records;`），后续版本会加入面板入口。

## 五、公网部署的隐私底线

见 README「安全部署须知」。一句话：**没有 `XIAOXI_API_TOKEN` + HTTPS，
不要把小汐放到公网上**——否则任何人都能读到上面所有数据。
v0.4.5 起，无 token 绑定非回环地址会**直接拒绝启动**。
