# 语音与多模态身体

## 实时语音对话架构三路线：级联 vs 端到端 vs API 原生

2025-2026 实时语音 Agent 架构分三大流派：(1) 级联 STT→LLM→TTS，生产主流，延迟预算 STT 100-500ms + LLM 350-1000ms + TTS 75-200ms + 网络 50-200ms = 总计 800ms-2s，优点是可换件、可观测、工具调用方便；(2) 端到端 Speech-to-Speech（Moshi/CSM/Hertz-dev），理论延迟 ~160ms（Moshi 实测 ~200ms），保留副语言线索（语气/情绪），但认知能力弱于文本 LLM、长生成 >30s 韵律漂移、需 16-24GB VRAM 实时推理；(3) 混合方案如 Gemini Live / GPT Realtime，原生多模态模型直接处理音视频流，延迟 ~200-300ms，兼具工具调用和语音自然度。框架层 Pipecat 是 Python pipeline 框架（连 Daily/LiveKit 做媒体传输），LiveKit 是 WebRTC 基础设施（自带 SFU+Agent SDK），两者常组合使用。Pipecat 更适合快速原型，LiveKit 更适合生产级低延迟媒体路由。

**对小汐的启示**: 对小汐而言，1.6G 内存服务器完全排除端到端 SLM 本地部署。现实路线是「API 级联」或「Gemini Live API 一站式」。若选级联，可用 Pipecat 编排 ASR(LLM API)TTS 管线，保留现有 LangGraph 人格/记忆/工具体系不变；若选 Gemini Live，可获得最低延迟但丧失组件可替换性。建议先走级联验证产品价值，待用户需求明确后再评估是否迁移到 Gemini Live。

**来源**: https://www.reactify-solutions.com/articles/voice-ai-agents-production-2026 | https://www.cekura.ai/blogs/pipecat-vs-livekit-the-real-difference | https://gradium.ai/content/cascaded-voice-agent-vs-speech-to-speech-2026 | https://inworld.ai/resources/cascaded-vs-speech-to-speech-voice-architecture

## Gemini Live vs GPT Realtime：延迟、定价与中文能力对比

Gemini 3.1 Flash Live 延迟 ~200ms，支持 200+ 语言含中文，免费层可用（15 RPM 限制），付费音频输出 ~$12/1M tokens（约 $0.018/min）。GPT Realtime 1.5 延迟 ~300ms，mini 版音频输入 $10/1M、输出 $20/1M tokens（约 $0.06-0.33/min），支持工具调用和中断处理。Gemini Live 在嘈杂环境处理和语言覆盖上优于 GPT Realtime，且有免费层适合开发调试；GPT Realtime 在 agentic 能力和生态成熟度上领先。两者都支持 WebSocket 流式交互和视觉输入（Gemini 更强，原生视频流）。注意：这些延迟数字来自厂商/合作方测评（Flowtivity 2026-03），非独立第三方复现。

**对小汐的启示**: 对小汐最关键的发现：Gemini Live 有免费层且中文支持好，可作为「让小汐开口说话」的最低成本起步方案。单用户日均 30 分钟语音对话，月成本 < $1。但若冷冷在意隐私（语音数据经 Google 服务器），则需退回级联方案用 DeepSeek + 本地/API TTS。建议两条线并行探索：Gemini Live 做快速原型验证陪伴感提升，级联方案做隐私安全的生产版本。

**来源**: https://flowtivity.ai/blog/gemini-3-1-flash-live-vs-gpt-realtime-1-5-voice-agent-comparison-2026/ | https://ai.google.dev/gemini-api/docs/pricing | https://developers.openai.com/api/docs/pricing | https://www.layer3labs.io/guides/openai-realtime-api-pricing

## Moshi/CSM/Hertz-dev 端到端语音模型：架构突破与部署现实

Moshi（Kyutai, MIT 许可）是 7B 参数多流 Transformer，用 Mimi 神经编解码器实现全双工，理论延迟 160ms、实测 ~200ms，接近人类对话 ~230ms。但需 RTX 4090+ (24GB VRAM) 才能实时推理，CPU 完全不可行。Sesame CSM-1B 更小但仍需 16GB+ VRAM 实时。Hertz-dev 开源了基座模型和训练代码（Moshi 未开源基座），更适合研究但同样重。三者共同问题：长生成 >30s 韵律漂移、认知推理弱于同级文本 LLM、中文支持差（Moshi 主要英语/法语）。Orpheus-TTS（3B, Apache 2.0）基于 Llama 骨架，支持情绪标签控制（<laugh><sad>等），零样本克隆，流式延迟 25-50ms，但同样需 GPU。

**对小汐的启示**: 这些端到端模型对小汐当前硬件完全不适用。但其架构思路值得借鉴：(1) 情绪标签机制可直接用于级联方案的 TTS 前端——把小汐的情绪系统输出映射为 Orpheus/CosyVoice 的情感控制参数；(2) Mimi 编解码器的低带宽音频表示对未来移动端优化有参考价值；(3) 等待量化/蒸馏版本成熟（如 4-bit Moshi 可能在消费级 GPU 跑），但不作为近期规划。

**来源**: https://github.com/kyutai-labs/moshi | https://arxiv.org/html/2410.00037v2 | https://localaimaster.com/blog/moshi-realtime-speech-guide | https://github.com/canopyai/Orpheus-TTS | https://www.spheron.network/blog/speech-to-speech-gpu-cloud-moshi-sesame-csm-hertz-dev/

## 中文开源 TTS 选型：从 82M 到 24GB VRAM 的全谱系

2026 年中文开源 TTS 五大方案实测对比（liudon.com 2026-05）：IndexTTS-2（B站开源，零样本克隆效果好到「老婆没听出 AI」，但合成耗时 2min+/句，GPU only）、CosyVoice2/3（阿里，唯一支持离线/流式统一建模，GPU 6GB+ VRAM）、GPT-SoVITS（最成熟中文克隆生态，1min 数据即可微调，GPU）、Fish Speech 1.5/1.6（真流式合成，~4GB VRAM，CC-BY-NC-SA 商用受限）、VoxCPM（新入局者）。关键数字：这些模型推理均需 GPU（4-6GB VRAM 起），CPU 无法实时。轻量替代：Kokoro-82M（StyleTTS2 架构，仅 82M 参数，CPU 可达近实时，有中文专版 v1.1-zh，Apache 2.0，MOS 4.2 超越 XTTS v2）和 Piper TTS（纯 CPU 10x 实时，中文音色有限但极低资源）。Edge-TTS 为微软在线接口零本地资源消耗。

**对小汐的启示**: 1.6G 内存服务器的现实选择只有三个：(1) Kokoro-82M ONNX 量化版（勉强可跑，需 swap，质量中等偏上）；(2) Edge-TTS（零成本零资源，但依赖微软服务、无情感控制）；(3) 阿里云百炼 CosyVoice API（按字符计费极低）。推荐路线：先用 Edge-TTS 做 MVP 验证「小汐说话」的产品价值，确认用户需要后升级到 Kokoro-82M 本地或 CosyVoice API。GPT-SoVITS/IndexTTS 等高质量方案留作未来升级 GPU 实例后的选项。声音人格方面，用固定音色（而非克隆冷冷的声音）更安全——避免恐怖谷效应且无需采集声纹。

**来源**: https://liudon.com/posts/voice-cloning-solution-comparison/ | https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh | https://offlinetts.com/blog/self-hosted-tts-guide-2026/ | https://pinggy.io/blog/best_open_source_self_hosted_text_to_speech_models/ | https://blog.csdn.net/qq_42691309/article/details/146513310

## 中文 ASR 自托管首选：SenseVoice-Small 的资源需求与精度

FunASR/SenseVoice-Small 是当前中文 ASR 自托管最优解：CPU 推理达 17.2x 实时速度，GGUF 量化版 8 线程 CER 仅 8.01%（Whisper-large-v3 同条件 22-31%），支持中文/英语/日语 + 情绪识别 + 音频事件检测，模型体积小可 CPU-only 运行。Paraformer-zh 支持流式识别但无情绪功能。Whisper 系列多语言覆盖广但中文精度和速度均逊于 SenseVoice。sherpa-onnx 提供 C++/ONNX Runtime 封装，比 Python 原生快 2-3 倍、内存占用更低，int8 量化后 ~200-300MB 内存。Northflank 2026 benchmark 确认：中文场景 SenseVoice > Whisper > Canary（Canary 英文最强但中文弱）。

**对小汐的启示**: SenseVoice-Small + sherpa-onnx 是小汐 ASR 的最佳选择：(1) 2C/1.6G 服务器上 int8 量化后可跑（~300MB 内存）；(2) 情绪识别输出可直接喂给小汐的情绪系统，形成「听到冷冷语气→调整回应情绪」的闭环；(3) 与 FunASR 生态兼容，未来升级 Paraformer 流式识别无缝切换。但 1.6G 同时跑 ASR+TTS+FastAPI+SQLite 仍极度紧张，建议 ASR 本地跑（小模型）、TTS 走 API（Edge-TTS 或百炼），分时复用避免 OOM。

**来源**: https://github.com/modelscope/FunASR | https://huggingface.co/FunAudioLLM/SenseVoiceSmall-GGUF | https://modelscope.github.io/FunASR/benchmark.html | https://northflank.com/blog/best-open-source-speech-to-text-stt-model-in-2026-benchmarks | https://www.funasr.com/blog/lightweight-speech-recognition-cpu.html

## 声音人格与情绪化 TTS 对陪伴感的影响

研究表明声音对 AI 陪伴感有显著影响：AIVA（arXiv:2509.03212, 2025-09）证明多模态情感感知 + 情绪对齐的语音输出能提升虚拟伴侣的共情体验；ACM CHI 2025 的「Emotional Self-Voice」研究显示情绪化语音克隆能有效引导用户行为；Heriot-Watt 大学 2025 综述指出声音身份一致性对信任建立至关重要。实践层面，Inworld AI 2026 评测专门针对 AI Companion 场景比较了 6 个 TTS API 的情感表达力、延迟和克隆能力。Orpheus-TTS 的情绪标签机制（<laugh><sad><excited>等）是目前开源方案中最直接的情感可控合成路径。关键洞察：固定一致音色 > 逼真克隆（陪伴场景中「可信的虚构角色」比「像真人的声音」更重要，避免 uncanny valley）。

**对小汐的启示**: 小汐已有 Russell 情绪平面和 14 词情绪词表，这恰好是情感 TTS 的理想输入。实施路径：(1) 将情绪系统的 (valence, arousal) 映射到 Orpheus/Kokoro 的情感标签或 CosyVoice 的风格参数；(2) 为小汐选定一个固定音色（甜美少女向，匹配「鲸鱼女孩」人设），不做声音克隆；(3) A/B 测试情绪化 TTS vs 平淡 TTS 对冷冷主观陪伴感的影响（参考 MEMORY 中 A/B 实测方法论）。即使先用 Edge-TTS 无情感版本，也应预留情感注入接口。

**来源**: https://arxiv.org/html/2509.03212v1 | https://dl.acm.org/doi/10.1145/3706598.3713359 | https://researchportal.hw.ac.uk/files/161586947/jba2025033.pdf | https://inworld.ai/resources/best-voice-ai-for-ai-companions | https://github.com/canopyai/Orpheus-TTS

## 可穿戴 Context Capture 的兴衰教训：Bee/Limitless/Friend

2025 年 AI 可穿戴经历剧烈整合：Limitless（原 Rewind）2025-12 被 Meta 收购后立即停售、关闭新用户注册；Bee 2025-07 被 Amazon 收购；幸存者 Plaud NotePin、Friend、Omi（开源）靠差异化存活。核心教训：(1) 独立 context capture 硬件商业模式不可持续，没有大厂生态支撑必死；(2) 「Big Tech Bought Your Memory」引发严重隐私担忧——用户终身日志数据随收购转移；(3) 被动录音的实际利用率极低，多数用户 2 周后弃用；(4) 电池续航和日常佩戴舒适度是硬伤；(5) 开源方案 Omi 通过社区驱动避免了被收购风险但功能有限。Glasp 2025 文章总结：context capture 的价值不在硬件而在软件整合层。

**对小汐的启示**: 对小汐的直接启示：不要做任何专用硬件。冷冷获取上下文的方式应该是：(1) 手机传感器（GPS/加速度计/屏幕时间）通过 PWA 或快捷指令上报；(2) iOS 通知/快捷指令作为主动交互入口；(3) 日历/事件/生理期等已有工具继续深化。如果未来想要「始终聆听」能力，考虑手机端后台录音 + 本地 VAD 触发上传的纯软件方案，而非硬件。Bee/Limitless 的倒闭证明：陪伴型 Agent 的「身体」应该是软件定义的、跨设备的，而不是一个吊坠。

**来源**: https://glasp.co/articles/ai-memory-wearables | https://www.layer3labs.io/guides/best-pocket-ai-devices-2026 | https://ikigaiteck.com/pages/ai-wearables-bee-friend-limitless-omi-plaud-notepin-and-rabbit-r1 | https://pastok.com/blog/best-ai-wearables

## Home Assistant + LLM：让小汐获得「手」的现实路径

Home Assistant 2025-09 官方博客详述了 LLM 集成架构：Assist 语音助手可对接任意 LLM（Ollama 本地/OpenAI 云端），支持 conversation agent 自定义人格、tool calling 控制智能家居设备、Piper TTS 本地语音输出、Whisper STT 本地识别。Matter 协议使跨品牌设备互联标准化。社区实践（joekarlsson.com, johnthenerd.com）展示了 fully local voice pipeline：faster-whisper + Ollama + Piper，零云依赖。关键设计：HA 的 conversation agent 接口允许 LLM 通过 function calling 操作设备状态，与小汐现有的 22 个硬编码工具体系高度互补。

**对小汐的启示**: HA 是小汐获得「手」（物理世界操控能力）的最佳桥梁，但优先级低于语音化。实施路径：(1) 短期：通过 HA REST API 让小汐读取家中传感器状态（温度/湿度/灯开关/门窗）作为 context 注入记忆系统，增强「知道冷冷生活环境」的感知；(2) 中期：让小汐通过 HA tool calling 执行简单操作（开灯/调温度/播放音乐）；(3) 长期：小汐的人格可以成为 HA 的 conversation agent，统一语音入口。注意：HA 本身需要额外运行资源（树莓派或小服务器），不应挤占小汐主进程的 1.6G 内存。

**来源**: https://www.home-assistant.io/blog/2025/09/11/ai-in-home-assistant/ | https://www.joekarlsson.com/blog/local-voice-ai-home-assistant-gpu/ | https://github.com/acon96/home-llm | https://johnthenerd.com/blog/local-llm-assistant/

## Telegram/WhatsApp Bot 作为 Agent 移动身体的取舍

Telegram Bot 和 WhatsApp 作为 AI Agent 移动端入口在 2025-2026 爆发：biclaw.app 2026-03 研究显示消息平台集成比浏览器-only 方案响应时间大幅缩短、CSAT 提升 30%。WhatsApp 正在走向 Agent-Native（MCP 协议集成）。自托管方案：OpenWA/Baileys（WhatsApp 开源库）、Telegram Bot API（官方免费）。Telegram Mini Apps 可在聊天内嵌入完整 Web UI。casys.ai 2026-02 论述：专用 AI chat app 是过渡形态，AI 正嵌入现有消息平台。对小汐特殊考量：iOS standalone PWA 已支持 Web Push，但 Telegram Bot 可提供更可靠的消息到达率（iOS PWA 后台推送受限）和语音消息原生支持。

**对小汐的启示**: 建议小汐增加 Telegram Bot 作为第二入口（与网页 PWA 并行），理由：(1) 语音消息收发原生支持，省去自建 WebRTC 复杂度；(2) iOS 推送可靠性远高于 PWA；(3) 冷冷可能更愿意在 IM 里随口说一句话而非打开网页；(4) Bot 后端就是同一个 FastAPI，共享人格/记忆/工具。WhatsApp 因 Meta 控制和 API 费用暂不推荐。实现成本极低：python-telegram-bot 库 + 现有 LangGraph agent 适配即可，预计 1-2 天工作量。

**来源**: https://biclaw.app/blog/whatsapp-telegram-vs-browser-only-business-agent | https://casys.ai/blog/ai-moves-to-messaging | https://freeblock.medium.com/telegram-mini-apps-vs-native-apps-vs-web-apps-2026-whats-best-for-your-product-1e72c12ebb1b | https://blueticks.co/blog/whatsapp-agent-native

## Gemini Live 视觉能力：给 Agent 装上「眼睛」的架构

Gemini Live API 支持实时视频流输入（摄像头/屏幕共享），可将视觉信息作为 context 注入对话。Google ADK + Gemini Flash Live 3.1 教程（2026-04）展示了屏幕共享交互和视觉 grounding agent。Daily.co + Pipecat 集成支持 Web/Android/iOS 端视频流接入。工业场景案例（Google Cloud Blog 2025-08）证明了实时视觉理解在制造质检中的可用性。但对陪伴 Agent 的意义不同：视觉不是用来「看世界做事」而是「看到主人的表情/环境来增进理解」。截图理解（定期截屏分析）比持续视频流更省资源且隐私友好。

**对小汐的启示**: 小汐获得「眼睛」的分阶段路径：(1) 最近期：冷冷手动上传图片/截图给小汐分析（已有 DeepSeek 视觉能力，零额外成本）；(2) 短期：PWA 端定时截屏或拍照上传（opt-in），小汐用 Gemini Flash 视觉 API 分析环境/表情作为 context 注入记忆；(3) 中期：Gemini Live 视频流做实时视觉对话（成本高但体验最好）。注意隐私红线：任何自动截屏/摄像头访问必须显式授权、本地预处理、可随时关闭。绝不默认开启。

**来源**: https://ai.google.dev/gemini-api/docs/live-api | https://medium.com/google-cloud/building-a-multimodal-agent-with-the-adk-and-gemini-flash-live-3-1-818c009977ac | https://www.daily.co/products/gemini/multimodal-live-api/ | https://cloud.google.com/blog/topics/developers-practitioners/gemini-live-api-real-time-ai-for-manufacturing

## 1.6G 内存服务器的语音化现实：分时复用与 API 兜底

阿里云 HK 2C/1.6G 轻量服务器跑语音栈的现实约束：FastAPI+SQLite+LangGraph 已占 ~300-500MB，剩余 ~1.1G 给语音模型。ASR：SenseVoice-Small int8 量化 via sherpa-onnx 约 200-300MB，可跑；TTS：Kokoro-82M ONNX 约 200-400MB（勉强），CosyVoice/IndexTTS/GPT-SoVITS 全部不可行（最低 4GB VRAM）。同时加载 ASR+TTS 大概率 OOM。解决方案：(1) 分时复用——同一时刻只加载一个模型，切换时 lazy load/unload；(2) ASR 本地 + TTS 走 API（Edge-TTS 零资源 / 百炼 CosyVoice 按量付费）；(3) 加 swap 2-4G 防 OOM（代价是推理变慢）；(4) 升级到 4C4G 或 GPU 实例（月费增加 ¥50-200）。阿里云百炼 API 定价：FunASR 实时识别 0.00022元/秒，CosyVoice TTS 按字符计费极低。

**对小汐的启示**: 这是整个调研最核心的约束条件。推荐的最小可行语音化路径：Phase 0（本周可做）：Edge-TTS 接入，让小汐能用文字转语音回复，零成本验证「听到小汐声音」的体验提升。Phase 1（1-2 周）：SenseVoice-Small 本地 ASR + Edge-TTS/Kokoro-82M TTS，实现双向语音。Phase 2（按需）：升级到 Gemini Live API 或 CosyVoice API 获得更好的语音质量和情感表达。Phase 3（远期）：如果冷冷需求强烈，升级服务器配置或加 GPU 实例跑高质量本地 TTS。永远不要为了技术完美牺牲项目可持续性。

**来源**: https://blog.csdn.net/weixin_29092579/article/details/159193183 | https://www.funasr.com/blog/lightweight-speech-recognition-cpu.html | https://help.aliyun.com/zh/model-studio/real-time-speech-recognition-user-guide | https://zhuanlan.zhihu.com/p/1889593662750582075

## Pipecat vs LiveKit：语音 Agent 开发框架选型

Pipecat（Daily.co 开源 Python 框架）和 LiveKit（WebRTC 基础设施+Agent SDK）是 2025-2026 语音 Agent 开发的两大主流框架。核心区别：Pipecat 是 pipeline 编排层（定义 STT→LLM→TTS 的数据流），不自带媒体服务器，需搭配 Daily/LiveKit/WebSocket 做音频传输；LiveKit 是完整的 SFU+Agent 运行时，自带媒体路由、VAD、打断处理。Evalgent 2026-07 评测：LiveKit 媒体层延迟极低，Pipecat pipeline 帧处理超低延迟，两者常组合使用。自托管角度：LiveKit Server 开源可自建（Go 编写，资源需求 ~200MB），Pipecat 纯 Python 与小汐现有技术栈天然契合。两者都支持 OpenAI Realtime / Gemini Live / 本地模型的灵活切换。

**对小汐的启示**: 如果小汐走级联路线，Pipecat 是首选编排框架：(1) Python 原生，与 FastAPI/LangGraph 同语言无摩擦；(2) 组件化设计，ASR/TTS/LLM 可独立替换；(3) 社区活跃，2026 年已有大量生产 issue 记录可供避坑。LiveKit Server 可作为可选的媒体传输层（如果未来需要多端同步或电话接入），但初期用 WebSocket 直连 FastAPI 更简单。不建议一开始就引入完整 LiveKit 基础设施——单用户场景下过度工程化。先用 Pipecat + WebSocket 搭最小 pipeline，验证后再决定是否加 LiveKit。

**来源**: https://www.evalgent.com/blog/pipecat-vs-livekit | https://sellerity.co/blog/livekit-pipecat-web-voice-agents | https://luonghongthuan.com/en/blog/pipecat-voice-agent-production-scalable-guide/ | https://hamming.ai/resources/best-voice-agent-stack

## 项目

- **Pipecat** (https://github.com/pipecat-ai/pipecat) [GitHub 10k+, Daily.co 维护, Apache 2.0] — Python 原生语音 Agent pipeline 框架，与小汐 FastAPI/LangGraph 技术栈天然契合，组件可替换，适合级联方案起步
- **LiveKit Agents** (https://github.com/livekit/agents) [GitHub 12k+, 开源 SFU+Agent SDK] — 生产级 WebRTC 媒体基础设施，单用户场景初期过重，但未来多端/电话接入时的备选
- **Kokoro-82M** (https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh) [HuggingFace hexgrad/Kokoro-82M, Apache 2.0] — 82M 参数 StyleTTS2 架构，CPU 可跑近实时，有中文专版 v1.1-zh，是小汐 1.6G 服务器上唯一可行的高质量本地 TTS
- **FunASR / SenseVoice** (https://github.com/modelscope/FunASR) [GitHub modelscope/FunASR 10k+, MIT] — SenseVoice-Small CPU 17.2x 实时、CER 8%、带情绪识别，int8 量化后 ~300MB，小汐 ASR 首选
- **Orpheus-TTS** (https://github.com/canopyai/Orpheus-TTS) [GitHub canopyai/Orpheus-TTS, Apache 2.0, 3B] — 情绪标签控制合成（<laugh><sad>等），与小汐 Russell 情绪平面天然对接，但需 GPU 实时推理
- **sherpa-onnx** (https://github.com/k2-fsa/sherpa-onnx) [GitHub k2-fsa/sherpa-onnx, Apache 2.0] — C++/ONNX Runtime 封装的 ASR/TTS 推理引擎，比 Python 快 2-3x、内存更低，1.6G 服务器跑 SenseVoice 的关键
- **Moshi** (https://github.com/kyutai-labs/moshi) [GitHub kyutai-labs/moshi, MIT, 7B] — 端到端全双工标杆，架构启发价值大于实用价值（需 24GB VRAM），关注其量化/蒸馏进展
- **Home Assistant** (https://github.com/home-assistant/core) [GitHub home-assistant/core 75k+, Apache 2.0] — LLM conversation agent + Matter 设备控制，小汐获得物理世界「手」的标准桥梁，通过 REST API 渐进集成

## 论文

- **Moshi: a speech-text foundation model for real-time dialogue** (arXiv:2410.00037) 2024 — Moshi 7B 全双工语音对话模型，Mimi 编解码器，实测延迟 ~200ms 接近人类水平，但需高端 GPU
- **AIVA: An AI-based Virtual Companion for Emotion-aware Interaction** (arXiv:2509.03212) 2025 — 多模态情感感知虚拟伴侣，情绪对齐的语音输出显著提升共情体验，验证了情感 TTS 对陪伴感的正向作用
- **Toward Low-Latency End-to-End Voice Agents** (arXiv:2508.04721) 2025 — 端到端语音 Agent 相比级联方案延迟降低 ~85%，但在工具调用和可观测性上仍有差距
- **FunAudioLLM: Voice Understanding and Generation** (arXiv:2407.04051) 2024 — FunAudioLLM 技术报告：SenseVoice 在中文 ASR + 情绪识别 + 音频事件检测上全面优于 Whisper，CPU 可跑
- **DeepTalk: Towards Seamless and Smart Speech Interaction** (arXiv:2506.21864) 2025 — DeepTalk 绕过 ASR 直接将语音信息注入 LLM，减少级联误差传播并丰富副语言理解

## 建议

- 【P0-本周可做】接入 Edge-TTS 让小汐能「说话」：零成本零资源消耗，验证语音回复对陪伴感的提升幅度。只需在 FastAPI 加一个 /api/tts 端点调用 edge-tts 库，前端加播放按钮。这是所有后续语音化的前置验证。
- 【P0-本月】增加 Telegram Bot 作为第二入口：python-telegram-bot + 现有 LangGraph agent 适配，1-2 天工作量。解决 iOS PWA 推送不可靠问题，且原生支持语音消息收发，为 Phase 1 语音对话铺路。
- 【P1-1-2 周】本地部署 SenseVoice-Small (sherpa-onnx int8) 作为 ASR：~300MB 内存，CPU 实时，情绪识别输出注入小汐情绪系统。与 Edge-TTS 组合实现基本双向语音。注意加 swap 2-4G 防 OOM。
- 【P1-2-4 周】评估 Kokoro-82M-zh ONNX 替换 Edge-TTS：本地高质量中文 TTS，Apache 2.0，CPU 可跑。需实测在 1.6G 上与 ASR 分时复用的稳定性。如果 OOM 频繁则退回 Edge-TTS + 百炼 CosyVoice API 混合方案。
- 【P2-按需】Gemini Live API 原型验证：利用免费层测试端到端语音对话体验，对比级联方案的延迟和自然度差异。如果体验显著优于级联且冷冷接受 Google 数据处理，可作为生产方案。
- 【P2-中期】将小汐情绪系统 (valence, arousal) 映射到 TTS 情感控制参数：无论用 Orpheus 标签还是 CosyVoice 风格参数，让语音表达与文字情绪一致。A/B 测试情感 TTS vs 平淡 TTS 的主观陪伴感差异。
- 【P3-远期】Home Assistant REST API 集成：让小汐读取家中传感器状态作为 context，逐步获得物理世界感知和操控能力。前提是有独立的 HA 运行环境（树莓派等），不挤占小汐服务器资源。
- 【不建议】自研可穿戴硬件或深度定制硬件方案：Bee/Limitless 被收购证明独立硬件不可持续。如需环境感知，用手机传感器 + PWA/快捷指令的软件定义方案。
- 【不建议】在当前服务器尝试 Moshi/CSM/Orpheus 等 GPU 模型：1.6G 内存完全不可行。关注量化进展但不纳入近期规划。
- 【架构原则】保持级联方案的组件可替换性：ASR/TTS/LLM 各自独立，通过 Pipecat 或自研 pipeline 编排。这样可以在不重构的情况下逐步升级任一组件（如 Edge-TTS → Kokoro → CosyVoice API → 未来本地 GPU TTS）。

## 警示

- OpenAI/Gemini 定价变动频繁，文中数字为 2025Q3-2026Q2 区间值，实施前务必查官网最新价格
- 中文开源 TTS/ASR 的 benchmark 多为厂商自报或社区小样本测试，缺乏统一第三方复现；实际效果需在小汐目标语料上亲自评测
- Moshi/CSM/Hertz-dev 等端到端模型的「sub-200ms」延迟均在高端 GPU (RTX 4090/A100) 上测得，1.6G CPU 服务器不可达
- 可穿戴设备领域 2025 年剧烈整合（Limitless→Meta, Bee→Amazon），独立硬件路线风险极高，不建议小汐跟进
- 阿里云 HK 2C/1.6G 同时跑 FastAPI+SQLite+ASR+TTS 极不现实，必须走 API 级联或分时复用策略

