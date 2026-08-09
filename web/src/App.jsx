import { useState, useEffect, useRef, useMemo, lazy, Suspense } from 'react'
import RoomScene from './components/RoomScene'
import ChatPanel from './components/ChatPanel'
// 四个控制台面板全部按需加载（评审 F4）：它们都是条件渲染，静态
// import 会把日历/记忆/设置整块塞进主包；lazy 后首次打开才拉 chunk
const ConfigPanel = lazy(() => import('./components/ConfigPanel'))
const MemoriesPanel = lazy(() => import('./components/MemoriesPanel'))
const SessionsPanel = lazy(() => import('./components/SessionsPanel'))
const CalendarPanel = lazy(() => import('./components/CalendarPanel'))
import IconBook from './assets/icons/IconBook'
import IconCalendar from './assets/icons/IconCalendar'
import IconHistory from './assets/icons/IconHistory'
import IconSettings from './assets/icons/IconSettings'
import { useChat } from './hooks/useChat'
import { useConfig } from './hooks/useConfig'
import { usePush } from './hooks/usePush'
import { usePresence } from './hooks/usePresence'
import { useStatus } from './hooks/useStatus'
import { useDeferredMarkRead } from './hooks/useDeferredMarkRead'

// 深夜判定（HKT 00:00–06:59）——与 SkyBackground 的天空时段共用
// utils/time.js 的 HKT 钟，保证天色与小汐困不困来自同一时区
import { isLateNight } from './utils/time'

// ── 后端心情 → 前端立绘映射 ──────────────────────────────
const moodToSprite = {
  开心: 'happy',
  兴奋: 'happy',
  活泼: 'exploring',
  好奇: 'exploring',
  期待: 'happy',
  满足: 'idle',
  发呆: 'idle',
  懒洋洋: 'idle',
  困倦: 'sleepy',
  担心: 'sad',
}

// ── 心情 → 房间光色族（U1 心情驱光，克制微妙档）─────────
// 10 种心情归 3 族：暖金（高能量正向）/ 中性（不动光）/ 冷蓝（低能量）。
// 立绘回应「表情」，光回应「气场」——情感反馈从角色扩到空间。
const moodToLight = {
  开心: 'warm',
  兴奋: 'warm',
  活泼: 'warm',
  期待: 'warm',
  满足: 'neutral',
  发呆: 'neutral',
  懒洋洋: 'neutral',
  好奇: 'neutral',
  困倦: 'cool',
  担心: 'cool',
}

export default function App() {
  // 面板互斥（P1-16）：单个枚举替代四个布尔——同一时刻至多一个面板挂载，
  // 天然互斥；再点同一按钮 = 关闭（toggle）
  const [activePanel, setActivePanel] = useState(null)
  // 退场动画的延迟卸载（2026-08-09 商业级 1.1）：activePanel 归零后
  // renderedPanel 多活 240ms、容器挂 .panel-closing 播下滑淡出，播完
  // 才真卸载——入场/退场合成闭环，不再是「凭空消失」
  const [renderedPanel, setRenderedPanel] = useState(null)
  const [panelClosing, setPanelClosing] = useState(false)
  useEffect(() => {
    if (activePanel) {
      setRenderedPanel(activePanel)
      setPanelClosing(false)
      return
    }
    // 归零时若没有面板在渲染（如初始挂载）→ 无需退场
    if (!renderedPanel) return
    setPanelClosing(true)
    const t = setTimeout(() => {
      setRenderedPanel(null)
      setPanelClosing(false)
    }, 240)
    return () => clearTimeout(t)
  }, [activePanel, renderedPanel])
  // 页面打开时即判定深夜——即使立绘状态后续因聊天流改变，
  // 深夜侧初始沉睡的判断基准是"打开页面的那一刻是不是深夜"。
  const [openedAtLateNight] = useState(isLateNight)

  const chat = useChat()
  const config = useConfig()
  const push = usePush()
  const presence = usePresence()
  const status = useStatus()
  const injectedRef = useRef(new Set())

  // 面板 chunk 空闲预加载（2026-08-09）：四个控制台面板是 lazy 的，
  // 首次点击要现拉 chunk——弱网下 Suspense 全屏占位会停留到可感，
  // 观感是一次突兀的闪屏。首屏稳定后借浏览器空闲把 chunk 预拉进
  // 缓存，之后点击即开；requestIdleCallback 缺席时（Safari）用
  // 延时兜底，绝不挤占首屏加载
  useEffect(() => {
    const preload = () => {
      import('./components/ConfigPanel')
      import('./components/MemoriesPanel')
      import('./components/SessionsPanel')
      import('./components/CalendarPanel')
    }
    if (typeof requestIdleCallback === 'function') {
      const id = requestIdleCallback(preload, { timeout: 4000 })
      return () => cancelIdleCallback(id)
    }
    const t = setTimeout(preload, 2500)
    return () => clearTimeout(t)
  }, [])

  // 立绘状态归并（优先级）：
  //   1. 聊天流程态（listening/thinking/talking/刚聊完的 happy）
  //      —— 聊天进行中必须让路，否则立绘跟文字脱节
  //   2. 深夜唤醒序列：
  //      a) 未发过消息 → sleepy（沉睡，有 Zzz）
  //      b) 只发了 1 条 → halfawake（睡眼惺忪，还没完全醒）
  //      c) 发了 ≥2 条 → 进入正常心情映射
  //   3. 冷冷不在 → sleepy（空闲超 4 分钟 / 标签页不可见）
  //   4. 正常心情映射 → 按后端 mood 选立绘
  //   5. fallback → idle
  const displayState = useMemo(() => {
    const ch = chat.characterState
    const cnt = chat.userMsgCount ?? 0

    // 1. 聊天流程态优先
    if (ch !== 'idle') return ch

    // 2. 深夜唤醒序列（仅限页面打开就在深夜的用户）
    if (openedAtLateNight) {
      if (cnt === 0) return 'sleepy'
      if (cnt === 1) return 'halfawake'
    }

    // 3. 冷冷不在
    if (presence.isIdle || !presence.isVisible) return 'sleepy'

    // 4. 心情 → 立绘
    const moodSprite = status.mood ? moodToSprite[status.mood] : null
    if (moodSprite) return moodSprite

    // 5. fallback
    return 'idle'
  }, [
    chat.characterState,
    chat.userMsgCount,
    openedAtLateNight,
    presence.isIdle,
    presence.isVisible,
    status.mood,
  ])

  // 小汐的主动消息（页面加载时的未读 / 实时推送到达）→ 注入聊天流并标记已读。
  // 依赖只收 unread 列表和两个稳定回调（useCallback 引用不变），
  // 避免 push/chat 对象每次渲染换新引用导致 effect 空转
  const { receiveProactive } = chat
  const { markRead, unread } = push
  const markReadWhenVisible = useDeferredMarkRead(markRead)
  useEffect(() => {
    const fresh = unread.filter((m) => m.id != null && !injectedRef.current.has(m.id))
    if (fresh.length === 0) return
    // 后端 unread 按 created_at DESC 返回（最新在前），注入聊天流前
    // 先按时间升序排回，多条积压未读才不会倒序出现
    fresh.sort((a, b) => String(a.created_at ?? '').localeCompare(String(b.created_at ?? '')))
    fresh.forEach((m) => injectedRef.current.add(m.id))
    receiveProactive(fresh)
    // 已读门控：后台标签页不标已读（用户没看到不算已读），回到前台
    // 由 useDeferredMarkRead 补发。promise 只在「真实 ack 完成」后 settle
    // （隐藏期间一直 pending，窗口内 id 保留在防重集合里）——settle 后移除：
    // ack 成功则服务端不再返回这些 id（留着只涨不消）；真实 ack 失败则
    // 回滚移除，让下一轮 60s 轮询拉到未读时重试注入（边角：ack 与在途
    // 轮询快照的极小竞争窗口内可能重复注入一条，与既有失败回滚同级，接受）。
    // 2026-08-09 P1 修复：此前 defer 假成功 + 无条件移除，后台轮询会把
    // 未 ack 的消息复利式重复注入聊天流。
    markReadWhenVisible(fresh.map((m) => m.id)).then(() => {
      fresh.forEach((m) => injectedRef.current.delete(m.id))
    })
  }, [unread, receiveProactive, markReadWhenVisible])

  // 心情光色族：跟随 status.mood（5min 轮询 + 心跳后刷新），
  // 不追聊天流程态——环境光是慢变量，聊天瞬间的表情由立绘负责
  const moodLight = useMemo(
    () => (status.mood ? (moodToLight[status.mood] ?? 'neutral') : 'neutral'),
    [status.mood]
  )

  return (
    <div className="relative h-full w-full max-w-[430px] mx-auto overflow-hidden bg-cream select-none sm:shadow-[0_24px_90px_-16px_rgba(110,74,86,0.6),0_4px_24px_rgba(110,74,86,0.35)]">
      {/* 面板打开期间主内容 inert（2026-08-09 商业级 3.4）：模态语义
          补完——焦点圈定管键盘，inert 管指针命中与辅助技术可读性，
          背景层在面板存活期间从可及性树里退场 */}
      <div style={{ display: 'contents' }} inert={renderedPanel !== null}>
        <RoomScene characterState={displayState} moodLight={moodLight} />

      {/* 顶栏控制台：四个入口收进顶部居中的一条磨砂胶囊。
          四图标用 2+2 分组（回望类「对话/记忆」| 打点类「日历/设置」），
          组间一条细线——偶数图标靠分组线保持胶囊的对称平衡。
          top 叠加 env(safe-area-inset-top)：viewport-fit=cover + iOS PWA
          black-translucent 状态栏下页面画到刘海/灵动岛后面，胶囊须让出
          顶部安全区（浏览器内与桌面 env()=0，偏移自然不生效） */}
      <div className="absolute top-[calc(0.75rem+env(safe-area-inset-top))] left-1/2 -translate-x-1/2 z-[60] flex items-center gap-0.5 rounded-full glass-panel px-1 py-1 shadow-[0_6px_24px_rgba(110,74,86,0.22)]">
        <button
          onClick={() => setActivePanel((p) => (p === 'sessions' ? null : 'sessions'))}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="以往的对话"
        >
          <IconHistory size={16} />
        </button>
        <button
          onClick={() => setActivePanel((p) => (p === 'memories' ? null : 'memories'))}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="小汐的记忆"
        >
          <IconBook size={16} />
        </button>
        <span aria-hidden className="w-px h-3.5 bg-plum/15 mx-0.5" />
        <button
          onClick={() => setActivePanel((p) => (p === 'calendar' ? null : 'calendar'))}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="小汐的日历"
        >
          <IconCalendar size={16} />
        </button>
        <button
          onClick={() => setActivePanel((p) => (p === 'config' ? null : 'config'))}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="设置"
        >
          <IconSettings size={16} />
        </button>
      </div>

      <ChatPanel chat={chat} status={status} displayState={displayState} />
      </div>
      {/* 面板区 Suspense：fallback 用一块奶油色占位，避免首次打开白闪。
          2026-08-09：占位不再用 glass-panel——全屏 backdrop-filter 盖住
          整个动画房间，弹出瞬间整页重光栅化喂模糊（.panel-surface 注释
          记录过的扫掠感根因）；配合下方 idle 预加载，fallback 几乎不露面 */}
      {/* display:contents 容器只承担 .panel-closing 的 CSS 作用域，
          不参与布局——面板仍是相对根容器的绝对定位 */}
      <div style={{ display: 'contents' }} className={panelClosing ? 'panel-closing' : ''}>
        <Suspense fallback={<div className="absolute inset-0 z-[70] bg-cream/95 animate-fade-in-up" />}>
          {renderedPanel === 'config' && (
            <ConfigPanel config={config} push={push} onClose={() => setActivePanel(null)} />
          )}
          {renderedPanel === 'memories' && (
            <MemoriesPanel onClose={() => setActivePanel(null)} />
          )}
          {renderedPanel === 'sessions' && (
            <SessionsPanel chat={chat} onClose={() => setActivePanel(null)} />
          )}
          {renderedPanel === 'calendar' && (
            <CalendarPanel onClose={() => setActivePanel(null)} lastDoneAt={chat.lastDoneAt} />
          )}
        </Suspense>
      </div>
    </div>
  )
}
