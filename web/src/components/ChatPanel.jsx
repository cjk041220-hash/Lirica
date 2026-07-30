import { useRef, useEffect, useState, useCallback } from 'react'
import MessageBubble from './MessageBubble'
import ChatInput from './ChatInput'

const statusTexts = {
  idle: '小汐在看着窗外',
  talking: '小汐在说话',
  listening: '小汐在认真听',
  thinking: '小汐在想办法',
  happy: '小汐心情很好',
  sleepy: '小汐趴着睡着了',
  halfawake: '小汐刚醒，还有点迷糊……',
  reading: '小汐在看书',
  wave: '小汐在映海里探险',
  sad: '小汐有点难过',
}

const DEFAULT_HEIGHT = 38
const MIN_VH = 10
const MAX_VH = 85

export default function ChatPanel({ chat, status, displayState }) {
  const { messages, isStreaming, sendMessage, stopStreaming } = chat
  // displayState 是 App 归并后的立绘状态（含 sleepy 休眠态）；
  // 未传时回退到 chat 自己的状态
  const characterState = displayState || chat.characterState
  const listRef = useRef(null)
  const panelRef = useRef(null)
  const [heightVh, setHeightVh] = useState(DEFAULT_HEIGHT)
  const [isDragging, setIsDragging] = useState(false)
  const dragData = useRef({ startY: 0, startH: 0, raf: null })
  // 用户是否停留在底部附近 —— 向上翻看历史时不再被流式输出拽回底部
  const stickBottomRef = useRef(true)

  const handleScroll = useCallback(() => {
    const el = listRef.current
    if (!el) return
    stickBottomRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
  }, [])

  useEffect(() => {
    const el = listRef.current
    if (!el) return
    const last = messages[messages.length - 1]
    // 新消息是用户自己发的 → 强制滚到底；流式追加 → 仅在已停留底部时跟随
    if (last?.role === 'user' || stickBottomRef.current) {
      el.scrollTop = el.scrollHeight
    }
  }, [messages])

  // 切换会话 → 滚动状态复位：新对话从底部开始看，不继承上一段
  // 对话的「正在往上翻」（否则载入的历史会停在另一段对话的中部）
  useEffect(() => {
    stickBottomRef.current = true
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [chat.sessionId])

  const onPointerDown = useCallback((e) => {
    e.preventDefault()
    setIsDragging(true)
    dragData.current.startY = e.clientY
    dragData.current.startH = panelRef.current.offsetHeight
    e.currentTarget.setPointerCapture(e.pointerId)
  }, [])

  const onPointerMove = useCallback((e) => {
    if (!dragData.current.startH) return
    // 用 rAF 节流，直接操作 DOM，不触发 React 渲染
    if (dragData.current.raf) return
    dragData.current.raf = requestAnimationFrame(() => {
      dragData.current.raf = null
      const delta = dragData.current.startY - e.clientY
      const nextPx = Math.min(
        window.innerHeight * MAX_VH / 100,
        Math.max(window.innerHeight * MIN_VH / 100, dragData.current.startH + delta)
      )
      if (panelRef.current) {
        panelRef.current.style.height = `${nextPx}px`
      }
    })
  }, [])

  const onPointerUp = useCallback(() => {
    setIsDragging(false)
    if (dragData.current.raf) {
      cancelAnimationFrame(dragData.current.raf)
      dragData.current.raf = null
    }
    // 松手后把最终高度同步回 state（转为 dvh 单位）
    if (panelRef.current) {
      const px = panelRef.current.offsetHeight
      const vh = (px / window.innerHeight) * 100
      setHeightVh(vh)
      panelRef.current.style.height = ''  // 清除内联，交回 state 控制
    }
    dragData.current.startH = 0
  }, [])

  const collapsed = heightVh < 14

  return (
    <div
      ref={panelRef}
      data-testid="chat-panel"
      className={`absolute bottom-0 left-0 right-0 z-50 flex flex-col glass-panel rounded-t-[24px] safe-bottom ${
        isDragging ? '' : 'transition-[height] duration-200 ease-out'
      }`}
      style={{ height: isDragging ? undefined : `${heightVh}dvh` }}
    >
      {/* 拖拽把手 */}
      <div
        data-testid="chat-handle"
        className="flex items-center justify-center pt-3 pb-1.5 cursor-grab active:cursor-grabbing touch-none shrink-0"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <div className={`w-10 h-1 rounded-full transition-colors ${isDragging ? 'bg-strawberry/50' : 'bg-plum/15'}`} />
      </div>

      {/* 状态栏：空闲/睡着时显示小汐今日碎碎念（心情 + 今天经历的事） */}
      {!collapsed && (
        <div className="flex items-center gap-2 px-4 pb-2 shrink-0">
          <div className={`w-2 h-2 rounded-full ${characterState === 'sleepy' ? 'bg-plum/25' : 'bg-strawberry animate-pulse-dot'}`} />
          <span className="text-[11px] text-plum/50 tracking-wide truncate">
            {(characterState === 'idle' || characterState === 'sleepy' || characterState === 'halfawake') && status?.daily_event
              ? `${status.mood ? `${status.mood} · ` : ''}${status.daily_event}`
              : (statusTexts[characterState] || '小汐在发呆')}
          </span>
        </div>
      )}

      {/* 消息列表（role=log + aria-live：流式新消息对屏幕阅读器可见） */}
      {!collapsed && (
        <div ref={listRef} onScroll={handleScroll} role="log" aria-live="polite" className="flex-1 overflow-y-auto px-4 py-2 chat-scroll min-h-0">
          {messages.length === 0 ? (
            <div className="h-full flex items-center justify-center">
              <p className="text-sm text-plum/40">你回来啦。小汐在看窗外的云。</p>
            </div>
          ) : (
            <div className="space-y-3">
              {messages.map(msg => (
                <MessageBubble key={msg.id} message={msg} />
              ))}
              {isStreaming && (
                <div className="flex items-center gap-1 pl-4">
                  <span className="w-1.5 h-1.5 rounded-full bg-plum/30 animate-pulse-dot" />
                  <span className="w-1.5 h-1.5 rounded-full bg-plum/30 animate-pulse-dot" style={{ animationDelay: '0.2s' }} />
                  <span className="w-1.5 h-1.5 rounded-full bg-plum/30 animate-pulse-dot" style={{ animationDelay: '0.4s' }} />
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* 输入框 */}
      <ChatInput onSend={sendMessage} isStreaming={isStreaming} onStop={stopStreaming} />
    </div>
  )
}
