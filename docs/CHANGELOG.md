# CHANGELOG

## 2026-07-29 · 小汐的日历（第四面板）+ 地图双引擎 + 前夜提醒

设计文档：`docs/superpowers/specs/2026-07-29-calendar-and-maps-design.md`
视觉基准：`docs/mockups/calendar-panel-v2.html`（本体取色，已验收）

**新增**
- **日历面板**：顶栏第四入口（2+2 分组），手写月历 42 格，事件 pill 三色语义
  （✦琥珀=小汐记的 / 疗愈绿=冷冷记的 / 陶土玫瑰=很重要）；日期详情 + 记/改
  事件两级 sheet；删除两次确认；只读纪念日；多天行程逐格延续；相似合并提示。
  冷冷与小汐**一本账**：UI（source=user）与 LLM 工具共享 dedupe_key 天然互挡；
  「聊完即刷」——小汐在对话里记下的日程即时出现在日历上。
- **事件墓碑机制（迁移 v9）**：软删事件（status=deleted）继续占 dedupe 索引，
  挡住 LLM 重新提取/检测器再生成——「删了就是删了」；归档（翻篇）语义不变，
  明年/下次照常再生成。同标题同日期重加 = 复活。
- **REST `/api/events`**：GET 月范围（白名单 + archived 回看）/ POST 创建
  （409 相似提示）/ POST {id} 更新 / DELETE 软删；只读型 403；写后刷新提示词缓存。
- **LLM 工具** +`update_event` / `delete_event`；`create_event` 补墓碑归因文案
  （「这件冷冷删掉过，小汐不偷偷记回来」）。
- **前夜提醒**：每日 21:00 HKT，明日有日程才出声（白名单过滤，台风走聊天
  关心路径不双份），12h 防复读，LLM 挂了固定文案兜底，走统一推送流水线；
  早安问候同步注入今日日程（标了很重要的认真说一次）。
- **地图双引擎**：`_pick_engine` 坐标几何判定——内地高德 / 港澳海外谷歌
  （港澳排除框上界≈深圳河一线，深圳归高德）；谷歌走 Places (New) searchText，
  key 经请求头（脱敏零负担），`_GoogleKeyMissing` 与高德文案严格分离；
  两引擎输出归一化同构渲染；路线规划谷歌侧婉拒（Directions 记为将来项）。
  `GOOGLE_MAPS_API_KEY` 留空 = 优雅降级，无 key 零调用零账单。

**收敛（第二轮体检 round2 🟠/🟡）**
- `RoomScene` / `SkyBackground` / `CharacterSprite` React.memo：消掉流式聊天
  逐 token 的全树空转；SkyBackground 自持时段钟（memo 后天色仍按时段切，
  顺带修「天空无自时钟」🟡-5）。
- `usePush` 未读 bail-out：60s 轮询内容不变不换引用，不再逼全树重渲染。
- 全局 `:focus-visible` 疗愈绿基线 + `prefers-reduced-motion` 静止化。
- 主包 gzip 76→81KB（+5KB 日历，Markdown 独立块不变）。

**文档**
- README：API 表 + 日历四端点；环境变量表补齐（DOC-03）；文档/脚本索引补全
  （DOC-02/04）；密钥轮换四→五。
- deploy/README：五密钥同步 + 迁移 v9 紧急回退 SQL 备注。

**验证**
- pytest 336→393 全绿（两轮零 flaky）：v9 谓词/墓碑挡重建/force 复活/
  路由 17 例/提醒 4 态/地图双引擎 13 例/联动剧本 A/B 半边。
- E2E 28/29（唯一失败项为已知空库种子依赖，非缺陷）：新增日历剧本 5 断言
  （dialog 挂载 / 详情 sheet / 创建→pill / 删除墓碑 / Esc 两级）全过，
  零控制台错误、零 API 4xx/5xx。
- 临时副本冒烟（:8001）验证迁移 v8→v9 在线升级；生产库零触碰。
