// 「看地图 / 去这里」联动按钮 — 挂在工具结果折叠区下方。
// 唤起用 window.location.href 而非 window.open：后者在移动端会
// 「先开网页再开 App」多一次跳动；location.href 直接唤起地图 App，
// 未安装时 uri.amap.com 自己回退网页版（callnative=1 语义）。
// 样式呼应 tool_hint 的 10px 小字/草莓粉纪律（<12px 彩色文字用
// raspberry 保证对比度，见 index.css 色板注）。
import { buildNavTargets } from '../utils/mapLink'

export default function MapNavButtons({ nav }) {
  const targets = buildNavTargets(nav)
  if (!targets) return null

  return (
    <div className="mt-1.5 flex items-center gap-1.5">
      <button
        type="button"
        className="btn-press relative text-[10px] px-2 py-1 rounded-full bg-plum/5 text-plum/40 border border-plum/15 hover:text-plum/60 hover:border-plum/30 select-none"
        onClick={() => { window.location.href = targets.markerUrl }}
      >
        看地图
      </button>
      <button
        type="button"
        className="btn-press relative text-[10px] px-2 py-1 rounded-full bg-strawberry/15 text-raspberry border border-strawberry/30 hover:bg-strawberry/25 select-none"
        onClick={() => { window.location.href = targets.goUrl }}
      >
        去这里
      </button>
    </div>
  )
}
