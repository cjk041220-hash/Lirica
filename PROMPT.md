# PROMPT.md —— Ralph 长任务发射文件（批次 2 · 门控与断言）

> 本文件是 Ralph Loop 每轮迭代读取的任务指令。唯一规格来源：`docs/Lirica_PRD_产品规格与执行计划_v2.0_2026-09-27.md`（下称 PRD）。
> 批次 1（文档修复）已于 2026-09-27 验收通过，执行记录见 git 历史 `abceb13..5a98050`。

## 任务

执行 **PRD §7 批次 2 · 门控与断言**，把 PRD §2 D8 禁止区从"感知侧熔断"扩展到"执行侧前置拦截"：

1. **执行前置校验**：`GestureExecutor` 与 `TextInputExecutor` 在执行动作前，将目标包名与控件上下文提交 `FinancialGatekeeper` 判定；命中禁止区的动作直接拒绝，且拒绝结果携带**用户可读的诚实原因**（为批次 3 失败叙述预留字段，本批次先用中性文案如"该操作属于受限范围，已停止"）。
2. **通信代发拦截**：禁止区新增通信类包名集合（至少含 `com.tencent.mm`、`com.tencent.mobileqq`，常量可扩展），对这些包的**文本输入类动作**（ACTION_SET_TEXT / ACTION_PASTE / 文本注入类 GestureSpec）一律拦截；点击类动作不拦（打开 App 看内容属于 D7 第一层合法行为）。
3. **禁止区常量化**：禁止区清单（金融包名黑名单 + 通信包名集合 + 密码框规则）收敛为只读常量/只读资产，不暴露任何设置项（PRD D6）。

## 硬约束（违反任何一条立即停止并报告）

1. 只允许修改：`app/src/main/kotlin/com/xiaoxi/android/automation/gatekeeper/**`、`automation/gesture/GestureExecutor.kt`、`automation/gesture/TextInputExecutor.kt`、`automation/coordinator/HelixGuiCoordinator.kt`（仅允许新增校验调用与结果字段透传，不得改动既有执行逻辑）、`automation/gesture/GestureSpec.kt`（仅允许新增只读元数据字段）及对应 `app/src/test/**` 测试文件
2. 禁止修改：`perception/**`、`proactivity/**`、`emotion/**`、`persona/**`、`memory/**`、`network/**`、PRD 本身、`docs/**`
3. 禁止 `git push`、禁止 `git reset`、禁止 `git checkout`、禁止删除任何文件
4. **禁止引入新第三方依赖**；禁止修改 `AndroidManifest.xml`
5. 每完成一个子任务单独 `git add <文件> && git commit -m "feat: 批次2-x <说明>"`（不 push）

## 迭代循环

1. 按上述 1→2→3 顺序执行；每完成一步在本文档末尾「执行记录」追加一行（编号/改了什么/验证方式）
2. 每完成一步跑 `./gradlew testDebugUnitTest`，必须全绿（当前基线 516，新增测试后总数应增长，0 失败 0 错误 0 跳过）
3. 测试失败 → 修复重跑，同一问题最多尝试 3 次，仍失败则记录并跳到下一步
4. 新增行为必须有对应单元测试：禁止区命中拒绝用例（至少覆盖：金融包名点击、通信包名文本输入、密码框输入、非禁止区放行）各至少 1 条

## 完成承诺

三个子任务全部完成、全量测试全绿、自检通过后，在最终输出中单独一行输出：`RALPH_COMPLETE`

## 执行记录

（Ralph 迭代时在此追加）
