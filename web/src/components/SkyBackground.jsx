import { useMemo } from 'react'
import { getTimePeriod } from '../utils/time'

// 四套天色叠层 + 交叉淡入淡出（CSS 不能让 background-image 过渡，故叠层切透明度）。
// 调色取自然天象的暖冷平衡，避开「近黑+霓虹」「奶油+赭石」等套路。
const SKY = {
  morning: 'linear-gradient(180deg,#33406e 0%,#6f6f9e 26%,#c98fa6 52%,#f0b89a 76%,#fbe3c2 100%)',
  day:     'linear-gradient(180deg,#4f93d6 0%,#7fb6e6 34%,#aed6ef 68%,#e3f3fb 100%)',
  evening: 'linear-gradient(180deg,#241d44 0%,#5d3a63 28%,#b5635f 56%,#e89a63 80%,#f6c585 100%)',
  night:   'linear-gradient(180deg,#080b1e 0%,#101733 36%,#172247 70%,#22305a 100%)',
}

// 窗下地平线的晕染（日出/日落/月升的光）
const HORIZON = {
  morning: 'radial-gradient(120% 50% at 50% 100%, rgba(255,200,140,0.55), transparent 70%)',
  day:     'radial-gradient(120% 45% at 50% 100%, rgba(255,255,255,0.30), transparent 72%)',
  evening: 'radial-gradient(130% 55% at 50% 100%, rgba(255,150,80,0.60), transparent 70%)',
  night:   'radial-gradient(120% 50% at 50% 100%, rgba(120,150,200,0.16), transparent 72%)',
}

const STAR_OP  = { night: 1, evening: 0.55, morning: 0.22, day: 0 }
const MOON_OP  = { night: 1, evening: 0.7, morning: 0.28, day: 0 }
const SUN_OP   = { day: 1, morning: 0.92, evening: 0.95, night: 0 }
// 云底色 + 浓度：白天白、晨昏暖、夜里是被月照亮的淡蓝灰
const CLOUD    = {
  day:     { c: '255,255,255', o: 0.85 },
  morning: { c: '255,236,224', o: 0.7 },
  evening: { c: '255,206,176', o: 0.6 },
  night:   { c: '150,170,210', o: 0.16 },
}
// 天体在窗内的位置（按时段，构图不同）
const MOON_POS = {
  night:   { top: '11%', right: '15%' },
  evening: { top: '13%', left: '14%' },   // 日落于右，月升于左
  morning: { top: '10%', right: '20%' },
  day:     { top: '10%', right: '20%' },
}
const SUN_POS = {
  day:     { top: '13%', right: '16%' },
  morning: { top: '30%', left: '11%' },   // 低悬初升
  evening: { top: '33%', right: '9%' },   // 低悬将落
  night:   { top: '33%', right: '9%' },
}

const PERIODS = ['morning', 'day', 'evening', 'night']

function Moon({ period }) {
  const pos = MOON_POS[period]
  return (
    <div className="absolute z-[2] pointer-events-none transition-opacity duration-[2000ms]"
      style={{ ...pos, opacity: MOON_OP[period] }}>
      <div className="relative w-14 h-14">
        {/* 月冕 */}
        <div className="absolute -inset-[70%] rounded-full"
          style={{ background: 'radial-gradient(circle, rgba(255,255,235,0.40), rgba(255,255,235,0.08) 45%, transparent 70%)', animation: 'moonPulse 8s ease-in-out infinite' }} />
        {/* 月盘 */}
        <div className="absolute inset-0 rounded-full"
          style={{
            background: 'radial-gradient(circle at 36% 32%, #fffef6 0%, #f3f2e6 42%, #d7d8c8 74%, #b9bba9 100%)',
            boxShadow: '0 0 16px 3px rgba(255,255,235,0.30), inset -3px -4px 8px rgba(120,120,100,0.30)',
          }}>
          {/* 环形山 */}
          <span className="absolute rounded-full" style={{ left: '30%', top: '28%', width: '18%', height: '18%', background: 'rgba(150,150,128,0.28)' }} />
          <span className="absolute rounded-full" style={{ left: '56%', top: '52%', width: '13%', height: '13%', background: 'rgba(150,150,128,0.22)' }} />
          <span className="absolute rounded-full" style={{ left: '44%', top: '64%', width: '9%', height: '9%', background: 'rgba(150,150,128,0.20)' }} />
        </div>
      </div>
    </div>
  )
}

function Sun({ period }) {
  const pos = SUN_POS[period]
  const warm = period !== 'day'
  const disc = warm
    ? 'radial-gradient(circle at 42% 40%, #fff3da 0%, #ffd28a 52%, #ff9d52 100%)'
    : 'radial-gradient(circle at 42% 40%, #fffaf0 0%, #ffe9a6 55%, #ffcf63 100%)'
  const glow = warm ? 'rgba(255,176,110,0.55)' : 'rgba(255,236,170,0.55)'
  return (
    <div className="absolute z-[2] pointer-events-none transition-opacity duration-[2000ms]"
      style={{ ...pos, opacity: SUN_OP[period] }}>
      <div className="relative w-12 h-12">
        <div className="absolute -inset-[95%] rounded-full"
          style={{ background: `radial-gradient(circle, ${glow}, ${warm ? 'rgba(255,150,90,0.16)' : 'rgba(255,210,120,0.16)'} 45%, transparent 70%)`, animation: 'sunPulse 7s ease-in-out infinite' }} />
        <div className="absolute inset-0 rounded-full"
          style={{ background: disc, boxShadow: `0 0 26px 7px ${glow}` }} />
      </div>
    </div>
  )
}

function Stars({ period }) {
  // 星场一次生成、跨渲染稳定；含少量带十字星芒的亮星
  const stars = useMemo(() => Array.from({ length: 46 }, (_, i) => ({
    id: i,
    left: `${Math.random() * 100}%`,
    top: `${Math.random() * 56}%`,
    size: Math.random() * 1.8 + 0.7,
    delay: `${Math.random() * 4}s`,
    dur: `${2.4 + Math.random() * 2.2}s`,
    bright: i % 9 === 0,
  })), [])
  return (
    <div className="absolute inset-0 z-[1] overflow-hidden pointer-events-none transition-opacity duration-[2000ms]"
      style={{ opacity: STAR_OP[period] }}>
      {stars.map(s => s.bright ? (
        <span key={s.id} className="star-cross"
          style={{ left: s.left, top: s.top, animation: `sparkle ${s.dur} ease-in-out infinite`, animationDelay: s.delay }} />
      ) : (
        <span key={s.id} className="absolute rounded-full bg-white animate-twinkle"
          style={{ left: s.left, top: s.top, width: s.size, height: s.size, animationDelay: s.delay, animationDuration: s.dur }} />
      ))}
      {/* 流星：夜与黄昏偶发，多数时间隐形 */}
      {(period === 'night' || period === 'evening') && (
        <>
          <span className="absolute" style={{ left: '16%', top: '12%', width: 90, height: 2, borderRadius: 2, background: 'linear-gradient(90deg, transparent, rgba(255,255,255,0.9))', boxShadow: '0 0 6px 1px rgba(255,255,255,0.6)', animation: 'shoot 9s ease-in infinite', animationDelay: '3s' }} />
          <span className="absolute" style={{ left: '58%', top: '8%', width: 70, height: 2, borderRadius: 2, background: 'linear-gradient(90deg, transparent, rgba(255,255,255,0.85))', boxShadow: '0 0 5px 1px rgba(255,255,255,0.5)', animation: 'shoot 13s ease-in infinite', animationDelay: '8s' }} />
        </>
      )}
    </div>
  )
}

function Cloud({ top, scale, dur, delay, tint, op }) {
  // 整朵云横向飘过窗，负 delay 让它一开场就在中途而非从左侧外排队
  return (
    <div className="absolute left-0 pointer-events-none" style={{ top, width: '42%', height: 46 * scale, animation: `cloudDrift ${dur}s linear infinite`, animationDelay: `-${delay}s` }}>
      <div className="relative w-full h-full">
        <span className="absolute rounded-full" style={{ left: '8%', top: '22%', width: '52%', height: '72%', filter: 'blur(5px)', background: `rgba(${tint},${op})` }} />
        <span className="absolute rounded-full" style={{ left: '32%', top: '0%', width: '44%', height: '96%', filter: 'blur(6px)', background: `rgba(${tint},${op})` }} />
        <span className="absolute rounded-full" style={{ left: '54%', top: '26%', width: '40%', height: '62%', filter: 'blur(5px)', background: `rgba(${tint},${op})` }} />
      </div>
    </div>
  )
}

function Clouds({ period }) {
  const { c, o } = CLOUD[period]
  return (
    <div className="absolute inset-0 z-[3] overflow-hidden pointer-events-none transition-opacity duration-[2000ms]">
      <Cloud top="9%"  scale={1.1} dur={64} delay={6}  tint={c} op={o} />
      <Cloud top="20%" scale={0.8} dur={88} delay={30} tint={c} op={o * 0.8} />
      <Cloud top="33%" scale={1.3} dur={52} delay={18} tint={c} op={o * 0.9} />
      <Cloud top="44%" scale={0.7} dur={104} delay={48} tint={c} op={o * 0.7} />
    </div>
  )
}

export default function SkyBackground() {
  const period = getTimePeriod()

  return (
    <div className="absolute inset-0 z-0">
      {/* 天色叠层：交叉淡入淡出，换时段不跳变 */}
      {PERIODS.map(p => (
        <div key={p} className="absolute inset-0 transition-opacity duration-[2000ms]"
          style={{ background: SKY[p], opacity: period === p ? 1 : 0 }} />
      ))}
      {/* 地平线晕染 */}
      {PERIODS.map(p => (
        <div key={`h-${p}`} className="absolute inset-0 transition-opacity duration-[2000ms] pointer-events-none"
          style={{ background: HORIZON[p], opacity: period === p ? 1 : 0 }} />
      ))}

      <Stars period={period} />
      <Moon period={period} />
      <Sun period={period} />
      <Clouds period={period} />

      {/* 窗顶漏下的一缕天光 + 整体暗角，把天空坐进窗框 */}
      <div className="absolute inset-x-0 top-0 h-24 bg-gradient-to-b from-white/8 to-transparent pointer-events-none z-[4]" />
      <div className="absolute inset-0 pointer-events-none z-[4]"
        style={{ boxShadow: 'inset 0 0 70px rgba(8,11,30,0.18)' }} />
    </div>
  )
}
