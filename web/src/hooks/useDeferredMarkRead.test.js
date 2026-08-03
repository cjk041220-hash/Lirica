import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useDeferredMarkRead } from './useDeferredMarkRead'

// jsdom 默认 visibilityState='visible'；用 defineProperty 可写改
const setVisibility = (v) => {
  Object.defineProperty(document, 'visibilityState', { value: v, configurable: true })
}

describe('useDeferredMarkRead（P1-15 后台不标已读）', () => {
  beforeEach(() => setVisibility('visible'))
  afterEach(() => setVisibility('visible'))

  it('可见时立即标记已读', async () => {
    const markRead = vi.fn(async () => true)
    const { result } = renderHook(() => useDeferredMarkRead(markRead))
    let ok
    await act(async () => { ok = await result.current([1, 2]) })
    expect(markRead).toHaveBeenCalledWith([1, 2])
    expect(ok).toBe(true)
  })

  it('隐藏时挂起不调用，回到前台补发', async () => {
    const markRead = vi.fn(async () => true)
    const { result } = renderHook(() => useDeferredMarkRead(markRead))
    setVisibility('hidden')
    let ok
    await act(async () => { ok = await result.current([3, 4]) })
    expect(markRead).not.toHaveBeenCalled()   // 隐藏 → 不标
    expect(ok).toBe(true)                     // 但调用方视为成功（不触发回滚）

    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(markRead).toHaveBeenCalledWith([3, 4])  // 回到前台 → 补发
  })

  it('补发失败回滚到挂起队列，下次可见再试', async () => {
    const markRead = vi.fn(async () => false)
    const { result } = renderHook(() => useDeferredMarkRead(markRead))
    setVisibility('hidden')
    await act(async () => { await result.current([7]) })
    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(markRead).toHaveBeenCalledTimes(1)  // 第一次失败
    setVisibility('hidden')
    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(markRead).toHaveBeenCalledTimes(2)  // 回滚后重试
    expect(markRead).toHaveBeenLastCalledWith([7])
  })
})