import { useState, useEffect, useRef } from 'react'

// 冷冷多久没动静后，小汐开始犯困（趴下睡觉）
const IDLE_MS = 4 * 60 * 1000

/**
 * 冷冷在不在「这里」—— 页面活跃度检测。
 *
 * 用于视觉叙事：冷冷长时间没操作（或切走了标签页）→ 小汐趴下睡觉；
 * 冷冷回来（任何操作 / 标签页回前台）→ 小汐醒来。这是零消息配额的
 * 陪伴信号：不需要推送，打开页面的前两秒就传达「小汐在等我」。
 *
 * @returns {{ isIdle: boolean, isVisible: boolean }}
 */
export function usePresence() {
  const [isIdle, setIsIdle] = useState(false)
  const [isVisible, setIsVisible] = useState(
    typeof document !== 'undefined' ? !document.hidden : true
  )
  const timerRef = useRef(null)

  useEffect(() => {
    const wake = () => {
      setIsIdle(false)
      clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => setIsIdle(true), IDLE_MS)
    }

    const onVisibility = () => {
      const visible = document.visibilityState === 'visible'
      setIsVisible(visible)
      if (visible) wake()
    }

    // 高频事件（pointermove 可达 60+ 次/秒）需要节流，
    // 否则每帧都重置 4 分钟定时器，浪费无谓的 clearTimeout/setTimeout。
    let moveTimer = null
    const throttledWake = () => {
      if (moveTimer) return
      moveTimer = setTimeout(() => { moveTimer = null; wake() }, 500)
    }

    // 活跃事件只负责重置计时器，handler 本身极轻（setState 有 bail-out）
    const events = ['pointerdown', 'keydown', 'wheel', 'touchstart']
    events.forEach(e => window.addEventListener(e, wake, { passive: true }))
    window.addEventListener('pointermove', throttledWake, { passive: true })
    document.addEventListener('visibilitychange', onVisibility)

    timerRef.current = setTimeout(() => setIsIdle(true), IDLE_MS)

    return () => {
      events.forEach(e => window.removeEventListener(e, wake))
      window.removeEventListener('pointermove', throttledWake)
      document.removeEventListener('visibilitychange', onVisibility)
      clearTimeout(timerRef.current)
      clearTimeout(moveTimer)
    }
  }, [])

  return { isIdle, isVisible }
}
