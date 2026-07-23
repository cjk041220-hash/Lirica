import { useMemo } from 'react'

function getTimePeriod() {
  const hour = new Date().getHours()
  if (hour >= 5 && hour < 8) return 'morning'
  if (hour >= 8 && hour < 17) return 'day'
  if (hour >= 17 && hour < 20) return 'evening'
  return 'night'
}

const skyGradients = {
  morning: 'linear-gradient(180deg, #FADADD 0%, #C5E0E0 45%, #E8F4F8 100%)',
  day: 'linear-gradient(180deg, #dceef7 0%, #E8F4F8 40%, #f5fbff 100%)',
  evening: 'linear-gradient(180deg, #f7c59f 0%, #FADADD 40%, #E8F4F8 100%)',
  night: 'linear-gradient(180deg, #0f0f23 0%, #1a1a2e 40%, #16213e 80%, #1a2744 100%)',
}

function Stars() {
  const stars = useMemo(() =>
    Array.from({ length: 40 }, (_, i) => ({
      id: i,
      left: `${Math.random() * 100}%`,
      top: `${Math.random() * 55}%`,
      size: Math.random() * 2 + 0.8,
      delay: `${Math.random() * 4}s`,
      duration: `${2.5 + Math.random() * 2}s`,
    })), [])

  return (
    <div className="absolute inset-0 overflow-hidden">
      {stars.map(s => (
        <div
          key={s.id}
          className="absolute rounded-full bg-white animate-twinkle"
          style={{
            left: s.left, top: s.top,
            width: s.size, height: s.size,
            animationDelay: s.delay,
            animationDuration: s.duration,
          }}
        />
      ))}
      {/* 月亮光晕 */}
      <div className="absolute top-[8%] right-[18%] w-10 h-10 rounded-full bg-white/10 shadow-[0_0_30px_10px_rgba(255,255,255,0.05)]" />
    </div>
  )
}

function Clouds({ period }) {
  return (
    <div className="absolute inset-0 overflow-hidden pointer-events-none">
      <div className="absolute top-[12%] left-[8%] w-28 h-9 bg-white/25 rounded-full blur-[6px] animate-float-soft" />
      <div className="absolute top-[22%] right-[12%] w-20 h-7 bg-white/15 rounded-full blur-[5px] animate-float-soft" style={{ animationDelay: '2s' }} />
      {period === 'day' && (
        <div className="absolute top-[32%] left-[45%] w-16 h-5 bg-white/10 rounded-full blur-[4px] animate-float-soft" style={{ animationDelay: '4s' }} />
      )}
    </div>
  )
}

export default function SkyBackground() {
  const period = getTimePeriod()

  return (
    <div className="absolute inset-0 z-0 transition-all duration-1000" style={{ background: skyGradients[period] }}>
      {period === 'night' && <Stars />}
      {(period === 'day' || period === 'morning' || period === 'evening') && <Clouds period={period} />}
      {/* 顶部柔光 */}
      <div className="absolute inset-x-0 top-0 h-24 bg-gradient-to-b from-white/8 to-transparent pointer-events-none" />
    </div>
  )
}
