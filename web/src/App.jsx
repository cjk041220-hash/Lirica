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
import { usePresenceReport } from './hooks/usePresenceReport'
import { useStatus } from './hooks/useStatus'
import { useDeferredMarkRead } from './hooks/useDeferredMarkRead'

// 深夜判定（HKT 00:00–06:59）——与 SkyBackground 的天空时段共用
// utils/time.js 的 HKT 钟，保证天色与小汐困不困来自同一时区
import { isLateNight } from './utils/time'

// ── 后端心情 → 前端立绘映射 ──────────────────────────────
// 批次一 P0-2 新增 4 词（2026-08-12）：平静/安心/轻松/想念。想念归
// idle 立绘 + cool 光（思念是安静的惦记，不是 sad 的难过）——漏加映射
// 的词会走下方 fallback（idle/neutral），新词映射同步是硬要求。
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
  平静: 'idle',
  安心: 'idle',
  轻松: 'idle',
  想念: 'idle',
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
  平静: 'neutral',
  安心: 'neutral',
  轻松: 'neutral',
  想念: 'cool',
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
  // 在线状态上报（v21 era 路由的数据源）：挂载/回前台/每 2 分钟报在线
  // + 当前会话；pagehide 上报离开（关页面即离线）
  usePresenceReport(chat.sessionId)

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
  // v21 era 归属过滤：主动消息带 session_id（后端 proactive_messages 列），
  // 只注入「当前活跃会话」的消息——在线时小汐实时搭话，实时可见；
  // era 时间线的消息不注入（它们属于「小汐的独处时间线」窗口，由
  // syncEra 切过去回放展示，这里注入会灌错窗口——本批要修的 bug 本体）。
  // 内容去重兜底：syncEra「先标已读再切会话」与注入 effect 之间存在
  // 竞态窗口，同一条消息可能已被回放展示——按内容去重，宁可漏不双写。
  // 依赖只收 unread 列表和两个稳定回调（useCallback 引用不变），
  // 避免 push/chat 对象每次渲染换新引用导致 effect 空转
  const { receiveProactive, switchSession } = chat
  const { markRead, unread, checkUnread } = push
  const markReadWhenVisible = useDeferredMarkRead(markRead)
  // 消息列表镜像（ref 非依赖）：注入 effect 读「当前渲染的消息」做内容
  // 去重——加进 deps 会让流式期间每 token 都重跑一次注入 effect
  const messagesRef = useRef([])
  messagesRef.current = chat.messages
  useEffect(() => {
    const fresh = unread.filter((m) => m.id != null && !injectedRef.current.has(m.id))
    if (fresh.length === 0) return
    const mine = fresh.filter((m) => !m.session_id || m.session_id === chat.sessionId)
    const seenContent = new Set(messagesRef.current.map((m) => m.content || ''))
    const deduped = mine.filter((m) => !seenContent.has(m.content || ''))
    if (deduped.length === 0) return
    // 后端 unread 按 created_at DESC 返回（最新在前），注入聊天流前
    // 先按时间升序排回，多条积压未读才不会倒序出现
    deduped.sort((a, b) => String(a.created_at ?? '').localeCompare(String(b.created_at ?? '')))
    // 积压只注入最新一条（2026-08-09 用户裁定）：积压全量注入 = 刷屏式
    // 「不同时段问好」堆进当前对话，人机感太重。更早的积压直接标已读
    // 丢弃（不展示就不算未读）；注入的那条仍走可见门控——后台标签页
    // 没看到就不标已读（useDeferredMarkRead 语义不变）
    const latest = deduped[deduped.length - 1]
    const dropped = deduped.slice(0, -1)
    injectedRef.current.add(latest.id)
    receiveProactive([latest])
    markReadWhenVisible([latest.id]).then(() => {
      injectedRef.current.delete(latest.id)
    })
    if (dropped.length > 0) markRead(dropped.map((m) => m.id))
  }, [unread, receiveProactive, markReadWhenVisible, markRead, chat.sessionId])

  // era 时间线同步（v21，用户裁定「一上线就能在新的聊天窗口看到小汐
  // 这段时间的活动和话语」）：status.unseen_era_id 非空 = 小汐在独处
  // 时间线写了新话（仍是碎碎念、且未读）且不是当前会话 → 自动切过去。
  //
  // 2026-08-30 收窄为「只在当前会话还没有任何消息时自动切」：
  // 原来的实现是无条件切——冷冷正在某段对话里打字、或只是刚发了一句
  // 话，小汐一句独白就把她从自己正待着的地方拽走（而且切走时输入框
  // 里没发出去的内容看着像是丢了）。她不在的那段时间里，当前会话本
  // 就是空的，此时切过去是「推开门就看见小汐」；她已经在聊了，就让
  // 她聊完——碎碎念会留在列表里，带「碎碎念」标签，她想看随时点。
  //
  // 次序保证：checkUnread 拉全 → 先批量 markRead era 未读（从 unread
  // state 乐观移除，注入 effect 摸不到它们、不会双写）→ 再 switchSession
  // 回放展示完整时间线。markRead 失败则保留在 unread，下一轮 status
  // 刷新 / 新消息到达时重试。
  const hasMessagesRef = useRef(false)
  hasMessagesRef.current = chat.messages.length > 0
  useEffect(() => {
    const eraId = status.unseen_era_id
    if (!eraId) return
    if (eraId === chat.sessionId) {
      // 已在 era 窗口（回放已展示）：清掉它的未读状态即可
      const eraUnread = unread.filter((m) => m.session_id === eraId)
      if (eraUnread.length > 0) markRead(eraUnread.map((m) => m.id))
      return
    }
    // 当前会话已经聊起来了 → 不打断（碎碎念留在列表里等她点开）
    if (hasMessagesRef.current) return
    let cancelled = false
    ;(async () => {
      try {
        // checkUnread 返回最新一次拉取结果（闭包里的 unread 是旧 state，
        // await 后尚未更新）——era 归属过滤必须用返回值
        const fresh = await checkUnread()
        if (cancelled) return
        const eraUnread = (fresh || []).filter((m) => m.session_id === eraId)
        if (eraUnread.length > 0) await markRead(eraUnread.map((m) => m.id))
        if (cancelled) return
        await switchSession(eraId)
      } catch (e) {
        console.warn('[App] syncEra failed:', e)
      }
    })()
    return () => {
      cancelled = true
    }
    // 依赖收窄：unread 变化（markRead 乐观移除）会触发重跑，用 cancelled
    // 让旧一轮让位；status 5 分钟轮询刷新 unseen_era_id 是重试通道；
    // switchSession 内部有「id 相同则跳过」守卫，双跑也安全。
    // chat.messages 刻意不进依赖（见 messagesRef 的同一理由：流式期间
    // 每 token 都会重跑本 effect）——只读它的 ref 镜像。
  }, [status.unseen_era_id, chat.sessionId, unread, markRead, switchSession, checkUnread])

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
