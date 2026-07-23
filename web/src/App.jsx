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

export default function App() {
  const [showConfig, setShowConfig] = useState(false)
  const [showMemories, setShowMemories] = useState(false)

  const chat = useChat()
  const config = useConfig()
  const push = usePush()
  const injectedRef = useRef(new Set())

  // 小汐的主动消息（页面加载时的未读 / 实时推送到达）→ 注入聊天流并标记已读
  useEffect(() => {
    const fresh = push.unread.filter(m => m.id != null && !injectedRef.current.has(m.id))
    if (fresh.length === 0) return
    fresh.forEach(m => injectedRef.current.add(m.id))
    chat.receiveProactive(fresh)
    push.markRead(fresh.map(m => m.id))
  }, [push.unread, push, chat])

  return (
    <div className="relative h-full w-full max-w-[430px] mx-auto overflow-hidden bg-warm-paper select-none">
      <RoomScene characterState={chat.characterState} />
      <SettingsButton onClick={() => setShowConfig(true)} />

      {/* 记忆/日记入口 */}
      <button
        onClick={() => setShowMemories(true)}
        className="icon-btn absolute top-4 left-4 z-[60] w-9 h-9 rounded-full glass-panel flex items-center justify-center"
        aria-label="小汐的记忆"
      >
        <IconBook size={16} className="text-ink-tea/60" />
      </button>

      <ChatPanel chat={chat} />
      {showConfig && <ConfigPanel config={config} push={push} onClose={() => setShowConfig(false)} />}
      {showMemories && <MemoriesPanel onClose={() => setShowMemories(false)} />}
    </div>
  )
}
