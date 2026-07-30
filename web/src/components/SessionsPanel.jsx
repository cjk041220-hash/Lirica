import { useState, useEffect } from 'react'
import IconClose from '../assets/icons/IconClose'
import IconHistory from '../assets/icons/IconHistory'
import IconWhale from '../assets/icons/IconWhale'
import { apiFetch } from '../api/client'
import { useFocusTrap } from '../hooks/useFocusTrap'

// 「今天/今年」按 HKT 判定——后端时间戳是 HKT 文本（utils/timezone），
// 项目原则是天空/深夜/时间显示全部走 HKT 钟，不跟设备本地时区
//（Intl 'sv' locale 输出 ISO 风格 "2026-07-27 22:04:33"，可切片取用）
const hktNow = () =>
  new Intl.DateTimeFormat('sv', { timeZone: 'Asia/Hong_Kong' }).format(new Date())

// updated_at 为 HKT「YYYY-MM-DD HH:MM」文本，字典序即时序：
// 今天 → HH:MM，今年 → MM-DD，更早 → YYYY-MM-DD
const fmtTime = (s) => {
  const str = String(s || '')
  const [date, time] = str.split(' ')
  if (!date) return ''
  const now = hktNow()
  if (date === now.slice(0, 10)) return time || ''
  if (date.startsWith(now.slice(0, 4))) return date.slice(5)
  return date
}

export default function SessionsPanel({ chat, onClose }) {
  const [sessions, setSessions] = useState([])
  const [loading, setLoading] = useState(true)
  // 删除两次点击确认：首点进入「确认？」态，2 秒后自动复位
  const [confirmId, setConfirmId] = useState(null)
  const dialogRef = useFocusTrap()

  useEffect(() => {
    let cancelled = false
    apiFetch('/api/sessions?limit=100')
      .then(res => (res.ok ? res.json() : { sessions: [] }))
      .then(data => { if (!cancelled) setSessions(Array.isArray(data.sessions) ? data.sessions : []) })
      .catch(e => console.warn('[SessionsPanel] fetch failed:', e))
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  // Esc 关闭（同 MemoriesPanel 的 dialog 语义）
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const openSession = (s) => {
    // 点当前会话只收起面板；点旧会话载入档案并可续聊
    if (s.id !== chat.sessionId) chat.switchSession(s.id)
    onClose()
  }

  const handleDelete = (e, id) => {
    e.stopPropagation()
    if (confirmId === id) {
      setConfirmId(null)
      apiFetch(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' })
        .then(res => {
          if (!res.ok) return
          setSessions(prev => prev.filter(s => s.id !== id))
          // 删掉的是当前会话 → 开一段新聊天。否则 sessionId 仍指向
          // 已删会话，下一条消息会让它换个标题「自愈复活」在列表里
          if (id === chat.sessionId) chat.newSession()
        })
        .catch(err => console.warn('[SessionsPanel] delete failed:', err))
    } else {
      setConfirmId(id)
      setTimeout(() => setConfirmId(cur => (cur === id ? null : cur)), 2000)
    }
  }

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-plum/15 overlay-enter" onClick={onClose} />

      <div className="panel-enter relative w-full h-[62%] rounded-t-[24px] flex flex-col"
        ref={dialogRef} tabIndex={-1}
        role="dialog" aria-modal="true" aria-label="以往的对话"
        style={{ background: 'rgba(252,250,248,0.95)', backdropFilter: 'blur(20px)', boxShadow: '0 -12px 48px rgba(92,84,77,0.1)' }}>

        <div className="flex items-center justify-between px-5 pt-5 pb-1">
          <div className="flex items-center gap-2">
            <IconHistory size={16} className="text-strawberry/70" />
            <h2 className="text-[13px] font-medium text-plum/80 tracking-wide">以往的对话</h2>
          </div>
          <button onClick={onClose} aria-label="关闭对话列表" className="btn-press w-7 h-7 rounded-full bg-plum/5 flex items-center justify-center">
            <IconClose size={13} className="text-plum/50" />
          </button>
        </div>

        {/* 新聊天 */}
        <div className="px-5 pb-2.5">
          <button
            onClick={() => { chat.newSession(); onClose() }}
            className="btn-press text-[11px] px-3 py-1 rounded-full bg-strawberry/15 text-strawberry"
          >
            ＋ 开一段新聊天
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 pb-8 chat-scroll">
          {loading ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <div className="w-6 h-6 border-2 border-strawberry/20 border-t-strawberry/60 rounded-full animate-spin" />
              <span className="text-[11px] text-plum/30">正在翻找以前的对话……</span>
            </div>
          ) : sessions.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <IconWhale size={28} className="text-plum/10" />
              <span className="text-[12px] text-plum/30">还没有对话呢，去和小汐聊聊吧。</span>
            </div>
          ) : (
            <div className="space-y-2 pt-1">
              {sessions.map((s, i) => {
                const isCurrent = s.id === chat.sessionId
                return (
                  // 行容器用 div role="button"（不用 <button>）——HTML 规范
                  // 不允许 button 内嵌交互元素，删除按钮需要是真正的 button
                  <div
                    key={s.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => openSession(s)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        openSession(s)
                      }
                    }}
                    className={`animate-fade-in-up w-full text-left rounded-xl px-3.5 py-2.5 transition-colors cursor-pointer ${
                      isCurrent ? 'bg-strawberry/10 ring-1 ring-strawberry/40' : 'hover:bg-plum/5'
                    }`}
                    style={{ animationDelay: `${i * 0.03}s` }}
                  >
                    <div className="flex items-center gap-2">
                      <p className="flex-1 truncate text-[13px] font-medium text-plum/75">
                        {s.title || '(没有标题)'}
                      </p>
                      {isCurrent && (
                        <span className="shrink-0 text-[9px] text-strawberry bg-strawberry/10 px-1.5 py-0.5 rounded-full">
                          现在
                        </span>
                      )}
                      <span className="shrink-0 text-[10px] text-plum/30">{fmtTime(s.updated_at)}</span>
                      <button
                        aria-label={confirmId === s.id ? '再点一次确认删除' : '删除这段对话'}
                        onClick={(e) => handleDelete(e, s.id)}
                        className={`btn-press shrink-0 w-5 h-5 rounded-full flex items-center justify-center transition-colors ${
                          confirmId === s.id
                            ? 'bg-red-400/15 text-red-500 text-[9px]'
                            : 'text-plum/20 hover:text-plum/50 hover:bg-plum/5'
                        }`}
                      >
                        {confirmId === s.id ? '确认?' : <IconClose size={10} />}
                      </button>
                    </div>
                    {s.preview && (
                      <p className="mt-0.5 truncate text-[11px] text-plum/40">{s.preview}</p>
                    )}
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
