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
