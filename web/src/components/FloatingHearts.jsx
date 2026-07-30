import { memo } from 'react'
import Heart from './Heart'

// 漂浮爱心层：6 颗爱心从窗底缓缓升过房间。
// 负延迟保证首帧就已有爱心在半途，不用干等第一轮（同 Cloud 的负 delay 手法）；
// 只动 transform/opacity（合成器友好，6 节点零 filter）。
// prefers-reduced-motion：index.css 里 .floating-hearts { display: none } 整体撤下
const HEARTS = [
  { left: '8%',  size: 12, color: '#F2789F', op: 0.30, dur: 15, delay: 2.0  },
  { left: '24%', size: 18, color: '#FFC9A3', op: 0.34, dur: 18, delay: 13.0 },
  { left: '41%', size: 10, color: '#C2477B', op: 0.24, dur: 13, delay: 7.0  },
  { left: '58%', size: 15, color: '#F2789F', op: 0.28, dur: 16, delay: 15.5 },
  { left: '73%', size: 20, color: '#FFC9A3', op: 0.30, dur: 17, delay: 4.5  },
  { left: '88%', size: 11, color: '#E9B44C', op: 0.32, dur: 12, delay: 9.5  },
]

function FloatingHearts() {
  return (
    <div className="floating-hearts absolute inset-0 z-[5] pointer-events-none" aria-hidden="true">
      {HEARTS.map((h, i) => (
        <span
          key={i}
          className="absolute"
          style={{
            left: h.left,
            bottom: '-6%',
            animation: `heartFloat ${h.dur}s ease-in-out infinite`,
            animationDelay: `-${h.delay}s`,
          }}
        >
          <Heart size={h.size} style={{ color: h.color, opacity: h.op }} />
        </span>
      ))}
    </div>
  )
}

export default memo(FloatingHearts)
