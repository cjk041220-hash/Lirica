import { useState, useEffect, useRef, useMemo } from 'react'
import RoomScene from './components/RoomScene'
import ChatPanel from './components/ChatPanel'
import ConfigPanel from './components/ConfigPanel'
import MemoriesPanel from './components/MemoriesPanel'
import SessionsPanel from './components/SessionsPanel'
import CalendarPanel from './components/CalendarPanel'
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
  '开心': 'happy',
  '兴奋': 'happy',
  '活泼': 'exploring',
  '好奇': 'exploring',
  '期待': 'happy',
  '满足': 'idle',
  '发呆': 'idle',
  '懒洋洋': 'idle',
  '困倦': 'sleepy',
  '担心': 'sad',
}

export default function App() {
  const [showConfig, setShowConfig] = useState(false)
  const [showMemories, setShowMemories] = useState(false)
  const [showSessions, setShowSessions] = useState(false)
  const [showCalendar, setShowCalendar] = useState(false)
  // 页面打开时即判定深夜——即使立绘状态后续因聊天流改变，
  // 深夜侧初始沉睡的判断基准是"打开页面的那一刻是不是深夜"。
  const [openedAtLateNight] = useState(isLateNight)

  const chat = useChat()
  const config = useConfig()
  const push = usePush()
  const presence = usePresence()
  const status = useStatus()
  const injectedRef = useRef(new Set())

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
  }, [chat.characterState, chat.userMsgCount, openedAtLateNight, presence.isIdle, presence.isVisible, status.mood])

  // 小汐的主动消息（页面加载时的未读 / 实时推送到达）→ 注入聊天流并标记已读。
  // 依赖只收 unread 列表和两个稳定回调（useCallback 引用不变），
  // 避免 push/chat 对象每次渲染换新引用导致 effect 空转
  const { receiveProactive } = chat
  const { markRead, unread } = push
  const markReadWhenVisible = useDeferredMarkRead(markRead)
  useEffect(() => {
    const fresh = unread.filter(m => m.id != null && !injectedRef.current.has(m.id))
    if (fresh.length === 0) return
    // 后端 unread 按 created_at DESC 返回（最新在前），注入聊天流前
    // 先按时间升序排回，多条积压未读才不会倒序出现
    fresh.sort((a, b) => String(a.created_at ?? '').localeCompare(String(b.created_at ?? '')))
    fresh.forEach(m => injectedRef.current.add(m.id))
    receiveProactive(fresh)
    // 已读门控：后台标签页不标已读（用户没看到不算已读），回到前台
    // 由 useDeferredMarkRead 补发。失败 → 回滚 injectedRef，让下一轮
    // 60s 轮询拉到未读时重试（边角：失败且下轮拉回时可能重复注入——
    // 发生条件苛刻，接受，同原注释）
    markReadWhenVisible(fresh.map(m => m.id)).then(ok => {
      if (!ok) fresh.forEach(m => injectedRef.current.delete(m.id))
    })
  }, [unread, receiveProactive, markReadWhenVisible])

  return (
    <div className="relative h-full w-full max-w-[430px] mx-auto overflow-hidden bg-cream select-none sm:shadow-[0_24px_90px_-16px_rgba(110,74,86,0.6),0_4px_24px_rgba(110,74,86,0.35)]">
      <RoomScene characterState={displayState} />

      {/* 顶栏控制台：四个入口收进顶部居中的一条磨砂胶囊。
          四图标用 2+2 分组（回望类「对话/记忆」| 打点类「日历/设置」），
          组间一条细线——偶数图标靠分组线保持胶囊的对称平衡。
          top 叠加 env(safe-area-inset-top)：viewport-fit=cover + iOS PWA
          black-translucent 状态栏下页面画到刘海/灵动岛后面，胶囊须让出
          顶部安全区（浏览器内与桌面 env()=0，偏移自然不生效） */}
      <div className="absolute top-[calc(0.75rem+env(safe-area-inset-top))] left-1/2 -translate-x-1/2 z-[60] flex items-center gap-0.5 rounded-full glass-panel px-1 py-1 shadow-[0_6px_24px_rgba(110,74,86,0.22)]">
        <button
          onClick={() => setShowSessions(true)}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="以往的对话"
        >
          <IconHistory size={16} />
        </button>
        <button
          onClick={() => setShowMemories(true)}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="小汐的记忆"
        >
          <IconBook size={16} />
        </button>
        <span aria-hidden className="w-px h-3.5 bg-plum/15 mx-0.5" />
        <button
          onClick={() => setShowCalendar(true)}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="小汐的日历"
        >
          <IconCalendar size={16} />
        </button>
        <button
          onClick={() => setShowConfig(true)}
          className="btn-press relative w-8 h-8 rounded-full flex items-center justify-center text-plum/55 transition-colors hover:bg-strawberry/10 hover:text-strawberry after:content-[''] after:absolute after:-inset-1.5"
          aria-label="设置"
        >
          <IconSettings size={16} />
        </button>
      </div>

      <ChatPanel chat={chat} status={status} displayState={displayState} />
      {showConfig && <ConfigPanel config={config} push={push} onClose={() => setShowConfig(false)} />}
      {showMemories && <MemoriesPanel onClose={() => setShowMemories(false)} />}
      {showSessions && <SessionsPanel chat={chat} onClose={() => setShowSessions(false)} />}
      {showCalendar && <CalendarPanel onClose={() => setShowCalendar(false)} lastDoneAt={chat.lastDoneAt} />}
    </div>
  )
}
