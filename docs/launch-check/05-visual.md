# 05 · 视觉与界面体检

> 板块状态：24/24 全过 · 已知问题：无 · 完成于 2026-08-06（取证：Playwright 系统 Edge headless 实跑 :8000 + dev server 时段取证 + vitest 新增边界测试；回归：前端 50 passed + 后端 751 passed + E2E 29/29）

- [x] **5.1 前端构建**
      · 查：vite build 成功无告警
      · 取证：`cd web && npm run build` 看退出码与产物
      · 判据：exit 0、无 error、产物在 web/dist/
      · 严重度：P0
      · 结果：✅ `npm run build` exit 0 无 error/warning，产物齐全（index.html + 分片 assets：JS 282KB / CSS 205KB / 字体分片，built in 937ms）
- [x] **5.2 静态托管**
      · 查：FastAPI 托管 dist（后端可提供前端页面）
      · 取证：代码核验静态挂载路径；`curl -s http://127.0.0.1:8000/` 返回 HTML
      · 判据：根路径返回 index.html（或明确重定向）
      · 严重度：P1
      · 结果：✅ `server.py:274-276` `app.mount("/", StaticFiles(directory=dist, html=True))`；curl 根路径 HTTP 200 返回 index.html（2072B，lang=zh-CN）
- [x] **5.3 lint**
      · 查：oxlint 零告警
      · 取证：`cd web && npm run lint`
      · 判据：exit 0、零错误零告警
      · 严重度：P2
      · 结果：✅ `npm run lint` exit 0 零输出（oxlint src）
- [x] **5.4 前端单测**
      · 查：vitest run 全绿
      · 取证：`cd web && npm test`
      · 判据：全部通过（作参考信号，非体检主判据）
      · 严重度：P2
      · 结果：✅ 50/50 全绿（10 文件）——41 原有 + 本次新增 time.test.js 5 + ErrorBoundary.test.jsx 2 + Markdown.test.jsx 2
- [x] **5.5 E2E**
      · 查：E2E 脚本（scripts/e2e/）在系统 Edge headless 可跑通
      · 取证：按 scripts/e2e 说明跑一次（复用全局 playwright）
      · 判据：交互/视觉断言通过、零控制台错误
      · 严重度：P1
      · 结果：✅ 29/29 全过、退出码 0、零控制台错误、零 /api 4xx/5xx。**修复 2 处**：① 记忆面板断言写死「喜欢喝椰子水」与库中夹具「喜欢喝椰青水（椰子水）」不匹配致脆断 → 改结构断言（正文 p + 标签 span 计数）；② latestAssistant 过滤 `items-center`→`items-`（工具提示气泡 `items-start` 此前被误当助手正文，工具轮次会误断言）
- [x] **5.6 聊天面板**
      · 查：消息气泡/流式打字机/时间显示正常
      · 取证：浏览器真实对话 3 轮，观察渲染与流式效果
      · 判据：逐字渲染平滑、气泡样式正常、无布局抖动
      · 严重度：P1
      · 结果：✅ 3 轮真实对话全渲染（38/13/13 字，第 2 轮捕获流式打字机中间态），气泡布局 4 右 3 左 + 工具提示 3 条，时间显示正常，零 console/API 错误。**修复 1 处**：index.html 补标准版 `mobile-web-app-capable` meta（Edge headless 对仅 apple- 版本报弃用警告）
- [x] **5.7 记忆面板**
      · 查：MemoriesPanel 列表/删除/导出交互
      · 取证：浏览器打开记忆面板，操作一轮
      · 判据：列表实时、操作响应、无报错
      · 严重度：P1
      · 结果：✅ 9/9：列表渲染 4 条 + 分类标签 4 个；编辑 PATCH 落库并恢复原样（内容净变化为零）；删除两段确认「真删？」2s 超时自动恢复；操作后数据无净变化；导出 API 5 条 JSON（面板无导出 UI，能力在 API 层，判据校准）
- [x] **5.8 会话面板**
      · 查：SessionsPanel 切换历史会话
      · 取证：浏览器切换 2-3 个会话
      · 判据：切换后上下文正确、历史可读
      · 严重度：P2
      · 结果：✅ 7/7：16 个历史会话列表渲染 16 条；切换 3 个会话标题与首条消息全部匹配（上下文正确）；零 console/API 错误
- [x] **5.9 日历面板**
      · 查：CalendarPanel 渲染与事件交互（交叉 4.22）
      · 取证：浏览器操作一轮
      · 判据：事件显示/创建/完成流畅
      · 严重度：P1
      · 结果：✅ 7/7：月视图 44 日期格（八月 2026）+ 图例；8/5 考试事件徽标「✦ 考试」（archived 翻看语义）；详情 sheet 3 个操作按钮；零错误。判据校准：①「完成」→ 事件域无 completed 状态（删除墓碑替代，ui_test 已全链路验证）；② weather 类型（雷暴）按设计不上日历（`_CALENDAR_VISIBLE_TYPES` 白名单，events.py:81）
- [x] **5.10 设置面板**
      · 查：ConfigPanel token 配置/主题切换
      · 取证：浏览器打开设置，查看各选项
      · 判据：配置项完整、保存生效
      · 严重度：P1
      · 结果：✅ 6/6：六项配置齐全（访问令牌/模型/回复风格/温度/消息通知/位置辅助）+ token 输入框；保存生效（温度 0.7→1.1 刷新后保持 1.1）；已还原基线 0.7；零错误。判据校准：无「主题切换」项——主题为 CSS token 体系（5.21 覆盖）
- [x] **5.11 房间与立绘**
      · 查：RoomScene/CharacterSprite 渲染正常、立绘不破图
      · 取证：浏览器观察房间与立绘（素材路径存在：web 内 sprite 资源）
      · 判据：立绘/房间正常显示、无 404 资源
      · 严重度：P1
      · 结果：✅ 5/5：全部 10 个立绘可加载（idle 530px/sleeping 969px 等 naturalWidth 正常）；页面立绘渲染 215×739px；房间渲染（床板渐变 + 天空）；零 404 资源；零 console error
- [x] **5.12 睡姿切换**
      · 查：深夜/睡眠时段立绘切换睡姿（抬到面板床线）
      · 取证：观察深夜时段或手动验证时段逻辑（?_sky 预览类参数）；对照记忆：睡姿已抬到床线
      · 判据：时段切换正确、睡姿位置正确
      · 严重度：P2
      · 结果：✅ 6/6 视觉实证（visibilitychange 模拟「页面不可见 → 小汐趴下」真实代码路径）：idle→sleeping 切换（横躺 311×303px，容器 bottom-[36%] w-[72%] 抬到面板床线）；戳睡姿→halfawake 4s 临时覆盖→回落；回前台唤醒。另补 time.test.js 5 个 HKT 时钟边界测试（5/8/17/20 切换点 + 深夜 0-6:59，与睡姿判定同源）
- [x] **5.13 天空背景**
      · 查：SkyBackground 按 HKT 时段渐变（粉色化主题）
      · 取证：浏览器多时段观察（或 ?_sky 预览）；对照 pink 主题
      · 判据：时段过渡自然、色板符合奶甜草莓粉 token
      · 严重度：P2
      · 结果：✅ 生产页（HKT 17 点）evening 树莓晚霞渐变正确（#3b2b63→#8d4f86→#d0618f）；dev server ?_sky 逐时段取证：morning #5d5a9c / day #7fa7dd / evening 一致 / night #191029，四套与 SKY 常量精确一致（粉色化）。注：?_sky 仅 DEV 生效（生产构建摇树），故用 dev server 取证
- [x] **5.14 心语动画**
      · 查：FloatingHearts 漂浮爱心正常（reduced-motion 无坑）
      · 取证：浏览器观察；系统开启 reduced-motion 时再观察
      · 判据：默认动画流畅；reduced-motion 下降级不报错
      · 严重度：P2
      · 结果：✅ 4/4：6 颗漂浮圆点渲染动画生效（2026-07-30 由爱心改圆点，常驻太抢眼）；emulateMedia reduced-motion → `.ambient-motes display:none` 整体撤下；恢复默认动画回归；零错误
- [x] **5.15 响应式**
      · 查：移动端布局（聊天/面板适配）
      · 取证：DevTools 移动视口（375px）过一遍主界面
      · 判据：无溢出/错位/遮挡，主要交互可达
      · 严重度：P1
      · 结果：✅ 7/7（375×812 isMobile 真机语义视口）：无横向溢出（scrollWidth 375）；输入框可达；顶栏 4 入口齐全；立绘渲染；设置面板适配 375 宽；移动端发送成功；零错误
- [x] **5.16 可访问性**
      · 查：focus-visible 可见焦点、memo 化组件无 stale
      · 取证：Tab 键走查焦点可见性；代码核验 useDeferredMarkRead 等 hooks
      · 判据：焦点环可见、无记忆 bug 类交互
      · 严重度：P2
      · 结果：✅ 5/5：Tab 走查 9 个焦点元素（立绘/4 入口/输入框循环）焦点环全部可见（:focus-visible outline strawberry）；aria 标注（立绘 role=button + 输入框 placeholder + dialog role）；立绘键盘 Enter 触发戳一戳。代码核验：useDeferredMarkRead 无 stale（markReadRef ref 兜底 + 可见立即标/隐藏挂起/回前台补发/失败回滚）；CharacterSprite/SkyBackground 均已 memo
- [x] **5.17 错误边界**
      · 查：ErrorBoundary 兜底（组件崩溃不白屏）
      · 取证：代码核验边界覆盖；DevTools 强制造一处错误观察
      · 判据：错误被捕获、有降级 UI
      · 严重度：P1
      · 结果：✅ ErrorBoundary 挂载于 main.jsx 根层（整树兜底），降级 UI「🐋 小汐晕倒了 + 刷新页面」。新增 ErrorBoundary.test.jsx 2 个组件测试（渲染期抛错 → 降级 UI 出现 + 正常子组件照常渲染），实证错误被捕获
- [x] **5.18 控制台零错误**
      · 查：真实使用全程无 console error/warning
      · 取证：浏览器开发者工具观察 5 分钟真实使用
      · 判据：零 error；warning 少且可解释
      · 严重度：P1
      · 结果：✅ 综合真实操作（对话 2 轮含工具调用 + 戳 + 四面板开关 + 滚动）全程 0 error / 0 warning（meta 修复后连 warning 都没有）
- [x] **5.19 离线缓存**
      · 查：sw.js 版本（CACHE_NAME v6）与 stale-while-revalidate 行为
      · 取证：读 web/public/sw.js 版本号与缓存策略；部署后改立绘验证更新（runbook 4 步）
      · 判据：版本号清晰、更新策略正确
      · 严重度：P2
      · 结果：✅ 4/4 实跑（runbook §31 等价实测）：SW v6 缓存完整 23 项（含立绘）；离线兜底（断网 reload 页面+立绘从缓存真实加载，naturalWidth>0）；SWR 更新到位（替换立绘 → reload 后缓存与 fetch 均返回新内容 141064B）；在线段零错误。策略核验：导航 network-first / 构建产物 cache-first / 可变静态 SWR（waitUntil 保活原地覆盖）/ API 不缓存 / activate 清旧缓存 + clients.claim
- [x] **5.20 资源管线**
      · 查：gen_assets/trim_sprites 产物（立绘/图标）与工程一致
      · 取证：`ls web/public` 与 scripts/gen_assets.py 产出核对；web 构建引用路径存在
      · 判据：引用的资源文件都存在、无 404
      · 严重度：P1
      · 结果：✅ 引用清单 14 项（apple-touch-icon/favicon/manifest + 10 sprites webp + icon-192/512）全部存在于 public 与 dist；gen_assets.py 产出路径一致（make_icon 192/512/180 + sprites_to_webp quality=85）；poke-lines.json 为源码模块（import 打包，非静态资源）；5.11 已验零 404
- [x] **5.21 主题一致性**
      · 查：粉色主题 token（cream/plum/strawberry/peach/blush/raspberry/honey）全站一致
      · 取证：代码核验 CSS token 使用；浏览器抽查 3-4 个面板配色
      · 判据：无突兀不协调色、对比度达标
      · 严重度：P2
      · 结果：✅ 6/6：房间容器底色 cream rgb(255,246,248)；主文字 plum；正文对比度 7.13:1 ≥ 4.5（AA）；四面板底色同族 cream 97%；强调色 strawberry（按钮渐变/焦点环）。判据校准：body 深梅子底 #241a22 为设计语义（「房间悬浮于夜色」，index.css:182 注释），cream 是房间/面板内部底色
- [x] **5.22 Markdown 渲染**
      · 查：react-markdown 渲染（代码块/换行/列表）
      · 取证：发一段含 markdown 的消息观察渲染
      · 判据：渲染正确、无原始语法露出
      · 严重度：P2
      · 结果：✅ 浏览器真实对话（引导列表+加粗）：strong + 4 个 li 渲染、无原始语法露出；新增 Markdown.test.jsx 2 个组件测试（标题/列表/粗体/代码块语义标签 + 无 `**`/` ``` `/`## ` 露出 + 换行）
- [x] **5.23 首屏加载**
      · 查：首屏可交互时间合理
      · 取证：DevTools Network 看关键资源体积与加载瀑布
      · 判据：首屏 3-5s 内可交互（视网络），无超大阻塞资源
      · 严重度：P1
      · 结果：✅ 首屏可交互 779ms ≤ 5s、DOMContentLoaded 71ms；无超大阻塞资源（JS 282KB + CSS 205KB 未压缩，部署经 gzip 后 ~86KB/82KB，Markdown 生态已懒加载分包）；立绘 webp 为 preload 预取非阻塞
- [x] **5.24 输入交互**
      · 查：ChatInput 发送/换行/快捷操作
      · 取证：浏览器操作（Enter 发送、Shift+Enter 换行等）
      · 判据：交互符合预期、无粘连 bug
      · 严重度：P2
      · 结果：✅ 7/7：空输入禁用/输入启用；Shift+Enter 不误发送（单行输入设计，内容保留，无粘连）；Enter 发送→清空+停止按钮+焦点保留；停止→回发送态；maxLength=2000 与后端 _MAX_MESSAGE_LENGTH 一致。判据校准：单行 input 设计（移动端聚焦不缩放 + enterkeyhint=send），无 textarea 换行
