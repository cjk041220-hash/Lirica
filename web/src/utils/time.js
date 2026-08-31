// 时区统一工具：小汐活在 HKT（UTC+8），天空背景与深夜判定必须同源，
// 否则非东八区用户会看到「窗外大白天、小汐却在深夜沉睡」的叙事错位。

/** 当前 HKT 小时数（0-23）。不依赖设备时区。
 *  开发态可用 ?_sky=小时 临时冻结天色，方便逐时段截图（生产构建被摇树删除）。 */
export function getHKTHour() {
  if (import.meta.env.DEV && typeof window !== 'undefined') {
    const o = new URLSearchParams(window.location.search).get('_sky')
    if (o !== null && o !== '' && !Number.isNaN(Number(o))) return ((Number(o) % 24) + 24) % 24
  }
  return (new Date().getUTCHours() + 8) % 24
}

/** 深夜判定：HKT 00:00–06:59。 */
export function isLateNight() {
  return getHKTHour() < 7
}

/** 当前 HKT 日期串 "YYYY-MM-DD"（2026-08-09 商业级 2.3：连续陪伴天数
 *  的「今天/昨天」比对用）。offsetDays 前推 N 天。与 getHKTHour 同源，
 *  不跟设备本地时区。 */
export function getHKTDateString(offsetDays = 0) {
  return new Date(Date.now() + 8 * 3600e3 + offsetDays * 86400e3).toISOString().slice(0, 10)
}

/**
 * 天空时段（SkyBackground 用）。与深夜判定共用 getHKTHour，
 * 保证「天色」和「小汐困不困」永远来自同一个钟。
 */
export function getTimePeriod() {
  const hour = getHKTHour()
  if (hour >= 5 && hour < 8) return 'morning'
  if (hour >= 8 && hour < 17) return 'day'
  if (hour >= 17 && hour < 20) return 'evening'
  return 'night'
}

// ── 对话时间线（2026-08-30 对话管理优化）──────────────────────
// 后端时间戳是 HKT 的 "YYYY-MM-DD HH:MM" 文本（utils/timezone.now_str），
// 字典序即时序，可以直接字符串比较。这里的所有函数都只做字符串
// 层面的解析——绝不 new Date(str)：Safari 不认 "YYYY-MM-DD HH:MM"
// 这种非 ISO 格式（实测返回 Invalid Date），而 new Date 一旦落到
// 设备本地时区，"今天/昨天"的判定会随用户所在时区漂移。

/** 当前 HKT 时刻的 "YYYY-MM-DD HH:MM" 串。
 *  本地新建的消息（用户气泡、流式占位）用它打时间戳，格式与后端
 *  落库的 created_at 逐字一致——前端排序与分组才不会两套标准。
 *  'sv' locale 输出 ISO 风格的 "2026-08-30 19:45"（与后端 "%Y-%m-%d
 *  %H:%M" 同形）。时间分量必须显式列出——默认 dateStyle 只出日期；
 *  hourCycle 走 h23 而非 hour12:false，后者在部分 ICU 版本会把午夜
 *  格式化成 "24:00"（JS 的经典陷阱）。 */
export function hktNowStr() {
  return new Intl.DateTimeFormat('sv', {
    timeZone: 'Asia/Hong_Kong',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  })
    .format(new Date())
    .replace('\u00a0', ' ')
    .slice(0, 16)
}

/** "YYYY-MM-DD HH:MM" → "YYYY-MM-DD"；非法输入返回 ''。 */
export function dateKey(ts) {
  const s = String(ts || '')
  return /^\d{4}-\d{2}-\d{2}/.test(s) ? s.slice(0, 10) : ''
}

/** "YYYY-MM-DD HH:MM" → "HH:MM"；非法输入返回 ''。 */
export function formatMessageTime(ts) {
  const s = String(ts || '')
  return s.length >= 16 ? s.slice(11, 16) : ''
}

/** "YYYY-MM-DD HH:MM" → 距 epoch 的分钟数（同天内比价用）。
 *  手工算而非 new Date——避免落到设备本地时区。 */
function minutesOf(ts) {
  const s = String(ts || '')
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/)
  if (!m) return NaN
  const d = Date.UTC(+m[1], +m[2] - 1, +m[3])
  return d / 60000 + (+(m[4] || 0)) * 60 + (+(m[5] || 0))
}

/** 两个时间戳之间相差的分钟数（NaN 安全：任一非法 → NaN）。 */
export function minutesBetween(a, b) {
  const x = minutesOf(a)
  const y = minutesOf(b)
  return Number.isNaN(x) || Number.isNaN(y) ? NaN : Math.abs(y - x)
}

const WEEKDAY_CN = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']

/** 会话内时间分隔条的文案：
 *  - 跨天 → "8月16日 星期六"（今年）/ "2025年8月16日"（往年）
 *  - 同天 → "14:23"
 *  日期用手工解析的 UTC 分量算星期，不走 new Date(str)（见上方时区说明）。 */
export function formatDayLabel(ts, nowStr = hktNowStr()) {
  const s = String(ts || '')
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/)
  if (!m) return ''
  const [, y, mo, d] = m
  const weekday = WEEKDAY_CN[new Date(Date.UTC(+y, +mo - 1, +d)).getUTCDay()]
  const sameYear = y === String(nowStr).slice(0, 4)
  return `${sameYear ? `${+mo}月${+d}日` : `${y}年${+mo}月${+d}日`} ${weekday}`
}

/**
 * 会话列表的时间分组桶（按 HKT 今天为基准）：
 *   今天 → 昨天 → 本周（本周一起到前天）→ 今年按月（"8月"）→ 往年按年（"2025年"）
 * 桶 key 直接当分组标题用；同 key 的相邻会话归为一组。
 * 返回 key，非法/空时间戳统一归到 '更早'——宁可聚成一堆，也不让
 * 无时间的会话散成 N 个空桶。
 */
export function bucketOf(ts, nowStr = hktNowStr()) {
  const key = dateKey(ts)
  const today = dateKey(nowStr)
  if (!key) return '更早'
  if (!today) return '更早'
  if (key === today) return '今天'
  if (key > today) return '今天' // 时钟回拨 / 未来时间戳：按今天展示

  // 昨天与本周一：用 Date.UTC 分量算，避免任何本地时区介入
  const [y, m, d] = today.split('-').map(Number)
  const todayUtc = Date.UTC(y, m - 1, d)
  const yesterday = new Date(todayUtc - 86400e3).toISOString().slice(0, 10)
  if (key === yesterday) return '昨天'

  // 本周一（ISO 周，周一 = 1）
  const dow = new Date(todayUtc).getUTCDay() || 7 // 周日 → 7
  const monday = new Date(todayUtc - (dow - 1) * 86400e3).toISOString().slice(0, 10)
  if (key >= monday) return '本周'

  if (key.slice(0, 4) === today.slice(0, 4)) return `${Number(key.slice(5, 7))}月`
  return `${Number(key.slice(0, 4))}年`
}
