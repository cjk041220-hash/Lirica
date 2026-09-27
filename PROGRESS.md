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


