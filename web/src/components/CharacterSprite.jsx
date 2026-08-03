import { useState, useEffect, useRef, useCallback, memo } from 'react'
// 戳一戳文案池单一真相源（E2E 共用 src/poke-lines.json，改一处即可，免双份同步）
import POKE_LINES from '../poke-lines.json'
import Heart from './Heart'

// 戳一戳爱心迸发的配色轮换（草莓/树莓/蜜桃）
const BURST_COLORS = ['text-strawberry', 'text-raspberry', 'text-peach']

const spriteMap = {
  idle: '/sprites/idle.webp',
  talking: '/sprites/speaking.webp',
  listening: '/sprites/listening.webp',
  happy: '/sprites/happy.webp',
  sleepy: '/sprites/sleeping.webp',
  thinking: '/sprites/thinking.webp',
  halfawake: '/sprites/halfawake.webp',
  exploring: '/sprites/exploring.webp',  // 心情「活泼/好奇」映射（App.jsx moodToSprite）
  sad: '/sprites/sad.webp',
  // wave / reading：SSE state 协议预留（useChat.js 的 {"type":"state"} 分发已就位，
  // 后端下发即可用），素材为 exploring.webp / reading.webp
  wave: '/sprites/exploring.webp',
  reading: '/sprites/reading.webp',
}

// POKE_LINES 见顶部 import：对象形态 { sleepy, halfawake, default }，handlePoke 按 effective 取池、缺省回落 default

const POKE_COOLDOWN_MS = 6000    // 冷却：防连戳刷屏
const BUBBLE_MS = 2800           // 气泡停留
const SLEEPY_OVERRIDE_MS = 4000  // 沉睡被戳 → halfawake 的临时覆盖时长

// 状态切换过渡时长（ms）——真 crossfade 的转移表（2026-08-03 系统性优化）。
// 替代此前「一律 180ms 先隐藏→换图→再显示」的伪 crossfade（中间有空白帧，
// 观感是「闪一下」而非「融一下」）。按语义距离分级：
// - 站姿↔趴下（sleepy/halfawake 进出）是大语义跳变：800ms，halfawake 中间帧顶住；
// - 心情表情切换（happy/thinking/sad/exploring…）：400ms；
// - 进聊天 talking 是即时反应：200ms（可打断）。
const transitionDurationFor = (from, to) => {
  if (from === 'sleepy' || from === 'halfawake' || to === 'sleepy' || to === 'halfawake') return 800
  if (to === 'talking') return 200
  return 400
}

// 立绘布局：睡姿是横躺画幅（要按宽度平铺在地板上，不能像站姿那样按 92vh
// 竖撑——否则横躺图被竖撑→飘在半空，正是之前的浮空方块观感来源之一）
const spriteLayout = (state) => {
  const lying = state === 'sleepy'
  return `${lying ? 'w-full h-auto max-h-[60vh]' : 'h-[92vh] w-auto'} object-contain select-none cursor-pointer`
}

// memo：props 皆原始值；流式聊天逐 token 的 App 重渲染不再穿透进来
export const CharacterSprite = memo(function CharacterSprite({ state = 'idle', pokeable = true }) {
  // crossfade 状态：curr = 当前显示状态；prev = 淡出中的旧状态（两图并存）
  const [curr, setCurr] = useState(state)
  const [prev, setPrev] = useState(null)
  const [duration, setDuration] = useState(400)
  const [bubble, setBubble] = useState(null)           // { id, text }
  const [burst, setBurst] = useState([])               // 戳一戳爱心迸发（自清理）
  const [pokeOverride, setPokeOverride] = useState(null) // 深夜戳醒的临时立绘
  const [wiggle, setWiggle] = useState(false)          // 冷却中的轻晃反馈
  const [broken, setBroken] = useState(false)          // 立绘加载失败 → 回退 idle
  const cooldownRef = useRef(0)
  const bubbleTimerRef = useRef(null)
  const overrideTimerRef = useRef(null)
  const wiggleTimerRef = useRef(null)
  const crossfadeTimerRef = useRef(null)
  // curr 的镜像 ref：crossfade effect 只应响应 state 变化（App 的显示状态），
  // 用 ref 读「当前状态」避免把 setCurr 结果塞进依赖数组造成空跑
  const currRef = useRef(curr)

  // 真 crossfade：state 变化时旧图就地淡出（spriteOut）、新图淡入
  // （spriteIn），两图同时在场、无空白帧。时长按转移表配置。
  // 连续快速切换（如 talking→thinking→talking）时 prev 会被刷新为
  // 上一帧、curr 为最新帧，中间帧被跳过——过渡期短，可接受。
  useEffect(() => {
    if (state === currRef.current) return
    const d = transitionDurationFor(currRef.current, state)
    setPrev(currRef.current)
    setCurr(state)
    currRef.current = state
    setDuration(d)
    clearTimeout(crossfadeTimerRef.current)
    crossfadeTimerRef.current = setTimeout(() => setPrev(null), d)
    return () => clearTimeout(crossfadeTimerRef.current)
  }, [state])

  // 立绘文件恢复（或状态切换）后重试加载
  useEffect(() => { setBroken(false) }, [curr])

  // 卸载清理全部定时器（App 根组件永不卸载，但组件要保持自洽）
  useEffect(() => () => {
    clearTimeout(bubbleTimerRef.current)
    clearTimeout(overrideTimerRef.current)
    clearTimeout(wiggleTimerRef.current)
    clearTimeout(crossfadeTimerRef.current)
  }, [])

  // 戳一戳：纯本地交互。pokeOverride 只是局部视觉层，
  // 绝不反向写 App 状态——4 秒后自动回落回 App 决定的状态
  const handlePoke = useCallback(() => {
    if (!pokeable) return
    const now = Date.now()
    if (now - cooldownRef.current < POKE_COOLDOWN_MS) {
      // 冷却中：不弹新气泡，只给一个轻晃反馈（不是完全无响应）
      setWiggle(true)
      clearTimeout(wiggleTimerRef.current)
      wiggleTimerRef.current = setTimeout(() => setWiggle(false), 300)
      return
    }
    cooldownRef.current = now

    const effective = pokeOverride || curr
    const pool = POKE_LINES[effective] || POKE_LINES.default
    const text = pool[Math.floor(Math.random() * pool.length)]
    setBubble({ id: now, text })
    clearTimeout(bubbleTimerRef.current)
    bubbleTimerRef.current = setTimeout(() => setBubble(null), BUBBLE_MS)

    // 爱心迸发：6 颗小心心在头周绽开飞散（已被 6s 冷却限流，onAnimationEnd 自清理）。
    // 减弱动态效果的用户直接不生成（CSS 侧还有 .heart-burst{display:none} 双保险）
    if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setBurst(Array.from({ length: 6 }, (_, i) => ({
        id: now + i,
        left: 30 + Math.random() * 40,             // 30–70%
        top: 18 + Math.random() * 8,               // 18–26%（头周）
        size: 7 + Math.random() * 5,               // 7–12px（小巧，不喧宾夺主）
        dx: (Math.random() - 0.5) * 56,            // -28..28px（收一点，留在她身边）
        dy: -(34 + Math.random() * 22),            // -34..-56px
        r: (Math.random() - 0.5) * 40,             // ±20°
        color: BURST_COLORS[i % BURST_COLORS.length],
      })))
    }

    // 深夜沉睡被戳 → 睡眼惺忪一会儿再睡回去
    if (effective === 'sleepy') {
      setPokeOverride('halfawake')
      clearTimeout(overrideTimerRef.current)
      overrideTimerRef.current = setTimeout(() => setPokeOverride(null), SLEEPY_OVERRIDE_MS)
    }
  }, [pokeable, pokeOverride, curr])

  const effectiveState = pokeOverride || curr
  const src = broken ? spriteMap.idle : (spriteMap[effectiveState] || spriteMap.idle)
  // 睡姿是横躺画幅：要按宽度平铺在地板上，不能像站姿那样按 92vh 竖撑
  const lying = effectiveState === 'sleepy'
  const prevLying = prev === 'sleepy'
  // 站姿脚部渐隐融入地板；睡姿整身在底部，不能再做底部渐隐
  const standingMask = {
    maskImage: 'linear-gradient(to bottom, black 90%, transparent 100%)',
    WebkitMaskImage: 'linear-gradient(to bottom, black 90%, transparent 100%)',
  }

  return (
    <div className={`absolute z-20 left-1/2 -translate-x-1/2 ${lying ? 'bottom-[36%] w-[72%]' : 'bottom-[8%]'}`}>
      {/* 身下光晕：站姿是脚下一汪，睡姿是蜷身下一片更宽的浅光。
          透明度类保持原值（20/25/12/15）——只换色相，粉色不压立绘原画 */}
      <div
        className={`absolute left-1/2 -translate-x-1/2 rounded-[50%] pointer-events-none ${lying ? 'bottom-[2%] w-[96%] h-[20%] opacity-20' : 'bottom-0 w-[92%] h-[14%] opacity-25'}`}
        style={{ background: 'radial-gradient(ellipse, rgba(242,120,159,0.5) 0%, transparent 70%)' }}
      />
      {/* 身后柔光（站姿）/ 蜷身暖晕（睡姿） */}
      {lying ? (
        <div
          className="absolute left-1/2 top-[34%] -translate-x-1/2 -translate-y-1/2 w-[112%] h-[58%] rounded-full opacity-12 pointer-events-none"
          style={{ background: 'radial-gradient(ellipse, rgba(242,120,159,0.6) 0%, transparent 62%)' }}
        />
      ) : (
        <div
          className="absolute left-1/2 top-[35%] -translate-x-1/2 -translate-y-1/2 w-[130%] h-[75%] rounded-full opacity-15 pointer-events-none"
          style={{ background: 'radial-gradient(ellipse, rgba(242,120,159,0.6) 0%, transparent 60%)' }}
        />
      )}

      {/* 戳一戳爱心迸发（animationend 冒泡到容器统一清空） */}
      {burst.length > 0 && (
        <div className="absolute inset-0 z-30 pointer-events-none" onAnimationEnd={() => setBurst([])}>
          {burst.map(h => (
            <span
              key={h.id}
              className={`heart-burst absolute ${h.color}`}
              style={{
                left: `${h.left}%`, top: `${h.top}%`,
                '--dx': `${h.dx.toFixed(1)}px`,
                '--dy': `${h.dy.toFixed(1)}px`,
                '--r': `${h.r.toFixed(1)}deg`,
                animation: 'heartBurst 0.9s cubic-bezier(0.16, 1, 0.3, 1) forwards',
              }}
            >
              <Heart size={h.size} />
            </span>
          ))}
        </div>
      )}

      {/* 戳一戳气泡（pointer-events-none：不挡任何操作） */}
      {bubble && (
        <div
          key={bubble.id}
          data-testid="poke-bubble"
          className="absolute top-[12%] left-1/2 z-30 pointer-events-none glass-panel px-3.5 py-1.5 text-sm text-plum/80 whitespace-nowrap animate-bubble-pop"
        >
          {bubble.text}
        </div>
      )}

      {/* 立绘 + 呼吸（睡觉时呼吸放慢）。睡姿的 Zzz 已烘进立绘原图、
          位置随呼吸一起动，故不再由 RoomScene 额外叠加（避免重复+错位）。
          真 crossfade：旧图淡出 + 新图淡入双图叠放，无空白帧 */}
      <div className={
        `${wiggle ? 'animate-wiggle ' : ''}` +
        (effectiveState === 'sleepy' ? 'animate-breathe-slow relative'
        : effectiveState === 'halfawake' ? 'animate-breathe-slower relative'
        : 'animate-breathe relative')
      }>
        <div className="relative">
          {prev && (
            <img
              src={broken ? spriteMap.idle : (spriteMap[prev] || spriteMap.idle)}
              alt=""
              aria-hidden
              draggable={false}
              // 旧图必须 absolute 叠在当前图之上（`inset` 由当前图撑起容器）：
              // 否则两张 inline 图在文档流里并排/堆叠，过渡期出现「分身」，
              // 淡出完成后布局才回弹——正是伪 crossfade 的观感残留。
              // bottom-0 对齐脚底：站姿↔睡姿切换时旧图双脚仍落在同一地面线。
              className={`absolute bottom-0 left-1/2 -translate-x-1/2 pointer-events-none ${spriteLayout(prev)}`}
              style={{
                animation: `spriteOut ${duration}ms ease forwards`,
                filter: 'drop-shadow(0 8px 20px rgba(110,74,86,0.12)) drop-shadow(0 2px 6px rgba(242,120,159,0.15))',
                ...(prevLying ? {} : standingMask),
              }}
            />
          )}
          <img
            src={src}
            alt="小汐"
            data-testid="sprite-img"
            role="button"
            aria-label={pokeable ? '戳一下小汐' : '小汐'}
            tabIndex={pokeable ? 0 : -1}
            onClick={handlePoke}
            onKeyDown={(e) => {
              // 键盘可达性：Enter / 空格触发戳一戳（与鼠标 onClick 等价）
              if (!pokeable) return
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                handlePoke()
              }
            }}
            onError={(e) => {
              // 裂图防线：任何立绘加载失败 → 回退 idle；idle 本身也裂
              // 就不再重试（避免死循环），靠 alt 兜底
              if (e.currentTarget.src.indexOf('idle.webp') === -1) setBroken(true)
            }}
            className={spriteLayout(effectiveState)}
            style={{
              filter: 'drop-shadow(0 8px 20px rgba(110,74,86,0.12)) drop-shadow(0 2px 6px rgba(242,120,159,0.15))',
              ...(lying ? {} : standingMask),
              // 新图从 spriteIn 淡入（prev 在场时）；平时无动画、正常显示
              animation: prev ? `spriteIn ${duration}ms ease forwards` : undefined,
            }}
            draggable={false}
          />
        </div>
      </div>
    </div>
  )
})

export default CharacterSprite