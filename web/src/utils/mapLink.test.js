import { describe, it, expect, vi } from 'vitest'
import { isMainland, gcj02ToWgs84, isValidNav, buildNavTargets } from './mapLink'

// GCJ-02 向量来自 backend utils/geo.py wgs84_to_gcj02 实跑（2026-08-05），
// 硬编码配对断言——前后端任一公式漂移都会让往返误差越界
const VECTORS = [
  { wgs: [22.5431, 114.0579], gcj: [22.540383, 114.063014] },  // 深圳·福田
  { wgs: [39.9042, 116.4074], gcj: [39.905603, 116.413642] },  // 北京
  { wgs: [22.3193, 114.1694], gcj: [22.316551, 114.174367] },  // 尖沙咀（港澳同样加偏）
]

// 往返误差实测 ≤1.4m ≈ 1.3e-5 度；3e-5 留 2 倍余量（≈3.3m）
const EPS = 3e-5

describe('gcj02ToWgs84', () => {
  it('深圳/北京/尖沙咀：逆向 ≈ 原 WGS-84（直接反推法精度 1-2m）', () => {
    for (const { wgs, gcj } of VECTORS) {
      const [lat, lon] = gcj02ToWgs84(gcj[0], gcj[1])
      expect(Math.abs(lat - wgs[0])).toBeLessThan(EPS)
      expect(Math.abs(lon - wgs[1])).toBeLessThan(EPS)
    }
  })

  it('东京（国境框外）：恒等返回', () => {
    expect(gcj02ToWgs84(35.6762, 139.6503)).toEqual([35.6762, 139.6503])
  })
})

describe('isMainland（与后端旧 _pick_engine 同一几何）', () => {
  it('内地 → true', () => {
    expect(isMainland(22.5431, 114.0579)).toBe(true)  // 深圳
    expect(isMainland(39.9042, 116.4074)).toBe(true)  // 北京
  })

  it('港澳排除框内 / 海外 → false', () => {
    expect(isMainland(22.3193, 114.1694)).toBe(false)  // 尖沙咀
    expect(isMainland(35.6762, 139.6503)).toBe(false)  // 东京
  })

  it('排除框外缘归回内地（边界误差 = 地图 App 换用，两侧都能定位）', () => {
    expect(isMainland(22.60, 114.20)).toBe(true)  // 框北外
    expect(isMainland(22.10, 114.20)).toBe(true)  // 框南外（仍在内地大框）
  })
})

describe('buildNavTargets', () => {
  const HK = { name: '尖沙咀站', lat: 22.316551, lon: 114.174367, coord: 'gcj02' }  // GCJ-02
  // 注意：测试点必须真的在内地（22.282,114.154 中环在港澳排除框内 →
  // 非内地分支）。深圳 22.543 在排除框北缘外，判内地
  const MAINLAND = { name: '海岸城', lat: 22.543, lon: 114.057, coord: 'gcj02' }
  const WINDOWS_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0'
  const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1'

  it('内地 → 高德 URI：coordinate=gaode 直传、lon,lat 序、GCJ-02 原值零转换', () => {
    const t = buildNavTargets(MAINLAND, WINDOWS_UA)
    expect(t.markerUrl).toBe(
      `https://uri.amap.com/marker?position=114.057,22.543&name=${encodeURIComponent('海岸城')}&coordinate=gaode&callnative=0`
    )
    expect(t.goUrl).toContain('navigation?to=114.057,22.543')
    expect(t.goUrl).toContain('coordinate=gaode&callnative=1')
    // 高德直传 GCJ-02：URL 坐标 == 后端原值，未做逆向
    expect(t.goUrl).toContain('22.543')
  })

  it('港澳 + iOS → Apple Maps：daddr 用 WGS-84 逆向坐标（≠ GCJ 原值）', () => {
    const t = buildNavTargets(HK, IPHONE_UA)
    expect(t.markerUrl).toMatch(/^http:\/\/maps\.apple\.com\/\?daddr=/)
    expect(t.goUrl).toBe(t.markerUrl)
    // 逆向后坐标 ≠ GCJ 原值（否则几百米偏移的回归一眼可见）
    expect(t.goUrl).not.toContain('22.316551,114.174367')
    const [wgsLat, wgsLon] = gcj02ToWgs84(HK.lat, HK.lon)
    expect(t.goUrl).toContain(`daddr=${wgsLat},${wgsLon}`)
  })

  it('港澳/海外 + 非 iOS → Google Maps 网页兜底（跳转链接无需 key）', () => {
    const t = buildNavTargets(HK, WINDOWS_UA)
    expect(t.markerUrl).toMatch(/^https:\/\/maps\.google\.com\/\?q=/)
    expect(t.goUrl).toMatch(/daddr=/)
    // 同样走逆向坐标
    const [wgsLat, wgsLon] = gcj02ToWgs84(HK.lat, HK.lon)
    expect(t.goUrl).toContain(`daddr=${wgsLat},${wgsLon}`)
  })

  it('不传 ua（MapNavButtons 真实调用形态）→ isIOS 回落真实 navigator：iOS 设备必须走 Apple Maps', () => {
    // 回归（2026-08-05 真机）：buildNavTargets 曾用 ua="" 默认值，空串绕过
    // isIOS 的 navigator 回退 → iPhone 全部打进 Google 兜底。此用例钉死
    // 生产路径：组件不传 ua，必须读真实 navigator.userAgent
    vi.stubGlobal('navigator', { userAgent: IPHONE_UA, platform: 'iPhone', maxTouchPoints: 0 })
    try {
      const t = buildNavTargets(HK)
      expect(t.markerUrl).toMatch(/^http:\/\/maps\.apple\.com\/\?daddr=/)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('非法 nav → null（前端不渲染按钮）', () => {
    expect(buildNavTargets(null, WINDOWS_UA)).toBeNull()
    expect(buildNavTargets({}, WINDOWS_UA)).toBeNull()
    expect(buildNavTargets({ name: 'x', lat: NaN, lon: 114.1 }, WINDOWS_UA)).toBeNull()
    expect(buildNavTargets({ name: '', lat: 22.28, lon: 114.15 }, WINDOWS_UA)).toBeNull()
    expect(buildNavTargets({ name: 'x', lat: 22.28, lon: 114.15, coord: 'gcj02' }, WINDOWS_UA)).not.toBeNull()
  })
})

describe('isValidNav', () => {
  it('形状校验：lat/lon 有限数 + name 非空', () => {
    expect(isValidNav({ name: 'a', lat: 22.28, lon: 114.15 })).toBe(true)
    expect(isValidNav({ name: 'a', lat: '22.28', lon: 114.15 })).toBe(false)  // 字符串 lat
    expect(isValidNav({ name: 'a', lat: Infinity, lon: 114.15 })).toBe(false)
    expect(isValidNav({ name: 'a', lat: 22.28 })).toBe(false)  // 缺 lon
    expect(isValidNav(undefined)).toBe(false)
  })
})
