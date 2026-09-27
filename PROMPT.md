# PROMPT.md —— Ralph 长任务发射文件（批次 3 · 失败叙述接口）

> 本文件是 Ralph Loop 每轮迭代读取的任务指令。唯一规格来源：`docs/Lirica_PRD_产品规格与执行计划_v2.0_2026-09-27.md`（下称 PRD）。
> 批次 1/2/2.5 已验收通过（当前基线测试 562 全绿）。批次 2 已就位 `honestReason` 基建（`GatekeeperDecision.PhysicalBlocked.honestReason`、`TextInputResult.Rejected`、`GestureExecutor.lastHonestReason`）。

## 任务

执行 **PRD §7 批次 3 · 失败叙述接口（D5 诚实失败原则）**，把"执行失败的坦白"从字段变成通道：

1. **话术模板层**：新建 `automation/healing/HonestNarrator.kt`——输入失败上下文（规则 rule、技术原因 technicalReason、目标 App/动作类型），输出**人设化的第一人称坦白文案**。要求：
   - 内置话术模板库（至少 6 类场景：金融禁止区、通信代发、密码框、系统提权、接地失败/元素找不到、自愈耗尽）
   - 每类 2-3 条变体，随机选取避免机械重复；语气克制、无奈、不卖惨，符合 PRD §1 D5 原话"这种无奈反而更有活人感"
   - 话术为**中性占位版**（末尾注明：正式话术库待俊凯过目定稿），模板结构化（占位符如 {appName}）便于后续替换
2. **Watchdog 接线**：`SelfHealingWatchdogImpl` 判定 Unrecoverable 后，调用 HonestNarrator 生成坦白文案，通过现有结果模型透传（`TaskExecutionResult` / `TaskExecutionState.Failed` 增加可选 `honestNarrative: String?` 字段）
3. **Gatekeeper 拒绝路径接入**：`HelixGuiCoordinatorImpl` 中现有的"零信任高危护栏物理熔断"失败分支，把 `honestReason` 升级为经 HonestNarrator 渲染的完整坦白文案（保留原始 rule 字段供日志）
4. **日志纪律**：technicalReason/rule 进日志，honestNarrative 进用户可见输出；**extraInfo（含用户输入明文）绝不进日志**

## 硬约束（违反任何一条立即停止并报告）

1. 只允许修改/新建：`automation/healing/HonestNarrator.kt`（新建）、`automation/healing/HealingModels.kt`、`automation/healing/SelfHealingWatchdog.kt`、`automation/coordinator/TaskExecutionModels.kt`、`automation/coordinator/HelixGuiCoordinator.kt`（仅失败分支改造）及对应测试；顺带修复批次 2 备注：删除 `ProhibitedZones.kt` 中 `com.tencent.wepay` 幻觉包名行
2. 禁止修改：`perception/**`、`proactivity/**`、`emotion/**`、`persona/**`、`memory/**`、`network/**`、PRD、`docs/**`、`FinancialGatekeeper.kt` 的判定逻辑（只允许透传其 honestReason）
3. 禁止 `git push`、`git reset`、`git checkout`、删除文件；禁止引入新依赖；禁止修改 `AndroidManifest.xml`
4. 每完成一个子任务单独 `git add <文件> && git commit -m "feat: 批次3-x <说明>"`（不 push）

## 迭代循环

1. 按 1→4 顺序执行；每完成一步在本文档末尾「执行记录」追加一行
2. 每完成一步跑 `./gradlew testDebugUnitTest`，必须全绿（当前基线 562，新增后应增长）
3. 新增行为必须有单测：HonestNarrator 每类场景至少 1 条（断言非空、含占位符填充、不同调用返回可能不同的变体机制）、Unrecoverable→坦白文案的接线测试、extraInfo 不进日志的断言
4. 测试失败 → 修复重跑，同一问题最多尝试 3 次，仍失败记录并跳过

## 完成承诺

四个子任务全部完成、全量测试全绿、自检通过后，在最终输出中单独一行输出：`RALPH_COMPLETE`

## 执行记录

（Ralph 迭代时在此追加）
