# PROMPT.md —— Ralph 长任务发射文件（批次 1 · 文档修复）

> 本文件是 Ralph Loop 每轮迭代读取的任务指令。唯一规格来源：`docs/Lirica_PRD_产品规格与执行计划_v2.0_2026-09-27.md`（下称 PRD）。

## 任务

执行 **PRD §7 批次 1 · 文档修复** 的全部 10 条任务：修复 `技术架构路线图.md` 的 8 处失实（逐条清单见 PRD §7 批次 1，含精确行号与改法）、修正 `README.md` 的 BYOK 失实行与 M3.6 字样移除、`.gitignore` 补充图片文件规则。

## 硬约束（违反任何一条立即停止并报告）

1. 只允许修改三个文件：`技术架构路线图.md`、`README.md`、`.gitignore`
2. 禁止 `git push`、禁止 `git reset`、禁止 `git checkout`、禁止删除任何文件
3. 禁止修改 PRD 本身与 `docs/` 下任何其他文件、禁止修改任何 `.kt/.kts/.xml` 代码文件
4. 每完成一个子任务（或每 3 条任务）单独 `git add <文件> && git commit -m "docs: 批次1-x <说明>"`（不 push）

## 迭代循环

1. 按 PRD §7 批次 1 清单顺序执行，每条改完在本文档末尾的「执行记录」追加一行（编号/改了什么/验证方式）
2. 每 3 条跑一次 `./gradlew testDebugUnitTest`，必须 516+ 全绿（文档改动不应影响测试，跑一遍兜底）
3. 测试失败 → 修复重跑，同一问题最多尝试 3 次，仍失败则记录到执行记录并跳过
4. 全部 10 条完成后：跑最后一遍全量测试，对照 PRD 清单逐条自检，更新下方执行记录，输出总结

## 完成承诺

全部任务完成且测试全绿、自检通过后，在最终输出中单独一行输出：`RALPH_COMPLETE`

## 执行记录

- [x] 1. 路线图 NodeExtractor 段改为真实机制（深度≤4/节点≤64/文本≤128），删除“四级塌缩/同质前3/压缩85%” | 验证：grep 校验关键词归零且行文与 NodeExtractor.kt 一致
- [x] 2. 路线图 :330 标注正态分布微抖与减速阻尼为【规划中，未实现】 | 验证：全文核对 BezierTrajectoryCalculator 说明符合代码现实
- [x] 3. 路线图 :332-333 改“重试上限为参数（默认 3）；250ms 为截屏节流间隔（ScreenCaptureManager）” | 验证：对照 SelfHealingWatchdogImpl 与 ScreenCaptureManager 源码确认一致
- [x] 4. 路线图 :303-317 三级沙箱表格标注【设计目标，未实现——白名单配置待 M4.1】 | 验证：说明与表格标题均明确标注未实现待 M4.1
- [x] 5. 路线图 :195 删“剪贴板敏感口令” | 验证：grep 校验路线图中“剪贴板”归零，符合 PrivacyBreaker 实际实现
- [x] 6. 路线图 :253 改“24h 周期 + 充电 + 设备空闲约束触发（WorkManager）” | 验证：grep 校验路线图中“凌晨 3:00”归零，与 SubconsciousDreamWorker 周期约束一致
- [x] 7. 路线图 :461 M4.0 改【待启动】；里程碑补 M2.4/M2.5 | 验证：路线图里程碑与实际完成的 M2.4/M2.5 验收记录及待启动 M4.0 状态一致
- [x] 8. 路线图 :349 Claude 模型名更新为现役版本；修正三处模块归属（DiaryEngine、ProactiveBubbleCoordinator、MoodDebugPanel）；“3D PAD”改为“2D Valence-Arousal” | 验证：grep 校验 PAD 归零且模型与模块路径全部与源码对齐
