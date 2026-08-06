import { useState, useEffect, useRef } from 'react'
import IconClose from '../assets/icons/IconClose'
import IconWhale from '../assets/icons/IconWhale'
import { apiFetch } from '../api/client'
import { useFocusTrap } from '../hooks/useFocusTrap'

const TABS = [
  { key: 'memories', label: '记忆' },
  { key: 'diaries', label: '日记' },
]

export default function MemoriesPanel({ onClose }) {
  const [tab, setTab] = useState('memories')
  const [memories, setMemories] = useState([])
  const [diaries, setDiaries] = useState([])
  const [showArchived, setShowArchived] = useState(false)
  const [loading, setLoading] = useState(true)
  const [reloadKey, setReloadKey] = useState(0)
  // 记忆编辑/删除（体检 R4：冷冷的记忆主权——控制权不押在 LLM 自觉上）
  const [editingId, setEditingId] = useState(null)
  const [editText, setEditText] = useState('')
  const [confirmDelId, setConfirmDelId] = useState(null)
  const dialogRef = useFocusTrap()
  const confirmDelTimerRef = useRef(null)
  useEffect(() => () => clearTimeout(confirmDelTimerRef.current), [])

  const reload = () => setReloadKey(k => k + 1)

  // 导出备份（体检产品决策 3：GET /api/memories/export 只有 API 层，
  // 面板加入口——冷冷的记忆主权包括「可以带走」）。含冷藏记忆与否
  // 跟随「已归档」开关状态；浏览器下载 xiaoxi_memories.json
  const exportMemories = () => {
    const url = showArchived
      ? '/api/memories/export?include_archived=1'
      : '/api/memories/export'
    apiFetch(url)
      .then(res => {
        if (!res.ok) throw new Error(`export ${res.status}`)
        return res.blob()
      })
      .then(blob => {
        const a = document.createElement('a')
        a.href = URL.createObjectURL(blob)
        a.download = `xiaoxi_memories_${new Date().toISOString().slice(0, 10)}.json`
        a.click()
        setTimeout(() => URL.revokeObjectURL(a.href), 1000)
      })
      .catch(e => console.warn('[MemoriesPanel] export failed:', e))
  }

  const saveEdit = (id) => {
    const content = editText.trim()
    if (!content) return
    apiFetch(`/api/memories/${encodeURIComponent(id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    })
      .then(res => { if (res.ok) { setEditingId(null); reload() } })
      .catch(e => console.warn('[MemoriesPanel] edit failed:', e))
  }

  const handleDelete = (id) => {
    if (confirmDelId === id) {
      setConfirmDelId(null)
      apiFetch(`/api/memories/${encodeURIComponent(id)}`, { method: 'DELETE' })
        .then(res => { if (res.ok) reload() })
        .catch(e => console.warn('[MemoriesPanel] delete failed:', e))
    } else {
      setConfirmDelId(id)
      confirmDelTimerRef.current = setTimeout(
        () => setConfirmDelId(cur => (cur === id ? null : cur)), 2000,
      )
    }
  }

  // 切换 tab / 归档开关时按需拉取（apiFetch 带 token，裸 fetch 在配置了
  // XIAOXI_API_TOKEN 的部署下会 401 静默成空列表）。
  // include_archived=1 时连冷藏记忆一起回看（D8：冷库可读，不是永久丢失）
  useEffect(() => {
    let cancelled = false
    setLoading(true)

    const load = tab === 'memories'
      ? apiFetch(showArchived ? '/api/memories?include_archived=1' : '/api/memories')
          .then(res => (res.ok ? res.json() : { memories: [] }))
          .then(data => { if (!cancelled) setMemories(Array.isArray(data.memories) ? data.memories : []) })
      : apiFetch('/api/diaries')
          .then(res => (res.ok ? res.json() : { diaries: [] }))
          .then(data => { if (!cancelled) setDiaries(Array.isArray(data.diaries) ? data.diaries : []) })

    load
      .catch(e => console.warn('[MemoriesPanel] fetch failed:', e))
      .finally(() => { if (!cancelled) setLoading(false) })

    return () => { cancelled = true }
  }, [tab, showArchived, reloadKey])

  const items = tab === 'memories' ? memories : diaries

  // Esc 关闭（dialog 语义的键盘出口；焦点圈定留待后续完善）
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-plum/15 overlay-enter" onClick={onClose} />

      {/* safe-bottom：面板底部让出 iPhone Home 指示条（env()=0 的环境无影响） */}
      <div className="panel-surface panel-enter relative w-full h-[62%] rounded-t-[24px] flex flex-col safe-bottom"
        ref={dialogRef} tabIndex={-1}
        role="dialog" aria-modal="true" aria-label="小汐的记忆">

        <div className="flex items-center justify-between px-5 pt-5 pb-1">
          <div className="flex items-center gap-2">
            <IconWhale size={16} className="text-strawberry/70" />
            <h2 className="font-display text-[15px] text-plum leading-tight tracking-wide">小汐的记忆</h2>
          </div>
          <button onClick={onClose} aria-label="关闭记忆" className="btn-press relative w-7 h-7 rounded-full bg-plum/5 flex items-center justify-center after:content-[''] after:absolute after:-inset-2">
            <IconClose size={13} className="text-plum/50" />
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
                  ? 'bg-strawberry/15 text-raspberry'
                  : 'text-plum/35 hover:text-plum/55'
              }`}
            >
              {t.label}
            </button>
          ))}
          {tab === 'memories' && (
            <>
              <button
                onClick={exportMemories}
                title="把所有记忆导出成一个 JSON 文件，随时带走或备份"
                className="btn-press text-[11px] px-3 py-1 rounded-full transition-colors text-plum/25 hover:text-plum/45"
              >
                ↧ 导出
              </button>
              <button
                onClick={() => setShowArchived(v => !v)}
                aria-pressed={showArchived}
                className={`btn-press text-[11px] px-3 py-1 rounded-full transition-colors ml-auto ${
                  showArchived
                    ? 'bg-plum/10 text-plum/60'
                    : 'text-plum/25 hover:text-plum/45'
                }`}
              >
                已归档 {showArchived ? '✓' : ''}
              </button>
            </>
          )}
        </div>

        <div className="flex-1 overflow-y-auto px-5 pb-8 chat-scroll">
          {loading ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <div className="w-6 h-6 border-2 border-strawberry/20 border-t-strawberry/60 rounded-full animate-spin" />
              <span className="text-[11px] text-plum/30">正在翻阅{tab === 'memories' ? '记忆' : '日记'}……</span>
            </div>
          ) : items.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <IconWhale size={28} className="text-plum/10" />
              <span className="text-[12px] text-plum/30">
                {tab === 'memories' ? '还没有记忆呢，和小汐多聊聊吧。' : '小汐还没写过日记，晚上她会自己记一篇的。'}
              </span>
            </div>
          ) : tab === 'memories' ? (
            <div className="space-y-5 pt-2">
              {memories.map((item, i) => (
                <div key={item.id || i} className="springy">
                  <div className="border-l-2 border-strawberry/25 pl-3.5 py-0.5">
                    <div className="flex items-start justify-between gap-2">
                      {(item.created_at || item.date) && (
                        <p className="text-[10px] text-plum/25 mb-1">{item.created_at || item.date}</p>
                      )}
                      {/* 编辑/删除：记忆主权（R4）。冷藏行只读——要改动先回活跃态由小汐来 */}
                      {item.id && item.status !== 'archived' && (
                        <div className="flex gap-1 shrink-0 -mt-0.5">
                          <button
                            onClick={() => {
                              if (editingId === item.id) { setEditingId(null) }
                              else { setEditingId(item.id); setEditText(item.content || '') }
                            }}
                            className="btn-press text-[10px] text-plum/35 hover:text-raspberry px-1.5 py-0.5 rounded"
                          >
                            {editingId === item.id ? '收起' : '改改'}
                          </button>
                          <button
                            onClick={() => handleDelete(item.id)}
                            className={`btn-press text-[10px] px-1.5 py-0.5 rounded ${
                              confirmDelId === item.id
                                ? 'text-raspberry bg-strawberry/10'
                                : 'text-plum/35 hover:text-raspberry'
                            }`}
                          >
                            {confirmDelId === item.id ? '真删？' : '忘掉'}
                          </button>
                        </div>
                      )}
                    </div>
                    {editingId === item.id ? (
                      <div className="space-y-1.5">
                        <textarea
                          value={editText}
                          onChange={e => setEditText(e.target.value)}
                          rows={2}
                          aria-label="编辑记忆内容"
                          className="w-full text-[16px] text-plum/75 leading-[1.8] bg-plum/3 border border-strawberry/25 rounded-lg px-2.5 py-1.5 resize-none focus:outline-none focus:border-strawberry/60"
                        />
                        <div className="flex gap-2">
                          <button
                            onClick={() => saveEdit(item.id)}
                            disabled={!editText.trim()}
                            className="btn-press text-[10px] text-raspberry bg-strawberry/10 px-3 py-1 rounded-full disabled:opacity-40"
                          >
                            存好
                          </button>
                          <button
                            onClick={() => setEditingId(null)}
                            className="btn-press text-[10px] text-plum/40 px-3 py-1 rounded-full"
                          >
                            算了
                          </button>
                        </div>
                      </div>
                    ) : (
                      <p className="text-[13px] text-plum/65 leading-[1.8]">
                        {item.content || item.text || '(no content)'}
                      </p>
                    )}
                    {item.category && (
                      <span className="inline-block mt-1.5 text-[10px] text-raspberry bg-strawberry/8 px-2.5 py-0.5 rounded-full">
                        {item.category}
                      </span>
                    )}
                    {item.status === 'archived' && (
                      <span className="inline-block mt-1.5 text-[10px] text-plum/40 bg-plum/5 px-2.5 py-0.5 rounded-full">
                        已归档
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
                <div key={item.date || i} className="springy">
                  <div className="border-l-2 border-blush/50 pl-3.5 py-0.5">
                    <p className="text-[10px] text-plum/25 mb-1">{item.title || item.date}</p>
                    <p className="text-[13px] text-plum/65 leading-[1.8] whitespace-pre-wrap">{item.content}</p>
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
