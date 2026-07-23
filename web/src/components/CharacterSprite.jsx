import { useState, useEffect } from 'react'

const spriteMap = {
  idle: '/sprites/idle.webp',
  talking: '/sprites/speaking.webp',
  listening: '/sprites/listening.webp',
  happy: '/sprites/happy.webp',
  sleepy: '/sprites/sleeping.webp',
  thinking: '/sprites/thinking.webp',
  wave: '/sprites/exploring.webp',
  reading: '/sprites/reading.webp',
  sad: '/sprites/sad.webp',
}

export default function CharacterSprite({ state = 'idle' }) {
  const [displayState, setDisplayState] = useState(state)
  const [transitioning, setTransitioning] = useState(false)

  useEffect(() => {
    if (state !== displayState) {
      setTransitioning(true)
      const timer = setTimeout(() => {
        setDisplayState(state)
        setTransitioning(false)
      }, 180)
      return () => clearTimeout(timer)
    }
  }, [state, displayState])

  const src = spriteMap[displayState] || spriteMap.idle

  return (
    <div className="absolute z-20 left-1/2 -translate-x-1/2 bottom-[24%]">
      {/* 脚下光晕 */}
      <div
        className="absolute left-1/2 bottom-0 -translate-x-1/2 w-[80%] h-[18%] rounded-[50%] opacity-25 pointer-events-none"
        style={{ background: 'radial-gradient(ellipse, rgba(136,181,181,0.5) 0%, transparent 70%)' }}
      />
      {/* 身后柔光 */}
      <div
        className="absolute left-1/2 top-[30%] -translate-x-1/2 -translate-y-1/2 w-[110%] h-[70%] rounded-full opacity-15 pointer-events-none"
        style={{ background: 'radial-gradient(ellipse, rgba(136,181,181,0.6) 0%, transparent 60%)' }}
      />

      {/* 立绘 + 呼吸 */}
      <div className="animate-breathe relative">
        <img
          src={src}
          alt="小汐"
          className={`h-[56vh] max-h-[440px] w-auto object-contain select-none transition-opacity duration-300 ease-out ${
            transitioning ? 'opacity-0 scale-[0.99]' : 'opacity-100 scale-100'
          }`}
          style={{
            filter: 'drop-shadow(0 8px 20px rgba(92,84,77,0.12)) drop-shadow(0 2px 6px rgba(136,181,181,0.15))',
            maskImage: 'linear-gradient(to bottom, black 90%, transparent 100%)',
            WebkitMaskImage: 'linear-gradient(to bottom, black 90%, transparent 100%)',
            transition: 'opacity 0.3s ease, transform 0.3s ease',
          }}
          draggable={false}
        />
      </div>
    </div>
  )
}
