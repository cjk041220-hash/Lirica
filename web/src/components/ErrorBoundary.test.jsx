import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import ErrorBoundary from './ErrorBoundary'

// 体检 5.17：渲染异常 → 边界兜底降级 UI（不白屏）
function Boom() {
  throw new Error('boom')
}

describe('ErrorBoundary', () => {
  afterEach(() => vi.restoreAllMocks())

  it('子组件渲染抛错 → 显示降级 UI', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {}) // 屏蔽 React 报错噪音
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>
    )
    expect(screen.getByText(/小汐晕倒了/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '刷新页面' })).toBeTruthy()
  })

  it('正常子组件照常渲染', () => {
    render(<ErrorBoundary><p>正常内容</p></ErrorBoundary>)
    expect(screen.getByText('正常内容')).toBeTruthy()
  })
})
