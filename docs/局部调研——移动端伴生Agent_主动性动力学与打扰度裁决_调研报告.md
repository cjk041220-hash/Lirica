# 移动端伴生智能体：主动性动力学与打扰度裁决 调研报告

> **检索时间**：2026-09-19（下文所有"当前/最新"以此为准）
> **角色视角**：移动端伴生智能体（Android 原生常驻、含情绪状态与长期记忆）方向研究员
> **范围**：内在驱动（好奇心 / 无聊感 / 关怀度）的量化与涨落；认知负荷与情境打扰代价的估计函数；分级表达形态；可中断性与通知时机实证
> **明确排除**：GUI 自动化、模型推理优化
> **引用规范**：关键结论后附 `[编号]`，对应文末「来源清单」。数值均来自一手文献或官方页面；未能取到原文数值的，在「不确定与未证实清单」中显式标注。

---

## 一、背景概述

### 1.1 问题的本质：一个双边约束的取舍

伴生 Agent 的核心张力在文献中被反复表述为同一句话——**"太被动显得无聊，太主动显得烦人"**。Rivoire 与 Lim（SoftBank Robotics）在 2016 年的长期 HRI 工作中把这句话直接写进了标题：*The Delicate Balance of Boring and Annoying*[13]。

这不是修辞，是可测量的双边界：

- **下边界（死工具）**：新颖效应消退后用户流失是家用机器人的经典失败模式[13]；伴生聊天产品的核心商业指标 CPS（Conversation-turns Per Session）本质就是"每次会话还能撑几轮"，小冰团队明确以**长期期望 CPS** 为优化目标而非短期指标[12]。
- **上边界（被卸载）**：通知是留存最强的单通道，也是最快的卸载触发器。用户每周 1 条推送约导致 10% 的人关闭通知；提到每周 3–6 条，约 40% 关闭[30]（**注：该数据来自行业/商业来源而非同行评议文献，仅作量级参考**）。

### 1.2 学术脉络：从"可中断性"到"主动性"

| 阶段 | 代表工作 | 核心命题 |
|---|---|---|
| 1996 | Weiser & Brown《The Coming Age of Calm Technology》[22] | 注意力分"中心/边缘"，技术应在边缘运行、必要时才进入中心 |
| 2003–2005 | Horvitz 等（MSR Attentional UI）[2]、Fogarty/Hudson/Atkeson/Avrahami [1]、Ho & Intille [3] | 用传感器学习"打扰代价 / 可中断性"，并**基于期望代价做成本—收益裁决** |
| 2004–2008 | Adamczyk & Bailey [4]、Mark, Gudith & Klocke [8] | 中断时机（任务边界）与中断代价（恢复时长）的实验心理学基线 |
| 2014–2017 | InterruptMe [17]、Attelia/Attelia II [9][10]、My Phone and Me [7]、Beyond Interruptibility [11] | 智能手机上的 in-the-wild 建模：断点检测、内容 vs 时机、个体化规则 |
| 2024–2026 | Inner Thoughts (CHI'25) [5]、ProactiveEval [6]、ContextAgent [16]、Villa et al. (BIT) [14]、THRI 主动性综述 [15] | LLM 时代：把"内在动机"显式建模为可打分、可阈值的机制 |

**关键转折**：2025 年前后，主动性研究从"预测下一个说话人 / 预测何时可打断"转向"**Agent 自己生成想法并给想法打分**"。CHI 2025 的 Inner Thoughts 明确指出，基于 next-speaker prediction 的方法在多方对话中**不比"重复上一句"的朴素基线强**，除非说话权被显式分配[5]。这对伴生 Agent 是直接的警告：**把"该不该说话"外包给上下文模式匹配是错的，必须建内在动机模型。**

### 1.3 Android 平台现状（时效：2026-09）

- 当前稳定版 **Android 17（Cinnamon Bun，API 37）**，2026-06-16 发布[26]；Android 17 QPR2 于 2026-08-28 达到 Platform Stability，预计 2026-12 随 Pixel Feature Drop 转正[27]。
- Android 17 QPR2 Beta 4（2026-08-28）新增 **"Notification Intelligence"** 设置区，把「通知摘要 / 通知整理 / 通知冷却」三块原有 AI 能力归入同一入口[27]。**注意：这是既有功能的重新分组，不是新算法**[27]。
- **通知冷却（Notification Cooldown）**：短时间内收到大量通知时，系统降低音量并最小化弹窗，最长约 1 分钟（Android 15 描述为 2 分钟）；**通话、闹钟、优先对话、紧急警报豁免**；默认关闭，需用户开启[25]。
- **通知重要性阶梯（官方）**：`IMPORTANCE_NONE(0) → MIN(1) → LOW(2) → DEFAULT(3) → HIGH(4) → MAX(5, 系统保留, 应用上限为 HIGH)`[23][24]。设计指南给出的语义是：HIGH=发声且浮动通知（须立即处理的时效信息）、DEFAULT=发声（方便时尽快看）、LOW=静音（新内容/社交邀请）、MIN=静音且不在状态栏显示（天气、推广等可延后信息）[23]。**渠道重要性提交后开发者不可改，只有用户能改**[24]。
- **合规红线**：获取前台应用/使用情况需要 `PACKAGE_USAGE_STATS`（用户手动开启"使用情况访问"）；用 `AccessibilityService` 监听窗口变化虽实时但**仅限辅助残障用户的用途，滥用会导致 Google Play 下架**[28]。同时 Google Play「醒目披露与同意」政策要求在后台收集个人/敏感数据前必须在应用内显著披露并取得明示同意[29]。**这意味着"读前台应用名"这条最直觉的打扰代价信号，在合规上是高成本的。**

---

## 二、核心发现

### 2.1 驱动如何量化与涨落？

#### 发现 1：驱动应建模为**带泄漏的累积量 + 表达后抑制**，不是布尔触发器

最强的一手证据来自 CHI 2025 Inner Thoughts[5]。该框架把 Agent 的"想法"持续生成但**默认不外显（covert）**，每个想法按 8 条内在动机启发式打 1–5 分，超过阈值才转为外显。其动机分数公式（原文）：

```
score = Σ_{i=1..5} p(s_i) · s_i · d_p
d_p = λ^(t − τ_p)，λ = 1.02（沉默时长带来的动机增长），τ_p 为该方上次发言时刻
```

即：**动机 = 内容质量分 × 沉默时长指数增长因子**[5]。

这给出了驱动涨落的可实现骨架：

| 分量 | 语义 | 建议来源信号 |
|---|---|---|
| `C` 好奇心 | 信息缺口 / 学习进展 | 长期记忆中的未闭合话题、用户提到但未展开的实体、环境新异性 |
| `B` 无聊感 | 用户侧低唤醒 + Agent 侧沉默成本 | 空闲时长、重复刷屏、无新输入；Agent 累计沉默时长（λ^(t−τ)） |
| `W` 关怀 | 关系维护 / 承诺兑现 | 距上次交流时长、历史情绪事件、用户曾表达的待办与约定 |
| `U` 紧急 | 时效性与后果 | 时间敏感事件、明显错误 |

建议的动力学（工程化表述，非原文，属本报告的合成）：

```
dX/dt = I_x(t) − λ_x · X(t)           # 泄漏积分器，λ_x 决定衰减速度
X ← min(X, X_max)                      # 饱和上限，防止长期不互动后数值爆炸
表达后：X ← ρ_x · X,  ρ_x ∈ (0,1)      # 满足/抑制（satiety）
同时进入不应期 R_x：期间 I_x 被压缩
```

三个必要组件缺一不可：**泄漏**（否则不互动两周后一开口就刷屏）、**饱和**（否则驱动无限增长）、**表达后抑制 + 不应期**（否则连续轰炸）。Inner Thoughts 的 λ=1.02 是一个很保守的沉默增长因子，说明**"沉默本身"应贡献的动机权重被刻意压得很低**[5]——这与"靠沉默硬憋出一次发言"的直觉相反。

#### 发现 2：好奇心有成熟的数学定义——**最大化学习进展**，而非最大化新颖度

Oudeyer、Kaplan 与 Hafner 的 Intelligent Adaptive Curiosity（IAC）[19] 是这一方向的标准引用：内在动机驱动系统奔向**学习进展最大化**的状态，从而**同时回避"过于可预测"和"过于不可预测"**的情境，自发形成由易到难的发展序列。Kaplan 与 Oudeyer 进一步把它落到神经机制假设（ tonic dopamine 作为学习进展信号）[20]。2024 年的跨学科综述把内在动机分为 **knowledge-based（积累知识）** 与 **competence-based（获取技能）** 两类，并指出"Goldilocks 效应"（中等复杂度最吸引人）是其共有特征[21]。

**对伴生 Agent 的直接推论**：Agent 的"好奇心"不应是"我没见过这个新 App"，而应是"**关于这个用户，我还有什么预测误差正在下降的维度**"。把好奇心锚定在**预测误差的可下降性**上，天然过滤掉无意义的猎奇式提问。

#### 发现 3：无聊感是**可操作的机会窗口**，但有严格前提

心理学实证提供了操作性定义。Eastwood 等（2012, *Perspectives on Psychological Science*）把无聊定义为**"想参与满意活动却无法参与的厌恶状态"**，源于注意网络失败，需同时满足三个条件：①难以把注意投入当前内外部信息；②意识到自己难以投入；③**把原因归因于环境**[18]。

更关键的是 Pielot 团队的 in-the-wild 发现（N=337）：**当用户处于高兴奋度（energetic）时，倾向于回避一切"再来点消遣"的提议**；作者的结论是——**主动推荐应当瞄准低唤醒时刻，例如无聊时刻**[11]。同时情绪效价决定内容类型：心情好时回避费脑内容（费脑推荐应瞄准中性或负效价时刻），紧张时回避消遣类内容[11]。

**推论**：无聊感不是"用户闲着我就去逗他"，而是**"低唤醒 × 无外部目标占用"**这一特定交叉窗口；并且该窗口应匹配**轻量、低认知负荷**的内容，而非需要深度参与的话题。

#### 发现 4：驱动的"涨落"必须个性化，且阈值应由偏好反推

Inner Thoughts 的用户研究（Slackbot "Swimmy"）给出了三档人格化配置与偏好结果[5]：

| 配置 | 参数 | 用户偏好（N=12 有效选择） |
|---|---|---|
| Non-stop Chatter | system1Prob = 0.7 | 4 人选为最佳，但**多人批评其贡献过多且常不相关** |
| **Active Contributor** | system1Prob = 0.2, **imThreshold = 3.59** | **6 人选为最佳（最受偏好）** |
| Selective Participant | system1Prob = 0, **imThreshold = 4.09** | 仅 2 人选为最佳，**7 人评为最差** |

**这是本报告认为最有工程价值的一条数据**：最优区间是**中等主动**（阈值 3.59），而不是最保守（4.09）。过度保守的 Agent 被判定为"最差"的比例远高于过度主动的 Agent[5]。

这一结论在人机交互与机器人领域被独立重复验证：
- Kraus 等把机器人主动性分为 **Notification / Suggestion / Intervention** 三档，**中等档（Suggestion）最受偏好**[15]；
- Peng 等在决策支持场景中同样发现**中等主动性优于最高主动性**[15]；
- Villa 等（*Behaviour & Information Technology*, 2026）用 mPLiCA 模型评估养老陪护机器人的主动性等级：**等级越高越"有用"，但也越"侵扰"；中等水平、尤其是对话式交互最被接受**[14]。

---

### 2.2 如何用轻量信号估算打扰代价？

#### 发现 5：权威框架是**期望打扰代价（Expected Cost of Interruption）**，不是打分卡

Horvitz 与 Apacible（ICMI 2003）建构了动态贝叶斯网络，对**注意焦点**变量维持概率分布（约 15 个互斥状态：高/中/低专注的独处活动、办公室内对话、演示、驾驶、私人时间、睡眠等），并在效用函数上**计算期望打扰代价**[2]。

**一手论文实测数值补全（2026-09-19 提取自原文 Table 1–4）[2]**：
- **分类准确率**：全特征推断模型预测当前打扰代价（COI）准确率：受试者 S1 为 **73%**（边际基线 53%），S2 为 **64%**（边际基线 37%）。
- **特征消融收益（Ablation）**：
  - 仅用系统事件（Sys. only）：S1 达 **64%**，S2 达 **60%**；
  - 系统 + 日历（Sys. + cal.）：S1 达 **69%**，S2 达 **63%**；
  - 系统 + 音频 + 视觉（Sys. + aud. + vis.）：S1 为 **69%**，S2 为 **63%**。
  - **核心启示**：音视频感知特征相比纯系统事件与日历**几乎未带来显著增益**（增益仅 0~5 个百分点），完全支持在移动端**剔除高功耗麦克风/摄像头感知，仅凭系统状态边沿与日历/时间建模**的选型决策。
- **跨用户泛化崩溃（Cross-user Collapse）**：直接把 S1 的模型套用到 S2 上，预测准确率暴跌至 **28%**（甚至低于 S2 朴素边际基线的 37%）[2]。**这给所有试图用“一套预训练全局大模型搞定所有用户可中断性”的方案敲响了丧钟：个体间打扰感知具有极高异质性，必须依赖端侧个性化微调与反馈自适应。**

**推论到移动端**：打扰代价应写成

```
Cost_int(t) = Σ_s P(state_s | signals) · c_s          # 期望代价（Horvitz 形式）
            × m(modality)                              # 形态系数：静默≈0 < 微动作 < 气泡 < LOW < DEFAULT < HIGH
            × g(task_completion)                       # 任务完成度：接近完成 / 处于断点 → 代价低
            × r(recency)                               # 距上次打扰的时长：越近越高（叠加效应）
```

**注意**：上式的具体乘性形式是本报告的工程化合成，Horvitz 原文给出的是"给定注意状态分布与效用评估下的期望代价"，未以完全相同形式给出乘性分解[2]。

#### 发现 6：可中断性用**廉价传感器**就能学出来，且精度接近人类

Fogarty、Hudson、Atkeson、Avrahami 等（CHI 2003 / TOCHI 2005）用 Wizard-of-Oz 模拟传感器 + 经验采样自评，构建统计模型预测可中断性：**多个模型整体准确率约 78%**；**其中一个被专门调参以避免不想要的打扰的模型，在其 90% 的预测上成功避免了打扰，同时保留 75% 的整体准确率**[1]。作者明确结论：**不需要视觉等复杂传感器，简单传感器就够**[1]。

这是一个极强的可行性信号：**78% 的天花板 + 可调偏保守**。对伴生 Agent 而言，这意味着打扰代价模型的目标不是"猜准"，而是"**偏保守且不牺牲太多召回**"。

#### 发现 7：**断点是唯一被反复验证的时机信号**

- Ho & Intille（CHI 2005）用无线加速度计实时检测**姿势与移动的活动转换**，在转换点投递消息，**比随机时机更受欢迎**[3]。
- Attelia / Attelia II[9][10]：在**用户交互断点**（UI 事件断点 + 身体活动断点 + 跨手机/手表多设备断点）投递通知。
  - 受控实验：断点时机通知比随机时机的**认知负荷低 46%**；
  - in-the-wild（30 人 × 16 天）：认知负荷**降低 33%**；
  - Attelia II（41 人 × 1 个月，手机+手表）：新增身体活动断点后，工作负荷感知的降低幅度比仅用 UI 事件断点**多 71.8%**；手表的断点检测相比随机时机**降低 19.4%**；跨设备断点检测的效果约为上一代系统的 **3 倍**[9][10]。
  - （另有来源给出"对时机敏感用户降低挫败感 28%"、"相比随机时机降低挫败感 37%"的表述[9]，与上文百分比口径不完全一致，**见「不确定清单」**。）

**推论**：屏幕状态本身价值有限，**"状态刚发生变化的那一刻"**才是有效信号。屏幕亮/灭、应用切换、活动识别变化、耳机摘戴——都应被当作**边沿事件**而非电平。

#### 发现 8：轻量信号的具体清单（可落地）

综合 Pielot 等[11]、Mehrotra 等[7]、Ito 等[31] 的实际特征集：

| 信号 | 获取成本（Android） | 文献中的角色 |
|---|---|---|
| 屏幕开关时刻与最近点亮时长 | 低（系统广播 / DisplayManager） | Beyond Interruptibility 使用"屏幕上次点亮时间"作为预测特征之一[11] |
| 活动识别（still/walking/cycling/in-vehicle） | 中（Activity Recognition API） | Attelia II 的身体活动断点贡献了 **71.8%** 的额外效果[10] |
| 最近 N 分钟数据流量 | 中 | Beyond Interruptibility 特征之一[11] |
| 时间 / 星期 / 日历忙闲 | 低 | Horvitz 的日历+时间是 DBN 核心输入[2] |
| **耳机/耳戴设备连接与佩戴** | 低–中 | UbiComp'24 SoNotify：户外步行时语音通知接受率 **≈86%**；但**需要集中注意避让行人/自行车/车辆时用户倾向拒绝**[31] |
| 通知呈现形态与提示类型（声音/震动/视觉） | 无成本（自身可控） | My Phone and Me：呈现方式与提示类型显著影响响应时间与感知打扰[7] |
| 前台应用名 | **高（合规风险）** | 需 `PACKAGE_USAGE_STATS`（用户手动授权，非实时）；AccessibilityService 实时但**违 Play 政策风险**[28]；后台收集需满足醒目披露与同意[29] |

**关于"前台应用名"的建议**：不要把产品逻辑押在上面。它是合规成本最高、最容易被用户与审核质疑的一条信号，而且**即便拿到包名，语义映射（包名 → 认知负荷）本身就是个未解决问题**。替代方案：用**屏幕交互节奏**（触控事件密度、应用内停留时长、滚动速度）间接估计负荷——Attelia 证明纯 UI 事件断点已经有效[10]，只是加活动断点更好。

#### 发现 9：**内容 > 时机**，时机优化的天花板被高估

Fischer 等（MobileHCI 2010）的自然准实验结论很尖锐：**在 SMS 这一投递形态下，接受度由内容决定，而非投递时机**；"合适时机"与"不合适时机"之间**没有显著差异**；真正显著提升接受度的是 **interest、entertainment、relevance、actionability** 四个内容维度[32]。

**一手论文实测数值补全（2026-09-19 提取自 Mehrotra et al. CHI 2016 原文 Table 3–6）[7]**：
- **感知打扰与接受率的 Logistic 回归**：
  $$\text{Logit}(P(\text{Accept})) = \alpha - 0.581 \cdot \text{Disruption} \quad (\chi^2(1) = 48.3, p < 0.001, 95\%\text{ CI } [0.497, 0.675])$$
  在 5 点李克特量表上，**感知打扰每上升 1 级，通知被用户接受（点击）的几率下降至 0.581 倍**。但该模型的决定系数 $R^2 = 0.1434$，说明打扰度仅能解释一小部分决策，其余受内容与情境共同支配[7]。
- **为什么用户会划掉/忽略通知（Table 4）**：
  - **43.5%** 因“内容不紧急”（Content is not urgent）；
  - **40.6%** 因“内容不重要”（Content is not important）；
  - **38.4%** 因“内容无用”（Content is not useful）；
  - **仅 19.6%** 的用户表示是因为“当时我正在忙”（At this moment, I was busy）。
  - **核心警示**：用户划掉通知的核心原因**根本不是因为“手头忙”，而是因为“内容水”**！这直接为 Fischer 的“内容主导论”提供了铁证。
- **高打扰通知为何仍被点击接受（Table 5）**：
  - 在全样本中被判定为打扰（Disruptive）的 104 条通知中，**仍有高达 54% 被用户点击接收**！其压倒性主因是：“内容有用”（35.2%）与“内容重要”（33.3%），而“当时有空”（At this moment, I was free）仅占 18.5%[7]。
- **大五人格对打扰感的强力支配（Table 6）**：
  - 线性回归表明，大五人格特质能解释 **73.7%** 的平均感知打扰差异（$R^2 = 0.737, F(5,5) = 2.802, p = 0.014$）；其中**外向性（Extroversion）与打扰感呈显著正相关（$\beta = 0.0175, p = 0.0278^*$）**，宜人性（Agreeableness）倾向于承受更少打扰（$\beta = -0.0128, p = 0.063$）[7]。

**综合判断**：时机是**乘数**，内容是**被乘数**。把工程预算全砸在"猜准时机"上，收益会远低于把预算砸在"确保这次开口值得"。同时个体人格特质是打扰感的主导因素，系统必须设立端侧自适应反馈。

---

### 2.3 分级阈值如何设定？

#### 发现 10：分级阶梯应直接对齐 Android 官方重要性语义

把"沉默注视 / 微动作 / 非阻塞气泡 / 系统级通知"映射到平台既有语义，可以让用户的系统设置直接成为分级策略的一部分[23][24]：

| 级别 | 形态 | 技术落点 | 打扰代价（相对） | 典型触发 |
|---|---|---|---|---|
| L0 沉默注视 | 只更新内部状态与记忆，不外显 | 无 UI | ≈ 0 | 驱动在累积但未过阈值 |
| L1 微动作 | 角标、常驻通知文案/图标微调、壁纸/小部件状态变化 | 常驻通知 `IMPORTANCE_MIN` 更新、App Widget、Launcher 角标 | 极低 | 驱动过最低阈值，但打扰代价高 |
| L2 非阻塞气泡 | 通知抽屉内静默条目、`IMPORTANCE_LOW`、Android Bubbles（浮动气泡，Android 17 扩展为系统级 App Bubbles[26]） | `IMPORTANCE_LOW(2)` | 低 | 打扰代价中低 |
| L3 标准通知 | `IMPORTANCE_DEFAULT(3)`：发声、不浮窗 | 需用户"方便时尽快看" | 中 | 打扰代价低 且 驱动较高 |
| L4 高优先级 | `IMPORTANCE_HIGH(4)`：发声 + 浮动通知/全屏意图 | 官方限定为"用户必须立即了解或处理的时效关键信息"[23] | 高 | **仅用于真实紧急；伴生类内容不应占用** |

**硬规则建议**：伴生 Agent 的**情感/关怀/好奇类内容永远不应落到 L4**。L4 在官方语义中被明确限定为"短信、闹铃、电话"级别的时效关键信息[23]；把它用于"我想你了"是典型的**假紧急**，会触发下文的反模式 R4。

#### 发现 11：阈值不是静态常数，应双层——**驱动阈值 + 打断阈值**

Inner Thoughts 给出了明确的两层结构[5]：

- `imThreshold`（内隐主动性，1–5）：想法外显所需的最低内在动机分。实测最优值 **3.59**（Active Contributor）。
- `interruptThreshold`（打断阈值，1–5）：**即使当前说话权不属于自己**，只要动机分超过它，就覆盖轮次分配规则强行插入。

**这个设计对伴生 Agent 是必要的**：常规发言（等用户空闲）与**打断**（用户正忙但有必须说的话）必须是两条不同阈值的通路，否则要么永远插不上话，要么随时乱插话。

#### 发现 12：推荐初始参数组（本报告的工程化建议，非文献原值）

基于 [5] 的实测最优区间与 [14][15] 的"中等最优"交叉验证，建议首版：

```
imThreshold = 3.6（对齐 Inner Thoughts 实测最优 3.59[5]）
interruptThreshold = 4.5（严格高于常规阈值，仅留给紧急/关怀高危情形）
系统通知阈值：仅在 Cost_int(t) 处于最低分位 且 断点事件发生时才允许 L3
默认形态：L1/L2（微动作 + 非阻塞气泡）
沉默增长因子 λ = 1.02（直接采用 [5] 的保守值）
不应期 R：单次表达后 ≥ 60 分钟（对齐 Android 通知冷却的 1 分钟量级之上的保守值[25]）
每日配额：情感/好奇类主动表达 ≤ 2 次/天（对齐"每周 1 条已致约 10% 关闭通知"的行业量级[30]，取保守值）
```

**所有阈值上线后必须以个体化反馈回环收敛**（用户接受 / 忽略 / 划掉 / 长按关闭），而不是写死。PrefMiner 的证据表明：从用户历史通知交互中学出的个性化规则，**仅用"位置 + 通知标题"两个特征就能以 91% 精度预测该通知会被接受还是被忽略**[33][17]——个性化规则的信号含量远高于通用模型。

#### 发现 13：时机决策应优先"等断点"，而不是"现在就发"

综合 Ho & Intille[3]、Attelia[9][10]、Adamczyk & Bailey[4]（事件知觉构建任务模型，在任务边界处打断影响更小）：**当驱动已过阈值但打扰代价偏高时，正确动作不是取消，而是排队等待下一个断点事件**（屏幕状态变化、活动转换、耳机摘戴、应用切换）。Inner Thoughts 的 "Retention" 机制支持这一点：想法可以**保留并在后续变相关时再表达**[5]。

---

### 2.4 有哪些反模式？

| # | 反模式 | 证据 |
|---|---|---|
| **R1** | **话痨模式（Non-stop Chatter）**——高频、低相关地持续输出 | Inner Thoughts 用户研究中该档虽被 4 人选为最佳，但**"多人批评其贡献过多且常不相关"**[5] |
| **R2** | **把"时机优化"当唯一杠杆，忽视内容质量** | Fischer 2010：时机在 SMS 形态下**无显著效应**，内容四维度才显著[32] |
| **R3** | **把"屏幕亮/手机解锁"当作可打断信号** | Mehrotra 2016：响应与打扰由任务类型/完成度/复杂度共同决定[7]；屏幕亮只说明在用手机，不代表愿意被打断 |
| **R4** | **假紧急**：情感内容占用 `IMPORTANCE_HIGH`（发声+浮窗） | Android 官方把 HIGH 限定为"必须立即处理的时效关键信息"[23]；告警领域"cry-wolf 效应"：被判定不可靠的告警系统会**被用户关闭或忽略**[34] |
| **R5** | **不设频率上限与冷却** | 告警疲劳定义即"暴露于过量告警导致响应时间上升或响应率下降"[34]；行业数据显示推送频率上升迅速推高关闭率[30] |
| **R6** | **过度保守**：把阈值调得极高以求"绝不打扰" | Inner Thoughts 的 Selective Participant（imThreshold 4.09）**被 7 人评为最差** [5]；中等主动性在多份独立研究中最优[14][15] |
| **R7** | **用 AccessibilityService 或静默使用 UsageStats 做前台应用检测** | Accessibility API 仅限辅助残障用途，滥用可致 Play 下架[28]；后台收集敏感数据需应用内醒目披露 + 明示同意[29] |
| **R8** | **把"用户无聊"当作唯一窗口，且不限制内容负荷** | Pielot：高兴奋度时用户**主动回避**消遣类建议[11]；且高负荷内容应瞄准中性/负效价时刻[11] |
| **R9** | **在需要集中注意的安全敏感场景用语音/耳机投递** | SoNotify：户外步行接受率 ≈86%，但**需要避让行人/车辆时被拒**[31] |
| **R10** | **一次性全通道开放 + 无偏好设置** | 一旦用户关闭通知，赢回极难[30]；Android 渠道重要性提交后开发者不可修改，只能引导用户改[24] |
| **R11** | **让"推理强度"直接转化为"推送强度"** | ProactiveEval（22 个前沿 LLM）发现**"思维悖论"**：强推理模型在目标规划上更强（DeepSeek-R1 7.60/10），但在对话引导中**变得 pushy**，试图在一条消息里塞进多个子目标[6] |

---

## 三、关键数据与案例

### 3.1 打扰代价的量级

| 数据 | 数值 | 来源 |
|---|---|---|
| 中断后**完全回到原任务**的平均耗时 | **23 分 15 秒** | Mark, Gudith & Klocke, CHI 2008（36 名知识工作者，逐秒记录）[8] |
| 中断原任务当天被恢复的比例 | **81.9%** | 同上[8] |
| 回到原任务前平均插入的**其他任务数** | **≈2 个** | 同上[8] |
| 开启通知提醒（vs 关闭）后的注意缺陷/多动症状 | **显著升高**；注意缺陷进而预测更低的生产力与心理幸福感 | Kushlev, Proulx & Dunn, CHI 2016，N=221，两周交叉实验[35] |

**解读**：一次主动通知的真实账单不是"用户看了几秒"，而是**后续数十分钟的上下文重建**。这直接支持"默认走 L1/L2 非阻塞形态"的设计取向。

### 3.2 时机优化的实际收益

| 系统 | 场景 | 效果 | 来源 |
|---|---|---|---|
| Ho & Intille | 活动转换点投递 | 比随机时机**更受欢迎**（方向性结论） | CHI 2005[3] |
| Attelia | 受控实验 | 认知负荷 **−46%** vs 随机时机 | PerCom 2015[9] |
| Attelia | 30 人 × 16 天 真实环境 | 认知负荷 **−33%** | 同上[9] |
| Attelia II | 41 人 × 1 个月，手机+手表 | 加活动断点后效果 **+71.8%**；手表上 vs 随机 **−19.4%**；跨设备 ≈ **3×** | UbiComp 2015[10] |
| Beyond Interruptibility | 337 人 × 4 周，1.2 亿事件，78,930 条通知 | 相比朴素基线**成功率 +66.6%**；加入用户历史后**提升 5 倍**，并避免约 **1/3 用户**的失败触达 | IMWUT 2017[11] |
| PrefMiner | 真实环境 | 仅用位置+标题预测接受/忽略，**精度 91%**；实部署提出 179 条规则，**56.98% 被用户接受**；过滤 unwanted 通知准确率 **45.81%** | Mehrotra 等，经 [17] 综述转引 |
| SoNotify（耳戴） | 8 人田野 | 户外步行语音通知接受率 **≈86%**；需集中避让时**倾向拒绝** | UbiComp'24 Companion[31] |

**关键矛盾**：PrefMiner 的**离线精度 91%** 与**实部署过滤准确率 45.81%** 之间存在巨大落差[17]。这是本轮调研最重要的警示之一：**离线可中断性指标严重高估线上表现**。

### 3.3 主动性等级的用户接受度

| 研究 | 设计 | 结论 |
|---|---|---|
| Inner Thoughts, CHI'25[5] | 三档 Agent（话痨 / 中等 / 保守），用户偏好投票 | **中等（6 票最佳）> 话痨（4 票）> 保守（2 票最佳，7 票最差）** |
| Villa et al., BIT 2026[14] | mPLiCA 分级视频情景评估 | 等级越高越有用、也越侵扰；**中等（对话式）最可接受** |
| Kraus et al.（转引自 THRI 综述）[15] | Notification / Suggestion / Intervention 三档 | **中等档最优** |
| Peng et al.（转引自 [15]） | 决策支持中的主动性等级 | **中等优于最高** |
| Deutsch et al.（转引自 [15]） | 老年人对家用机器人主动性 | 25 条相关表述中 **16 条反对**，顾虑自主性与控制感丧失 |
| Zhang et al.（转引自 [15]） | 协作搜救中的主动支持 | 用户**偏好**有主动支持的机器人，但**认知负荷更高** |

### 3.4 案例：Android 自己的答案（可作为产品参照系）

Android 17 把「通知摘要（AI 汇总）/ 通知整理（按重要性分组）/ 通知冷却（突发降噪）」统一为 **Notification Intelligence**[27]。三者的共同点是：**都不试图替用户决定"该不该看"，而是降低"看"的成本**——汇总压缩数量、整理压缩决策成本、冷却压缩突发音量。

**对伴生 Agent 的启示**：不要只做"我要不要发"，还要做"**我发的这条在用户的通知流里会占多少成本**"。优先把自己塞进"摘要/整理"友好的形态（短、可一眼扫完、有明确 actionability —— 对应 Fischer 的 actionability 维度[32]）。

---

## 四、争议或局限性

### 4.1 时机优化到底有多大用？——文献内部存在直接冲突

- 支持方：Ho & Intille[3]、Attelia[9][10]、Pielot[11] 都报告了显著的正向效果。
- 反对方：Fischer 等[32] 在自然情境下**未发现时机显著效应**，只有内容显著。
- 折衷解释：Attelia 的收益主要来自**"断点"这一特定时机语义**（事件边界），而非通用的"最佳时刻预测"；Fischer 实验中的"合适/不合适时机"是用户自报的时间窗，粒度更粗。**两者并非完全互斥，但说明"通用时机预测"的收益远小于"断点检测"。**
- 另一重折衷：PrefMiner 的线上过滤准确率仅 45.81%[17]，说明即便离线模型很漂亮，线上也不到一半。

### 4.2 样本与泛化

- Fogarty 等明确声明其样本"**人口统计学上受限**"[1]；Attelia 系列为日本样本；Beyond Interruptibility 的 337 人为 Telefónica 研究招募（含欧洲）[11]；SoNotify 仅 **8 人**，作者自己标注为 *preliminary*[31]。
- 大量中断研究基于西方办公室知识工作者；**移动端伴生场景（私人时间、睡前、通勤）覆盖不足**。
- 2024–2026 的 LLM 主动性工作（ProactiveEval[6]、ContextAgent[16]）几乎都是**静态基准或模拟评估**，**缺乏长期 in-the-wild 数据**；Inner Thoughts 的用户研究规模也很小[5]。

### 4.3 内在驱动的跨领域迁移未经验证

好奇心/学习进展模型（IAC[19][20]）来自**发展机器人学**；无聊的操作性定义[18]来自**注意与情绪心理学**；主动性分级结论来自**HRI**[14][15]。**三者被直接搬到"手机里的陪伴型对话 Agent"上，缺少端到端实证**。本报告把它们组合成驱动向量，是**有依据的推断，不是被验证的结论**。

### 4.4 平台与合规约束会直接改变技术选型

Google Play 对 Accessibility API 与后台数据收集的限制[28][29]，加上 Android 逐步收紧的使用情况访问，意味着**"丰富情境感知"在移动端并不总是可得**。学术研究（IntrruptMe、Attelia）中大量使用的传感器特征，在合规上架的产品里可能不可用或需要显著披露成本。

### 4.5 商业指标与用户福祉的冲突

伴生产品的主流优化目标是 CPS / 留存[12]，而主动性天然服务于这个目标。XiaoIce 明确以**长期**期望 CPS 为指标并说明短期 CPS 具有可博弈性[12]——这个设计自觉值得肯定，但也说明**用参与度指标驱动主动性存在结构性利益冲突**。Cite 的 ADA Lovelace Institute 分析指出，陪伴类产品"以吸引注意力为产品策略，通常提供用户无法抗拒的东西而非用户需要的东西"[36]（**注：该来源为机构分析文章，非同行评议**）。

---

## 五、结论与建议

### 5.1 六条结论

1. **主动性必须建模为连续驱动量，而非触发规则。** 泄漏积分 + 饱和上限 + 表达后抑制 + 不应期，四个组件缺一不可；沉默增长因子要保守（λ≈1.02[5]）。
2. **中等主动性是最优解，过度保守比过度主动更糟。** Inner Thoughts（6:2 最佳票，保守档 7 票最差）[5]、Villa et al.[14]、Kraus/Peng[15] 三方独立收敛。
3. **期望打扰代价（Horvitz 形式[2]）是正确的裁决框架**，且用廉价传感器就能达到约 78% 的可中断性预测准确率，可显式调偏保守[1]。
4. **断点是唯一被反复验证的时机信号**；屏幕状态要当**边沿**用，不要当**电平**用[3][9][10]。
5. **内容 > 时机**：actionability / relevance / interest / entertainment 才是主效应[32]；把预算全砸在时机上收益有限。
6. **分级阶梯对齐 Android 官方重要性语义**，情感类内容永久封顶在 LOW/DEFAULT，HIGH 只留给真实紧急[23][24]。

### 5.2 可落地的裁决管线（建议架构）

```
① 驱动层（每 tick 更新）
   C/B/W/U 四个泄漏积分器 → D(t) = Σ w_i · D_i(t)

② 内容层（每次候选想法）
   按 Inner Thoughts 8 条启发式打分（Relevance / Information Gap /
   Expected Impact / Urgency / Coherence / Originality / Balance / Dynamics）[5]
   → Q ∈ [1,5]

③ 打扰代价层（每 tick 更新）
   Cost_int(t) = Σ_s P(state_s | 轻量信号) · c_s
                × m(形态) × g(任务完成度) × r(距上次打扰时长)

④ 裁决层
   Urgency = Q × λ^(t−τ) − Cost_int(t) − H(习惯化抑制)
      Urgency < imThreshold(3.6)        → L0 沉默注视（保留想法，等待 Retention[5]）
      imThreshold ≤ Urgency < 打扰代价门限 → L1 微动作 / L2 非阻塞气泡（若断点事件已到）
      Urgency ≥ 打扰代价门限 且 断点事件 → L3 DEFAULT 通知（受每日配额约束）
      Urgency ≥ interruptThreshold(4.5) → 允许打断（仅 U 类紧急 / W 类高危关怀）

⑤ 反馈层
   接受 / 忽略 / 划掉 / 关闭渠道 → 在线调整 w_i、各阈值、配额
```

### 5.3 分阶段落地建议

| 阶段 | 动作 | 验收指标 |
|---|---|---|
| P0（1–2 周） | 只做 L0/L1：驱动累积 + 等待断点 + 微动作表达；**完全不发系统通知** | 驱动分布、断点检测率、微动作被注意率 |
| P1 | 开 L2（LOW 静默通知），开每日配额与冷却 | 打开率、忽略率、**7 日通知关闭率**（红线：>5% 立即回退） |
| P2 | 引入个体化阈值（借鉴 PrefMiner 思路[17]，只用低风险特征） | 个体阈值收敛稳定性 |
| P3 | 谨慎开 L3，仅限高 actionability 内容 | 卸载率、长期留存、CPS 的**长期**值（对齐 XiaoIce 的长期口径[12]） |

**红线指标**：通知关闭率、渠道被用户降级（Android 用户可随时改渠道重要性[24]）、卸载率。这三项任一恶化，主动性系统必须自动降档——**这是防止"活跃度指标"绑架"打扰度"的唯一有效闸门**。

---

## 六、不确定与未证实清单

以下条目中，部分已在二次深度解析中补全，其余仍属**未能取到原文数值或未能直接验证**事项，请严格辨析使用：

1. **【已补全】Horvitz & Apacible (ICMI 2003)[2] 的模型准确率与消融数值**：已于 2026-09-19 从原文正文提取。模型全事件预测准确率 S1: 73%, S2: 64%（边际基线 53%, 37%）；纯系统事件已达 64%/60%，消融证实视听感知增益极低；跨用户迁移崩溃至 28%（详见 2.2 节发现 5）。
2. **【已补全】Mehrotra et al. CHI 2016[7] 的具体效应量**：已于 2026-09-19 从原文正文提取。感知打扰每升 1 级使接受概率下降至 0.581 倍（p<0.001）；40%+ 用户划掉通知归因于内容不重要/不紧急/无用，仅 19.6% 归因于忙碌；大五人格解释了 73.7% 的打扰方差（详见 2.2 节发现 9）。
3. **Attelia 的百分比口径不一致**：不同来源给出"−46% / −33% 认知负荷"（PerCom 2015[9]）与"−37% / −28% 挫败感"（作者主页[9]）两套数字，口径与测量指标不同，**不可混用**。
4. **Adamczyk & Bailey (CHI 2004)[4] 的具体效应量**：仅取到摘要，确认"不同打断时刻对情绪状态与社会归因影响不同"，未取到数值。
5. **Push 关闭率/卸载率的量化数据**（每周 1 条 → 约 10% 关闭；每周 3–6 条 → 约 40% 关闭；Android 加入率中位数 81% / iOS 51%）**全部来自行业报告与商业博客**[30]，**非同行评议文献**，仅作量级直觉参考，不可写进对外材料。
6. **"Notification Intelligence"的具体算法**：已确认为 Android 17 QPR2 Beta 4 中对既有三项功能的**重新分组**，Google 未公开底层算法[27]；是否有后续新功能**未知**。
7. **Android 17 App Bubbles 与伴生场景的适配性**：确认 Android 17 引入系统级浮动 App Bubbles[26]，但**未验证**其在常驻伴生 Agent 场景下的可用性与政策限制。
8. 本报告 5.2 节的**具体公式形式与推荐参数**（乘性代价分解、imThreshold 3.6、interruptThreshold 4.5、每日配额 2 次、不应期 60 分钟）是**基于文献区间的工程化合成建议**，其中仅 imThreshold 3.59/4.09 与 λ=1.02 有文献原值支撑[5]；其余需在自家数据上标定。

---

## 七、来源清单

| 编号 | 来源 | 类型 / 时效 |
|---|---|---|
| [1] | Fogarty J., Hudson S.E., Atkeson C.G., Avrahami D., Forlizzi J., Kiesler S., Lee J.C., Yang J. *Predicting human interruptibility with sensors*. CHI 2003 / ACM TOCHI 12(1), 2005. | ACM CHI / TOCHI · 2003–2005 |
| [2] | Horvitz E., Apacible J. *Learning and Reasoning about Interruption*. ICMI 2003. | ACM ICMI · 2003 |
| [3] | Ho J., Intille S.S. *Using context-aware computing to reduce the perceived burden of interruptions from mobile devices*. CHI 2005. | ACM CHI · 2005 |
| [4] | Adamczyk P.D., Bailey B.P. *If Not Now, When? The Effects of Interruption at Different Moments Within Task Execution*. CHI 2004. | ACM CHI · 2004 |
| [5] | Liu X.B., Fang S., Shi W., Wu C.-S., Igarashi T., Chen X.'A'. *Proactive Conversational Agents with Inner Thoughts*. CHI 2025 / arXiv:2501.00383. | ACM CHI · 2025（arXiv v2 2025-02-18） |
| [6] | Liu T., Wan F., Guo J., Quan X., Liu T.-N., Guo J. *ProactiveEval: A Unified Evaluation Framework for Proactive Dialogue Agents*. arXiv:2508.20973. | arXiv 预印本 · 2025 |
| [7] | Mehrotra A., Pejovic V., Vermeulen J., Hendley R., Musolesi M. *My Phone and Me: Understanding People's Receptivity to Mobile Notifications*. CHI 2016. | ACM CHI · 2016 |
| [8] | Mark G., Gudith D., Klocke U. *The Cost of Interrupted Work: More Speed and Stress*. CHI 2008.（23分15秒 / 81.9% / 2 个插入任务） | ACM CHI · 2008 |
| [9] | Okoshi T., Ramos J., Nozaki H., Nakazawa J., Dey A.K., Tokuda H. *Attelia: Reducing user's cognitive load due to interruptive notifications on smart phones*. PerCom 2015. | IEEE PerCom · 2015 |
| [10] | Okoshi T., Ramos J., Nozaki H., Nakazawa J., Dey A.K., Tokuda H. *Reducing users' perceived mental effort due to interruptive notifications in multi-device mobile environments*. UbiComp 2015. | ACM UbiComp · 2015 |
| [11] | Pielot M., Cardoso B., Katevas K., Serrà J., Matic A., Oliver N. *Beyond Interruptibility: Predicting Opportune Moments to Engage Mobile Phone Users*. IMWUT 1(3), 2017（UbiComp 2017）；及 Pielot 研究博客关于情绪与内容选择的部分。 | ACM IMWUT · 2017 |
| [12] | Zhou L., Gao J., Li D., Shum H.-Y. *The Design and Implementation of XiaoIce, an Empathetic Social Chatbot*. Computational Linguistics 46(1), 2020. | ACL / MIT Press · 2020 |
| [13] | Rivoire C., Lim A. *The Delicate Balance of Boring and Annoying: Learning Proactive Timing in Long-Term Human Robot Interaction*. ICMI 2016 Workshop. | ACM ICMI Workshop · 2016 |
| [14] | Villa L., Hervás R., Cabañero L., Fontecha J., López G., Favela J. *Evaluating proactivity levels in socially assistive robots for elderly care: a user adoption assessment*. Behaviour & Information Technology 45(6), 2026. | Taylor & Francis · 2026 |
| [15] | *What is Proactive Human-Robot Interaction? A Review of a Progressive Field and Its Definitions*. ACM Transactions on Human-Robot Interaction, 2024. doi:10.1145/3650117 | ACM THRI · 2024 |
| [16] | Yang B. 等. *ContextAgent: Context-Aware Proactive LLM Agents with Open-World Sensory Perceptions*. arXiv:2505.14668（NeurIPS 2025 收录）。 | arXiv / NeurIPS · 2025 |
| [17] | Mehrotra A., Musolesi M. *Intelligent Notification Systems: A Survey of the State of the Art and Research Challenges*. arXiv:1711.10171. | arXiv 综述 · 2017–2018 |
| [18] | Eastwood J.D., Frischen A., Fenske M.J., Smilek D. *The Unengaged Mind: Defining Boredom in Terms of Attention*. Perspectives on Psychological Science, 2012. | APS 期刊 · 2012 |
| [19] | Oudeyer P.-Y., Kaplan F., Hafner V.V. *Intrinsic Motivation Systems for Autonomous Mental Development*. IEEE Transactions on Evolutionary Computation, 2007. | IEEE TEVC · 2007 |
| [20] | Kaplan F., Oudeyer P.-Y. *In search of the neural circuits of intrinsic motivation*. Frontiers in Neuroscience, 2007. | Frontiers · 2007 |
| [21] | *A transdisciplinary view on curiosity beyond linguistic humans*. Biological Reviews / Wiley, 2024.（knowledge-based vs competence-based 内在动机分类） | 跨学科综述 · 2024 |
| [22] | Weiser M., Brown J.S. *The Coming Age of Calm Technology*, 1996（修订自 *Designing Calm Technology*, PowerGrid Journal 1.01, 1996）。 | Xerox PARC · 1996 |
| [23] | Android Developers — 通知设计指南（重要性 HIGH/DEFAULT/LOW/MIN 语义与用法示例）。developer.android.com/design/ui/mobile/guides/home-screen/notifications | Google 官方 · 检索于 2026-09 |
| [24] | Android Developers — 创建和管理通知渠道（重要性等级与 PRIORITY 映射、IMPORTANCE_* 常量、渠道提交后不可更改）。developer.android.com/training/notify-user/channels | Google 官方 · 检索于 2026-09 |
| [25] | Google 官方支持 — Android 通知控制 / 通知冷却说明。support.google.com/android/answer/9079661 | Google 官方 · 检索于 2026-09 |
| [26] | Android 17（Cinnamon Bun，API 37）发布与特性资料（2026-06-16 稳定发布；含 App Bubbles）。 | 公开资料 · 2026 |
| [27] | Android 17 QPR2 Beta 4 发布说明（2026-08-28，新增 Notification Intelligence 设置区、状态栏设置）。 | 公开资料 · 2026-08 |
| [28] | Google Play 开发者计划政策 — 受限权限与 API（Accessibility API 仅限辅助残障用途等）；以及 Android 前台应用检测技术方案对比（UsageStatsManager vs AccessibilityService）。 | Google 官方政策 + 开发者资料 · 检索于 2026-09 |
| [29] | Google Play 开发者计划政策 — 醒目披露与同意要求（后台收集个人/敏感数据须应用内显著披露 + 明示同意）。support.google.com/googleplay/android-developer/answer/16944162 | Google 官方政策 · 检索于 2026-09 |
| [30] | 推送通知行业基准（加入率、关闭率、频率—关闭关系）。businessofapps.com / dev.to 等 | **商业来源，非同行评议** · 2026 |
| [31] | Ito M., Tsubouchi K., Nishio N., Shimosaka M., Taya A., Sezaki K., Nishiyama Y. *Investigating Acceptable Voice-based Notification Timings through Earable Devices: A Preliminary Field Study*. UbiComp'24 Companion, 2024（SoNotify；N=8，户外步行接受率 ≈86%）。 | ACM UbiComp Companion · 2024 |
| [32] | Fischer J.E., Yee N., Bellotti V., Good N., Benford S., Greenhalgh C. *Effects of content and time of delivery on receptivity to mobile interruptions*. MobileHCI 2010. | ACM MobileHCI · 2010 |
| [33] | Mehrotra A., Musolesi M., Hendley R., Pejovic V. *Designing content-driven intelligent notification mechanisms for mobile applications*. UbiComp 2015（PrefMiner 相关，数值经 [17] 转引）。 | ACM UbiComp · 2015 |
| [34] | *ATC Signaling Systems: A Review of the Literature on Alarms, Alerts, and Warnings*（FAA 资助，DOT/ROSAP 发布）——告警疲劳定义与 cry-wolf 效应（Breznitz 1984）。 | 政府技术报告 · 检索于 2026-09 |
| [35] | Kushlev K., Proulx J., Dunn E.W. *"Silence Your Phones": Smartphone Notifications Increase Inattention and Hyperactivity Symptoms*. CHI 2016（N=221）。 | ACM CHI · 2016 |
| [36] | Ada Lovelace Institute — *Friends for sale: the rise and risks of AI companions*（陪伴产品的注意力策略与谄媚倾向分析）。 | **机构分析文章，非同行评议** |
| [37] | Chen V., Zhu A., Zhao S., Mozannar H., Sontag D., Talwalkar A. *Need Help? Designing Proactive AI Assistants for Programming*. CHI 2025. | ACM CHI · 2025 |

---

*报告完 · 检索截止 2026-09-19*
