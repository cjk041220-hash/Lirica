import { describe, it, expect, beforeEach } from 'vitest'
import { getHKTHour, isLateNight, getTimePeriod } from './time'

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
    setSky(0); expect(isLateNight()).toBe(true)
    setSky(3); expect(isLateNight()).toBe(true)
    setSky(6); expect(isLateNight()).toBe(true)
    setSky(7); expect(isLateNight()).toBe(false)
    setSky(23); expect(isLateNight()).toBe(false)
  })

  it('getTimePeriod 边界：5/8/17/20 切换点', () => {
    setSky(5);  expect(getTimePeriod()).toBe('morning')
    setSky(7);  expect(getTimePeriod()).toBe('morning')
    setSky(8);  expect(getTimePeriod()).toBe('day')
    setSky(16); expect(getTimePeriod()).toBe('day')
    setSky(17); expect(getTimePeriod()).toBe('evening')
    setSky(19); expect(getTimePeriod()).toBe('evening')
    setSky(20); expect(getTimePeriod()).toBe('night')
    setSky(23); expect(getTimePeriod()).toBe('night')
    setSky(0);  expect(getTimePeriod()).toBe('night')
    setSky(4);  expect(getTimePeriod()).toBe('night')
  })
})
