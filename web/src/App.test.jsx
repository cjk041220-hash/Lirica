import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react'
import App from './App'

// 面板组件 mock 成可辨认的占位，方便断言「谁在 DOM 里」。
// vi.hoisted：vi.mock 工厂会提升到文件顶部，顶部 const 此时未初始化
//（ReferenceError），须用 hoisted 共享（同 useChat.test.js 的 gps 模式）
const panelPlaceholder = vi.hoisted(() => (name) => () => <div data-testid={`panel-${name}`} />)

vi.mock('./components/RoomScene', () => ({ default: () => null }))
vi.mock('./components/ChatPanel', () => ({ default: () => null }))
vi.mock('./components/ConfigPanel', () => ({ default: panelPlaceholder('config') }))
vi.mock('./components/MemoriesPanel', () => ({ default: panelPlaceholder('memories') }))
vi.mock('./components/SessionsPanel', () => ({ default: panelPlaceholder('sessions') }))
vi.mock('./components/CalendarPanel', () => ({ default: panelPlaceholder('calendar') }))
vi.mock('./assets/icons/IconBook', () => ({ default: () => null }))
vi.mock('./assets/icons/IconCalendar', () => ({ default: () => null }))
vi.mock('./assets/icons/IconHistory', () => ({ default: () => null }))
vi.mock('./assets/icons/IconSettings', () => ({ default: () => null }))

// 可变 mock 状态：v21 era 测试需要按用例切换 sessionId / unseen_era_id /
// unread / 回调实现。vi.hoisted 与 vi.mock 工厂共享同一引用，测试体里
// 改 mockState 后 rerender 即生效（hooks 每次渲染都读它）
const mockState = vi.hoisted(() => ({
  sessionId: 's-test',
  unseenEraId: null,
  unread: [],
  userSentCount: 0,
  isStreaming: false,
  markRead: vi.fn(async () => true),
  checkUnread: vi.fn(async () => []),
  switchSession: vi.fn(async () => {}),
  receiveProactive: vi.fn(),
}))

vi.mock('./hooks/useChat', () => ({
  useChat: () => ({
    messages: [],
    isStreaming: mockState.isStreaming,
    characterState: 'idle',
    sendMessage: vi.fn(),
    stopStreaming: vi.fn(),
    receiveProactive: mockState.receiveProactive,
    userMsgCount: 0,
    userSentCount: mockState.userSentCount,
    sessionId: mockState.sessionId,
    newSession: vi.fn(),
    switchSession: mockState.switchSession,
    lastDoneAt: 0,
  }),
}))
vi.mock('./hooks/useConfig', () => ({ useConfig: () => ({}) }))
vi.mock('./hooks/usePush', () => ({
  usePush: () => ({
    unread: mockState.unread,
    markRead: mockState.markRead,
    subscribe: vi.fn(),
    unsubscribe: vi.fn(),
    checkUnread: mockState.checkUnread,
    subscribed: false,
    supported: false,
  }),
}))
vi.mock('./hooks/usePresence', () => ({ usePresence: () => ({ isIdle: false, isVisible: true }) }))
// v21：presence 上报旁路（真调用会发 fetch），测试里钉死空实现
vi.mock('./hooks/usePresenceReport', () => ({ usePresenceReport: () => {} }))
vi.mock('./hooks/useStatus', () => ({
  useStatus: () => ({
    mood: null,
    last_seen_at: null,
    active_session_id: null,
    unseen_era_id: mockState.unseenEraId,
  }),
}))

// beforeEach 注册在顶层（describe 内注册只作用于该 describe 的用例）：
// era 测试与面板测试共用同一份 mock 状态复位，防止跨用例泄漏
beforeEach(() => {
  localStorage.clear()
  // v21 era 测试用的可变 mock 状态复位
  mockState.sessionId = 's-test'
  mockState.unseenEraId = null
  mockState.unread = []
  mockState.userSentCount = 0
  mockState.isStreaming = false
  mockState.receiveProactive.mockClear()
  mockState.markRead.mockClear()
  mockState.checkUnread.mockClear()
  mockState.switchSession.mockClear()
  mockState.markRead.mockResolvedValue(true)
  mockState.checkUnread.mockResolvedValue([])
  mockState.switchSession.mockResolvedValue(undefined)
})

describe('App 四面板互斥（P1-16）', () => {
  // vitest 未开 globals，@testing-library 的自动 cleanup 不注册，须显式清
  afterEach(cleanup)

  it('打开一个面板时其他面板不挂载', async () => {
    render(<App />)
    // 初始无面板
    expect(screen.queryByTestId('panel-config')).toBeNull()
    expect(screen.queryByTestId('panel-calendar')).toBeNull()

    // 面板已改 React.lazy（评审 F4）：动态 import 异步解析，
    // 断言用 findBy* 等 Suspense 落定，而非同步 getBy*
    fireEvent.click(screen.getByLabelText('小汐的日历'))
    expect(await screen.findByTestId('panel-calendar')).toBeTruthy()
    expect(screen.queryByTestId('panel-config')).toBeNull()

    // 再开设置 → 日历被替换（互斥），不会两个同挂
    fireEvent.click(screen.getByLabelText('设置'))
    expect(await screen.findByTestId('panel-config')).toBeTruthy()
    expect(screen.queryByTestId('panel-calendar')).toBeNull()
  })

  it('关闭面板后可再打开', async () => {
    render(<App />)
    fireEvent.click(screen.getByLabelText('小汐的记忆'))
    expect(await screen.findByTestId('panel-memories')).toBeTruthy()
    fireEvent.click(screen.getByLabelText('小汐的记忆')) // 再次点击同一按钮
    // 2026-08-09 退场动画（商业级 1.1）：关闭后面板多留 240ms 播下滑
    // 淡出才卸载——断言从「立即消失」改为「退场动画播完后消失」
    await new Promise((r) => setTimeout(r, 320))
    expect(screen.queryByTestId('panel-memories')).toBeNull()
    // 播完还能再打开（渲染槽位正确复位）
    fireEvent.click(screen.getByLabelText('小汐的记忆'))
    expect(await screen.findByTestId('panel-memories')).toBeTruthy()
  })
})

describe('App v21 era 时间线（主动消息独立会话）', () => {
  afterEach(cleanup)

  it('只注入当前活跃会话的主动消息；era 消息不注入、不误标已读', () => {
    mockState.unread = [
      { id: 'p-era', content: '我在看云。', created_at: '2026-08-09 10:00', session_id: 'e-1' },
      { id: 'p-mine', content: '在想你。', created_at: '2026-08-09 11:00', session_id: 's-test' },
    ]
    const { rerender } = render(<App />)
    rerender(<App />) // unread 引用变化 → 注入 effect 重跑

    // era 消息被归属过滤（它属于「小汐的独处时间线」窗口，由 syncEra 回放），
    // 只有当前会话的消息被注入
    expect(mockState.receiveProactive).toHaveBeenCalledTimes(1)
    expect(mockState.receiveProactive.mock.calls[0][0].map((m) => m.id)).toEqual(['p-mine'])
    // era 消息保留在未读（等 syncEra 切过去后由回放展示，不由注入标已读）
    expect(mockState.markRead).not.toHaveBeenCalledWith(['p-era'])
  })

  it('syncEra：unseen_era_id 非空且非当前会话 → 先批量标已读、再切到 era 窗口', async () => {
    mockState.unseenEraId = 'e-1'
    mockState.checkUnread.mockResolvedValue([
      { id: 'p-era', content: '我在看云。', created_at: '2026-08-09 10:00', session_id: 'e-1' },
    ])
    render(<App />)

    await waitFor(() => {
      expect(mockState.markRead).toHaveBeenCalledWith(['p-era'])
      expect(mockState.switchSession).toHaveBeenCalledWith('e-1')
    })
    // 次序保证：先标已读（从 unread 移除，注入 effect 摸不到、不双写）再切换
    expect(mockState.markRead.mock.invocationCallOrder[0]).toBeLessThan(
      mockState.switchSession.mock.invocationCallOrder[0]
    )
  })

  it('syncEra：era 已是当前会话 → 只清未读、不切换', async () => {
    mockState.sessionId = 'e-1'
    mockState.unseenEraId = 'e-1'
    mockState.unread = [
      { id: 'p-era', content: '我在看云。', created_at: '2026-08-09 10:00', session_id: 'e-1' },
    ]
    render(<App />)

    await waitFor(() => expect(mockState.markRead).toHaveBeenCalledWith(['p-era']))
    expect(mockState.switchSession).not.toHaveBeenCalled()
  })

  it('syncEra：用户在当前页面已主动发过消息（userSentCount > 0）→ 不打断，不自动切换', async () => {
    mockState.unseenEraId = 'e-1'
    mockState.userSentCount = 1
    mockState.checkUnread.mockResolvedValue([
      { id: 'p-era', content: '我在看云。', created_at: '2026-08-09 10:00', session_id: 'e-1' },
    ])
    render(<App />)

    // 不应自动切会话
    expect(mockState.switchSession).not.toHaveBeenCalled()
  })
})
