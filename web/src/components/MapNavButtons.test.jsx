import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import MapNavButtons from './MapNavButtons'

// jsdom 不支持真实导航（location.href 赋值会报 not implemented）；
// 把 window.location 换成可断言对象——jsdom 的 location 属性可删除重赋
function stubLocation() {
  delete window.location
  window.location = { href: '' }
  return window.location
}

// 深圳坐标（22.543 在港澳排除框北缘外 → 内地分支 → 高德 URI）
const MAINLAND_NAV = { name: '海岸城', lat: 22.543, lon: 114.057, coord: 'gcj02' }

describe('MapNavButtons', () => {
  beforeEach(() => {
    stubLocation()
  })
  afterEach(() => {
    cleanup()
  })

  it('有合法 nav → 渲染「看地图」「去这里」两个 pill', () => {
    render(<MapNavButtons nav={MAINLAND_NAV} />)
    expect(screen.getByText('看地图')).toBeTruthy()
    expect(screen.getByText('去这里')).toBeTruthy()
  })

  it('nav 非法 / 缺失 → 不渲染任何按钮', () => {
    const { container } = render(<MapNavButtons nav={null} />)
    expect(container.innerHTML).toBe('')
    const c2 = render(<MapNavButtons nav={{ name: 'x', lat: NaN, lon: 1 }} />).container
    expect(c2.innerHTML).toBe('')
  })

  it('点击「去这里」→ window.location.href 赋高德 URI（非 window.open）', () => {
    render(<MapNavButtons nav={MAINLAND_NAV} />)
    screen.getByText('去这里').click()
    expect(window.location.href).toBe(
      `https://uri.amap.com/navigation?to=114.057,22.543,${encodeURIComponent('海岸城')}&coordinate=gaode&callnative=1`
    )
  })

  it('点击「看地图」→ marker URI', () => {
    render(<MapNavButtons nav={MAINLAND_NAV} />)
    screen.getByText('看地图').click()
    expect(window.location.href).toContain('uri.amap.com/marker?position=114.057,22.543')
  })
})
