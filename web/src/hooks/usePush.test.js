import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, cleanup } from '@testing-library/react'
import { usePush } from './usePush'

// 推送链路是全项目「最容易静默断掉」的路径（CHANGELOG 三次强调），
// 本文件补上它的回归网。apiFetch mock（hoisted 共享）：按用例设置
// 不同响应。
const apiFetchMock = vi.hoisted(() => vi.fn())

vi.mock('../api/client', () => ({ apiFetch: apiFetchMock }))

// ── 环境桩 ──────────────────────────────────────────────────
// jsdom 默认没有 serviceWorker / PushManager / Notification——每个用例
// 按需搭建。supported 状态在首次渲染时求值，桩必须在 renderHook 之前装好。

const unreadResponse = (messages) => ({ ok: true, json: async () => ({ messages }) })

// 按 URL 路由的 apiFetch 实现：mount 时的 checkUnread 与用例内 subscribe
// 各消费各的路由，互不抢占（Once 队列会被挂载首调吃掉，不可用）。
// 值可以是单个响应或响应队列（Array，shift 消费）。
function route(routes) {
  apiFetchMock.mockImplementation(async (url) => {
    const entry = routes[url]
    if (!entry) return { ok: true }
    return Array.isArray(entry) ? entry.shift() : entry
  })
}

function stubServiceWorker({ subscription = null, readyNever = false } = {}) {
  const listeners = new Map()
  const pushManager = {
    subscribe: vi.fn(async () =>
      subscription || { endpoint: 'https://push.example/1', unsubscribe: vi.fn(async () => {}) }
    ),
    getSubscription: vi.fn(async () => subscription),
  }
  const sw = {
    ready: readyNever ? new Promise(() => {}) : Promise.resolve({ pushManager }),
    addEventListener: vi.fn((evt, fn) => listeners.set(evt, fn)),
    removeEventListener: vi.fn((evt) => listeners.delete(evt)),
  }
  Object.defineProperty(navigator, 'serviceWorker', { value: sw, configurable: true })
  vi.stubGlobal('PushManager', class PushManager {})
  return { sw, listeners, pushManager }
}

function stubNotification(permission = 'granted', requestResult = permission) {
  // requestResult 默认等于初始权限；真实浏览器「default → 用户点允许」
  // 用 stubNotification('default', 'granted') 模拟
  vi.stubGlobal('Notification', {
    permission,
    requestPermission: vi.fn(async () => requestResult),
  })
}

describe('usePush（Web Push 全链路回归网）', () => {
  beforeEach(() => {
    apiFetchMock.mockReset()
  })
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    Reflect.deleteProperty(navigator, 'serviceWorker')
    vi.useRealTimers()
  })

  it('挂载时恢复浏览器已有订阅状态', async () => {
    stubServiceWorker({ subscription: { endpoint: 'https://push.example/old' } })
    stubNotification()
    apiFetchMock.mockResolvedValue(unreadResponse([]))

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    expect(result.current.subscribed).toBe(true)
  })

  it('浏览器无订阅 → subscribed 保持 false', async () => {
    stubServiceWorker({ subscription: null })
    stubNotification()
    apiFetchMock.mockResolvedValue(unreadResponse([]))

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    expect(result.current.subscribed).toBe(false)
  })

  it('不支持的环境（无 PushManager）→ supported=false 且不报错', async () => {
    Object.defineProperty(navigator, 'serviceWorker', { value: {}, configurable: true })
    apiFetchMock.mockResolvedValue(unreadResponse([]))

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    expect(result.current.supported).toBe(false)
  })

  it('checkUnread 拉取未读；同 id 重复拉取不换 state 引用（防重渲染）', async () => {
    stubServiceWorker()
    stubNotification()
    apiFetchMock.mockResolvedValue(unreadResponse([{ id: 'm1', content: '早' }]))

    const { result } = renderHook(() => usePush())
    await act(async () => {})
    const first = result.current.unread
    expect(first).toHaveLength(1)

    await act(async () => {
      await result.current.checkUnread()
    })
    expect(result.current.unread).toBe(first) // 同 id → bail-out 原引用

    apiFetchMock.mockResolvedValue(unreadResponse([{ id: 'm1', content: '早' }, { id: 'm2', content: '晚' }]))
    await act(async () => {
      await result.current.checkUnread()
    })
    expect(result.current.unread).toHaveLength(2)
  })

  it('checkUnread 网络失败 → 静默返回 []（不抛）', async () => {
    stubServiceWorker()
    stubNotification()
    apiFetchMock.mockRejectedValue(new Error('network'))

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    await act(async () => {
      const got = await result.current.checkUnread()
      expect(got).toEqual([])
    })
    expect(result.current.unread).toEqual([])
  })

  it('markRead 乐观移除 + 后端失败返回 false（供调用方回滚）', async () => {
    stubServiceWorker()
    stubNotification()
    apiFetchMock.mockResolvedValue(unreadResponse([{ id: 'm1', content: '早' }]))

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    apiFetchMock.mockResolvedValueOnce({ ok: true })
    let ok = true
    await act(async () => {
      ok = await result.current.markRead(['m1'])
    })
    expect(ok).toBe(true)
    expect(result.current.unread).toEqual([])
    expect(apiFetchMock).toHaveBeenCalledWith('/api/push/read', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message_ids: ['m1'] }),
    })

    // 后端失败：markRead 返回 false，调用方据此回滚 injectedRef 等下一轮轮询重试
    apiFetchMock.mockResolvedValueOnce({ ok: false })
    await act(async () => {
      ok = await result.current.markRead(['m1'])
    })
    expect(ok).toBe(false)
  })

  it('订阅成功全链路：vapid → subscribe → POST，subscribed=true', async () => {
    stubServiceWorker()
    stubNotification('default', 'granted') // 走 requestPermission 分支：用户点允许
    route({
      '/api/push/unread': unreadResponse([]),
      '/api/push/vapid-key': { ok: true, json: async () => ({ key: 'BK-vapid', enabled: true }) },
      '/api/push/subscribe': { ok: true },
    })

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    await act(async () => {
      const ok = await result.current.subscribe()
      expect(ok).toBe(true)
    })
    expect(result.current.subscribed).toBe(true)
    // 后端收到订阅对象本体（endpoint 等字段）
    expect(apiFetchMock).toHaveBeenLastCalledWith('/api/push/subscribe', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ endpoint: 'https://push.example/1' }),
    })
  })

  it('权限 denied → permissionDenied=true，零网络调用', async () => {
    stubServiceWorker()
    stubNotification('denied')
    route({ '/api/push/unread': unreadResponse([]) })

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    apiFetchMock.mockClear() // 只看 subscribe 阶段是否触网
    await act(async () => {
      const ok = await result.current.subscribe()
      expect(ok).toBe(false)
    })
    expect(result.current.permissionDenied).toBe(true)
    expect(apiFetchMock).not.toHaveBeenCalled()
  })

  it('服务器未配 VAPID → 显性错误文案，不尝试 subscribe', async () => {
    const { pushManager } = stubServiceWorker()
    stubNotification()
    route({
      '/api/push/unread': unreadResponse([]),
      '/api/push/vapid-key': { ok: true, json: async () => ({ key: null, enabled: false }) },
    })

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    await act(async () => {
      const ok = await result.current.subscribe()
      expect(ok).toBe(false)
    })
    expect(result.current.subscribeError).toContain('还没开通消息推送')
    expect(pushManager.subscribe).not.toHaveBeenCalled()
  })

  it('后端拒绝订阅 → 本地清理 unsubscribe + 错误文案', async () => {
    stubServiceWorker()
    stubNotification()
    route({
      '/api/push/unread': unreadResponse([]),
      '/api/push/vapid-key': { ok: true, json: async () => ({ key: 'BK-vapid', enabled: true }) },
      '/api/push/subscribe': { ok: false },
    })

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    await act(async () => {
      const ok = await result.current.subscribe()
      expect(ok).toBe(false)
    })
    expect(result.current.subscribed).toBe(false)
    expect(result.current.subscribeError).toContain('被服务器拒绝')
  })

  it('subscribe 抛 NotAllowedError → permissionDenied（iOS 标签页典型）', async () => {
    const { pushManager } = stubServiceWorker()
    stubNotification()
    pushManager.subscribe.mockRejectedValue(
      Object.assign(new Error('denied'), { name: 'NotAllowedError' })
    )
    route({
      '/api/push/unread': unreadResponse([]),
      '/api/push/vapid-key': { ok: true, json: async () => ({ key: 'BK-vapid', enabled: true }) },
    })

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    await act(async () => {
      const ok = await result.current.subscribe()
      expect(ok).toBe(false)
    })
    expect(result.current.permissionDenied).toBe(true)
  })

  it('Notification undefined（iOS <16.4 半支持）→ 静默 false，零网络调用', async () => {
    // 只装 PushManager、不装 Notification——supported=true 但权限 API 缺失
    stubServiceWorker()
    route({ '/api/push/unread': unreadResponse([]) })
    // 确认 jsdom 环境里 Notification 确实不存在（本用例的前提）
    expect(typeof Notification).toBe('undefined')

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    expect(result.current.supported).toBe(true)
    apiFetchMock.mockClear() // 只看 subscribe 阶段是否触网
    await act(async () => {
      const ok = await result.current.subscribe()
      expect(ok).toBe(false)
    })
    expect(apiFetchMock).not.toHaveBeenCalled()
  })

  // 挂载首拉与 subscribe 并发 act 会死锁（前一个 act 未 flush 时第二个
  // act 阻塞等待）——改用 advanceTimersByTimeAsync 在同一个 act 内推进
  // 假定时器并让微任务穿插执行，消除重叠 act
  it('serviceWorker.ready 永久 pending → 10s 超时显性文案（防卡死）', async () => {
    vi.useFakeTimers()
    stubServiceWorker({ readyNever: true })
    stubNotification()
    route({
      '/api/push/unread': unreadResponse([]),
      '/api/push/vapid-key': { ok: true, json: async () => ({ key: 'BK-vapid', enabled: true }) },
    })

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    let ok = true
    await act(async () => {
      const sub = result.current.subscribe().then((v) => (ok = v))
      await vi.advanceTimersByTimeAsync(10_000)
      await sub
    })
    expect(ok).toBe(false)
    expect(result.current.subscribeError).toContain('初始化超时')
  })

  it('取消订阅：DELETE 走 query 优先 + 本地 unsubscribe + subscribed=false', async () => {
    const subscription = { endpoint: 'https://push.example/x', unsubscribe: vi.fn(async () => {}) }
    stubServiceWorker({ subscription })
    stubNotification()
    route({
      '/api/push/unread': unreadResponse([]),
      '/api/push/subscribe': { ok: true },
    })

    const { result } = renderHook(() => usePush())
    await act(async () => {})

    await act(async () => {
      await result.current.unsubscribe()
    })
    expect(result.current.subscribed).toBe(false)
    expect(subscription.unsubscribe).toHaveBeenCalled()
    // 部分反代剥离 DELETE body → query 优先携带 endpoint
    expect(apiFetchMock).toHaveBeenCalledWith(
      `/api/push/subscribe?endpoint=${encodeURIComponent(subscription.endpoint)}`,
      expect.objectContaining({ method: 'DELETE' })
    )
  })

  it('60s 轮询兜底 + visibilitychange 立即刷新', async () => {
    vi.useFakeTimers()
    stubServiceWorker()
    stubNotification()
    apiFetchMock.mockResolvedValue(unreadResponse([]))

    renderHook(() => usePush())
    await act(async () => {})

    const callsAfterMount = apiFetchMock.mock.calls.filter(([url]) => url === '/api/push/unread').length
    expect(callsAfterMount).toBe(1) // 挂载即拉一次

    await act(async () => {
      vi.advanceTimersByTime(60_000)
    })
    expect(apiFetchMock.mock.calls.filter(([url]) => url === '/api/push/unread').length).toBe(2)

    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(apiFetchMock.mock.calls.filter(([url]) => url === '/api/push/unread').length).toBe(3)
  })

  it('SW 转发 PUSH_MESSAGE 事件 → 重新拉取未读', async () => {
    const { listeners } = stubServiceWorker()
    stubNotification()
    apiFetchMock.mockResolvedValue(unreadResponse([]))

    renderHook(() => usePush())
    await act(async () => {})

    const before = apiFetchMock.mock.calls.filter(([url]) => url === '/api/push/unread').length
    await act(async () => {
      listeners.get('message')?.({ data: { type: 'PUSH_MESSAGE' } })
    })
    const after = apiFetchMock.mock.calls.filter(([url]) => url === '/api/push/unread').length
    expect(after).toBe(before + 1)
  })
})
