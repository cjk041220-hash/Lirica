import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { usePresenceReport } from './usePresenceReport'

// apiFetch mock（hoisted 共享）：记录 POST /api/presence 的调用
const apiFetchMock = vi.hoisted(() => vi.fn(async () => ({ ok: true })))

vi.mock('../api/client', () => ({
  apiFetch: apiFetchMock,
  getToken: () => 'test-token',
  UNAUTHORIZED_EVENT: 'unauthorized',
}))

describe('usePresenceReport（v21 era 路由的在线数据源）', () => {
  beforeEach(() => {
    apiFetchMock.mockClear()
    apiFetchMock.mockResolvedValue({ ok: true })
  })
  afterEach(() => {
    // vitest 未开 globals，@testing-library 不自动 cleanup——不显式清的话
    // 上一个用例的 hook 效果（pagehide 监听器/2 分钟定时器）全部存活，
    // 下一个用例 dispatch 一次事件会触发 N 个监听器（实锤：beacon 3 连）
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('挂载即上报在线 + 当前会话', async () => {
    renderHook(() => usePresenceReport('s-act'))
    await act(async () => {})
    expect(apiFetchMock).toHaveBeenCalledWith('/api/presence', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ session_id: 's-act', leaving: false }),
    })
  })

  it('会话切换后重新上报新会话', async () => {
    const { rerender } = renderHook(({ sid }) => usePresenceReport(sid), {
      initialProps: { sid: 's-1' },
    })
    await act(async () => {})
    apiFetchMock.mockClear()

    rerender({ sid: 'e-2' })
    await act(async () => {})
    expect(apiFetchMock).toHaveBeenCalledTimes(1)
    expect(JSON.parse(apiFetchMock.mock.calls[0][1].body).session_id).toBe('e-2')
  })

  it('pagehide → sendBeacon 上报 leaving（关页面即离线）', async () => {
    const beacon = vi.fn(() => true)
    vi.stubGlobal('navigator', { ...navigator, sendBeacon: beacon })
    renderHook(() => usePresenceReport('s-act'))

    window.dispatchEvent(new Event('pagehide'))

    expect(beacon).toHaveBeenCalledTimes(1)
    const [url, blob] = beacon.mock.calls[0]
    expect(url).toBe('/api/presence')
    expect(blob.type).toBe('application/json') // text/plain 会被 FastAPI 415
    const payload = JSON.parse(await blob.text())
    expect(payload).toEqual({ session_id: 's-act', leaving: true })
  })

  it('上报失败静默（不影响主流程）', async () => {
    apiFetchMock.mockRejectedValue(new Error('network down'))
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    renderHook(() => usePresenceReport('s-act'))
    await act(async () => {})
    expect(consoleWarn).toHaveBeenCalled()
  })
})
