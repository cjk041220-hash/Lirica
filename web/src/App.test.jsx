import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
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

vi.mock('./hooks/useChat', () => ({
  useChat: () => ({
    messages: [],
    isStreaming: false,
    characterState: 'idle',
    sendMessage: vi.fn(),
    stopStreaming: vi.fn(),
    receiveProactive: vi.fn(),
    userMsgCount: 0,
    sessionId: 's-test',
    newSession: vi.fn(),
    switchSession: vi.fn(),
    lastDoneAt: 0,
  }),
}))
vi.mock('./hooks/useConfig', () => ({ useConfig: () => ({}) }))
vi.mock('./hooks/usePush', () => ({
  usePush: () => ({
    unread: [],
    markRead: vi.fn(async () => true),
    subscribe: vi.fn(),
    unsubscribe: vi.fn(),
    checkUnread: vi.fn(),
    subscribed: false,
    supported: false,
  }),
}))
vi.mock('./hooks/usePresence', () => ({ usePresence: () => ({ isIdle: false, isVisible: true }) }))
vi.mock('./hooks/useStatus', () => ({ useStatus: () => ({ mood: null }) }))

describe('App 四面板互斥（P1-16）', () => {
  // vitest 未开 globals，@testing-library 的自动 cleanup 不注册，须显式清
  afterEach(cleanup)
  beforeEach(() => {
    localStorage.clear()
  })

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
