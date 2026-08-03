import { useState, useEffect, useRef } from 'react'

// 滞回（hysteresis）：入睡阈值 ≠ 唤醒成本，临界区不抖动。
// - 前台 10 分钟完全无操作 → 入睡（4 分钟是阅读/思考常态，不是「离开」）
// - 页面不可见 5 分钟 → 入睡（不可见是强信号，但短暂切走不算）
// - 唤醒只需一次有效交互（点击/按键/触摸/滚动）；单纯移动鼠标不唤醒
//   （pointermove 只刷新计时器——「鼠标划一下即醒」是反复横跳的主因）
const IDLE_MS = 10 * 60 * 1000
const HIDDEN_IDLE_MS = 5 * 60 * 1000

/**
 * 冷冷在不在「这里」—— 页面活跃度检测（滞回版，2026-08-03 系统性优化）。
 *
 * 用于视觉叙事：冷冷长时间没操作（或切走标签页）→ 小汐趴下睡觉；
 * 冷冷回来（有效交互 / 标签页回前台）→ 小汐醒来。这是零消息配额的
 * 陪伴信号：不需要推送，打开页面的前两秒就传达「小汐在等我」。
 *
 * 修复前（4 分钟即入睡 + pointermove 即醒）：用户挂机/阅读时立绘在
 * 「站 ↔ 睡」间反复横跳。滞回后入睡需 ≥10 分钟（或隐藏 ≥5 分钟），
 * 唤醒只需一次有效交互，且移动鼠标不会唤醒已睡着的她。
 *
 * @returns {{ isIdle: boolean, isVisible: boolean }}
 */
export function usePresence() {
  const [isIdle, setIsIdle] = useState(false)
  const [isVisible, setIsVisible] = useState(
    typeof document !== 'undefined' ? !document.hidden : true
  )
  const idleTimerRef = useRef(null)    // 前台无操作计时器
  const hiddenTimerRef = useRef(null)  // 页面不可见计时器

  useEffect(() => {
    // 只刷新前台计时器，不唤醒：pointermove 用（入睡后移动鼠标不醒，
    // 但在电脑前持续移动会无限续期，不会误入睡）
    const refreshTimer = () => {
      clearTimeout(idleTimerRef.current)
      idleTimerRef.current = setTimeout(() => setIsIdle(true), IDLE_MS)
    }

    // 唤醒：解除入睡 + 重置前台计时器
    const wake = () => {
      // setState 有 bail-out：isIdle 已是 false 时调用不触发重渲染
      setIsIdle(false)
      refreshTimer()
    }

    // 页面不可见：5 分钟仍无互动才入睡（短暂切走不算「离开」）
    const armHiddenFallback = () => {
      clearTimeout(hiddenTimerRef.current)
      hiddenTimerRef.current = setTimeout(() => setIsIdle(true), HIDDEN_IDLE_MS)
    }

    const onVisibility = () => {
      const visible = document.visibilityState === 'visible'
      setIsVisible(visible)
      clearTimeout(hiddenTimerRef.current)
      if (visible) wake()
      else armHiddenFallback()
    }

    // 唤醒源：只有有效交互（点击/按键/触摸/滚动）才唤醒。
    // 高频事件（pointermove 可达 60+ 次/秒）节流后只刷新计时器，
    // 不唤醒——鼠标划一下不算「回来」，正是此前的横跳主因。
    const events = ['pointerdown', 'keydown', 'touchstart', 'wheel']
    events.forEach(e => window.addEventListener(e, wake, { passive: true }))

    let moveTimer = null
    const throttledRefresh = () => {
      if (moveTimer) return
      moveTimer = setTimeout(() => { moveTimer = null; refreshTimer() }, 500)
    }
    window.addEventListener('pointermove', throttledRefresh, { passive: true })
    document.addEventListener('visibilitychange', onVisibility)

    idleTimerRef.current = setTimeout(() => setIsIdle(true), IDLE_MS)

    return () => {
      events.forEach(e => window.removeEventListener(e, wake))
      window.removeEventListener('pointermove', throttledRefresh)
      document.removeEventListener('visibilitychange', onVisibility)
      clearTimeout(idleTimerRef.current)
      clearTimeout(hiddenTimerRef.current)
      clearTimeout(moveTimer)
    }
  }, [])

  return { isIdle, isVisible }
}