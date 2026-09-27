# PROMPT.md —— Ralph 长任务发射文件（批次 2.5 · 记忆向量检索基建）

> 本文件是 Ralph Loop 每轮迭代读取的任务指令。唯一规格来源：`docs/Lirica_PRD_产品规格与执行计划_v2.0_2026-09-27.md`（下称 PRD）。
> 批次 1（文档修复）、批次 2（门控与断言）已验收通过（批次 2 基线测试 533 全绿）。

## 任务

执行 **PRD §7 批次 2.5 · sqlite-vec 基建（D21 记忆端侧向量化）**，含模型资产下载：

1. **模型资产下载**（人工不介入，你自行完成）：
   - 从 `https://hf-mirror.com` 或 `https://huggingface.co` 下载 `Xenova/bge-small-zh-v1.5` 的 `onnx/model_quantized.onnx`（INT8 量化，约 20-30MB）与 `tokenizer.json`
   - 存放至 `app/src/main/assets/models/bge-small-zh/`（目录自建）
   - 下载后校验：文件大小 >15MB（模型）/ >1KB（tokenizer），把实际字节数与来源 URL 记录到 PROGRESS.md
   - 下载连续失败 3 次 → 记录原因与手动下载指引，跳过模型加载部分，先完成 3/4/5（代码与测试用 @Ignore 标注模型相关用例），不阻塞其他工作
2. **ONNX Runtime 依赖**：`com.microsoft.onnxruntime:onnxruntime-android`（1.x 稳定版），仅此一个新依赖
3. **Tokenizer**：调研后二选一——纯 Kotlin 实现 BERT WordPiece（加载 tokenizer.json 的 vocab，几百行，零依赖）或 DJL tokenizers（若确认 Android 兼容）。选型理由写入 PROGRESS.md
4. **EmbeddingModel 封装**：加载模型 → tokenize → 推理 → mean pooling → 输出 `FloatArray`（bge-small-zh-v1.5 维度 512）；推理线程数限 2（移动端热节流）
5. **VectorStore（存储选型授权）**：单用户记忆量级数千条，**优先纯 Kotlin 暴力余弦检索**（零 native 依赖、可测性 100%）；sqlite-vec 仅当你评估 native 集成成本可控时采用，选型理由写入 PROGRESS.md
6. **检索融合接口**：提供 `retrieve(query, topK)` = 向量召回 top-20 → 调用现有 `MemoryScorer` 六维重排 → 返回最终 top-N。只新增融合层，不改动 MemoryScorer 既有实现

## 硬约束（违反任何一条立即停止并报告）

1. 模型只允许从 `hf-mirror.com` 或 `huggingface.co` 下载；禁止下载任何 >200MB 的文件；禁止执行下载到的任何文件
2. 只允许修改/新建：`app/src/main/assets/models/**`（模型资产）、`app/build.gradle.kts`（仅加 onnxruntime 依赖）、新建 `memory/retrieval/**`、`database/memory/**`（仅新增向量存储与融合调用）、`database/XiaoxiDatabase.kt`（仅新增表注册）及对应测试
3. 禁止修改：`MemoryScorer.kt` 既有实现（只读调用）、`proactivity/**`、`emotion/**`、`persona/**`、`automation/**`、`perception/**`、`network/**`、PRD、`docs/**`
4. 禁止 `git push`、`git reset`、`git checkout`、删除文件；禁止引入 onnxruntime 与（若选）sqlite-vec 之外的任何新依赖
5. 模型文件不入 git：确认 `.gitignore` 覆盖 `app/src/main/assets/models/*.onnx`（tokenizer.json 与小文本资产可入库）
6. 每完成一个子任务单独 `git add <文件> && git commit -m "feat: 批次2.5-x <说明>"`（不 push）

## 迭代循环

1. 按 1→6 顺序执行；每完成一步在本文档末尾「执行记录」追加一行（编号/做了什么/验证方式）
2. 每完成一步跑 `./gradlew testDebugUnitTest`，必须全绿（当前基线 533，新增后应增长）
3. 新增行为必须有单测：embedding 确定性（同文本两次输出一致）、维度断言（=512）、非零断言、**相对相似度断言**（sim("喜欢周杰伦","爱听周董的歌") > sim("喜欢周杰伦","今天天气不错")）、VectorStore 检索正确性
4. 测试失败 → 修复重跑，同一问题最多尝试 3 次，仍失败记录并跳过

## 完成承诺

资产下载校验通过（或按规则降级为 @Ignore）、六个子任务全部完成（或按规则跳过）、全量测试全绿、自检通过后，在最终输出中单独一行输出：`RALPH_COMPLETE`

## 执行记录

- 1 / 完成 bge-small-zh-v1.5 模型与 tokenizer.json 资产下载，配置 .gitignore 排除 onnx / 校验文件大小（模型 24,010,842B > 15MB，tokenizer 439,125B > 1KB），全量单元测试通过 (533 passed)
- 2 / 引入 com.microsoft.onnxruntime:onnxruntime-android:1.19.2 稳定版依赖（仅新增此一个依赖）并验证构建与全量单测通过 (533 passed)
- 3 / 完成纯 Kotlin 零依赖 BERT WordPiece 分词器 (BertWordPieceTokenizer) 实现与词表解析，选型理由写入 PROGRESS.md，新增单测全绿 (541 passed)
- 4 / 完成 EmbeddingModel 接口与 LiricaEmbeddingModel 封装（线程数限 2、Mean Pooling、L2 归一化），维度=512、确定性、非零模长、相对相似度 ("周董" 0.6678 > "天气不错" 0.3099)、推理延迟 (<2ms/条) 单测全绿 (548 passed)
- 5 / 完成 MemoryVectorStore（纯 Kotlin 暴力余弦向量检索，5000条向量检索耗时 ~1ms）与 Room memory_embeddings 表注册 (v3 及 MIGRATION_2_3)，选型理由写入 PROGRESS.md，单测全绿 (556 passed)





