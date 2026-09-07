import { useState, useEffect, useMemo, useRef } from 'react'
import IconClose from '../assets/icons/IconClose'
import IconCalendar from '../assets/icons/IconCalendar'
import {
  fetchEvents,
  createEvent,
  updateEvent,
  deleteEvent,
  setEventCompleted,
} from '../api/events'
import { setToken, apiFetch, TOKEN_UPDATED_EVENT } from '../api/client'
import { useFocusTrap } from '../hooks/useFocusTrap'

// 「今天」按 HKT 判定（同 SessionsPanel 的时区纪律：天空/深夜/日历同一口钟）
const hktTodayISO = () =>
  new Intl.DateTimeFormat('sv', { timeZone: 'Asia/Hong_Kong' }).format(new Date()).slice(0, 10)

const CN_MONTHS = [
  '一月',
  '二月',
  '三月',
  '四月',
  '五月',
  '六月',
  '七月',
  '八月',
  '九月',
  '十月',
  '十一月',
  '十二月',
]
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const TYPE_LABELS = {
  exam: '考试',
  trip: '旅行',
  appointment: '约定',
  health: '身体',
  general: '其他',
}
// 系统派生的日子（生日/相识纪念日）：日历上可见、只读——节奏由小汐的检测器掌握
const READONLY_TYPES = new Set(['birthday', 'anniversary'])
// 天气事件（天气预报自动检测的）：日历可见、只读——它们是信息不是日程，
// 不可改不可删不可勾完成。event_type 与检测器（utils/events.py）对齐
const WEATHER_TYPES = new Set(['typhoon', 'gale', 'thunderstorm', 'rainstorm'])
const WEATHER_ICONS = { typhoon: '🌬️', gale: '🌬️', thunderstorm: '⚡', rainstorm: '☔️' }
// 检测器的标题带日期前缀（「2026-08-08 有雷暴」是给提示词读的），日历格子里太挤，剥掉
const weatherLabel = (ev) => (ev.title || '').replace(/^\d{4}-\d{2}-\d{2}\s*/, '')

const pad = (n) => String(n).padStart(2, '0')
const isoOf = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`

// 42 格月历（周一开头）：覆盖当月 1 日所在周一 ~ 月末所在周日，
// 用 UTC 运算躲开设备时区与夏令时（日期纯文本，不碰本地时间）
function monthGrid(year, month) {
  const first = new Date(Date.UTC(year, month - 1, 1))
  const lead = (first.getUTCDay() + 6) % 7
  const start = Date.UTC(year, month - 1, 1 - lead)
  return Array.from({ length: 42 }, (_, i) => {
    const d = new Date(start + i * 86400000)
    return { iso: isoOf(d), day: d.getUTCDate(), inMonth: d.getUTCMonth() + 1 === month }
  })
}

// 事件按覆盖日期展开（多天旅行每天一格：首格全称、续格圆点延续）
function expandByDay(events, cells) {
  const [lo, hi] = [cells[0].iso, cells[cells.length - 1].iso]
  const map = {}
  for (const ev of events) {
    const from = ev.event_date
    const to = ev.end_date && ev.end_date > from ? ev.end_date : from
    for (const c of cells) {
      if (c.iso >= from && c.iso <= to && c.iso >= lo && c.iso <= hi) {
        ;(map[c.iso] ||= []).push({ ev, first: c.iso === from })
      }
    }
  }
  return map
}

// pill 四色语义：✦琥珀=小汐记的 / 疗愈绿=冷冷记的 / 陶土玫瑰=很重要 / 灰雾=天气预报
function pillClasses(ev) {
  if (ev.source === 'weather') return 'bg-plum/10 border-plum/30 text-plum/55'
  if (ev.severity === 'high') return 'bg-raspberry/15 border-raspberry text-plum/85'
  if (ev.source === 'user') return 'bg-strawberry/15 border-strawberry text-plum/85'
  return 'bg-honey/20 border-honey text-plum/85' // llm / profile
}
const isXiaoxiNoted = (ev) => ev.source === 'llm' || ev.source === 'profile'
const sourceLabel = (ev) =>
  ev.source === 'user'
    ? '冷冷记的'
    : ev.source === 'weather'
      ? '天气预报自动记的'
      : READONLY_TYPES.has(ev.event_type)
        ? '小汐派生的日子'
        : '小汐从聊天里记的'

export default function CalendarPanel({ onClose, lastDoneAt = 0 }) {
  const today = hktTodayISO()
  const [viewY, viewM] = [today.slice(0, 4) * 1, today.slice(5, 7) * 1]
  const [month, setMonth] = useState({ y: viewY, m: viewM })
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [isUnauthorized, setIsUnauthorized] = useState(false)
  const [unlockToken, setUnlockToken] = useState('')
  const [unlockError, setUnlockError] = useState('')

  // 弹层层级：日期详情 → 记/改事件（Esc 从最上层关起）
  const [selectedDay, setSelectedDay] = useState(null)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState(null) // 事件对象 or null=新建
  const [form, setForm] = useState({
    title: '',
    event_type: 'exam',
    severity: 'normal',
    memo: '',
    end_date: '',
    event_date: '',
  })
  const [similar, setSimilar] = useState(null) // 409 相似提示
  const [confirmDelId, setConfirmDelId] = useState(null)
  const [toast, setToast] = useState(null)
  const [saving, setSaving] = useState(false) // 表单提交 in-flight（防双击重复提交）
  const [reloadKey, setReloadKey] = useState(0)
  const dialogRef = useFocusTrap()
  // toast 与删除确认态的自动消失定时器：卸载时清理（体检杂项，hook 自洽）
  const toastTimerRef = useRef(null)
  const confirmDelTimerRef = useRef(null)
  useEffect(
    () => () => {
      clearTimeout(toastTimerRef.current)
      clearTimeout(confirmDelTimerRef.current)
    },
    []
  )
  // 两层 sheet 各自的焦点陷阱（体检 F-5：此前只有外层面板有 trap，
  // Tab 会穿过 sheet 边界操作被遮住的月历）。useFocusTrap 的嵌套栈
  // 保证焦点在内层时外层 trap 让路；callback ref 随条件渲染挂载激活
  const daySheetRef = useFocusTrap()
  const formSheetRef = useFocusTrap()

  const cells = useMemo(() => monthGrid(month.y, month.m), [month])
  const byDay = useMemo(() => expandByDay(events, cells), [events, cells])
  const dayEvents = selectedDay
    ? events.filter(
        (ev) => selectedDay >= ev.event_date && selectedDay <= (ev.end_date || ev.event_date)
      )
    : []

  // 监听 Token 更新事件，自动刷新数据并解除锁定
  useEffect(() => {
    const onTokenUpdated = () => refresh()
    window.addEventListener(TOKEN_UPDATED_EVENT, onTokenUpdated)
    return () => window.removeEventListener(TOKEN_UPDATED_EVENT, onTokenUpdated)
  }, [])

  const handleUnlock = async (e) => {
    if (e) e.preventDefault()
    const trimmed = unlockToken.trim()
    if (!trimmed) return
    setToken(trimmed)
    setUnlockError('')
    try {
      const res = await apiFetch('/api/events?from=' + cells[0].iso + '&to=' + cells[cells.length - 1].iso)
      if (res.status === 401) {
        setUnlockError('暗号好像不太对哦，再试一次吧~')
      } else {
        setIsUnauthorized(false)
        refresh()
      }
    } catch {
      setUnlockError('连接服务器时出了点小状况')
    }
  }

  // 拉取：切月 / 聊完一条（小汐可能刚记下/改过/删过日程）/ 写操作后
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetchEvents(cells[0].iso, cells[cells.length - 1].iso)
      .then((data) => {
        if (!cancelled) {
          if (data.unauthorized) {
            setIsUnauthorized(true)
          } else {
            setIsUnauthorized(false)
            setEvents(data.events)
          }
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [cells, lastDoneAt, reloadKey])

  // Esc 层级：填写 → 详情 → 面板
  useEffect(() => {
    const onKey = (e) => {
      if (e.key !== 'Escape') return
      if (formOpen) {
        setFormOpen(false)
        setEditing(null)
        setSimilar(null)
      } else if (selectedDay) setSelectedDay(null)
      else onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [formOpen, selectedDay, onClose])

  const showToast = (text) => {
    setToast(text)
    toastTimerRef.current = setTimeout(() => setToast(null), 2200)
  }
  const refresh = () => setReloadKey((k) => k + 1)

  const shiftMonth = (d) => {
    setMonth(({ y, m }) => {
      const nm = m + d
      return nm < 1 ? { y: y - 1, m: 12 } : nm > 12 ? { y: y + 1, m: 1 } : { y, m: nm }
    })
  }

  const openForm = (ev = null) => {
    setEditing(ev)
    setSimilar(null)
    setForm(
      ev
        ? {
            title: ev.title,
            event_type: ev.event_type,
            severity: ev.severity,
            memo: ev.memo || '',
            end_date: ev.end_date || '',
            event_date: ev.event_date,
          }
        : {
            title: '',
            event_type: 'exam',
            severity: 'normal',
            memo: '',
            end_date: '',
            event_date: '',
          }
    )
    setFormOpen(true)
  }

  const handleSave = async (confirm = false) => {
    if (saving) return
    const title = form.title.trim()
    if (!title) {
      showToast('先告诉小汐是什么事呀')
      return
    }
    // 编辑可改日期（后端 update_event 支持，dedupe_key 不随编辑改写；
    // 改到过去 = 修正记错的日子，看门狗会自然翻篇）。新建日期来自选日。
    const date = form.event_date || (editing ? editing.event_date : selectedDay)
    const payload = {
      title,
      event_type: form.event_type,
      severity: form.severity,
      memo: form.memo.trim(),
      end_date: form.end_date || '',
      event_date: date,
    }
    setSaving(true)
    try {
      if (editing) {
        const { ok, data } = await updateEvent(editing.id, payload)
        if (ok) {
          showToast('小汐改好啦 ✏️')
          setFormOpen(false)
          setEditing(null)
          refresh()
        } else showToast((data && data.detail) || '呜，没改成……')
        return
      }
      const { ok, status, data } = await createEvent({ ...payload, event_date: date, confirm })
      if (ok) {
        showToast(data && data.created === false ? '这件小汐已经记过啦 ✦' : '小汐帮冷冷记下啦 ✨')
        setFormOpen(false)
        setSimilar(null)
        refresh()
      } else if (status === 409 && data && data.detail && data.detail.similar) {
        setSimilar(data.detail.similar) // 相似提示：冷冷点「还是要记」带 confirm 重提
      } else {
        showToast((data && ((data.detail && data.detail.message) || data.detail)) || '呜，没记上……')
      }
    } finally {
      setSaving(false)
    }
  }

  const handleComplete = (ev) => {
    const next = !ev.completed_at
    setEventCompleted(ev.id, next).then(({ ok, data }) => {
      if (ok) {
        showToast(next ? '办完啦，小汐不惦记了 ✓' : '好，小汐继续惦记着 ✦')
        refresh()
      } else showToast((data && data.detail) || '呜，没勾上……')
    })
  }

  const handleDelete = (ev) => {
    if (confirmDelId === ev.id) {
      setConfirmDelId(null)
      deleteEvent(ev.id).then(({ ok, data }) => {
        if (ok) {
          showToast('小汐把这条划掉啦，不会偷偷记回来的')
          refresh()
        } else showToast((data && data.detail) || '呜，没删掉……')
      })
    } else {
      setConfirmDelId(ev.id)
      confirmDelTimerRef.current = setTimeout(
        () => setConfirmDelId((cur) => (cur === ev.id ? null : cur)),
        2000
      )
    }
  }

  const monthKey = `${month.y}-${month.m}`

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-plum/15 overlay-enter" onClick={onClose} />

      <div
        className="panel-surface panel-enter relative w-full max-h-[88%] rounded-t-[24px] flex flex-col"
        ref={dialogRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="小汐的日历"
      >
        {/* 头部 */}
        <div className="flex items-center justify-between px-5 pt-5 pb-1">
          <div className="flex items-center gap-2">
            <IconCalendar size={16} className="text-strawberry/70" />
            <h2 className="font-display text-[15px] text-plum leading-tight tracking-wide">
              小汐的日历
            </h2>
          </div>
          <button
            onClick={onClose}
            aria-label="关闭日历"
            className="btn-press relative w-7 h-7 rounded-full bg-plum/5 flex items-center justify-center after:content-[''] after:absolute after:-inset-2"
          >
            <IconClose size={13} className="text-plum/50" />
          </button>
        </div>

        {/* 月份栏 */}
        <div
          key={monthKey}
          className="animate-fade-in-up flex items-center justify-center gap-3 px-5 py-2"
        >
          <button
            onClick={() => shiftMonth(-1)}
            aria-label="上个月"
            title="上个月"
            className="btn-press relative w-7 h-7 rounded-full bg-plum/5 text-plum/55 text-[15px] leading-none after:content-[''] after:absolute after:-inset-2"
          >
            ‹
          </button>
          <div className="flex items-baseline gap-1.5 min-w-[7.5rem] justify-center">
            <span className="font-display text-[20px] text-plum/85 leading-tight">
              {CN_MONTHS[month.m - 1]}
            </span>
            <span className="text-[11px] font-semibold text-raspberry tracking-[0.2em]">
              {month.y}
            </span>
          </div>
          <button
            onClick={() => shiftMonth(1)}
            aria-label="下个月"
            title="下个月"
            className="btn-press relative w-7 h-7 rounded-full bg-plum/5 text-plum/55 text-[15px] leading-none after:content-[''] after:absolute after:-inset-2"
          >
            ›
          </button>
          <button
            onClick={() => {
              const t = hktTodayISO()
              setMonth({ y: t.slice(0, 4) * 1, m: t.slice(5, 7) * 1 })
            }}
            className="btn-press text-[11px] px-2.5 py-1 rounded-full bg-strawberry/15 text-raspberry"
          >
            今天
          </button>
        </div>

        {/* 网格 */}
        <div className="flex-1 overflow-y-auto px-3 pb-2 chat-scroll flex flex-col">
          {isUnauthorized ? (
            <div className="flex flex-col items-center justify-center h-full px-6 py-12 text-center my-auto">
              <div className="w-12 h-12 rounded-full bg-strawberry/15 flex items-center justify-center mb-3 text-xl">
                🗝️
              </div>
              <h3 className="font-display text-[15px] text-plum mb-1.5">
                小汐还在房间里等你哦~
              </h3>
              <p className="text-[12px] text-plum/65 mb-4 leading-relaxed max-w-[260px]">
                对上专属暗号，小汐记下的重要日程与约定就会立刻显示啦。
              </p>
              <form onSubmit={handleUnlock} className="flex flex-col w-full max-w-[240px] gap-2">
                <input
                  type="password"
                  placeholder="输入访问暗号..."
                  value={unlockToken}
                  onChange={(e) => setUnlockToken(e.target.value)}
                  className="w-full text-xs px-3 py-2 rounded-xl bg-plum/5 border border-strawberry/20 text-plum focus:outline-none focus:border-strawberry/50 text-center"
                />
                {unlockError && <p className="text-[10px] text-raspberry">{unlockError}</p>}
                <button
                  type="submit"
                  className="btn-press w-full py-1.5 rounded-xl bg-strawberry/20 text-raspberry text-xs font-medium hover:bg-strawberry/30 transition-colors"
                >
                  唤醒小汐
                </button>
              </form>
            </div>
          ) : (
            <>
              <div className="grid grid-cols-7 px-1 pb-1">
                {WEEKDAYS.map((w) => (
                  <span key={w} className="text-center text-[10px] text-plum/60 font-medium">
                    {w}
                  </span>
                ))}
              </div>
              {loading ? (
                // 骨架屏（2026-08-09 商业级 1.2）：7 列日期格点阵预告月历结构
                <div
                  className="grid grid-cols-7 gap-1"
                  style={{ minHeight: '300px' }}
                  aria-busy="true"
                  aria-label="正在加载日历"
                >
                  {Array.from({ length: 35 }, (_, i) => (
                    <div
                      key={i}
                      className="skeleton-bar rounded-xl"
                      style={{ animationDelay: `${(i % 7) * 0.06 + Math.floor(i / 7) * 0.1}s` }}
                    />
                  ))}
                </div>
              ) : (
                <div
                  key={monthKey}
                  className="grid grid-cols-7 grid-rows-6 gap-1 auto-rows-fr"
                  style={{ minHeight: '300px' }}
                >
                  {cells.map((c) => {
                    const items = byDay[c.iso] || []
                    const isToday = c.iso === today
                    const isSel = c.iso === selectedDay
                    return (
                      <button
                        key={c.iso}
                        onClick={() => setSelectedDay(c.iso)}
                        disabled={!c.inMonth}
                        aria-label={`${month.m}月${c.day}日${items.length ? `，${items.length}件事` : ''}`}
                        className={`springy relative min-w-0 rounded-xl p-1 pt-1.5 text-left align-top border transition-colors
                          ${c.inMonth ? '' : 'opacity-25 pointer-events-none'}
                          ${isToday ? 'bg-honey/10' : ''}
                          ${isSel ? 'bg-strawberry/10 border-strawberry/50' : 'border-transparent hover:bg-plum/5'}
                          ${c.iso < today && !isToday ? 'opacity-55' : ''}`}
                      >
                        <span
                          className={`block text-[11px] font-semibold pl-0.5 ${isToday ? 'text-raspberry' : 'text-plum/60'}`}
                        >
                          {c.day}
                          {isToday && (
                            <span className="inline-block ml-1 w-1 h-1 rounded-full bg-honey align-middle shadow-[0_0_5px_rgba(233,180,76,0.9)]" />
                          )}
                        </span>
                        {items.slice(0, 2).map(({ ev, first }, j) => {
                          const done = !!ev.completed_at
                          return (
                            <div
                              key={ev.id + j}
                              className={`mt-0.5 min-w-0 truncate rounded-md border-l-2 px-1 text-[9px] leading-[1.6] ${pillClasses(ev)} ${done ? 'opacity-50' : ''}`}
                            >
                              {ev.source === 'weather'
                                ? `${WEATHER_ICONS[ev.event_type] || '☁️'} ${first ? weatherLabel(ev) : '·'}`
                                : `${isXiaoxiNoted(ev) && first ? '✦ ' : ''}${done && first ? '✓ ' : ''}${first ? ev.title : '·'}`}
                            </div>
                          )
                        })}
                        {items.length > 2 && (
                          <div className="text-[8px] text-plum/60 pl-0.5">＋{items.length - 2}</div>
                        )}
                      </button>
                    )
                  })}
                </div>
              )}
            </>
          )}
          {/* 图例 */}
          <div className="flex justify-center gap-4 py-2 text-[10px] text-plum/40">
            <span>
              <b className="text-honey">✦</b> 小汐记的
            </span>
            <span>
              <b className="text-strawberry">▎</b> 冷冷记的
            </span>
            <span>
              <b className="text-raspberry">▎</b> 很重要
            </span>
            <span>
              <b className="text-plum/50">☁️</b> 天气预报
            </span>
          </div>
        </div>

        {/* ── 日期详情 sheet ── */}
        {selectedDay && !formOpen && (
          <div className="absolute inset-0 z-[5] flex items-end rounded-t-[24px] overflow-hidden">
            <div
              className="absolute inset-0 bg-plum/15 overlay-enter"
              onClick={() => setSelectedDay(null)}
            />
            <div
              className="panel-surface-solid panel-enter relative w-full max-h-[80%] rounded-t-[20px] overflow-y-auto chat-scroll px-5 pt-3 pb-safe-6"
              ref={daySheetRef}
              tabIndex={-1}
              role="dialog"
              aria-modal="true"
              aria-label={`${selectedDay} 的安排`}
            >
              <div className="w-9 h-1 rounded-full bg-blush mx-auto mb-3" />
              <div className="flex items-baseline gap-2 mb-3">
                <h3 className="font-display text-[16px] text-plum/85 leading-tight">
                  {selectedDay.slice(5, 7) * 1}月{selectedDay.slice(8, 10) * 1}日
                </h3>
                <span className="text-[11px] text-plum/40">
                  周{WEEKDAYS[(new Date(selectedDay + 'T00:00:00Z').getUTCDay() + 6) % 7]}
                </span>
              </div>

              {dayEvents.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-blush bg-peach-light/20 px-4 py-6 text-center text-[12px] leading-8 text-plum/55">
                  这一天还空着，<b className="text-raspberry">要不要和小汐约点什么？</b>
                </div>
              ) : (
                dayEvents.map((ev) => {
                  const readonly = READONLY_TYPES.has(ev.event_type)
                  const isWeather = WEATHER_TYPES.has(ev.event_type)
                  const done = !!ev.completed_at
                  return (
                    <div
                      key={ev.id}
                      className="flex items-center gap-2.5 rounded-2xl bg-[#FFF0F4] border border-peach-light px-3.5 py-3 mb-2"
                    >
                      <span
                        className={`w-2 h-2 rounded-full shrink-0 ${
                          ev.severity === 'high' && !isWeather
                            ? 'bg-raspberry'
                            : ev.source === 'weather'
                              ? 'bg-plum/40'
                              : ev.source === 'user'
                                ? 'bg-strawberry'
                                : 'bg-honey shadow-[0_0_6px_rgba(233,180,76,0.5)]'
                        }`}
                      />
                      <div className="flex-1 min-w-0">
                        <p
                          className={`text-[13px] font-semibold truncate ${done ? 'text-plum/45 line-through decoration-raspberry/50' : 'text-plum/85'}`}
                        >
                          {isXiaoxiNoted(ev) ? '✦ ' : ''}
                          {isWeather ? `${WEATHER_ICONS[ev.event_type] || '☁️'} ` : ''}
                          {done ? '✓ ' : ''}
                          {ev.title}
                        </p>
                        <p className="text-[10.5px] text-plum/40">
                          {sourceLabel(ev)}
                          {ev.end_date
                            ? ` · ${ev.event_date.slice(5)} ~ ${ev.end_date.slice(5)}`
                            : ''}
                          {ev.memo ? ` · ${ev.memo}` : ''}
                        </p>
                      </div>
                      {ev.severity === 'high' && !isWeather && (
                        <span className="shrink-0 text-[9.5px] text-raspberry border border-raspberry/50 rounded-full px-2 py-0.5">
                          很重要
                        </span>
                      )}
                      {isWeather ? (
                        <span className="shrink-0 text-[9.5px] text-plum/40 border border-plum/25 rounded-full px-2 py-0.5">
                          天气预报
                        </span>
                      ) : readonly ? (
                        <span className="shrink-0 text-[9.5px] text-plum/40 border border-blush rounded-full px-2 py-0.5">
                          只读
                        </span>
                      ) : (
                        <>
                          {ev.status === 'active' && (
                            <button
                              aria-label={done ? `取消完成${ev.title}` : `完成${ev.title}`}
                              title={done ? '取消完成' : '标记完成'}
                              onClick={() => handleComplete(ev)}
                              className={`btn-press shrink-0 w-7 h-7 rounded-lg border text-[12px] ${
                                done
                                  ? 'bg-honey/25 border-honey text-[#946312]'
                                  : 'bg-cream border-peach-light text-plum/55'
                              }`}
                            >
                              {done ? '↺' : '✓'}
                            </button>
                          )}
                          <button
                            aria-label={`编辑${ev.title}`}
                            title="编辑"
                            onClick={() => openForm(ev)}
                            className="btn-press shrink-0 w-7 h-7 rounded-lg bg-cream border border-peach-light text-plum/55 text-[12px]"
                          >
                            ✎
                          </button>
                          <button
                            aria-label={
                              confirmDelId === ev.id ? '再点一次确认删除' : `删除${ev.title}`
                            }
                            title="删除"
                            onClick={() => handleDelete(ev)}
                            className={`btn-press shrink-0 w-7 h-7 rounded-lg border text-[11px] ${
                              confirmDelId === ev.id
                                ? 'bg-raspberry/15 border-raspberry/50 text-raspberry text-[9px]'
                                : 'bg-cream border-peach-light text-plum/55'
                            }`}
                          >
                            {confirmDelId === ev.id ? '确认?' : '✕'}
                          </button>
                        </>
                      )}
                    </div>
                  )
                })
              )}
              <button
                onClick={() => openForm(null)}
                className="btn-press mt-2 w-full py-3 rounded-2xl font-display text-[13px] tracking-[0.12em] text-cream shadow-[0_6px_18px_rgba(194,71,123,0.32)]"
                style={{ background: 'linear-gradient(135deg,#F2789F,#C2477B)' }}
              >
                ＋ 记一件事
              </button>
            </div>
          </div>
        )}

        {/* ── 记 / 改事件 sheet ── */}
        {formOpen && (
          <div className="absolute inset-0 z-[6] flex items-end rounded-t-[24px] overflow-hidden">
            <div
              className="absolute inset-0 bg-plum/15 overlay-enter"
              onClick={() => {
                setFormOpen(false)
                setEditing(null)
                setSimilar(null)
              }}
            />
            <div
              className="panel-surface-solid panel-enter relative w-full max-h-[84%] rounded-t-[20px] overflow-y-auto chat-scroll px-5 pt-3 pb-safe-6"
              ref={formSheetRef}
              tabIndex={-1}
              role="dialog"
              aria-modal="true"
              aria-label={editing ? '修改这件事' : '记一件事'}
            >
              <div className="w-9 h-1 rounded-full bg-blush mx-auto mb-3" />
              <h3 className="font-display text-[16px] text-plum/85 leading-tight mb-1">
                {editing ? '改一件事' : '记一件事'}
              </h3>
              <p className="text-[10.5px] text-plum/40 mb-4">
                {editing ? '改给小汐，也改给自己' : '记给小汐 · 也记给自己'}
              </p>

              <label htmlFor="ev-title" className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mb-1.5">
                什么事
              </label>
              <input
                id="ev-title"
                type="text"
                value={form.title}
                maxLength={60}
                onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))}
                placeholder="例如：期中考试"
                className="w-full rounded-xl bg-[#FFF6F8] border border-blush px-3.5 py-2.5 text-[16px] text-plum/85 outline-none focus:border-strawberry"
              />

              {!editing && (
                <>
                  <label htmlFor="ev-date" className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">
                    日期
                  </label>
                  <div
                    id="ev-date"
                    className="rounded-xl bg-peach-light/25 border border-peach-light px-3.5 py-2.5 text-[13px] text-plum/70"
                  >
                    {selectedDay.slice(5, 7) * 1}月{selectedDay.slice(8, 10) * 1}日
                  </div>
                </>
              )}
              {editing && (
                <>
                  <label htmlFor="ev-date-edit" className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">
                    日期
                  </label>
                  <input
                    id="ev-date-edit"
                    type="date"
                    value={form.event_date}
                    onChange={(e) => setForm((f) => ({ ...f, event_date: e.target.value }))}
                    className="w-full rounded-xl bg-[#FFF6F8] border border-blush px-3.5 py-2.5 text-[16px] text-plum/85 outline-none focus:border-strawberry"
                  />
                </>
              )}

              <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">
                类型
              </label>
              <div className="flex flex-wrap gap-2">
                {Object.entries(TYPE_LABELS).map(([k, v]) => (
                  <button
                    key={k}
                    onClick={() => setForm((f) => ({ ...f, event_type: k }))}
                    className={`btn-press px-3.5 py-1.5 rounded-full text-[11.5px] border transition-colors ${
                      form.event_type === k
                        ? 'bg-strawberry/15 border-strawberry text-raspberry font-bold'
                        : 'border-blush text-plum/55'
                    }`}
                  >
                    {v}
                  </button>
                ))}
              </div>

              {form.event_type === 'trip' && (
                <>
                  <label htmlFor="ev-end" className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">
                    回来的日子（可选）
                  </label>
                  <input
                    id="ev-end"
                    type="date"
                    value={form.end_date}
                    min={editing ? editing.event_date : selectedDay}
                    onChange={(e) => setForm((f) => ({ ...f, end_date: e.target.value }))}
                    className="w-full rounded-xl bg-[#FFF6F8] border border-blush px-3.5 py-2.5 text-[16px] text-plum/85 outline-none focus:border-strawberry"
                  />
                </>
              )}

              <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">
                重要性
              </label>
              <div className="flex gap-2">
                <button
                  onClick={() => setForm((f) => ({ ...f, severity: 'normal' }))}
                  className={`btn-press px-3.5 py-1.5 rounded-full text-[11.5px] border ${
                    form.severity === 'normal'
                      ? 'bg-strawberry/15 border-strawberry text-raspberry font-bold'
                      : 'border-blush text-plum/55'
                  }`}
                >
                  普通
                </button>
                <button
                  onClick={() => setForm((f) => ({ ...f, severity: 'high' }))}
                  className={`btn-press px-3.5 py-1.5 rounded-full text-[11.5px] border ${
                    form.severity === 'high'
                      ? 'bg-raspberry/15 border-raspberry text-raspberry font-bold'
                      : 'border-blush text-plum/55'
                  }`}
                >
                  很重要
                </button>
              </div>

              <label htmlFor="ev-memo" className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">
                备注（可选）
              </label>
              <textarea
                id="ev-memo"
                value={form.memo}
                maxLength={300}
                onChange={(e) => setForm((f) => ({ ...f, memo: e.target.value }))}
                placeholder="例如：考完想去吃顿好的"
                className="w-full h-14 resize-none rounded-xl bg-[#FFF6F8] border border-blush px-3.5 py-2.5 text-[16px] text-plum/85 outline-none focus:border-strawberry"
              />

              {/* 相似合并提示：后端 409 → 给冷冷选择，而不是静默并存两条 */}
              {similar && (
                <div className="mt-3 rounded-xl bg-honey/10 border border-honey/40 px-3.5 py-2.5 text-[11.5px] text-plum/70 leading-6">
                  已经有件很像的：<b>「{similar.title}」</b>（{similar.event_date}），还要记吗？
                  <div className="flex gap-2 mt-1.5">
                    <button
                      onClick={() => handleSave(true)}
                      className="btn-press px-3 py-1 rounded-full bg-honey/20 text-[#946312] text-[11px] font-bold"
                    >
                      还是要记
                    </button>
                    <button
                      onClick={() => {
                        setSimilar(null)
                        setFormOpen(false)
                      }}
                      className="btn-press px-3 py-1 rounded-full bg-plum/5 text-plum/55 text-[11px]"
                    >
                      算了
                    </button>
                  </div>
                </div>
              )}

              <button
                onClick={() => handleSave(false)}
                disabled={saving}
                className="btn-press mt-4 w-full py-3 rounded-2xl font-display text-[13px] tracking-[0.12em] text-cream shadow-[0_6px_18px_rgba(194,71,123,0.32)] disabled:opacity-60"
                style={{ background: 'linear-gradient(135deg,#F2789F,#C2477B)' }}
              >
                {saving ? '记着呢……' : editing ? '改好啦 ✨' : '记好啦 ✨'}
              </button>
            </div>
          </div>
        )}

        {/* toast（role=status：保存/删除等异步结果对屏幕阅读器可闻） */}
        {toast && (
          <div
            role="status"
            aria-live="polite"
            className="absolute left-1/2 bottom-24 z-[9] -translate-x-1/2 animate-fade-in-up whitespace-nowrap
            rounded-full bg-plum/90 text-peach-light px-5 py-2.5 text-[12px] tracking-wide shadow-[0_10px_30px_rgba(110,74,86,0.4)]"
          >
            {toast}
          </div>
        )}
      </div>
    </div>
  )
}
