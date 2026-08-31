import { describe, it, expect, beforeEach } from 'vitest'
import {
  getHKTHour,
  isLateNight,
  getTimePeriod,
  hktNowStr,
  dateKey,
  formatMessageTime,
  minutesBetween,
  formatDayLabel,
  bucketOf,
} from './time'

// 体检 5.12：HKT 时钟边界——天空时段与深夜判定（睡姿）必须来自同一个钟。
// getHKTHour 支持开发态 ?_sky=小时 冻结（生产构建摇树删除），
// 测试用 replaceState 伪造查询串驱动各时段分支。
const setSky = (h) => history.replaceState({}, '', h === null ? '/?x=1' : `/?_sky=${h}`)

describe('HKT 时钟（time.js）', () => {
  beforeEach(() => setSky(null))

  it('无 _sky 时返回真实 HKT 小时（0-23）', () => {
    const h = getHKTHour()
    expect(h).toBeGreaterThanOrEqual(0)
    expect(h).toBeLessThan(24)
  })

  it('_sky 覆盖小时且模 24', () => {
    setSky(3)
    expect(getHKTHour()).toBe(3)
    setSky(25)
    expect(getHKTHour()).toBe(1)
    setSky(-1)
    expect(getHKTHour()).toBe(23)
  })

  it('_sky 非法值回落真实时钟', () => {
    setSky('abc')
    expect(getHKTHour()).toBeGreaterThanOrEqual(0)
    expect(getHKTHour()).toBeLessThan(24)
  })

  it('isLateNight：HKT 00:00–06:59', () => {
    setSky(0)
    expect(isLateNight()).toBe(true)
    setSky(3)
    expect(isLateNight()).toBe(true)
    setSky(6)
    expect(isLateNight()).toBe(true)
    setSky(7)
    expect(isLateNight()).toBe(false)
    setSky(23)
    expect(isLateNight()).toBe(false)
  })

  it('getTimePeriod 边界：5/8/17/20 切换点', () => {
    setSky(5)
    expect(getTimePeriod()).toBe('morning')
    setSky(7)
    expect(getTimePeriod()).toBe('morning')
    setSky(8)
    expect(getTimePeriod()).toBe('day')
    setSky(16)
    expect(getTimePeriod()).toBe('day')
    setSky(17)
    expect(getTimePeriod()).toBe('evening')
    setSky(19)
    expect(getTimePeriod()).toBe('evening')
    setSky(20)
    expect(getTimePeriod()).toBe('night')
    setSky(23)
    expect(getTimePeriod()).toBe('night')
    setSky(0)
    expect(getTimePeriod()).toBe('night')
    setSky(4)
    expect(getTimePeriod()).toBe('night')
  })
})

// ── 对话时间线（2026-08-30 对话管理优化）──────────────────────
// 后端时间戳是 HKT 的 "YYYY-MM-DD HH:MM" 文本。所有函数都接受
// 注入的 nowStr，测试不依赖真实时钟（"今天/昨天/本周"才可钉死）。
describe('对话时间线（time.js）', () => {
  it('hktNowStr 输出与后端同形的 "YYYY-MM-DD HH:MM"', () => {
    // 真实时钟：只钉格式（SessionsPanel 取日期段用的是同一招）
    expect(hktNowStr()).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/)
  })

  it('dateKey / formatMessageTime 按位切片，不解析日期对象', () => {
    expect(dateKey('2026-08-15 14:23')).toBe('2026-08-15')
    expect(formatMessageTime('2026-08-15 14:23')).toBe('14:23')
    // 非法输入返回空串（不抛、不产生 Invalid Date）
    expect(dateKey('')).toBe('')
    expect(dateKey(undefined)).toBe('')
    expect(dateKey('乱码')).toBe('')
    expect(formatMessageTime('2026-08-15')).toBe('')
  })

  it('minutesBetween 跨小时与跨天都算得对', () => {
    expect(minutesBetween('2026-08-15 14:00', '2026-08-15 14:30')).toBe(30)
    expect(minutesBetween('2026-08-15 23:50', '2026-08-16 00:10')).toBe(20)
    expect(minutesBetween('2026-08-15 10:00', '2026-08-16 10:00')).toBe(1440)
    expect(Number.isNaN(minutesBetween('', '2026-08-16 10:00'))).toBe(true)
  })

  it('formatDayLabel：今年带星期、往年带年份', () => {
    // 2026-08-15 是星期六；2025-08-15 是星期五（非闰年 +365 天 = 退 1 天）
    expect(formatDayLabel('2026-08-15 14:23', '2026-08-30 10:00')).toBe('8月15日 星期六')
    expect(formatDayLabel('2025-08-15 14:23', '2026-08-30 10:00')).toBe('2025年8月15日 星期五')
    expect(formatDayLabel('')).toBe('')
  })

  it('bucketOf：今天 / 昨天 / 本周 / 今年按月 / 往年按年', () => {
    // 2026-08-30 是星期日 → 本周一为 2026-08-24
    const now = '2026-08-30 10:00'
    expect(bucketOf('2026-08-30 09:00', now)).toBe('今天')
    expect(bucketOf('2026-08-29 23:59', now)).toBe('昨天')
    expect(bucketOf('2026-08-28 12:00', now)).toBe('本周') // 周五，仍在本周
    expect(bucketOf('2026-08-24 12:00', now)).toBe('本周') // 本周一
    expect(bucketOf('2026-08-23 12:00', now)).toBe('8月') // 上周日，已出本周
    expect(bucketOf('2026-01-02 12:00', now)).toBe('1月')
    expect(bucketOf('2025-12-31 12:00', now)).toBe('2025年')
  })

  it('bucketOf：无时间戳与未来时间戳都不散成空桶', () => {
    const now = '2026-08-30 10:00'
    expect(bucketOf('', now)).toBe('更早')
    expect(bucketOf(undefined, now)).toBe('更早')
    // 时钟回拨 / 服务端时间超前：按今天展示，绝不产生「明天」这种桶
    expect(bucketOf('2026-09-01 10:00', now)).toBe('今天')
  })

  it('bucketOf：周一/周二时「本周」桶自然为空（不残留空分组）', () => {
    // 2026-08-31 是星期一 → 本周一即今天，本周桶无成员
    const monday = '2026-08-31 10:00'
    expect(bucketOf('2026-08-31 09:00', monday)).toBe('今天')
    expect(bucketOf('2026-08-30 09:00', monday)).toBe('昨天') // 昨天是周日，属上周
    expect(bucketOf('2026-08-29 09:00', monday)).toBe('8月')
  })
})
