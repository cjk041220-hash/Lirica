import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import ChatPanel from './ChatPanel'

// 把手键盘可达性（2026-08-14）：拖拽/双击之外，slider 语义的方向键步进
// 与 Enter/Space 三档快切必须有回归网——键盘用户调面板高度的唯一通道
const chatBase = {
  messages: [],
  isStreaming: false,
  sendMessage: vi.fn(),
  stopStreaming: vi.fn(),
  characterState: 'idle',
  sessionId: 's-test',
}

function renderPanel(overrides = {}) {
  return render(
    <ChatPanel chat={{ ...chatBase, ...overrides }} status={{ mood: null, daily_event: null }} />
  )
}

const handle = () => screen.getByTestId('chat-handle')
const valuenow = () => Number(handle().getAttribute('aria-valuenow'))

describe('ChatPanel 把手键盘交互', () => {
  afterEach(cleanup)

  it('把手是可聚焦的 slider，暴露 min/max/now', () => {
    renderPanel()
    const h = handle()
    expect(h.getAttribute('role')).toBe('slider')
    expect(h.getAttribute('tabIndex')).toBe('0')
    expect(Number(h.getAttribute('aria-valuemin'))).toBe(10)
    expect(Number(h.getAttribute('aria-valuemax'))).toBe(85)
    expect(valuenow()).toBe(38)
  })

  it('方向键上下 ±5vh 步进', () => {
    renderPanel()
    fireEvent.keyDown(handle(), { key: 'ArrowUp' })
    expect(valuenow()).toBe(43)
    fireEvent.keyDown(handle(), { key: 'ArrowDown' })
    fireEvent.keyDown(handle(), { key: 'ArrowDown' })
    expect(valuenow()).toBe(33)
  })

  it('Enter 三档快切：38 → 70', () => {
    renderPanel()
    fireEvent.keyDown(handle(), { key: 'Enter' })
    expect(valuenow()).toBe(70)
  })

  it('Space 三档快切与 Enter 同款；连续快切 70 → 10', () => {
    renderPanel()
    fireEvent.keyDown(handle(), { key: ' ' })
    fireEvent.keyDown(handle(), { key: ' ' })
    expect(valuenow()).toBe(10)
  })

  it('步进夹在上限 85', () => {
    renderPanel()
    for (let i = 0; i < 20; i++) fireEvent.keyDown(handle(), { key: 'ArrowUp' })
    expect(valuenow()).toBe(85)
  })

  it('步进夹在下限 10', () => {
    renderPanel()
    for (let i = 0; i < 20; i++) fireEvent.keyDown(handle(), { key: 'ArrowDown' })
    expect(valuenow()).toBe(10)
  })
})
