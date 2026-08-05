// 地图联动工具 — 从后端 nav（GCJ-02）生成「看地图 / 去这里」跳转 URL。
//
// 坐标系契约（与 backend/tools/map_tools.py 的 NAV_COORD_GCJ02 互引）：
// 后端统一下发 GCJ-02 原值 + coord:"gcj02"，本模块不做正向转换。
// 内地 → 高德 URI 直传（coordinate=gaode 即 GCJ-02，零转换、零损耗）；
// 港澳/海外 → Apple/Google Maps 要 WGS-84，需在此做一次逆向（标准
// 反推法，精度 1-2m，实测往返误差 <1.5m）。
//
// 区域判定与后端单引擎收敛前的 _pick_engine 同一几何（注释互引防漂移）：
// 高德 URI 对内地最优；港澳 Apple Maps 数据最好（香港系统级唤起），
// 海外 Google 网页链接兜底（跳转链接不需要 key）。

// 内地粗框（经度 73–135，纬度 18–54）——与旧 _MAINLAND_BBOX 一致
const MAINLAND_BBOX = [73.0, 18.0, 135.0, 54.0]
// 港澳排除框（113.82–114.44 / 22.15–22.50）——与旧 _HKMO_BBOX 一致
const HKMO_BBOX = [113.82, 22.15, 114.44, 22.50]

export function isMainland(lat, lon) {
  const [w, s, e, n] = MAINLAND_BBOX
  const inMainland = w <= lon && lon <= e && s <= lat && lat <= n
  if (!inMainland) return false
  const [w2, s2, e2, n2] = HKMO_BBOX
  return !(w2 <= lon && lon <= e2 && s2 <= lat && lat <= n2)
}

// ── GCJ-02 → WGS-84（镜像 backend/utils/geo.py 正向公式） ──
// 国境框外（海外）恒等返回，与 geo.py out_of_china 同步
const A = 6378245.0                // Krasovsky 椭球长半轴
// 与 backend/utils/geo.py 逐位镜像的标准常数：float64 无法精确表示
// 属算法固有（解析到最近 double，与后端 Python 行为一致），规则误报
// eslint-disable-next-line no-loss-of-precision
const EE = 0.00669342162296594323  // 第一偏心率平方

function outOfChina(lat, lon) {
  return !(72.004 <= lon && lon <= 137.8347 && 0.8293 <= lat && lat <= 55.8271)
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
  ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
  return ret
}

function transformLon(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
  ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
  ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
  return ret
}

export function gcj02ToWgs84(lat, lon) {
  if (outOfChina(lat, lon)) return [lat, lon]
  const dlat = transformLat(lon - 105.0, lat - 35.0)
  const dlon = transformLon(lon - 105.0, lat - 35.0)
  const radlat = lat / 180.0 * Math.PI
  let magic = Math.sin(radlat)
  magic = 1 - EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  const dLat = (dlat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
  const dLon = (dlon * 180.0) / (A / sqrtMagic * Math.cos(radlat) * Math.PI)
  return [lat - dLat, lon - dLon]
}

// iOS 判定（镜像 useChat.needsXhrStreaming 的 iOS 片段）：
// 真 iPhone/iPad + iPadOS 13+ 伪装 Mac UA 靠触控点数量识破。ua 可注入留测试
export function isIOS(ua = typeof navigator !== 'undefined' ? navigator.userAgent : '') {
  return /iP(hone|od|ad)/.test(ua) ||
    (typeof navigator !== 'undefined' && navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
}

// nav 形状校验（后端契约 {name, lat, lon, coord}）：lat/lon 有限数、name 非空
export function isValidNav(nav) {
  return !!nav &&
    typeof nav === 'object' &&
    typeof nav.name === 'string' && nav.name.length > 0 &&
    typeof nav.lat === 'number' && Number.isFinite(nav.lat) &&
    typeof nav.lon === 'number' && Number.isFinite(nav.lon)
}

// nav → {markerUrl, goUrl} | null。ua 可注入供测试；不传（生产路径）时
// 保持 undefined → isIOS 内部回退真实 navigator.userAgent——绝不能给
// 空串默认值，否则会绕过 iOS 判定把 iPhone 打进 Google 兜底（2026-08-05
// 真机回归：iPhone 上「去这里」跳 Google Maps 的根因）
export function buildNavTargets(nav, ua) {
  if (!isValidNav(nav)) return null
  const { name, lat, lon } = nav
  const encoded = encodeURIComponent(name)

  if (isMainland(lat, lon)) {
    // 高德 URI：coordinate=gaode 即 GCJ-02 直传；callnative=1 未装 App
    // 自动回退网页版（无需特判非移动端）；position/to 都是 lon,lat 序
    return {
      markerUrl: `https://uri.amap.com/marker?position=${lon},${lat}&name=${encoded}&coordinate=gaode&callnative=0`,
      goUrl: `https://uri.amap.com/navigation?to=${lon},${lat},${encoded}&coordinate=gaode&callnative=1`,
    }
  }

  // 港澳/海外：Apple/Google 要 WGS-84，先逆向（结果 ≠ GCJ 原值）
  const [wgsLat, wgsLon] = gcj02ToWgs84(lat, lon)
  if (isIOS(ua)) {
    // Apple Maps（香港数据最好，系统级唤起）：HTTP 链接只能开路线页，
    // 用户点「开始」起航；saddr 省略 = 当前位置起步
    const url = `http://maps.apple.com/?daddr=${wgsLat},${wgsLon}`
    return { markerUrl: url, goUrl: url }
  }
  return {
    markerUrl: `https://maps.google.com/?q=${wgsLat},${wgsLon}`,
    goUrl: `https://maps.google.com/?daddr=${wgsLat},${wgsLon}`,
  }
}
