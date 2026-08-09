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
