import { useState, useEffect, useRef, useMemo } from 'react'
import IconClose from '../assets/icons/IconClose'
import IconHistory from '../assets/icons/IconHistory'
import IconWhale from '../assets/icons/IconWhale'
import { apiFetch } from '../api/client'
import { useFocusTrap } from '../hooks/useFocusTrap'
import { bucketOf, formatMessageTime, hktNowStr } from '../utils/time'

// 单用户陪伴场景：会话量级是「几百条」，一次拉齐比分页体验更好——
// 前端要按时间连续分组，分页会在翻页处把分组切断。上限只是防御性封顶。
const LIST_LIMIT = 500

// 行内是否补一天中的时刻：分组标题已经给出日期，只有「今天/昨天」
// 这种跨一整天的桶才需要再补 HH:MM（「今天 14:23」比「今天」有用），
// 「8月」「2025年」这种桶补时刻反而是噪音。
const SHOWS_CLOCK = new Set(['今天', '昨天'])

/**
 * 按时间把会话切成连续的分组。
 *
 * 后端已按 updated_at DESC 排好序，所以只需顺序扫描、桶 key 一变就
 * 起一个新分组——不需要先建 Map 再排序，也天然保证「同桶的会话一定
 * 相邻」（真要做 key→list 的聚合，遇到未排序数据反而会散成多段）。
 */
function groupByTime(sessions, nowStr) {
  const groups = []
  for (const s of sessions) {
    const key = bucketOf(s.updated_at, nowStr)
    const last = groups[groups.length - 1]
    if (last && last.key === key) last.items.push(s)
    else groups.push({ key, items: [s] })
  }
  return groups
}

export default function SessionsPanel({ chat, onClose }) {
  const [sessions, setSessions] = useState([])
  const [loading, setLoading] = useState(true)
  // 删除两次点击确认：首点进入「确认？」态，2 秒后自动复位
  const [confirmId, setConfirmId] = useState(null)
  const dialogRef = useFocusTrap()
  // 确认态自动复位定时器：卸载时清理（体检杂项——React 18 下落到
  // 已卸载组件的 setState 是静默 no-op，属纪律瑕疵而非 bug，但 hook
  // 应保持自洽）
  const confirmTimerRef = useRef(null)
  useEffect(() => () => clearTimeout(confirmTimerRef.current), [])

  useEffect(() => {
    let cancelled = false
    apiFetch(`/api/sessions?limit=${LIST_LIMIT}`)
      .then((res) => (res.ok ? res.json() : { sessions: [] }))
      .then((data) => {
        if (!cancelled) setSessions(Array.isArray(data.sessions) ? data.sessions : [])
      })
      .catch((e) => console.warn('[SessionsPanel] fetch failed:', e))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  // 「现在」在面板挂载时取一次：分组标题（今天/昨天/本周）在面板
  // 打开期间保持一致。中途跨越午夜也不重算——半开的面板里日期分界
  // 突然跳一下比「慢一拍」更晃眼。
  const nowStrRef = useRef(null)
  if (nowStrRef.current === null) nowStrRef.current = hktNowStr()
  const groups = useMemo(() => groupByTime(sessions, nowStrRef.current), [sessions])

  // Esc 关闭（同 MemoriesPanel 的 dialog 语义）
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') onClose()
    }
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
      clearTimeout(confirmTimerRef.current)
      setConfirmId(null)
      apiFetch(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' })
        .then((res) => {
          if (!res.ok) return
          setSessions((prev) => prev.filter((s) => s.id !== id))
          // 删掉的是当前会话 → 开一段新聊天。否则 sessionId 仍指向
          // 已删会话，下一条消息会让它换个标题「自愈复活」在列表里
          if (id === chat.sessionId) chat.newSession()
        })
        .catch((err) => console.warn('[SessionsPanel] delete failed:', err))
    } else {
      clearTimeout(confirmTimerRef.current)
      setConfirmId(id)
      confirmTimerRef.current = setTimeout(
        () => setConfirmId((cur) => (cur === id ? null : cur)),
        2000
      )
    }
  }

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-plum/15 overlay-enter" onClick={onClose} />

      {/* safe-bottom：面板底部让出 iPhone Home 指示条（env()=0 的环境无影响） */}
      <div
        className="panel-surface panel-enter relative w-full h-[62%] rounded-t-[24px] flex flex-col safe-bottom"
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="以往的对话"
      >
        <div className="flex items-center justify-between px-5 pt-5 pb-1">
          <div className="flex items-center gap-2">
            <IconHistory size={16} className="text-strawberry/70" />
            <h2 className="font-display text-[15px] text-plum leading-tight tracking-wide">
              以往的对话
            </h2>
          </div>
          <button
            onClick={onClose}
            aria-label="关闭对话列表"
            className="btn-press relative w-7 h-7 rounded-full bg-plum/5 flex items-center justify-center after:content-[''] after:absolute after:-inset-2"
          >
            <IconClose size={13} className="text-plum/50" />
          </button>
        </div>

        {/* 新聊天 */}
        <div className="px-5 pb-2.5">
          <button
            onClick={() => {
              chat.newSession()
              onClose()
            }}
            className="btn-press text-[11px] px-3 py-1 rounded-full bg-strawberry/15 text-raspberry"
          >
            ＋ 开一段新聊天
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 pb-8 chat-scroll">
          {loading ? (
            // 骨架屏（2026-08-09 商业级 1.2）：形状预告列表结构——
            // 标题条 + 预览条，错落 delay 渐次铺开，替代 spinner
            <div className="space-y-2 pt-1" aria-busy="true" aria-label="正在加载对话列表">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="rounded-xl px-3.5 py-2.5 bg-plum/[0.04]">
                  <div
                    className="skeleton-bar h-3 w-2/5"
                    style={{ animationDelay: `${i * 0.12}s` }}
                  />
                  <div
                    className="skeleton-bar h-2.5 w-3/4 mt-2"
                    style={{ animationDelay: `${i * 0.12 + 0.06}s` }}
                  />
                </div>
              ))}
            </div>
          ) : sessions.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-40 gap-3">
              <IconWhale size={28} className="text-plum/10" />
              <span className="text-[12px] text-plum/60">还没有对话呢，去和小汐聊聊吧。</span>
            </div>
          ) : (
            <div className="space-y-3 pt-1">
              {groups.map((g) => (
                <div key={g.key}>
                  {/* 时间分组标题：日期只在分组层说一次，行内不再重复。
                      粘性吸顶——长列表往下滚时始终知道自己在哪一天。
                      背景取面板同色的实色奶油（刻意不用 backdrop-filter，
                      同 .panel-surface 记录的扫掠感教训）。 */}
                  <p className="sticky top-0 z-10 bg-cream py-1 mb-1 text-[10px] font-medium text-plum/45 tracking-wide">
                    {g.key}
                  </p>
                  <div className="space-y-2">
                    {g.items.map((s) => {
                      const isCurrent = s.id === chat.sessionId
                      // 「碎碎念」标签读 kind（会话**现在**的类型），不读
                      // is_era（它的来源）。冷冷在独处时间线里接了话，kind
                      // 就变成 'chat'，标签自然消失——此前读 is_era 的结果
                      // 是：接话后这段明明已经是对话，标签还永远挂在那儿。
                      const isSolo = s.kind === 'solo'
                      const clock = SHOWS_CLOCK.has(g.key)
                        ? formatMessageTime(s.updated_at)
                        : ''
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
                          className={`springy w-full text-left rounded-xl px-3.5 py-2.5 transition-colors cursor-pointer ${
                            isCurrent ? 'bg-strawberry/10 ring-1 ring-strawberry/40' : 'hover:bg-plum/5'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            <p className="flex-1 truncate text-[13px] font-medium text-plum/75">
                              {s.title || '(没有标题)'}
                            </p>
                            {/* 小汐的碎碎念：她独处时说的话，冷冷还没接话 */}
                            {isSolo && (
                              <span className="shrink-0 text-[9px] text-plum/55 bg-plum/8 px-1.5 py-0.5 rounded-full">
                                碎碎念
                              </span>
                            )}
                            {isCurrent && (
                              <span className="shrink-0 text-[9px] text-raspberry bg-strawberry/10 px-1.5 py-0.5 rounded-full">
                                现在
                              </span>
                            )}
                            {clock && (
                              <span className="shrink-0 text-[10px] text-plum/60">{clock}</span>
                            )}
                            <button
                              aria-label={confirmId === s.id ? '再点一次确认删除' : '删除这段对话'}
                              onClick={(e) => handleDelete(e, s.id)}
                              /* 20px 视觉 + 透明伪元素扩到 44px 点击区（HIG 拇指目标），
                                 伪元素属按钮自身，点击仍命中删除而非行容器 */
                              className={`btn-press relative shrink-0 w-5 h-5 rounded-full flex items-center justify-center transition-colors after:content-[''] after:absolute after:-inset-3 ${
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
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
