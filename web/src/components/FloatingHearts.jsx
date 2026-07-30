import { memo } from 'react'

// 漂浮微粒层：6 颗柔和圆点从窗底缓缓升过房间，像浮尘/微光，给房间一点「活着」的呼吸。
// 2026-07-30 由漂浮爱心改为圆点——常驻爱心太抢眼，圆点更安静；
// 真正的爱心留给「戳一戳」的即时反馈（见 CharacterSprite 的 heartBurst）。
// 负延迟保证首帧就已有微粒在半途；只动 transform/opacity（合成器友好，6 节点零 filter）。
// prefers-reduced-motion：index.css 里 .ambient-motes { display: none } 整体撤下。
// 圆点用 radial-gradient 柔边（核心实、边缘虚），不用 filter:blur，省合成层。
const MOTES = [
  { left: '10%', size: 12, rgb: '242,120,159', op: 0.42, dur: 16, delay: 2.0  },
  { left: '26%', size: 8,  rgb: '255,201,163', op: 0.46, dur: 19, delay: 13.0 },
  { left: '43%', size: 10, rgb: '243,195,214', op: 0.44, dur: 14, delay: 7.0  },
  { left: '60%', size: 7,  rgb: '242,120,159', op: 0.40, dur: 17, delay: 15.5 },
  { left: '75%', size: 11, rgb: '233,180,76',  op: 0.44, dur: 18, delay: 4.5  },
  { left: '89%', size: 9,  rgb: '194,71,123',  op: 0.36, dur: 13, delay: 9.5  },
]

function FloatingHearts() {
  return (
    <div className="ambient-motes absolute inset-0 z-[5] pointer-events-none" aria-hidden="true">
      {MOTES.map((m, i) => (
        <span
          key={i}
          className="absolute rounded-full"
          style={{
            left: m.left,
            bottom: '-4%',
            width: m.size,
            height: m.size,
            background: `radial-gradient(circle, rgba(${m.rgb},${m.op}) 0%, rgba(${m.rgb},${m.op * 0.5}) 42%, transparent 72%)`,
            animation: `floatUp ${m.dur}s ease-in-out infinite`,
            animationDelay: `-${m.delay}s`,
          }}
        />
      ))}
    </div>
  )
}

export default memo(FloatingHearts)
