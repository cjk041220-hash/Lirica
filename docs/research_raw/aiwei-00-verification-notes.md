# aiwei 调研 · 核验记录（2026-08-30）

> 本文件记录「人机感 / AI 味」调研中**经 Bash curl 一手核验**的证据与网络可达性地图。
> 与 `aiwei-01-products.md`（产品侧）、`aiwei-02-oss.md`（开源工程侧）、`aiwei-03-perception.md`（感知机制侧）配套。
> 用途：后续联网环境下补核验，以及给「哪些话可信、哪些只是训练知识」划线。

## 0. 网络环境（本轮硬约束，重要）

- **WebSearch / WebFetch 工具层全部失效**（空返回 / 「无法验证域名安全」），但 **Bash + curl 可出网**，且命中一个**放行名单**：
  - ✅ 可达：`arxiv.org` / `export.arxiv.org`(https) / `docs.sillytavern.app` / `raw.githubusercontent.com` / `api.github.com` / `aclanthology.org` / `zhihu.com` / `bilibili.com` / `hf.co`(301) / `paperswithcode.com`(302) / `kindroid.ai`(host 通,路径需现查)
  - ❌ 不可达：`github.com` 主站、`huggingface.co`、`openreview.net`、`wikipedia.org`、`character.ai`、`help.character.ai`、`support.replika.com`、`www.nomi.ai`、`docs.minimax.io`、`r.jina.ai`
- 推论：凡是「产品内部机制」「人物采访」「论文原文不在 arxiv/aclanthology 的」均无法本轮在线核验 → 归 ⚠️ 训练知识档。

## 1. 置信档位（整次调研共用）

- **✅ 一手已核验**：下方带 URL 的 curl 抓取。
- **📄 仓库既有经核验调研**：`docs/research_raw/dim-01.md`、`docs/PERSONA_DEEP_DIVE_2026-08-10.md`（撰写时核过）。
- **⚠️ 训练知识·未联网核验**：产品（c.ai/Replika/Kindroid/Nomi/星野等）内部机制、The Aliveness Framework 具体支柱原文、感知实验数据。引用时明确标注，不冒充一手。
- **❌ 待补核验清单**：见 §4。

## 2. 一手核验到的开源工程侧证据

### 2.1 反 AI 味「技能」生态（GitHub 检索，api.github.com/search/repositories）
- `Leonxlnx/taste-skill` — 82,260 ★「gives your AI good taste. stops the AI from generating boring, generic slop」（README 已抓，来源 https://github.com/Leonxlnx/taste-skill ）
- `Nutlope/hallmark` — 27,520 ★「Anti-AI-slop design skill for Claude Code, Cursor, and Codex」
- `hardikpandya/stop-slop` — 16,577 ★「A skill file for removing AI tells from prose」
- 结论：anti-slop 已成为「可安装的技能」这一品类（skill 文件分发），且有 8 万星级项目 → 反 AI 味是可工程化的，不只是提示词玄学。

### 2.2 stop-slop 技能体（✅ 一手，https://raw.githubusercontent.com/hardikpandya/stop-slop/main/SKILL.md）
8 条核心规则 + 快速检查，可复用到小汐的生成后校验：
1. 删填充短语（清喉咙开场、强调拐杖、所有副词）
2. 打破公式化结构（二元对立、负面列举、戏剧性碎片句、设问开场、假自主）
3. 用主动语态，具体主语
4. 要具体（禁止「the reasons are structural」式空洞陈述）
5. 把读者放进现场（「你」胜过「人们」）
6. 变化节奏、混搭句长、避免破折号
7. 相信读者，直接给事实（去软化、去免责、去手把手）
8. 删金句（像 pull-quote 的就重写）

### 2.3 stop-slop `references/phrases.md`（✅ 一手）——「AI 腔短语清单（英文侧）」分类
- 清喉咙开场：Here's the thing / It turns out / The uncomfortable truth is / Let me be clear / I'm going to be honest…
- 强调拐杖：Full stop / Let that sink in / This matters because / Make no mistake…
- 商务腔：navigate→handle、unpack→explain、deep dive、game-changer、circle back…
- 副词（尽杀）：really / just / literally / genuinely / simply / actually / deeply / truly / interestingly / importantly…
- 填充语：At its core / It's worth noting / At the end of the day / When it comes to / The reality is…
- 元评论：Hint: / Plot twist: / X is a feature, not a bug / Let me walk you through…
- 假亲密：creeps in / I promise / They exist, I promise…
- 讲而不示：This is genuinely hard / This is what X actually looks like…
- 空泛宣言：The implications are significant / The stakes are high…
- ⚠️ 该清单是英文写作向；中文侧的「AI 腔」清单位于 aiwei-02，本身多为社区经验（训练知识），需在放行名单外找一手（知乎/B站在线只到能搜到的情况）。

### 2.4 角色卡 / 角色人格锚（✅ 一手）
- 角色卡规范 V3 仓库 `kwaroran/character-card-spec-v3`（107★，SPEC_V3.md 已抓）：
  https://raw.githubusercontent.com/kwaroran/character-card-spec-v3/main/SPEC_V3.md
  （字段、embedding、装饰器语义；完整抽取见 aiwei-02 综合。）
- **SillyTavern「角色设计」官方文档**（✅ 一手 https://docs.sillytavern.app/usage/core-concepts/characterdesign/）确认：
  - Description/Personality/Scenario = **永久 token**（每轮必发）
  - **First Message → 风格锚**：「模型最可能从第一条消息学风格与长度，胜过其他任何东西」
  - Example messages → 只在历史未填满时保留，可强制常驻
  - token 预算警告：2048 上下文里 1000-token 人格定义 = 对话记忆减半
  - 官方背书的三份人格写法指南：Trappu's PLists / AliCat's Ali:Chat / kingbri 极简指南
- **SillyTavern「Author's Note」官方文档**（✅ 一手 https://docs.sillytavern.app/usage/core-concepts/authors-note/）确认：
  - 任意位置 + 任意频率注入
  - Depth 0 = 会话结尾（影响最大）；Depth 4 = 最近 3 条之前；越靠底影响越大
  - Frequency N = 每 N 条用户输入注入一次
  - 官方范例「[Remember the instructions you were given at the beginning of this chat.]」= 重锚定机制（与 PERSONA_DEEP_DIVE §2.1 断言一致，现有一手出处）

## 3. 对「防 AI 味」的工程化启示（核验后更硬的三条）
1. **反 AI 味可做「生成后校验」**：stop-slop 证明了 8 类规则 + 打分维度的技能化可行；小汐可在主动消息/日记落地 `_StyleCritic`（规则台账 + LLM-as-judge 兜底）。
2. **风格来自锚（First Message/示例）与重锚定（Author's Note）**：示例对话项目的「情绪面分组 + 视角/句式差异化」不是玄学，是有官方文档背书的做法；重锚定用「每 N 轮重申一句话」有明确现成机制可抄。
3. **token 预算=人格与记忆的隐形成本**：人格串越沉，「记住的对话」越少，越容易靠模板凑 → 压缩人格、把恒产从 prompt 挪到检索，是反 AI 味的架构位。

## 4. 待联网补核验清单（✅ 之外皆需）
- taste-skill 的 SKILL 体全文（含具体 slop 习语列表）——本轮只抓了 README
- Character.AI 官方 long-term memory / Definition 文档页（help.character.ai 不可达）
- Replika / Kindroid / Nomi 官方 help 与 FAQ（不可达）
- 星野 / 筑梦岛 / 猫箱 创作者手册（minimax docs 不可达；zhihu 可达但需搜索）
- The Aliveness Framework 原文及其承载页
- arXiv 感知侧：`uncanny valley`文本对应、人工真实性实验、verbal style matching——本轮 arxiv API 命中稀疏
- 中文 AI 腔清单的一手出处（当前为社区/训练知识）

## 5. 已核验源速查（curl 可达）
| 源 | 路径 |
|---|---|
| stop-slop | https://github.com/hardikpandya/stop-slop （SKILL.md / references/phrases.md） |
| taste-skill | https://github.com/Leonxlnx/taste-skill |
| hallmark | https://github.com/Nutlope/hallmark |
| 角色卡 V3 | https://github.com/kwaroran/character-card-spec-v3 |
| ST 角色设计 | https://docs.sillytavern.app/usage/core-concepts/characterdesign/ |
| ST Author's Note | https://docs.sillytavern.app/usage/core-concepts/authors-note/ |
| ST 文档 sitemap | https://docs.sillytavern.app/sitemap.xml |