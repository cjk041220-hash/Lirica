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
    await act(async () => {
      ok = await result.current([1, 2])
    })
    expect(markRead).toHaveBeenCalledWith([1, 2])
    expect(ok).toBe(true)
  })

  it('隐藏时挂起不调用；回到前台真实 ack 后才 settle', async () => {
    const markRead = vi.fn(async () => true)
    const { result } = renderHook(() => useDeferredMarkRead(markRead))
    setVisibility('hidden')
    let settled = false
    let ok
    act(() => {
      result.current([3, 4]).then((v) => {
        settled = true
        ok = v
      })
    })
    expect(markRead).not.toHaveBeenCalled() // 隐藏 → 不标
    // promise 必须保持 pending：调用方以 settle 为信号移除防重标记，
    // 假成功会让后台轮询重复注入未 ack 的消息（2026-08-09 P1 回归测试）
    expect(settled).toBe(false)

    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(markRead).toHaveBeenCalledWith([3, 4]) // 回到前台 → 补发
    expect(settled).toBe(true) // 真实 ack 完成才 settle
    expect(ok).toBe(true)
  })

  it('补发失败回滚到挂起队列，promise 保持 pending，下次可见再试', async () => {
    const markRead = vi.fn(async () => false)
    const { result } = renderHook(() => useDeferredMarkRead(markRead))
    setVisibility('hidden')
    let settled = false
    act(() => {
      result.current([7]).then(() => {
        settled = true
      })
    })
    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(markRead).toHaveBeenCalledTimes(1) // 第一次失败
    expect(settled).toBe(false) // 失败不 settle：重试期间防重标记保留

    setVisibility('hidden')
    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(markRead).toHaveBeenCalledTimes(2) // 回滚后重试
    expect(markRead).toHaveBeenLastCalledWith([7])
    expect(settled).toBe(false) // 仍失败，继续 pending
  })

  it('补发时网络异常等同失败：回队重试，不 reject 到调用方', async () => {
    const markRead = vi
      .fn()
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce(true)
    const { result } = renderHook(() => useDeferredMarkRead(markRead))
    setVisibility('hidden')
    let settled = false
    let rejected = false
    act(() => {
      result
        .current([9])
        .then(() => {
          settled = true
        })
        .catch(() => {
          rejected = true
        })
    })
    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(rejected).toBe(false) // 异常被门控吞掉，不炸调用方
    expect(settled).toBe(false) // 回队保持 pending

    setVisibility('hidden')
    setVisibility('visible')
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(markRead).toHaveBeenCalledTimes(2)
    expect(settled).toBe(true) // 第二次成功 → settle
  })
})
