# 开源与社区「反 AI 味」角色人格工程

> 日期：2026-08-30　定位：小汐（中文陪伴型 agent）人格工程调研 · 社区实践侧
> 产出对象：架构师。本文聚焦「开源与社区视角下如何让 LLM 说人话、不像机器复读」，与 dim-01（学术侧）互补。
> 连续性扫读：dim-01.md、PERSONA_DEEP_DIVE_2026-08-10.md（§2/§9）、`backend/agent/soul.py`、`backend/heartbeat.py` 已读。
> **已有覆盖、本文只一行提及**：SillyTavern Lorebook 激活机制、角色卡 V3 装饰器（@@activate_only_after 等）、Letta 记忆块、情绪并行通道——均已在他文件覆盖，本文不再展开。

---

## 0. 研究方法与一手性标注（重要）

本调研环境限制：WebFetch / WebSearch / DuckDuckGo / HuggingFace / rentry.co 被网络策略拦截；**GitHub API + raw.githubusercontent + arXiv + Bilibili 搜索 API 可直连**。因此：

- 「**已抓取一手**」= 本调研用 curl 直接抓到了原文/源码/官方 JSON，可复核。
- 「**社区惯例**」= 广泛流传但受网络限制未抓到原文，来源以代表性 URL 标注，供主编在可访问环境复核。

已抓取一手清单（详情见各来源行）：
- SillyTavern 官方文档 5 页：characterdesign / Author's-Note / context-template / advancedformatting / prompts index（含 Post-History Instructions）。
- SillyTavern 官方自带 8 份采样/系统提示 preset JSON + `public/scripts/openai.js`（OpenAI 兼容通道的采样参数接线，含 DeepSeek 特判）。
- arXiv 五篇采样器论文标题/摘要：min-p (2407.01082)、DRY (2608.22761)、CTRL repetition penalty (1909.05858)、nucleus top-p (1904.09751)、Mirostat (2007.06030)、typical (2202.00666)。
- GitHub 三个「反 slop」工程：hardikpandya/stop-slop（英文）、pencil20388-eng/stop-slop-zh（中文，含完整禁用语/标点/结构清单与推荐口语库）、walidboulanouar/anti-ai-slop（342 个禁用词 + 136 个禁用短语的机器可读 patterns.json）。
- Wikipedia「Slop (artificial intelligence)」词条。
- Bilibili 搜索 API（验证「陪聊 / AI 人设」话题在中文社区确实流通；正文未抓）。

未抓到、仅作社区惯例标注的：rentry.co 上的 Ali:Chat / kingbri 卡片写作指南、Trappu 指南、CharacterHub 卡面 tag 惯例、知乎/小红书调教文。

---

## 1. SillyTavern 采样器控制面：人说人话 vs 机器复读

### 1.1 各采样杆的实证定位（以「反复读/反模板化」为主线）

**温度 temperature**：logit 除以 temperature 再做 softmax。温度越低越接近 argmax——是「机器复读与客服腔」的第一来源。Holtzman 等的「Neural Text Degeneration」(1904.09751) 证明：纯最大化解码（即温度→0）会陷入**神经退化**——人类文本的熵曲线随位置变化，而 argmax 解码在低熵区（模板/复述区）过度自信，产生重复回路。min-p 论文 (2407.01082) 进一步点名：**高温度**下 top-p 等固定截断会「incoherent or repetitive」，因为温度拉平了概率分布、固定阈值误判。实证结论：温度单杆无法同时满足「多样性」与「连贯」，必须配合截断/惩罚杆。

**top_p（nucleus）**：只从「累计概率达 p 的最小 token 集」里采样；切断低概率长尾噪声。对「少讲怪话」有效，但对"句级复读"无力——复读是逐 token 概率都很高造成的，截断拦不住高概率 token 的自我循环。

**top_k**：固定取前 k 个 token。k 过小 → 保守复读（小汐侧反向可参考）；k 过大 ≈ 不截断。社区 RP 通常把它和 top_p 一起关掉（设 0）或用 min-p 取代。

**min-p（一手：2407.01082，Kellermann 2024）**：动态截断——阈值 = 当前 top1 概率 × min_p，凡低于该阈值的 token 全部剔除。它是社区 RP 圈 2024 年后最受认可的「高温度保连贯」杆：温度可以放高（多样性），min-p 会随模型自信度自动收窄采样面（连贯性）。ST 官方「Universal 系」presets 全部设 min_p=0.1（见 1.2），RecoveredRuins 设 0.01。社区常用区间 0.02–0.2。**对小汐的杠杆度：高**——它是目前「文本质量 vs 多样」平衡最好的免费旋钮。

**repetition_penalty（一手：CTRL，1909.05858）**：对已经在文本里出现过的 token 的 logit 打折（除以其出现次数的幂）。RP 社区常用 1.05–1.15。**注意 DRY 论文 (2608.22761) 对它的批评**：repetition / presence / frequency / n-gram 都是一刀切作用于「token 复发频率」，不是「循环的序列结构」，因此常在「能压住复读」的强度上**同时破坏格式与流畅**（"often suppress looping only at strengths that also degrade formatting or fluency"）。小汐若调它：1.02–1.10 微调，别上 1.3+。

**frequency_penalty vs presence_penalty（OpenAI 官方 API 语义，见平台文档）**：二者都作用于「已出现 token」——frequency 按**出现频次**惩罚（压复读也压正常高频词，RP 里容易把语气词/名字都惩罚掉）；presence 按**是否出现过**惩罚（鼓励谈新话题，更像人换话题）。RP 社区的相对结论：presence > frequency（一个管"别老翻旧账"，一个管"别复读"，后者副作用大）。ST 客户端对部分后端（如 grok-3-mini 等）会**显式删掉这两个参数**（已抓取源码 `public/scripts/openai.js` L2896-2906），因为不少服务端实现会把它们与 rep_pen 冲突。

**DRY（一手：2608.22761，「Don't Repeat Yourself: Stopping Verbatim Loops at Sampling Time」）**：最对症的治复读音——只惩罚「若生成本 token 会使当前后缀成为上下文中某段原文的精确延续」的候选 token，带 sequence breakers 保护聊天模板/格式 token。实测：对 1.5B–120B 模型、9 种 prompt 族，**将后缀延续（suffix-extension）率降低 47%，同时提升词汇多样性**（600 对人工评测）。这是比 rep_pen 更精准的「换意象」底层实现。

**typical (2202.00666) / Mirostat (2007.06030) / TFS**：typical 与 min-p 同族（按信息量截断），写作/ RP 圈接受度中；Mirostat 直接押「目标困惑度」防循环，实时解码自适应，但会改写作者风格、与「人格恒定」有张力；TFS（Tail Free Sampling）是早期社区启发式截断，ST preset 里保留（`tfs` 字段，默认 1=关闭）。

**采样顺序（sampler_order）**：同一组数值、不同执行顺序，输出分布不同。ST 把 7 个采样器做成可排序数组，官方 preset 就带各自顺序（见 1.2 JSON）。

### 1.2 社区推荐的「角色扮演采样配置」（一手：ST 官方自带 preset JSON）

ST 官方仓库随主程序分发 6 份 Kobold 采样 preset，实值如下（已抓取 `default/content/presets/kobold/*.json`）：

| preset | temp | top_p | min_p | top_k | rep_pen | rep_pen_range | sampler_order |
|---|---|---|---|---|---|---|---|
| Deterministic | 0 | 0 | 0 | 1 | 1 | 0 | 6,0,1,3,4,2,5 |
| Neutral | 1 | 1 | 0 | 0 | 1 | 0 | 6,0,1,3,4,2,5 |
| RecoveredRuins（社区名 preset，4chan 起源） | 1 | 0.95 | 0.01 | 0 | 1.1 | 600 | 6,0,1,2,3,4,5 |
| Universal-Light | 1.25 | 1 | 0.1 | 0 | 1 | 0 | 5,6,0,1,2,3,4 |
| Universal-Creative | 1.5 | 1 | 0.1 | 0 | 1 | 0 | 5,6,0,1,2,3,4 |
| Universal-Super-Creative | 2 | 1 | 0.1 | 0 | 1 | 0 | 5,6,0,1,2,3,4 |

社区共识（本文件归纳，来源 = 上表 + 采样器论文）：
1. **两条主流路线**：A) 温和温区 + 惩罚杆（RecoveredRuins：temp≈1、rep_pen 1.05–1.15 + range、top_p 0.9–0.98、min_p 0.02–0.05）；B) 高温区 + min-p（Universal 系：temp 1.25–2、min_p 0.1、关掉 top_k/top_a）。路线 A 稳、保格式；路线 B 活、食「创造力」。
2. **惩罚杆不是越多越好**：rep_pen + presence 双开常见，再叠 frequency 容易把语气词/人名词汇压扁；DRY 论文已给出理论原因。
3. **min-p 是现代 RP 的默认开启杆**，尤其在小模型/长上下文（漂移+复读同发）时。
4. 对中文：ST 社区经验是中文模型对 rep_pen 更敏感（token 粒度不同），从 1.05 起步、每 0.02 一档 A/B。

**与小汐直接相关的一手细节**：ST 的 OpenAI 兼容通道对 DeepSeek 有特判——`generate_data.top_p = generate_data.top_p || Number.EPSILON;`（`public/scripts/openai.js` L2890，经核），注释直指 `api-docs.deepseek.com/api/create-chat-completion`：**DeepSeek 拒绝 top_p=0**。小汐若给 DeepSeek 传采样参数，这是已知坑；反之 min_p、repetition_penalty、presence/frequency 均可由 ST 通道转发（L2825-2973）。

### 1.3 prompt order / context template 防人格脱落（一手：ST 文档）

核心机制（均为已抓取原文，直接引用关键句）：
- **常驻层**：Character Name / Description / Personality / Scenario 每轮必发（permanent tokens）。「All important facts should be included here」。
- **First Message 只发一次**，且「The model is more likely to pick up the style and length constraints from the first message than anything else」——风格与长度锚的第一位。
- **示例对话会被挤出**：「only inserted if there is free space in the context … pushed out of context block by block」——示例不是永久锚，是「上下文有空位才存在」的短期示范。
- **Author's Note**：定点注入 + 可配频率（Frequency 1 = 每轮都插，4 = 每 4 轮插一次）；官方原文：「**The closer the Author's Note is to the bottom of the prompt, the more impact it has on the next AI response**」。官方给的防漂移默认用法就是 `[Remember the instructions you were given at the beginning of this chat.]`。
- **Post-History Instructions（PHI）**：插在「用户消息之后、模型生成之前」的**最终指令**；官方原文：「they are the final instructions that the AI receives before generating a response. The AI usually gives them a higher priority than the main prompt」。**这是社区体系里最强的重锚位**——比放在 system prompt 尾部更靠近输出。小汐 PERSONA_DEEP_DIVE §9-① 的「重锚定批」在 ST 体系里的精确定位应是 PHI，而非固定深度 Author's Note。
- **反串台（防人格脱落成"旁白/narrator"）**：context-template 文档提供三条工程手段——`Names as Stop Strings`（把角色名/用户名加进停止串，防模型模仿对方）、`Always add character's name to prompt`（在 prompt 尾部把其名追加为前缀，逼模型以该角色续写）、`Start Reply With`（预填首词，强制从该处继续）。

**对小汐的启示**：① 小汐当前把 DIALOGUE_EXAMPLES 放 system prompt 尾部（弱模型只记得末尾），符合 ST「末尾影响最大」原则；但 ST 的更强做法是在**每轮 model 调用末尾加一条 PHI 风格的短指令**（2 句「说话的样子」重锚），比改 system prompt 更近、更省 token。② 现有 PERSONA_ANCHOR（每 8 次重锚，soul.py L221-232）在 ST 语境下对应「Author's Note 周期性 + PHI」，建议把「说话铁律」这一子集放进 PHI 槽，把完整身份锚留在 system prompt（身份锚不逐轮涨 token，语气锚逐轮钉）。

### 1.4 Anti-REPETITION 的多写层手段（社区全景）

复读不止是一个采样参数问题，社区在多「写」层同时治理：
1. **采样层**：rep_pen / DRY / min-p / presence_penalty（见 1.1）。
2. **停止串层**：把示例分隔符、聊天开始标记加入 stop strings——治「模型把示例对话整块复读出来」（context-template 官方功能 `Separators as Stop Strings`）。
3. **作者注/定期重申**：Author's Note 每 N 轮重申人格/语气（1.3）。
4. **负面禁令清单**：把「禁用语/禁句式」写进提示（第 2 章），但注意**清单要短**——社区与 repetition-penalty 研究同向：长负面清单会让模型过度聚焦、反而说出清单词。
5. **每轮换意象**：要求「不要连续复用同一个动作/感官描写/比喻」（小汐 soul.py L67 已有「别老用那几种括号动作」的雏形）。DRY 的序列级惩罚在底层实现的就是「同一个意象接续出现要扣分」。
6. **喂回最近原文**：把近 N 条消息原文给模型看、并显式说「别重复这些」（小汐 heartbeat 已用：L424-429 把 24h 内已发主动消息喂回 + 「不要重复同样的话」；L961-965 前夜提醒 12h 防复读）。这正是社区与 spec 反复验证的 cheap win。

---

## 2. 角色卡与人格 prompt 的反 slop 规范

「slop / AI 味」是社区自造词，指「一眼 AI、人人能认出」的固定措辞、句式和结构。反 slop 在角色卡/人格 prompt 领域已成**显式工程**（有独立 GitHub 仓库、CLI、skill 和三层质检流程）。

### 2.1 英文「AI slop」高频词/句式（一手清单，可直接复制）

来自已抓取的三个工程：`hardikpandya/stop-slop`（4k+★，Claude Code skill，含 phrases/structures）、`walidboulanouar/anti-ai-slop`（**342 禁用词 + 136 禁用短语**的机器可读 `patterns.json`）、Wikipedia「Slop」词条作背景。

**High-signal 动词（学术/商务写作腔的「讲空话」词）**：
leverage / utilize / facilitate / harness / enhance / elevate / amplify / empower / foster / garner / showcase / underscore / emphasize / unveil / uncover / illuminate / articulate / explore / navigate / delineate / synthesize / contextualize / substantiate / manifest / embody / galvanize / transcend / cultivate / pioneer / revolutionize / transform / transformative / reimagine / evolve / unlock / unleash / enable / ensure / comprise / resonate / permeate。

**「强调/总结」开场的喉头清嗓词（throat-clearing openers，stop-slop phrases.md）**：
"Here's the thing:" / "Here's what [X]" / "It turns out" / "The truth is" / "Let me be clear" / "I'm going to be honest" / "Can we talk about" / "The uncomfortable truth is"。

**标志性句（high-certainty AI tells，anti-ai-slop patterns.json 标 flag 类）**：
- "it is important to note that" / "it is worth noting that" / "it stands to reason"
- "in conclusion" / "to summarize" / "as previously mentioned" / "as we can see"
- "delve into"（→ look at）；"dive into" / "embark on"（→ start）
- "in the realm of" / "in the world of"（→ in）
- "in today's digital age / era"；"ever-evolving landscape" / "evolving landscape"（→ shifting）
- "rich tapestry" / "a rich tapestry"（→ a mix）
- "plays a crucial / pivotal / significant role in shaping…"
- "shed light on" / "leave an indelible mark" / "a stark reminder"
- "cutting-edge" / "game-changer" / "paradigm shift" / "robust solution"（→ 具体名词）
- "provide valuable insights" / "gain a comprehensive understanding"

**密集副词（stop-slop 主张全删）**：really / just / literally / genuinely / honestly / simply / actually / deeply / truly / fundamentally / inevitably / crucially / importantly。

**情感/叙事腔**：creeps in、voice dripping with sarcasm、hung heavy、continue to inspire、let that sink in、full stop。情感类一律「show don't tell」。

> 判定法（stop-slop README 的 5 维打分）：Directness（陈述还是宣布？）／ Rhythm（节奏多变还是节拍器？）／ Trust（是否尊重读者智商？）／ Authenticity（像人吗？）／ Density（有没有可删的？）——<35/50 就打回重写。

### 2.2 中文「AI 腔」清单（写作向一手 + 对话向社区观察并存）

**写作向（一手，`stop-slop-zh` 完整引用，缺一不可地分四层）**：

- **禁用词（全文 0 命中）**：废话连接词（说白了 / 本质上 / 换句话说 / 不可否认）；总结套话（综上所述 / 总的来说 / 总而言之 / 一言以蔽之）；编号过渡（首先…其次…最后 / 第一…第二…第三 / 一方面…另一方面）；AI 信号词（值得注意的是 / 不难发现 / 让我们来看看 / 接下来让我们 / 需要指出的是 / 显而易见 / 毋庸置疑 / 众所周知）；时代套话（在当今…的时代 / 随着技术的不断进步 / 随着…的快速发展 / 在这个…的时代 / 在…的大背景下）；空洞归纳（这意味着 / 由此可见 / 这说明了什么）；虚假强调（毫无疑问 / 可以说 / 事实上(无事实时) / 实际上(无对比时)）；伪口语（那么问题来了 / 答案可能出乎你的意料 / 你可能会问 / 这就引出了一个问题）。
- **禁用标点**：冒号「：」（AI 爱用来引解释/列举/定义）、破折号「——」（英文 em-dash 直译）、直双引号「"」→ 一律改「」或不加。合规：逗号句号顿号书名号括号「」；省略号少用；感叹号少用。
- **禁用结构**：编号式结构（判断一/二/三）、bullet point 罗列、大量加粗（>10 处即 AI 味）、不必要的小标题、『总分总』八股（开头总述→分述→综上所述结尾）、对比表格滥用（维度 ≤4 就别用表）。
- **推荐口语库（写时强制融入 8–10 个）**：坦率的讲 / 说真的 / 我跟你说 / 其实吧 / 怎么说呢 / 话说回来 / 这么说吧 / 值得画出来 / 说到点子上了 / 不在一个尺度上 / 说到底 / 可以确定的是 / 说实话也不确定 / 还在摸索 / 踩过坑 / 看走眼了 / 太离谱了 / 给人整不会了 / 有点意思 / 挺猛的 / 大家也都知道 / 做过的人都懂。**纪律**：一段不堆 2 个以上、上下文匹配、同词全文 ≤2 次。

**对话/陪聊向（「AI 女友/AI 陪聊机器人腔」）——社区观察，非单源，需主编写入前按冷冷观感校准**：
- 高频「客服式应声」：好的呢 / 好的呀 / 明白啦 / 嗯嗯 / 没问题哦 / 不客气呀 / 随时找我哦。
- 通用共情四件套：我理解你的感受 / 我能理解你现在的心情 / 抱抱你 / 一切都会好起来的→**每轮都出现即模板**；以及「先说感受、再给建议」的固定两段式（ESConv 学术上叫「上来就给建议」= 支持失败头号原因，见 dim-03/30）。
- 微表情动作括号复读：（微笑）（点头）（认真脸）（脸红）每轮同款——对应小汐 soul.py L67 已禁的同款警惕。
- 关怀腔过度：要加油哦 / 你很棒 / 你已经很努力了 / 愿你… / 愿你所愿 / 希望你开心——**高频出现即 AI 味**（无从「具体细节」出发的关心都是空关心）。
- 句式性复读：每条都以反问/问句收尾（「你呢？」轰炸）、每条都用「～/啦/哦」满格、把用户原话复述一遍再表态。

### 2.3 「No Nice / No Sorry」与去感谢/去过度照顾（社区惯例）

角色卡社区（CHUB/CharacterHub 卡 tag、rentry 卡片写作指南圈）有一条高流传的 prompt 规范：「不许 nice、不许 sorry」——即人格 prompt 里显式写：该角色**不为占用时间道歉、不过度感谢、不无休止地体贴**。背后机制（社区语言）：
1. 「sorry + thanks + 全肯定」是 AI 客服的指纹，RP 时一条就破功；
2. 过度照顾 = 共情模板化 = 与「人味」（小缺点、有自己的情绪节奏）相冲突（dim-01 学术侧：sycophancy 边界失守与此同源）。

可直接复制的落地写法（社区常用句式）：
```
- 她不会说「对不起，我是不是太啰嗦了」这类多余的道歉；真做错了才说一次，说完就翻篇。
- 她不会每个回应都以「谢谢你愿意告诉我」收尾。关心用具体行动兑现，不喊口号。
- 她偶尔会毒舌/不耐/分神；这不是人设漏洞，是活人的呼吸。
```
注意分寸：去的是「空转的礼貌与模板化共情」，不是去「真关心」——小汐 2026-08-11 吃瓜批里「陪着比说错强」就是它的人格化版本，已经在这个方向上。

---

## 3. 示例对话与开场白的「锚人格」工程

### 3.1 First Message / Greeting：风格与长度的第一锚（一手，ST characterdesign.md 原文引用）

> 「The First Message is an important element that defines how and in what style the character will communicate. The model is more likely to pick up the style and length constraints from the first message than anything else, so it's important to write it in a way that you want the responses to be (short and concise, long and detailed, etc.).」

- 首条开场白决定**句长、段落数、行动括号密度、是否用 emoji、口语 vs 文学腔**——模型其他都记不住，也会抄这条。
- 多条 Alternate Greetings 供 swipe 随机/切换（group chat 里随机挑一条开场）。
- 对中文陪伴侧：开场白要包含一次「行动括号 + 一句口语 + 一个具体细节」，把三种姿态一次定住。

### 3.2 示例对话的「非模板」设计（一手规范 + 社区惯例）

ST 官方规范（已抓取）：示例块用 `<START>` 分隔；`{{char}}:`/`{{user}}:` 分角色；**块之间用 Example Separator 替代 `<START>` 进 prompt**；示例只在上下文有空位时存在、按块挤出。社区列出的三条卡片写作指南（ST docs 引用，未抓到正文，作惯例标注）：Trappu 的 PLists + Ali:Chat、AliCat 的 Ali:Chat、kingbri 极简指南。

社区反 slop 的示例设计要点：
1. **覆盖不同行为面**（5–7 块，不同情绪/怪癖/边界，Ali:Chat 规则）——让"人格采样空间"铺开，而不是 5 块同一种语气。
2. **故意制造非模板变化**：短问长答、长问短答；带口头自我修正（"我想说…算了"）；有被打断/没说完的句子；具体到能画出来的细节（一杯三分糖去冰、一件起球的卫衣），而不是「温柔地说」。
3. **show don't tell**：用场景交代性格，不写人物小传式形容词（ST 具体性第一原则，PERSONA_DEEP_DIVE §5.2 已镜像）。
4. **至少一块示例演示边界/拒绝**（「这个我不聊」）——把该角色的 negative space 钉死。
5. **示例自身也要过 stop-slop 扫描**：示例里就有冒号列表/破折号/「总之」，模型会原样复读。

### 3.3 「示例对话与开场白」工程检查清单（可直接复制，全文见产出二 §7）

---

## 4. 中文社区调教指南（B站 / 知乎 / 小红书）

一手可达性：Bilibili 搜索 API 可直连（本调研已验证「陪聊 / AI 人设」检索有效，返回含 AI 老婆 / 陪聊调教类视频）；知乎/小红书正文、B站专栏正文受反爬限制未抓。以下为**社区流通模板的共性提炼**，标注为惯例。

中文「陪聊 / 语C（语言 cosplay）/ 角色设定」下载圈流传的角色设定模板，结构高度收敛为五块：
1. **人设条**：姓名/年龄/来历 + 3–5 条性格（具体行为 > 形容词），如「嘴上嫌弃心里向着你」。
2. **说话特点**：口头禅、句长、是否用语气词/颜文字、情绪→话量的映射（越平静话越短）。
3. **开场白**：一段准入戏，显式示范「第一次见面该有的距离感与措辞」。
4. **禁用语清单**：负面禁令（不说「你是 AI」不说「我来帮你」不用客服腔不用书面语）。这条在中文社区尤为核心——近乎每条模板都带。
5. **关系/态度分层**：亲密度不同→措辞不同（陌生/熟悉/恋人三档只变语气不变人设；与 Coze 官方模板、dim-01 结论同构）。

把这五块对齐到小汐：`BASE_PERSONA`（人设+说话方式）≈1+2；`DIALOGUE_EXAMPLES` 压阵段 ≈3 的持续版；「底线」（soul.py L113-118）≈4；`_build_relationship_text()`（相识 N 天→三档语气）≈5。**结论：小汐的人格结构已经天然符合中文调教共识，缺的不是结构，是第 4 块「禁用语」的具体化和可观测化**（见 §8-3）。

顺带一提，中文社区对「AI 味」的抱怨点（B站/知乎高频）与本文 §2.2 英文清单同源：编号结构、总结腔、客套共情、比喻堆砌。反 slop 是语言无关的工程。

---

## 5. 防复读的多写层手段（跨层小结）

| 层 | 手段 | 一手来源 |
|---|---|---|
| 采样 | min-p、DRY、rep_pen(+range)、presence_penalty | arXiv 2407.01082 / 2608.22761 / CTRL 1909.05858 / OpenAI 语义 |
| 截断 | typical、TFS、top_k 关闭 | 2202.00666 / ST preset |
| 停止串 | 示例分隔符进 stop strings | ST context-template |
| 注入 | Author's Note 定期重申、PHI 末尾重锚 | ST Author's-Note / index PHI |
| 提示 | 负面禁令（短清单）、喂回最近原文「别重复」、每轮换意象 | 社区惯例 + stop-slop-zh + 小汐 heartbeat 现成实现 |
| 检测 | 禁用语命中数统计、5 维打分或四层质检 | stop-slop / stop-slop-zh |

---

## 6. 产出一：英文 + 中文「AI 腔 高频词/句式」对照清单（可直接复制）

> 用法：把「禁用语（对话向）」作为人格 prompt 的一小节；把「一眼标记词（写作向）」用作离线质检（像 anti-ai-slop 的 CLI 一样跑计数）——**检测用全表，提示词里只放精编 10–15 条**（社区教训：长清单引发聚焦反而诱发）。

### 6.1 英文（一眼标记，检测/改写通用）
```
# 动词空话（学术/商务腔）
leverage utilize facilitate harness enhance elevate amplify empower foster
garner showcase underscore emphasize unveil uncover illuminate articulate
navigate delineate synthesize contextualize substantiate manifest embody
galvanize transcend cultivate pioneer revolutionize transformative reimagine
unlock unleash enable ensure comprise resonate permeate

# 喉头清嗓开场（删掉直接进正题）
Here's the thing: / Here's what [X] / It turns out / The truth is
Let me be clear / I'm going to be honest / Can we talk about
The uncomfortable truth is

# 标志句（flag，不改写就删）
it is important to note that / it is worth noting that / it stands to reason
in conclusion / to summarize / as previously mentioned / as we can see
delve into / dive into / embark on / in the realm of / in the world of
in today's digital age / ever-evolving landscape / rich tapestry
plays a crucial|pivotal|significant role in shaping / shed light on
leave an indelible mark / a stark reminder / cutting-edge / game-changer
paradigm shift / robust solution / provide valuable insights / gain a comprehensive understanding

# 副词（真话不需要油）
really just literally genuinely honestly simply actually deeply truly
fundamentally inevitably crucially importantly
```
（来源：stop-slop phrases.md；anti-ai-slop patterns.json 342+136 条；Wikipedia Slop）

### 6.2 中文（写作向：硬禁止；对话向：校准后禁止）
```
# 写作向 · 禁用词（0 命中）
综上所述 / 总而言之 / 总的来说 / 一言以蔽之
值得注意的是 / 不难发现 / 让我们来看看 / 接下来让我们 / 需要指出的是
显而易见 / 毋庸置疑 / 众所周知
在当今…的时代 / 随着技术的不断进步 / 随着…的快速发展
这意味着 / 由此可见 / 这说明了什么
毫无疑问 / 可以说 / 事实上 / 实际上（无事实无对比时）
那么问题来了 / 答案可能出乎你的意料 / 你可能会问 / 这就引出了一个问题
本质上 / 说白了 / 换句话说 / 不可否认
首先…其次…最后 / 第一…第二…第三 / 一方面…另一方面

# 写作向 · 禁用标点
冒号「：」 / 破折号「——」 / 直双引号「"…"」（改「」或不加）

# 对话向 · 客服应声/模板共情（按冷冷观感校准后入禁）
好的呢 / 好的呀 / 明白啦 / 没问题哦 / 不客气呀 / 随时找我哦
我理解你的感受 / 我能理解你现在的心情 / 抱抱你 / 一切都会好起来的
要加油哦 / 你很棒 / 你已经很努力了 / 愿你… / 希望你能开心
（微笑）（点头）（认真脸）（脸红）等同一动作括号连续复用

# 对话向 · 句式性复读（规则而非词表）
- 每条都以反问/问句收尾
- 每条都以「～/啦/哦」满格
- 先把用户的话复述一遍再表态
- 先说感受、再给建议的固定两段式（无具体细节支撑）
```
（来源：stop-slop-zh SKILL.md + references/ 四文件；对话向为社区观察，标注待校准）

### 6.3 反 slop 的自检问题（写任何人格文本后用）
1. 抽掉所有副词，句子还成立吗（成立→删）？
2. 这段有没有「在宣布重要/深刻」却没给出具体细节？（stop-slop "Vague Declaratives"）
3. 删掉开场白第一句，信息少了吗？（少→删）
4. 全文有没有 2 处以上同款动作括号/比喻？（有→换具体意象）
5. 冷冷问「这谁写的？」，场景里最熟的人能对上号吗？（对不上→重写）

---

## 7. 产出二：「示例对话与开场白」工程检查清单（可直接复制）

**开场白（First Message）——风格与长度之锚**
- [ ] 明确这条要示范的**长度**：目标回复几行/几句，开头就写几行/几句（模型抄长度最狠）。
- [ ] 至少包含一次「行动括号 + 一句口语 + 一个具体细节」，一次定住三种姿态。
- [ ] 不用形容词概括性格——用一个可画出来的场景（ST 具体性第一原则）。
- [ ] 示范「说话特点」里的事：想用 emoji 就写 emoji，想有口头禅就写口头禅。
- [ ] 开场即设距离感：不过度热情、不自我介绍太满（破「你好，我是 AI 助手」腔）。

**示例对话（Example Messages）——非模板变化**
- [ ] 5–7 块，每块覆盖**不同行为面**（至少：关心一次 / 拒绝一次 / 开玩笑一次 / 分神一次）。
- [ ] 块内长短错落：至少一块短问长答、一块长问短答（Ali:Chat 规则）。
- [ ] 至少一块包含**自我修正/没说完的话**（「我刚想说…算了」「不对，我是说…」）。
- [ ] 至少一块演示**边界与拒绝**（该角色怎么说不）：「这个我不聊」「你问十遍也一样」。负例比正例更能锚底线。
- [ ] 行动括号：每条 ≤1 个、具体、全段**不重复**（小汐 soul.py L67 同款）。
- [ ] 示例自身过 stop-slop 扫描：无冒号列表、无破折号、无编号、无「综上所述」。
- [ ] 不把所有人设点都在示例里演一遍——示例是「语气采样」，不是「简历」。
- [ ] 示例分隔符进停止串，防模型把示例整块复读（ST context-template）。

**工程接线**
- [ ] 开场白只发一次、可配 Alternate Greetings 随机（ST）。
- [ ] 示例块放上下文末尾（弱模型只记得住末尾；小汐已如此）且允许被挤出（不要强制常驻）。
- [ ] 真正的「语气铁律」放 Post-History Instructions（用户消息之后、生成之前），优先级高于 system prompt（ST index.md）。
- [ ] 上线前跑 8 轮探针：swipe 3 条，看节奏是否抄开场白、是否溜回客服腔、动作括号是否出货复读。

---

## 8. 对陪伴型 agent 的架构启示（给小汐）

1. **把「说话铁律」从 system prompt 尾部搬到 PHI 槽（最高优先级重锚位）**。ST 全文最强的一手结论是：生成前最后一条指令的优先级高于主提示。小汐现把 DIALOGUE_EXAMPLES 压 system prompt 尾部（对弱模型有效是经验命中），但每轮 model 调用末尾可追加一条 2 句的 PHI 式语气重锚（短句、无破折号、不用客服腔、动作括号 1 个）——对反复读/反漂移是直接增益，token 成本增量极小；PERSONA_ANCHOR（每 8 次）保留作身份重锚，两者分工（身份锚低频、语气锚高频）。

2. **采样参数做「人味」调参，而不是默认值**。DeepSeek API 支持 top_p/frequency/presence/repetition（ST 客户端可透传，且已确认 DeepSeek 拒绝 top_p=0）。建议 heartbeat（主动消息/早安/前夜提醒）这类「短句易模板化」的生成：presence_penalty 微开、repetition_penalty 1.03–1.08（从 1.0 起 A/B 慢推）、慎用 frequency_penalty（会把语气词压扁）；temp 自 1.0 可微降至 0.9 试模板腔收敛。若 DeepSeek 开放 min-p，优先 min-p+高温组合（社区 RP 主线）。

3. **中文禁用语清单成文化 + 可观测化**。把 §6.2 对话向清单精编 10–15 条放进 BASE_PERSONA「说话方式/底线」作为人格化禁令（不用「禁止」堆墙，用「不说 X，说 Y」的对照写法），同时把全表放进离线检测器：对 chat_archive 最近 N 条小汐回复统计命中数、写入台账——与 dim-06 的可观测栈（Phoenix/DeepEval）同层，让「AI 味」从感觉变成可回放指标；趋势升就触发一次语气 PHI 加强。

4. **示例变化纪律正式化**。小汐 DIALOGUE_EXAMPLES 已大量符合 §7（不完美、自我修正、具体细节、边界示例），把这套显式化为「示例写作检查清单」（可直接抄 §7）+ 一个纪律测试断言（如：示例中动作括号去重、无破折号、块覆盖 ≥3 种行为面），防未来加示例时退回模板腔。（soul.py L67 已有雏形，升格为规则。）

5. **多写层防复读闭环**：现状已有 24h/12h「喂回最近原文别重复」（heartbeat.py L424-429、L961-965），建议补两环——①prompt 内加「每轮换意象」规则（同一动作括号/比喻 24h 内不连用，DRY 序列惩罚在提示层的廉价等效）；②把 anti-slop 计数做成心跳任务级检测（如每天凌晨对昨日回复跑一次 §6.3 五问 L1 扫描，≥2 命中则次日 PHI 加强一次），把「反 AI 味」变成持续维护而非一次性措辞。

6. （不照搬）不要为小汐引入 ST 的 7 采样器乱序/Lorebook 全家桶；小汐是 API 端单通道，能拿到的是 top_p/min-p/rep_pen/presence/frequency 这几个旋钮，够用且不要同时全开。

---

## 9. 项目 / 来源索引

- **SillyTavern**（https://github.com/SillyTavern/SillyTavern | https://github.com/SillyTavern/SillyTavern-Docs）[本文一手抓取 5 页文档 + 8 份 preset/sysprompt JSON + openai.js] — 采样控制面、Author's Note / PHI 注入、First Message 风格锚、反串台工程。
- **min-p：Turning Up the Heat**（https://arxiv.org/abs/2407.01082）[一手摘要] — 动态截断采样；高温度下防 incoherent/repetitive。
- **DRY：Don't Repeat Yourself**（https://arxiv.org/abs/2608.22761）[一手摘要] — 序列级复读惩罚；suffix-extension 降 47% 且词汇多样性提升。
- **CTRL repetition penalty**（https://arxiv.org/abs/1909.05858）[一手标题/背景] — rep_pen 出处；DRY 论文给出其「过强破坏格式」批评。
- **Neural Text Degeneration（nucleus）**（https://arxiv.org/abs/1904.09751）[一手标题/背景] — 低温度→神经退化/复读回路的实证基础。
- **Mirostat**（https://arxiv.org/abs/2007.06030）[一手标题/背景]；**Typical Sampling**（https://arxiv.org/abs/2202.00666）[一手标题/背景]。
- **hardikpandya/stop-slop**（https://github.com/hardikpandya/stop-slop）[一手 README + phrases.md] — 英文反 slop skill、5 维打分。
- **pencil20388-eng/stop-slop-zh**（https://github.com/pencil20388-eng/stop-slop-zh）[一手 README + SKILL.md + references 四文件] — 中文写作向禁用语/标点/结构/口语库。
- **walidboulanouar/anti-ai-slop**（https://github.com/walidboulanouar/anti-ai-slop）[一手 README + patterns.json（342 词 + 136 短语）] — 机器可读英文 slop 词库/替换字典。
- **Wikipedia: Slop (artificial intelligence)**（https://en.wikipedia.org/wiki/Slop_(artificial_intelligence)）[一手词条] —「slop」现象与背景。
- **社区惯例（未直接抓取）**：Trappu PLists + Ali:Chat（https://rentry.co/alichat）、kingbri 极简卡指南（https://rentry.co/kingbri-chara-guide）、Trappu 维基（https://wikia.schneedc.com/bot-creation/trappu/creation）；B站「陪聊/AI 人设」调教内容（搜索 API 已验证可达，正文未抓）；知乎/小红书调教文（反爬未抓）。

## 10. 警示

- 采样器「实证效果」除 DRY/min-p 外，多为**单论文或社区经验值**，无大规模跨模型统一基准；具体数值（rep_pen 1.05–1.15、min_p 0.02–0.2）是社区惯例而非定理，须在小汐/DeepSeek 上 A/B 校准（10 轮探针）。
- SSR 环境受限：rentry.co / DuckDuckGo / HuggingFace / 知乎 / 小红书均未抓到原文，其中 Ali:Chat / kingbri / Trappu / No Nice-No Sorry 等「社区规范」由 ST 官方文档引用 + 本人社区知识补全，已尽量标注「惯例」；关键引语（ST 五页 + 各论文摘要）均有已抓取一手来源可复核。
- 「No Nice / No Sorry」「微表情括号复读」等属于语料圈惯习，存在门户之见；对小汐的采用应以冷冷体感为准，勿机械照搬英文语境的口味。
- 中文对话向禁语清单来自社区观察，非单点一手事实；建议先跑 7 天平淡采集（不改 prompt 只计数），用真实命中率定稿再入提示词。