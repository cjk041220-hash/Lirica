import { useState, useEffect, useMemo } from 'react'
import IconClose from '../assets/icons/IconClose'
import IconCalendar from '../assets/icons/IconCalendar'
import { apiFetch } from '../api/client'
import { fetchEvents, createEvent, updateEvent, deleteEvent } from '../api/events'
import { useFocusTrap } from '../hooks/useFocusTrap'

// 「今天」按 HKT 判定（同 SessionsPanel 的时区纪律：天空/深夜/日历同一口钟）
const hktTodayISO = () =>
  new Intl.DateTimeFormat('sv', { timeZone: 'Asia/Hong_Kong' }).format(new Date()).slice(0, 10)

const CN_MONTHS = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月']
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const TYPE_LABELS = { exam: '考试', trip: '旅行', appointment: '约定', health: '身体', general: '其他' }
// 系统派生的日子（生日/相识纪念日）：日历上可见、只读——节奏由小汐的检测器掌握
const READONLY_TYPES = new Set(['birthday', 'anniversary'])

const pad = n => String(n).padStart(2, '0')
const isoOf = d => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`

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

// pill 三色语义：✦琥珀=小汐记的 / 疗愈绿=冷冷记的 / 陶土玫瑰=很重要
function pillClasses(ev) {
  if (ev.severity === 'high') return 'bg-raspberry/15 border-raspberry text-plum/85'
  if (ev.source === 'user') return 'bg-strawberry/15 border-strawberry text-plum/85'
  return 'bg-honey/20 border-honey text-plum/85'   // llm / profile
}
const isXiaoxiNoted = ev => ev.source === 'llm' || ev.source === 'profile'
const sourceLabel = ev =>
  ev.source === 'user' ? '冷冷记的' : READONLY_TYPES.has(ev.event_type) ? '小汐派生的日子' : '小汐从聊天里记的'

export default function CalendarPanel({ onClose, lastDoneAt = 0 }) {
  const today = hktTodayISO()
  const [viewY, viewM] = [today.slice(0, 4) * 1, today.slice(5, 7) * 1]
  const [month, setMonth] = useState({ y: viewY, m: viewM })
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)

  // 弹层层级：日期详情 → 记/改事件（Esc 从最上层关起）
  const [selectedDay, setSelectedDay] = useState(null)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState(null)        // 事件对象 or null=新建
  const [form, setForm] = useState({ title: '', event_type: 'exam', severity: 'normal', memo: '', end_date: '' })
  const [similar, setSimilar] = useState(null)        // 409 相似提示
  const [confirmDelId, setConfirmDelId] = useState(null)
  const [toast, setToast] = useState(null)
  const [reloadKey, setReloadKey] = useState(0)
  const dialogRef = useFocusTrap()

  const cells = useMemo(() => monthGrid(month.y, month.m), [month])
  const byDay = useMemo(() => expandByDay(events, cells), [events, cells])
  const dayEvents = selectedDay
    ? events.filter(ev => selectedDay >= ev.event_date && selectedDay <= (ev.end_date || ev.event_date))
    : []

  // 拉取：切月 / 聊完一条（小汐可能刚记下/改过/删过日程）/ 写操作后
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetchEvents(cells[0].iso, cells[cells.length - 1].iso)
      .then(data => { if (!cancelled) setEvents(data.events) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [cells, lastDoneAt, reloadKey])

  // Esc 层级：填写 → 详情 → 面板
  useEffect(() => {
    const onKey = e => {
      if (e.key !== 'Escape') return
      if (formOpen) { setFormOpen(false); setEditing(null); setSimilar(null) }
      else if (selectedDay) setSelectedDay(null)
      else onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [formOpen, selectedDay, onClose])

  const showToast = (text) => {
    setToast(text)
    setTimeout(() => setToast(null), 2200)
  }
  const refresh = () => setReloadKey(k => k + 1)

  const shiftMonth = d => {
    setMonth(({ y, m }) => {
      const nm = m + d
      return nm < 1 ? { y: y - 1, m: 12 } : nm > 12 ? { y: y + 1, m: 1 } : { y, m: nm }
    })
  }

  const openForm = (ev = null) => {
    setEditing(ev)
    setSimilar(null)
    setForm(ev
      ? { title: ev.title, event_type: ev.event_type, severity: ev.severity, memo: ev.memo || '', end_date: ev.end_date || '' }
      : { title: '', event_type: 'exam', severity: 'normal', memo: '', end_date: '' })
    setFormOpen(true)
  }

  const handleSave = async (confirm = false) => {
    const title = form.title.trim()
    if (!title) { showToast('先告诉小汐是什么事呀'); return }
    const date = editing ? editing.event_date : selectedDay
    const payload = {
      title, event_type: form.event_type, severity: form.severity,
      memo: form.memo.trim(), end_date: form.end_date || '',
    }
    if (editing) {
      const { ok, data } = await updateEvent(editing.id, payload)
      if (ok) { showToast('小汐改好啦 ✏️'); setFormOpen(false); setEditing(null); refresh() }
      else showToast((data && data.detail) || '呜，没改成……')
      return
    }
    const { ok, status, data } = await createEvent({ ...payload, event_date: date, confirm })
    if (ok) {
      showToast(data && data.created === false ? '这件小汐已经记过啦 ✦' : '小汐帮冷冷记下啦 ✨')
      setFormOpen(false); setSimilar(null); refresh()
    } else if (status === 409 && data && data.detail && data.detail.similar) {
      setSimilar(data.detail.similar)        // 相似提示：冷冷点「还是要记」带 confirm 重提
    } else {
      showToast((data && (data.detail && data.detail.message || data.detail)) || '呜，没记上……')
    }
  }

  const handleDelete = (ev) => {
    if (confirmDelId === ev.id) {
      setConfirmDelId(null)
      deleteEvent(ev.id).then(({ ok, data }) => {
        if (ok) { showToast('小汐把这条划掉啦，不会偷偷记回来的'); refresh() }
        else showToast((data && data.detail) || '呜，没删掉……')
      })
    } else {
      setConfirmDelId(ev.id)
      setTimeout(() => setConfirmDelId(cur => (cur === ev.id ? null : cur)), 2000)
    }
  }

  const monthKey = `${month.y}-${month.m}`

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-plum/15 overlay-enter" onClick={onClose} />

      <div className="panel-enter relative w-full max-h-[88%] rounded-t-[24px] flex flex-col"
        ref={dialogRef} tabIndex={-1}
        role="dialog" aria-modal="true" aria-label="小汐的日历"
        style={{ background: 'rgba(252,250,248,0.95)', backdropFilter: 'blur(20px)', boxShadow: '0 -12px 48px rgba(92,84,77,0.1)' }}>

        {/* 头部 */}
        <div className="flex items-center justify-between px-5 pt-5 pb-1">
          <div className="flex items-center gap-2">
            <IconCalendar size={16} className="text-strawberry/70" />
            <h2 className="text-[13px] font-medium text-plum/80 tracking-wide">小汐的日历</h2>
          </div>
          <button onClick={onClose} aria-label="关闭日历" className="btn-press w-7 h-7 rounded-full bg-plum/5 flex items-center justify-center">
            <IconClose size={13} className="text-plum/50" />
          </button>
        </div>

        {/* 月份栏 */}
        <div key={monthKey} className="animate-fade-in-up flex items-center justify-center gap-3 px-5 py-2">
          <button onClick={() => shiftMonth(-1)} aria-label="上个月" title="上个月"
            className="btn-press w-7 h-7 rounded-full bg-plum/5 text-plum/55 text-[15px] leading-none">‹</button>
          <div className="flex items-baseline gap-1.5 min-w-[7.5rem] justify-center">
            <span className="text-[20px] font-bold text-plum/85" style={{ fontFamily: 'var(--font-serif, "Noto Serif SC", "Songti SC", serif)' }}>
              {CN_MONTHS[month.m - 1]}
            </span>
            <span className="text-[11px] font-semibold text-honey tracking-[0.2em]">{month.y}</span>
          </div>
          <button onClick={() => shiftMonth(1)} aria-label="下个月" title="下个月"
            className="btn-press w-7 h-7 rounded-full bg-plum/5 text-plum/55 text-[15px] leading-none">›</button>
          <button onClick={() => { const t = hktTodayISO(); setMonth({ y: t.slice(0, 4) * 1, m: t.slice(5, 7) * 1 }) }}
            className="btn-press text-[11px] px-2.5 py-1 rounded-full bg-strawberry/15 text-strawberry">今天</button>
        </div>

        {/* 网格 */}
        <div className="flex-1 overflow-y-auto px-3 pb-2 chat-scroll">
          <div className="grid grid-cols-7 px-1 pb-1">
            {WEEKDAYS.map(w => (
              <span key={w} className="text-center text-[10px] text-plum/35 font-medium">{w}</span>
            ))}
          </div>
          {loading ? (
            <div className="flex items-center justify-center h-48">
              <div className="w-6 h-6 border-2 border-strawberry/20 border-t-strawberry/60 rounded-full animate-spin" />
            </div>
          ) : (
            <div key={monthKey} className="grid grid-cols-7 grid-rows-6 gap-1 auto-rows-fr" style={{ minHeight: '300px' }}>
              {cells.map((c, i) => {
                const items = byDay[c.iso] || []
                const isToday = c.iso === today
                const isSel = c.iso === selectedDay
                return (
                  <button
                    key={c.iso}
                    onClick={() => setSelectedDay(c.iso)}
                    aria-label={`${month.m}月${c.day}日${items.length ? `，${items.length}件事` : ''}`}
                    className={`animate-fade-in-up relative min-w-0 rounded-xl p-1 pt-1.5 text-left align-top border transition-colors
                      ${c.inMonth ? '' : 'opacity-25 pointer-events-none'}
                      ${isToday ? 'bg-honey/10' : ''}
                      ${isSel ? 'bg-strawberry/10 border-strawberry/50' : 'border-transparent hover:bg-plum/5'}
                      ${c.iso < today && !isToday ? 'opacity-55' : ''}`}
                    style={{ animationDelay: `${i * 0.012}s` }}
                  >
                    <span className={`block text-[11px] font-semibold pl-0.5 ${isToday ? 'text-honey' : 'text-plum/60'}`}>
                      {c.day}
                      {isToday && <span className="inline-block ml-1 w-1 h-1 rounded-full bg-honey align-middle shadow-[0_0_5px_rgba(217,164,91,0.9)]" />}
                    </span>
                    {items.slice(0, 2).map(({ ev, first }, j) => (
                      <div key={ev.id + j}
                        className={`mt-0.5 min-w-0 truncate rounded-md border-l-2 px-1 text-[9px] leading-[1.6] ${pillClasses(ev)}`}>
                        {isXiaoxiNoted(ev) && first ? '✦ ' : ''}{first ? ev.title : '·'}
                      </div>
                    ))}
                    {items.length > 2 && <div className="text-[8px] text-plum/35 pl-0.5">＋{items.length - 2}</div>}
                  </button>
                )
              })}
            </div>
          )}
          {/* 图例 */}
          <div className="flex justify-center gap-4 py-2 text-[10px] text-plum/40">
            <span><b className="text-honey">✦</b> 小汐记的</span>
            <span><b className="text-strawberry">▎</b> 冷冷记的</span>
            <span><b className="text-raspberry">▎</b> 很重要</span>
          </div>
        </div>

        {/* ── 日期详情 sheet ── */}
        {selectedDay && !formOpen && (
          <div className="absolute inset-0 z-[5] flex items-end rounded-t-[24px] overflow-hidden">
            <div className="absolute inset-0 bg-plum/15 overlay-enter" onClick={() => setSelectedDay(null)} />
            <div className="panel-enter relative w-full max-h-[80%] rounded-t-[20px] overflow-y-auto chat-scroll px-5 pt-3 pb-6"
              role="dialog" aria-modal="true" aria-label={`${selectedDay} 的安排`}
              style={{ background: 'rgba(252,250,248,0.98)', backdropFilter: 'blur(20px)', boxShadow: '0 -10px 40px rgba(92,84,77,0.14)' }}>
              <div className="w-9 h-1 rounded-full bg-peach mx-auto mb-3" />
              <div className="flex items-baseline gap-2 mb-3">
                <h3 className="text-[16px] font-bold text-plum/85">
                  {selectedDay.slice(5, 7) * 1}月{selectedDay.slice(8, 10) * 1}日
                </h3>
                <span className="text-[11px] text-plum/40">
                  周{WEEKDAYS[(new Date(selectedDay + 'T00:00:00Z').getUTCDay() + 6) % 7]}
                </span>
              </div>

              {dayEvents.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-blush bg-peach-light/20 px-4 py-6 text-center text-[12px] leading-8 text-plum/55">
                  这一天还空着，<b className="text-strawberry">要不要和小汐约点什么？</b>
                </div>
              ) : dayEvents.map(ev => {
                const readonly = READONLY_TYPES.has(ev.event_type)
                return (
                  <div key={ev.id} className="flex items-center gap-2.5 rounded-2xl bg-[#F6EFE5] border border-peach-light px-3.5 py-3 mb-2">
                    <span className={`w-2 h-2 rounded-full shrink-0 ${
                      ev.severity === 'high' ? 'bg-raspberry' : ev.source === 'user' ? 'bg-strawberry' : 'bg-honey shadow-[0_0_6px_rgba(217,164,91,0.5)]'}`} />
                    <div className="flex-1 min-w-0">
                      <p className="text-[13px] font-semibold text-plum/85 truncate">
                        {isXiaoxiNoted(ev) ? '✦ ' : ''}{ev.title}
                      </p>
                      <p className="text-[10.5px] text-plum/40">
                        {sourceLabel(ev)}
                        {ev.end_date ? ` · ${ev.event_date.slice(5)} ~ ${ev.end_date.slice(5)}` : ''}
                        {ev.memo ? ` · ${ev.memo}` : ''}
                      </p>
                    </div>
                    {ev.severity === 'high' && (
                      <span className="shrink-0 text-[9.5px] text-raspberry border border-raspberry/50 rounded-full px-2 py-0.5">很重要</span>
                    )}
                    {readonly ? (
                      <span className="shrink-0 text-[9.5px] text-plum/40 border border-blush rounded-full px-2 py-0.5">只读</span>
                    ) : (
                      <>
                        <button aria-label={`编辑${ev.title}`} title="编辑" onClick={() => openForm(ev)}
                          className="btn-press shrink-0 w-7 h-7 rounded-lg bg-cream border border-peach-light text-plum/55 text-[12px]">✎</button>
                        <button aria-label={confirmDelId === ev.id ? '再点一次确认删除' : `删除${ev.title}`} title="删除"
                          onClick={() => handleDelete(ev)}
                          className={`btn-press shrink-0 w-7 h-7 rounded-lg border text-[11px] ${
                            confirmDelId === ev.id
                              ? 'bg-raspberry/15 border-raspberry/50 text-raspberry text-[9px]'
                              : 'bg-cream border-peach-light text-plum/55'}`}>
                          {confirmDelId === ev.id ? '确认?' : '✕'}
                        </button>
                      </>
                    )}
                  </div>
                )
              })}
              <button onClick={() => openForm(null)}
                className="btn-press mt-2 w-full py-3 rounded-2xl text-[13px] font-bold tracking-[0.12em] text-cream shadow-[0_6px_18px_rgba(119,168,168,0.35)]"
                style={{ background: 'linear-gradient(135deg,#93BEBE,#77A8A8)' }}>
                ＋ 记一件事
              </button>
            </div>
          </div>
        )}

        {/* ── 记 / 改事件 sheet ── */}
        {formOpen && (
          <div className="absolute inset-0 z-[6] flex items-end rounded-t-[24px] overflow-hidden">
            <div className="absolute inset-0 bg-plum/15 overlay-enter" onClick={() => { setFormOpen(false); setEditing(null); setSimilar(null) }} />
            <div className="panel-enter relative w-full max-h-[84%] rounded-t-[20px] overflow-y-auto chat-scroll px-5 pt-3 pb-6"
              role="dialog" aria-modal="true" aria-label={editing ? '修改这件事' : '记一件事'}
              style={{ background: 'rgba(252,250,248,0.98)', backdropFilter: 'blur(20px)', boxShadow: '0 -10px 40px rgba(92,84,77,0.14)' }}>
              <div className="w-9 h-1 rounded-full bg-peach mx-auto mb-3" />
              <h3 className="text-[16px] font-bold text-plum/85 mb-1">{editing ? '改一件事' : '记一件事'}</h3>
              <p className="text-[10.5px] text-plum/40 mb-4">{editing ? '改给小汐，也改给自己' : '记给小汐 · 也记给自己'}</p>

              <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mb-1.5">什么事</label>
              <input type="text" value={form.title} maxLength={60}
                onChange={e => setForm(f => ({ ...f, title: e.target.value }))}
                placeholder="例如：期中考试"
                className="w-full rounded-xl bg-[#FBF6EF] border border-blush px-3.5 py-2.5 text-[13px] text-plum/85 outline-none focus:border-strawberry" />

              {!editing && (
                <>
                  <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">日期</label>
                  <div className="rounded-xl bg-peach-light/25 border border-peach-light px-3.5 py-2.5 text-[13px] text-plum/70">{selectedDay}</div>
                </>
              )}

              <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">类型</label>
              <div className="flex flex-wrap gap-2">
                {Object.entries(TYPE_LABELS).map(([k, v]) => (
                  <button key={k} onClick={() => setForm(f => ({ ...f, event_type: k }))}
                    className={`btn-press px-3.5 py-1.5 rounded-full text-[11.5px] border transition-colors ${
                      form.event_type === k
                        ? 'bg-strawberry/20 border-strawberry text-strawberry font-bold'
                        : 'border-blush text-plum/55'}`}>
                    {v}
                  </button>
                ))}
              </div>

              {form.event_type === 'trip' && (
                <>
                  <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">回来的日子（可选）</label>
                  <input type="date" value={form.end_date} min={editing ? editing.event_date : selectedDay}
                    onChange={e => setForm(f => ({ ...f, end_date: e.target.value }))}
                    className="w-full rounded-xl bg-[#FBF6EF] border border-blush px-3.5 py-2.5 text-[13px] text-plum/85 outline-none focus:border-strawberry" />
                </>
              )}

              <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">重要性</label>
              <div className="flex gap-2">
                <button onClick={() => setForm(f => ({ ...f, severity: 'normal' }))}
                  className={`btn-press px-3.5 py-1.5 rounded-full text-[11.5px] border ${
                    form.severity === 'normal' ? 'bg-strawberry/20 border-strawberry text-strawberry font-bold' : 'border-blush text-plum/55'}`}>
                  普通
                </button>
                <button onClick={() => setForm(f => ({ ...f, severity: 'high' }))}
                  className={`btn-press px-3.5 py-1.5 rounded-full text-[11.5px] border ${
                    form.severity === 'high' ? 'bg-raspberry/15 border-raspberry text-raspberry font-bold' : 'border-blush text-plum/55'}`}>
                  很重要
                </button>
              </div>

              <label className="block text-[10px] font-semibold text-plum/45 tracking-[0.24em] mt-4 mb-1.5">备注（可选）</label>
              <textarea value={form.memo} maxLength={300}
                onChange={e => setForm(f => ({ ...f, memo: e.target.value }))}
                placeholder="例如：考完想去吃顿好的"
                className="w-full h-14 resize-none rounded-xl bg-[#FBF6EF] border border-blush px-3.5 py-2.5 text-[13px] text-plum/85 outline-none focus:border-strawberry" />

              {/* 相似合并提示：后端 409 → 给冷冷选择，而不是静默并存两条 */}
              {similar && (
                <div className="mt-3 rounded-xl bg-honey/10 border border-honey/40 px-3.5 py-2.5 text-[11.5px] text-plum/70 leading-6">
                  已经有件很像的：<b>「{similar.title}」</b>（{similar.event_date}），还要记吗？
                  <div className="flex gap-2 mt-1.5">
                    <button onClick={() => handleSave(true)}
                      className="btn-press px-3 py-1 rounded-full bg-honey/20 text-[#8a6420] text-[11px] font-bold">还是要记</button>
                    <button onClick={() => { setSimilar(null); setFormOpen(false) }}
                      className="btn-press px-3 py-1 rounded-full bg-plum/5 text-plum/55 text-[11px]">算了</button>
                  </div>
                </div>
              )}

              <button onClick={() => handleSave(false)}
                className="btn-press mt-4 w-full py-3 rounded-2xl text-[13px] font-bold tracking-[0.12em] text-cream shadow-[0_6px_18px_rgba(119,168,168,0.35)]"
                style={{ background: 'linear-gradient(135deg,#93BEBE,#77A8A8)' }}>
                {editing ? '改好啦 ✨' : '记好啦 ✨'}
              </button>
            </div>
          </div>
        )}

        {/* toast */}
        {toast && (
          <div className="absolute left-1/2 bottom-24 z-[9] -translate-x-1/2 animate-fade-in-up whitespace-nowrap
            rounded-full bg-plum/90 text-peach-light px-5 py-2.5 text-[12px] tracking-wide shadow-[0_10px_30px_rgba(66,60,54,0.4)]">
            {toast}
          </div>
        )}
      </div>
    </div>
  )
}
