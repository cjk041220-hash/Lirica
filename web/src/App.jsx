import { useState, useEffect, useRef } from 'react'
import RoomScene from './components/RoomScene'
import ChatPanel from './components/ChatPanel'
import ConfigPanel from './components/ConfigPanel'
import MemoriesPanel from './components/MemoriesPanel'
import SettingsButton from './components/SettingsButton'
import IconBook from './assets/icons/IconBook'
import { useChat } from './hooks/useChat'
import { useConfig } from './hooks/useConfig'
import { usePush } from './hooks/usePush'
import { usePresence } from './hooks/usePresence'
import { useStatus } from './hooks/useStatus'

export default function App() {
  const [showConfig, setShowConfig] = useState(false)
  const [showMemories, setShowMemories] = useState(false)

  const chat = useChat()
  const config = useConfig()
  const push = usePush()
  const presence = usePresence()
  const status = useStatus()
  const injectedRef = useRef(new Set())

  // 立绘状态归并（优先级）：
  //   聊天流程态（listening/thinking/talking/刚聊完的 happy）>
  //   冷冷不在时的 sleepy（空闲超 4 分钟 或 标签页不可见）> idle
  // 打开页面的前两秒，这个状态就在传达「小汐在等我」。
  const displayState =
    chat.characterState !== 'idle'
      ? chat.characterState
      : (presence.isIdle || !presence.isVisible) ? 'sleepy' : 'idle'

  // 小汐的主动消息（页面加载时的未读 / 实时推送到达）→ 注入聊天流并标记已读。
  // 依赖只收 unread 列表和两个稳定回调（useCallback 引用不变），
  // 避免 push/chat 对象每次渲染换新引用导致 effect 空转
  const { receiveProactive } = chat
  const { markRead, unread } = push
  useEffect(() => {
    const fresh = unread.filter(m => m.id != null && !injectedRef.current.has(m.id))
    if (fresh.length === 0) return
    fresh.forEach(m => injectedRef.current.add(m.id))
    receiveProactive(fresh)
    markRead(fresh.map(m => m.id))
  }, [unread, receiveProactive, markRead])

  return (
    <div className="relative h-full w-full max-w-[430px] mx-auto overflow-hidden bg-warm-paper select-none">
      <RoomScene characterState={displayState} />
      <SettingsButton onClick={() => setShowConfig(true)} />

      {/* 记忆/日记入口 */}
      <button
        onClick={() => setShowMemories(true)}
        className="icon-btn absolute top-4 left-4 z-[60] w-9 h-9 rounded-full glass-panel flex items-center justify-center"
        aria-label="小汐的记忆"
      >
        <IconBook size={16} className="text-ink-tea/60" />
      </button>

      <ChatPanel chat={chat} status={status} displayState={displayState} />
      {showConfig && <ConfigPanel config={config} push={push} onClose={() => setShowConfig(false)} />}
      {showMemories && <MemoriesPanel onClose={() => setShowMemories(false)} />}
    </div>
  )
}
