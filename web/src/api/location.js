// GPS 辅助（修复 P1-2 的"GPS 从不上传"）：
// 首次对话时请求一次定位，缓存 30 分钟；用户拒绝后不再弹窗打扰。
// 设置页「位置辅助」开关写入 ENABLED_KEY（'0' = 关闭）。
// 后端 chat 接口收到坐标后缓存到 location_store，地图工具的
//「附近」场景与早安天气就能用到真实位置。

const CACHE_KEY = 'xiaoxi-gps'
const ENABLED_KEY = 'xiaoxi-gps-enabled'
const CACHE_TTL_MS = 30 * 60 * 1000   // 缓存 30 分钟
const MAX_AGE_MS = 10 * 60 * 1000     // 允许系统复用 10 分钟内的定位

export const isGpsEnabled = () => {
  try { return localStorage.getItem(ENABLED_KEY) !== '0' } catch { return true }
}

export const setGpsEnabled = (on) => {
  try {
    if (on) localStorage.removeItem(ENABLED_KEY)
    else localStorage.setItem(ENABLED_KEY, '0')
  } catch {}
}

/**
 * 获取当前坐标（带缓存）。失败/拒绝/关闭时静默返回 null，不反复弹窗。
 * @returns {Promise<{latitude: number, longitude: number} | null>}
 */
export function getGpsCoords() {
  return new Promise((resolve) => {
    try {
      if (!isGpsEnabled()) return resolve(null)
      if (typeof navigator === 'undefined' || !navigator.geolocation) return resolve(null)

      // 缓存解析单独兜底：此前坏 JSON 会被外层 catch 直接 resolve(null)，
      // 缓存一旦损坏就永久跳过真实定位；现在删掉坏缓存继续往下走
      let cached = null
      try {
        cached = JSON.parse(localStorage.getItem(CACHE_KEY) || 'null')
      } catch {
        try { localStorage.removeItem(CACHE_KEY) } catch {}
      }
      if (cached && Date.now() - cached.ts < CACHE_TTL_MS) {
        return resolve({ latitude: cached.lat, longitude: cached.lon })
      }

      let settled = false
      // 兜底超时：若浏览器 geolocation 实现有 bug 导致两个回调都不触发，
      // 10s 后 resolve(null) 防止 sendMessage 永远卡在 GPS 获取阶段
      const safety = setTimeout(() => {
        if (!settled) { settled = true; resolve(null) }
      }, 10_000)

      navigator.geolocation.getCurrentPosition(
        (pos) => {
          if (settled) return
          settled = true
          clearTimeout(safety)
          const { latitude, longitude } = pos.coords
          try {
            localStorage.setItem(CACHE_KEY, JSON.stringify({ lat: latitude, lon: longitude, ts: Date.now() }))
          } catch {}
          resolve({ latitude, longitude })
        },
        () => {
          if (settled) return
          settled = true
          clearTimeout(safety)
          resolve(null)
        },
        { timeout: 6000, maximumAge: MAX_AGE_MS }
      )
    } catch {
      resolve(null)
    }
  })
}
