# 小汐实战可靠性审计 Brief（新窗口执行）

> 建档：2026-08-13　给一个全新窗口的 Claude 用
> 目标：用户对小汐上线仅 5 天、缺乏长期有效实测有合理不放心。本 brief 让 fresh eyes
> 带着"真实日常使用会怎么坏"的眼睛读代码，产出分级 findings，供修复窗口对照实行。
> 这不是论文/竞品研究（那已完成：`docs/PERSONA_DEEP_DIVE_2026-08-10.md` + 评估报告 +
> `docs/OPTIMIZATION_ROADMAP_2026-08-12.md`）。这是**故障狩猎**。

## 一、审计纪律（必须遵守）

1. **只读审计为主**：findings 附 `file:line`；默认不改代码（修复在另一个窗口做，审计与修复隔离）。
   例外：发现**明确的 P0 级数据损坏/静默失效**且修法一行时，可顺手修并在 findings 标注"已修"。
2. **不碰生产库**：一切实测走临时副本（`:8001` 假 key / 副本 DB）；结束前校验 `backend/data/userdata.db` 与 `checkpoints.db` 的 sha256 前后一致（零污染）。
3. **禁止后台 agent 依赖**：本项目后台 agent 有两次全批失联记录，主窗口直接手做。
4. **验证纪律**：每条 finding 必须能指出触发路径（什么真实用户操作会走到它），不许只报"代码难看"。
5. **情绪倾向判定禁 LLM**：查 mood 逻辑时，确认没有把"情绪倾向"交给 LLM 判定（纪律：确定性可算）。

## 二、输入材料（先读这些建立上下文）

- `backend/agent/soul.py`（BASE_PERSONA 八段 + PERSONA_ANCHOR + INTERACTION_RULES + 关系成长段）
- `backend/agent/graph.py`（pre_model_hook / 记忆注入 / 裁剪 / 三层中间件 / 锚注入）
- `backend/memory/`（manager.py 六因子召回 / narrative.py / extract.py / consolidate.py / profile.py / summarize.py / chat_archive.py）
- `backend/heartbeat.py` + `backend/heartbeat_moods.py`（主动消息系统 + 情绪气候）
- `backend/era.py` + `backend/agent/care.py`（独处时间线 + 异常感知关心）
- `backend/api/routes/`（chat/presence/push/status/sessions/memories/config）
- `web/src/`（App.jsx / useChat.js / usePush.js / usePresenceReport.js / useDeferredMarkRead.js / client.js）
- 文档：`docs/PERSONA_DEEP_DIVE_2026-08-10.md` §8 验证纪律、`docs/SERVER_ADMIN.md` 故障速查

## 三、四维度故障模式清单（按此逐项猎杀）

### A. 记忆系统（最长的链，最容易静默坏）
- [ ] **提取卡死/静默停**：`extract.py` 水位线、死信机制、`_parse_actions` 后门——什么情况下提取会永久不再写入却无告警？
- [ ] **召回偏差**：`manager.py` 六因子里 bigram 话题命中、时近衰减、hit_count 通胀——真实对话里会不会"聊 A 浮现 B"、重要事实被流水挤掉？
- [ ] **session summary（新，2026-08-13 上线）**：`summarize.py` 合格判定会不会误摘/漏摘正在进行的会话？摘要会不会编造对话里没有的内容（幻觉污染后续对话）？`narrative.py` 注入块会不会把"过去对话"误导成"当前状态"？
- [ ] **长对话脉络**：`graph.py:62-83` 裁剪后，跨多天续聊同一会话时上下文会不会断得离谱？era 会话与普通会话切换时记忆承接是否错乱？
- [ ] **并发写**：写锁覆盖是否完整？`get_write_lock` 有没有漏包的多语句写（TOCTOU）？重启时 checkpoint 与 userdata 会不会不同步？
- [ ] **清理**：`delete_session` 级联是否完整（含 session_summaries）？`prune_checkpoints` 会不会误删正在进行的会话？

### B. 情绪/心情模型
- [ ] **mood 漂移合理性**：`heartbeat_moods.py` 五信号 + carryover 叠加，会不会在真实事件序列下把 mood 钉死在某个角落（如连续雨天一直"担心"）？
- [ ] **carryover 误触发**：`_CARRYOVER_SAD_LEXICON` 在真实聊天里会不会命中歧义句（"吃到撑得难受"）？扫尾窗口会不会把旧会话当新情绪？
- [ ] **mood↔文风**：A/B 已证文风句对 Flash 输出 0/4 无可测影响——查一遍所有"mood 应该影响行为"的断言是否都只是自我安慰（无实际消费方）。
- [ ] **mood↔sprite/今日状态**：前端 `App.jsx` moodToSprite/moodToLight 与后端 mood 词表是否同步？未知词会不会导致前端静默挂起/闪旧立绘？
- [ ] **mood-congruent 召回**：`manager.py` 软调制会不会在负面情绪持续时把记忆越收越窄（回音室）？

### C. 人格/拟人度（"露馅"猎杀）
- [ ] **人格锚失效路径**：`_PersonaAnchorMiddleware` 每 8 次注入——什么情况下会跳过/注入位置错乱？冷启动（开机 2h 内无锚）还有没有别的洞？
- [ ] **防注入绕过**：记忆叙事"数据非指令"双层包裹——有没有路径让对话内容/记忆以指令身份进入？
- [ ] **独处时间线露馅**：era 会话——会不会出现"小汐在自言自语时突然谈实时事件/天气（她不该知道）"或"接了不该接的上下文"？三写一推的降级会不会让 era 和普通会话串线？
- [ ] **主动消息复读/突兀**：`heartbeat.py` 历史尾巴 + 防复读 + 时段门——真实一天（2-3 条）会不会撞上"冷冷正在忙/生气"还硬发？概率与冷却有没有兜底？
- [ ] **生气/和解机制**：`soul.py` 生气段 + 逃生阀——多轮对话里会不会"生气状态"被记忆/锚覆盖而突然变温顺（违背人设一致性）？

### D. 部署/真机（未验证的硬缺口）
- [ ] **iOS Safari standalone**（**最硬缺口，从没真机跑过**）：iOS 推送只在"添加到主屏幕"可用；`usePush.js` 权限请求时序、`iosInstallNeeded` 检测、SW 注册、standalone 判定——有没有代码层面就能看出的洞？
- [ ] **iOS<17.4 流式**：`needsXhrStreaming` 回退——XHR 缓冲/断流会不会卡死或漏字？
- [ ] **CSP/PWA 缓存**：`web/public` 的 CSP、SW 版本号、资产哈希——更新后旧缓存会不会让用户看到坏页面？硬刷新 ×2 的机制还能不能更稳？
- [ ] **内地坐标 GCJ-02**：`utils/geo.py` 转换边界（港澳框）——真实 GPS 边界值会不会偏几百米？
- [ ] **推送订阅清理**：失效订阅、取消订阅、`send_push` 失败——会不会累积到"发了永远不送达"或 404 堆积？

## 四、找什么（通用的坏味道信号）

- `except: pass` / `log.warning` 之后无兜底的降级路径（降级本身会坏）
- 超时无兜底（`timeout=120` 但 reader 无字节守护——08-13 已修 useChat，查别的）
- 竞态（检查-使用之间没锁的 TOCTOU）
- 前端/后端状态不同步（life_state 只后端改、前端缓存、push 未读计数）
- 进程内状态在重启/异常下失效（限流桶/GPS 缓存/锚计数器/心跳调度）
- "永远不触发"的路径（写了但没有任何真实调用方 / 条件恒假）
- 重启后不一致（checkpoint vs userdata vs proactive_messages 归属）

## 五、报告模板（产出 `AUDIT_FINDINGS_2026-08-13.md`）

每条 finding：

```markdown
## [P0|P1|P2|Info] 一句话标题
- **位置**：`file:line`
- **触发条件**：真实用户做什么会走到（具体操作序列）
- **影响**：数据损坏/功能静默失效/体验瑕疵/仅理论
- **证据**：代码引用 or 实测复现步骤（副本）
- **建议修法**：1-3 句，指明改哪个文件哪个函数
```

分级：**P0** 数据损坏/不可恢复 / **P1** 功能静默失效（用户无感知地坏了）/ **P2** 体验瑕疵（能感知但不致命）/ **Info** 理论风险或观察项。

## 六、收尾

- 输出 `docs/AUDIT_FINDINGS_2026-08-13.md`（若零 findings 也要写明"猎杀清单全部查过，未发现"）。
- 报告测试基线（全量 pytest + ruff）、生产库 sha256 零污染确认。
- 不 push 不部署（等用户看 findings 拍板修复批）。
