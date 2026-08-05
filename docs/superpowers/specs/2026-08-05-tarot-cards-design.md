# 塔罗牌前端卡牌可视化 — 设计文档

日期：2026-08-05
状态：已确认（方案 A：复用 content_and_artifact 契约）

## 背景

小汐的塔罗工具（`backend/tools/tarot.py`）目前只输出人格化纯文本，78 张韦特牌的数据（keywords/element/symbol/正逆位各 5 条含义）只展示了每张牌一条随机含义，牌面丰富度浪费。参考 `E:\SonettoHere-main\tools\entertainment\tool_tarot.py` 后确定方向：**前端卡牌可视化展示**——后端输出结构化数据，前端渲染成可翻牌的卡牌。

关键事实：
- 两份 `tarot_deck.yaml` 内容一致（仅引号转义差异），数据无需改动
- 地图工具已存在 `content_and_artifact` 契约（LLM 只见文本、artifact 走 SSE 下行），塔罗同构接入即可

## 产品决策（用户已确认）

1. 方向：前端卡牌可视化展示
2. 牌面视觉：CSS 绘制牌面（不生成图片，留未来图位）
3. 交互：洗牌后逐张翻牌动画（reduced-motion 降级为直接显示）
4. 牌面信息：简洁款（牌名 + 象征符号 + 元素/花色图标 + 2-3 关键词 + 底部一条含义）
5. 布局：自适应网格（单牌 1 居中 / 三牌 3 列 / 凯尔特十字 2×5 紧凑）

## 架构与数据流

```
浏览器                                    FastAPI
useChat.js ←─SSE tool_end─ chat.py ←─ToolMessage.artifact─ tarot.py
   │ tool_end {name:'tarot', tarot:{...}}          LLM 只见 content 文本
   ▼
MessageBubble → tool_hint 分支 → TarotCards 组件（卡背 → 逐张翻牌）
```

- LLM 链路零改动：`content` 仍是现有人格化解读文本（LLM 写回复的素材）；`artifact` 只在 SSE 下行给前端
- 降级兜底：塔罗出错/牌组损坏时返回 `(友好文本, None)` → 前端无卡片数据，回退为现有文本折叠块
- 卡片数据到达时才出现牌背，数据驱动翻牌，无中间状态

## 后端改动

### `backend/tools/tarot.py`（约 30 行）

1. 工具签名改 `@tool(response_format="content_and_artifact")`，返回 `(str, dict | None)`
2. artifact 结构：

```json
{
  "spread_type": "three",
  "spread_name": "三牌占卜",
  "cards": [{
    "name": "恋人", "symbol": "VI", "suit": "大阿尔卡纳", "element": "风",
    "keywords": ["吸引力", "真爱", "选择"],
    "is_reversed": false, "position": "过去",
    "fortune": "……本次抽到的含义……",
    "meaning": ["……", "……"]
  }]
}
```

3. 所有降级分支（空牌组/数据损坏/凑不齐/无问题）→ `(友好文本, None)`
4. 对齐参考实现：非法 `spread_type` 从静默回退改为友好报错列可选值

### `backend/api/routes/chat.py`（约 15 行）

5. `_tool_end_payload` 泛化：artifact 为 dict 且校验通过 → `payload["tarot"]`（与 `nav` 并列互不干扰）；校验失败只透传文本，不炸流

### 后端测试

- `test_tarot.py`：适配 `content_and_artifact` 返回值断言 + 非法牌阵友好报错用例
- `test_tool_end_payload.py`：tarot 透传用例 + 脏数据拒发用例

## 前端视觉与交互（TarotCards）

全部使用现有粉色主题 token（cream 底 / plum 文字 / strawberry 主色 / blush 描边 / honey 星光金），圆体 `--font-display`，对比度纪律（<12px 彩色文字用 raspberry）。

### 卡背（翻牌前）

奶油粉底 + blush 波点纹理（radial-gradient 平铺）+ 中央 honey 金色星星（twinkle 呼吸微光）+ strawberry/40 描边、14px 圆角、轻阴影。

### 牌面（简洁款）

- 顶部：位置 pill（strawberry/15 底 raspberry 字）；逆位徽标（raspberry 底白字，仅逆位牌）
- 中部：大牌罗马数字象征 + 元素/花色图标（权杖🔥 圣杯💧 宝剑⚔️ 星币🪙，大阿尔卡纳🌟）；牌名（圆体 font-display、plum）
- 关键词 2-3 个 pill（blush/40 底 plum/70 字）
- 底部：本次含义一条（10px、plum/50）
- 白→cream 微渐变底 + blush 描边，与聊天气泡质感一致

### 翻牌动画

1. 卡片组 fade-in-up 入场（复用现有 token）
2. 每张牌错峰 120ms 依次 3D 翻转（rotateY 0→180°，600ms cubic-bezier(0.16,1,0.3,1)），翻转中卡背掠过 honey 微光
3. 翻开瞬间牌面中央星星 twinkle 闪一下
4. 全部翻完后整组轻微「落地」settle（scale 1.02→1，300ms）
5. prefers-reduced-motion → 跳过翻转直接显示正面（matchMedia 检查）

### 布局

| 牌阵 | 布局 | 牌面信息 |
|------|------|---------|
| 单牌 | 1 张居中（宽卡 ~140px） | 全信息 |
| 三牌 | 3 列横排 | 全信息 |
| 凯尔特十字 | 2 行×5 列紧凑网格 | 位置缩写 + 图标 + 牌名（含义由文本承载） |

### 前端文件改动

- `web/src/utils/tarot.js`（新）：`isValidTarot()` 严格 shape 校验（与 `mapLink.isValidNav` 同风格），脏数据 → null → 不渲染纯文本兜底
- `web/src/hooks/useChat.js`：tool_end 分支加 `const tarot = isValidTarot(parsed.tarot) ? parsed.tarot : null`，与 output/nav 同一次 setMessages 原子挂载
- `web/src/components/MessageBubble.jsx`：tool_hint 分支 `{message.tarot && <TarotCards tarot={message.tarot} />}`，插在折叠文本块下方（文本折叠保留）
- `web/src/components/TarotCards.jsx`（新）：spread_type 决定网格列数；卡背→翻牌状态机（'back'→'flipping'→'face'），CSS transition + 少量 JS 定时器 stagger，无动画库依赖

### 前端测试

- `utils/tarot.test.js`：isValidTarot 严格用例（缺字段/空 cards/非数组 → false）
- `TarotCards.test.jsx`：三种牌阵网格类名、初始卡背、fake timers 翻牌后显示牌面、reduced-motion（mock matchMedia）直接正面、逆位徽标、脏数据 null
- `useChat.test.js`：tool_end 带 tarot 挂载 + 脏 tarot 不挂载

## 非目标

- 不生成/不采集 78 张牌面图（CSS 牌面为主，未来可在牌面留图位）
- 不改 LLM 人格文本内容（content 保持现状）
- 不做牌阵以外的交互（点牌展开完整含义等留待后续）

## 验收清单

- [ ] 后端测试全绿（tarot 改造 + tool_end_payload 透传）
- [ ] 前端测试全绿（tarot 校验 + TarotCards + useChat 挂载）
- [ ] 冒烟：真实对话抽三牌，聊天里出现卡背 → 逐张翻牌 → 3 列牌面
- [ ] 凯尔特十字 2×5 紧凑卡渲染正常
- [ ] 无卡片数据时（工具降级）纯文本兜底，旧协议行为不变
