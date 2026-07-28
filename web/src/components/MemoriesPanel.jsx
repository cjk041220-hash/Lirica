import { useState, useEffect } from 'react'
import IconClose from '../assets/icons/IconClose'
import IconWhale from '../assets/icons/IconWhale'
import { apiFetch } from '../api/client'

const TABS = [
  { key: 'memories', label: '记忆' },
  { key: 'diaries', label: '日记' },
]

export default function MemoriesPanel({ onClose }) {
  const [tab, setTab] = useState('memories')
  const [memories, setMemories] = useState([])
  const [diaries, setDiaries] = useState([])
  const [loading, setLoading] = useState(true)

  // 切换 tab 时按需拉取（apiFetch 带 token，裸 fetch 在配置了
  // XIAOXI_API_TOKEN 的部署下会 401 静默成空列表）
  useEffect(() => {
    let cancelled = false
    setLoading(true)

    const load = tab === 'memories'
      ? apiFetch('/api/memories')
          .then(res => (res.ok ? res.json() : { memories: [] }))
          .then(data => { if (!cancelled) setMemories(Array.isArray(data.memories) ? data.memories : []) })
      : apiFetch('/api/diaries')
          .then(res => (res.ok ? res.json() : { diaries: [] }))
          .then(data => { if (!cancelled) setDiaries(Array.isArray(data.diaries) ? data.diaries : []) })

    load
      .catch(e => console.warn('[MemoriesPanel] fetch failed:', e))
      .finally(() => { if (!cancelled) setLoading(false) })

    return () => { cancelled = true }
  }, [tab])

  const items = tab === 'memories' ? memories : diaries

  // Esc 关闭（dialog 语义的键盘出口；焦点圈定留待后续完善）
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-ink-tea/15 overlay-enter" onClick={onClose} />

      <div className="panel-enter relative w-full h-[62%] rounded-t-[24px] flex flex-col"
        role="dialog" aria-modal="true" aria-label="小汐的记忆"
        style={{ background: 'rgba(252,250,248,0.95)', backdropFilter: 'blur(20px)', boxShadow: '0 -12px 48px rgba(92,84,77,0.1)' }}>

        <div className="flex items-center justify-between px-5 pt-5 pb-1">
          <div className="flex items-center gap-2">
            <IconWhale size={16} className="text-healing-aqua/70" />
            <h2 className="text-[13px] font-medium text-ink-tea/80 tracking-wide">小汐的记忆</h2>
          </div>
          <button onClick={onClose} aria-label="关闭记忆" className="btn-press w-7 h-7 rounded-full bg-ink-tea/5 flex items-center justify-center">
            <IconClose size={13} className="text-ink-tea/50" />
          </button>
        </div>

        {/* 记忆 / 日记 切换 */}
        <div className="flex items-center gap-1 px-5 pb-2.5">
          {TABS.map(t => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`btn-press text-[11px] px-3 py-1 rounded-full transition-colors ${
                tab === t.key
                  ? 'bg-healing-aqua/15 text-healing-aqua'
                  : 'text-ink-tea/35 hover:text-ink-tea/55'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="flex-1 overflow-y-auto px-5 pb-8 chat-scroll">
          {loading ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <div className="w-6 h-6 border-2 border-healing-aqua/20 border-t-healing-aqua/60 rounded-full animate-spin" />
              <span className="text-[11px] text-ink-tea/30">正在翻阅{tab === 'memories' ? '记忆' : '日记'}……</span>
            </div>
          ) : items.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <IconWhale size={28} className="text-ink-tea/10" />
              <span className="text-[12px] text-ink-tea/30">
                {tab === 'memories' ? '还没有记忆呢，和小汐多聊聊吧。' : '小汐还没写过日记，晚上她会自己记一篇的。'}
              </span>
            </div>
          ) : tab === 'memories' ? (
            <div className="space-y-5 pt-2">
              {memories.map((item, i) => (
                <div key={item.id || i} className="animate-fade-in-up" style={{ animationDelay: `${i * 0.05}s` }}>
                  <div className="border-l-2 border-healing-aqua/25 pl-3.5 py-0.5">
                    {(item.created_at || item.date) && (
                      <p className="text-[10px] text-ink-tea/25 mb-1">{item.created_at || item.date}</p>
                    )}
                    <p className="text-[13px] text-ink-tea/65 leading-[1.8]">
                      {item.content || item.text || '(no content)'}
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
          ) : (
            /* 日记：按日期倒序，保留换行（晚间自动补记与白天手记分段呈现） */
            <div className="space-y-5 pt-2">
              {diaries.map((item, i) => (
                <div key={item.date || i} className="animate-fade-in-up" style={{ animationDelay: `${i * 0.05}s` }}>
                  <div className="border-l-2 border-wood-border/50 pl-3.5 py-0.5">
                    <p className="text-[10px] text-ink-tea/25 mb-1">{item.title || item.date}</p>
                    <p className="text-[13px] text-ink-tea/65 leading-[1.8] whitespace-pre-wrap">{item.content}</p>
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
