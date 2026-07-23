import { useState, useEffect } from 'react'
import IconClose from '../assets/icons/IconClose'
import IconWhale from '../assets/icons/IconWhale'

export default function MemoriesPanel({ onClose }) {
  const [memories, setMemories] = useState([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch('/api/memories')
      .then(res => (res.ok ? res.json() : { memories: [] }))
      .then(data => setMemories(data.memories || data || []))
      .catch(() => setMemories([]))
      .finally(() => setLoading(false))
  }, [])

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-ink-tea/15 overlay-enter" onClick={onClose} />

      <div className="panel-enter relative w-full h-[62%] rounded-t-[24px] flex flex-col"
        style={{ background: 'rgba(252,250,248,0.95)', backdropFilter: 'blur(20px)', boxShadow: '0 -12px 48px rgba(92,84,77,0.1)' }}>
        
        <div className="flex items-center justify-between px-5 pt-5 pb-3">
          <div className="flex items-center gap-2">
            <IconWhale size={16} className="text-healing-aqua/70" />
            <h2 className="text-[13px] font-medium text-ink-tea/80 tracking-wide">小汐的记忆</h2>
          </div>
          <button onClick={onClose} aria-label="关闭记忆" className="btn-press w-7 h-7 rounded-full bg-ink-tea/5 flex items-center justify-center">
            <IconClose size={13} className="text-ink-tea/50" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 pb-8 chat-scroll">
          {loading ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <div className="w-6 h-6 border-2 border-healing-aqua/20 border-t-healing-aqua/60 rounded-full animate-spin" />
              <span className="text-[11px] text-ink-tea/30">正在翻阅日记……</span>
            </div>
          ) : memories.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <IconWhale size={28} className="text-ink-tea/10" />
              <span className="text-[12px] text-ink-tea/30">还没有记忆呢，和小汐多聊聊吧。</span>
            </div>
          ) : (
            <div className="space-y-5 pt-2">
              {memories.map((item, i) => (
                <div key={item.id || i} className="animate-fade-in-up" style={{ animationDelay: `${i * 0.05}s` }}>
                  <div className="border-l-2 border-healing-aqua/25 pl-3.5 py-0.5">
                    {(item.created_at || item.date) && (
                      <p className="text-[10px] text-ink-tea/25 mb-1">{item.created_at || item.date}</p>
                    )}
                    <p className="text-[13px] text-ink-tea/65 leading-[1.8]">
                      {item.content || item.text || JSON.stringify(item)}
                    </p>
                    {item.category && (
                      <span className="inline-block mt-1.5 text-[10px] text-healing-aqua/80 bg-healing-aqua/8 px-2.5 py-0.5 rounded-full">
                        {item.category}
                      </span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
