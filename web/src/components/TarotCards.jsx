// 塔罗牌可视化 — 卡背 → 逐张翻牌 → 牌面网格。
// 数据来自后端 tool_end 事件的 tarot artifact（content_and_artifact 契约）：
// LLM 只见文本，卡片数据只在 SSE 下行；MessageBubble 在 message.tarot
// 存在时渲染本组件（isValidTarot 已挡掉脏数据）。
// 动画纯 CSS transition + 定时器错峰，无动画库依赖；reduced-motion
// 初始即全翻（matchMedia 惰性读取，无首帧卡背闪烁）。
import { useEffect, useState } from 'react'

// 花色图标（与后端工具文本映射同源）
const SUIT_ICONS = { 大阿尔卡纳: '🌟', 权杖: '🔥', 圣杯: '💧', 宝剑: '⚔️', 星币: '🪙' }
const DEFAULT_ICON = '🃏'
// 错峰间隔：每 120ms 翻一张（单张翻转 600ms 由 CSS transition 驱动）
const STAGGER_MS = 120

function usePrefersReducedMotion() {
  const [reduced, setReduced] = useState(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  })
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined
    const mq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const onChange = (e) => setReduced(e.matches)
    mq.addEventListener?.('change', onChange)
    return () => mq.removeEventListener?.('change', onChange)
  }, [])
  return reduced
}

export default function TarotCards({ tarot }) {
  const { spread_type, cards } = tarot
  const reduced = usePrefersReducedMotion()
  const [flipped, setFlipped] = useState(reduced ? cards.length : 0)

  // 错峰翻牌：一次性调度每张牌的定时器（第 i 张于 i*120ms 翻），
  // 不依赖 React 提交时序逐级续链——单次推进时间即可推满，卸载时全部清理。
  // reduced 翻转时重置 flipped：reduced→true 直接全翻；reduced→false 归零
  // 重播错峰动画（否则中途切换会停在「半翻」态）
  useEffect(() => {
    if (reduced) {
      setFlipped(cards.length)
      return undefined
    }
    setFlipped(0)
    const timers = Array.from({ length: cards.length }, (_, i) =>
      setTimeout(() => setFlipped((c) => Math.max(c, i + 1)), STAGGER_MS * (i + 1))
    )
    return () => timers.forEach(clearTimeout)
  }, [reduced, cards.length])

  const settled = flipped >= cards.length
  const gridClass =
    spread_type === 'single'
      ? 'grid-cols-1 justify-items-center'
      : spread_type === 'celtic'
        ? 'grid-cols-5'
        : 'grid-cols-3'

  return (
    <div
      className={`mt-1.5 grid gap-1.5 ${gridClass} ${settled ? 'animate-tarot-settle' : ''}`}
      data-testid="tarot-cards"
    >
      {cards.map((card, i) => (
        // 同一牌阵内 position 唯一（后端 tarot.py 保证），不随重渲染/重抽漂移
        <TarotCard
          key={`${card.position}-${card.name}`}
          card={card}
          flipped={i < flipped}
          compact={spread_type === 'celtic'}
        />
      ))}
    </div>
  )
}

function TarotCard({ card, flipped, compact }) {
  const icon = SUIT_ICONS[card.suit] ?? DEFAULT_ICON
  return (
    <div className="relative w-full max-w-[140px] aspect-[3/5]" style={{ perspective: '600px' }}>
      <div
        className={`relative h-full w-full transition-transform duration-[600ms] ${flipped ? '[transform:rotateY(180deg)]' : ''}`}
        style={{
          transformStyle: 'preserve-3d',
          transitionTimingFunction: 'cubic-bezier(0.16,1,0.3,1)',
        }}
        data-flipped={flipped}
      >
        {/* 卡背：奶油粉底 + blush 波点 + honey ✦ 呼吸微光 */}
        <div className="absolute inset-0 rounded-[14px] border border-strawberry/40 bg-cream bg-[radial-gradient(circle,rgba(243,195,214,0.55)_1.5px,transparent_1.5px)] bg-[size:10px_10px] shadow-[0_1px_4px_rgba(110,74,86,0.08)] [backface-visibility:hidden] flex items-center justify-center">
          <span className="text-honey text-lg animate-twinkle">✦</span>
        </div>
        {/* 牌面：白→cream 渐变 + blush 描边（翻转后挂载，随 wrapper 一起转，
            过半程自然显形——3D 翻牌观感不变，未翻时内容不在 DOM 中） */}
        {flipped && (
          <div className="absolute inset-0 rounded-[14px] border border-blush bg-gradient-to-b from-white to-cream shadow-[0_1px_4px_rgba(110,74,86,0.08)] [backface-visibility:hidden] [transform:rotateY(180deg)] flex flex-col items-center px-1 py-1.5 overflow-hidden">
            {compact ? (
              // 凯尔特十字紧凑款：位置 truncate + 图标 + 牌名 + 逆位徽标
              // （含义文字由小汐的解读文本承载，卡片只做视觉）
              <>
                <span className="w-full text-center text-[8px] leading-tight text-plum/40 truncate">
                  {card.position}
                </span>
                <span className="text-base leading-none my-1">{icon}</span>
                <span className="font-display text-[11px] leading-tight text-plum text-center">
                  {card.name}
                </span>
                {card.is_reversed && (
                  <span className="mt-0.5 text-[7px] px-1 py-px rounded-full bg-raspberry text-white">
                    逆位
                  </span>
                )}
              </>
            ) : (
              <>
                <div className="w-full flex items-center justify-between px-0.5">
                  <span className="text-[8px] px-1.5 py-px rounded-full bg-strawberry/15 text-raspberry">
                    {card.position}
                  </span>
                  {card.is_reversed && (
                    <span className="text-[8px] px-1.5 py-px rounded-full bg-raspberry text-white">
                      逆位
                    </span>
                  )}
                </div>
                <span className="text-lg leading-none my-1">
                  {card.symbol} {icon}
                </span>
                <span className="font-display text-[13px] leading-tight text-plum text-center">
                  {card.name}
                </span>
                <div className="mt-1 flex flex-wrap justify-center gap-0.5">
                  {(card.keywords || []).slice(0, 3).map((k) => (
                    <span
                      key={k}
                      className="text-[7px] px-1 py-px rounded-full bg-blush/40 text-plum/70"
                    >
                      {k}
                    </span>
                  ))}
                </div>
                <span className="mt-auto pt-0.5 text-center text-[10px] leading-snug text-plum/50 line-clamp-2">
                  {card.fortune}
                </span>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
