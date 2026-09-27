# PROGRESS.md —— 批次 2.5 执行进度记录

## 1. 模型资产下载记录

- **模型文件**: `app/src/main/assets/models/bge-small-zh/model_quantized.onnx`
  - **来源 URL**: `https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_quantized.onnx`
  - **实际字节数**: 24,010,842 字节（约 22.9 MB）
  - **校验结果**: 通过（> 15MB 阈值，INT8 量化模型）
  - **Git 状态**: `.gitignore` 规则排除 `*.onnx`，不入库

- **Tokenizer 配置文件**: `app/src/main/assets/models/bge-small-zh/tokenizer.json`
  - **来源 URL**: `https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json`
  - **实际字节数**: 439,125 字节（约 428.8 KB）
  - **校验结果**: 通过（> 1KB 阈值，包含 21,128 词表与 tokenizer 配置）
  - **Git 状态**: 纳入版本库

## 2. ONNX Runtime 依赖引入

- **依赖项**: `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`（以及 host 单测 `com.microsoft.onnxruntime:onnxruntime:1.19.2`）
- **选型说明**: 选用 1.x 官方最新稳定版，包含 Android 平台（arm64-v8a, armeabi-v7a, x86_64）原生推导支持与 Java/Kotlin 绑定层。
- **验证结果**: Gradle 依赖解析正常，全量 533 单测通过。

## 3. Tokenizer 调研与选型

- **选型结果**: 纯 Kotlin 实现 BERT WordPiece 分词器 (`BertWordPieceTokenizer`)
- **选型理由**:
  1. **零第三方依赖约束**：硬约束第 4 条严格禁止引入除 onnxruntime（与 sqlite-vec）外的依赖。DJL tokenizers (`ai.djl.huggingface:tokenizers`) 会引入多套 JNI/Rust 本地库与额外 jar，增大 APK 体积并带来 Android ABI 兼容性风险。
  2. **100% 可测性与全平台一致性**：纯 Kotlin 实现无任何原生动态链接库依赖，在 Android 设备运行时与 JVM / Robolectric 宿主单测环境下表现完全一致。
  3. **架构与性能极致轻量**：bge-small-zh-v1.5 基于标准 BERT 架构与 21,128 词表。纯 Kotlin 解析 `tokenizer.json` 耗时 <100ms，短文本分词耗时 <0.1ms，完美满足端侧热节流与低开销诉求。
- **功能覆盖**:
  - `cleanText` 规整与控制字符过滤
  - `handle_chinese_chars: true` CJK 中文字符逐字隔离拆分
  - 标点符号与空白拆分 (Basic Tokenization)
  - WordPiece 贪婪最大匹配与 `##` 子词拆分，未登录词退化为 `[UNK]`
  - `[CLS]=101` 与 `[SEP]=102` 首尾标记拼接，定长截断
- **验证结果**: 词表提取（21,128 tokens）、中英混排、截断、特殊标记、确定性单测全部通过。

## 4. EmbeddingModel 封装与单测验证

- **核心实现**: `LiricaEmbeddingModel` (实现 `EmbeddingModel` 契约)
- **技术要点**:
  1. `OrtSession.SessionOptions().setIntraOpNumThreads(2)`：严格限制 ONNX 推理线程数为 2，契合移动端热节流防降频硬指标。
  2. 流水线：`BertWordPieceTokenizer` 分词 → 构造 `input_ids`, `attention_mask`, `token_type_ids` int64 张量 → ONNX 推理输出 `last_hidden_state` [1, seqLen, 512] → 依据 `attention_mask` 进行 Mean Pooling 聚合 → L2 单位向量归一化。
  3. 单位向量模长归一化：将 512 维向量投影到单位超球面上，使得后续余弦相似度计算等价于向量点积，将检索计算量降至最低。
- **单测断言验证结果**:
  - **维度断言**: 准确输出 512 维 FloatArray
  - **确定性断言**: 同一文本输入两次产生完全一致的浮点向量（误差 < 1e-6）
  - **非零与单位模长断言**: 向量有效非零，L2 范数精确为 1.0 (±1e-3)
  - **相对相似度断言**:
    - `sim("喜欢周杰伦", "爱听周董的歌") = 0.6678`
    - `sim("喜欢周杰伦", "今天天气不错") = 0.3099`
    - 验证通过，语义强相关相比无关样本具有显著区分度 (> 0.35 余弦差距)
  - **推理延迟估算**: 平均单句推导耗时约 1.8ms/条（Robolectric / JVM 估算，待真机，远低于 200ms 上限）



