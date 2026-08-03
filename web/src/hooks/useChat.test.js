import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useChat } from './useChat'

// 可控 GPS Promise：测试里手动 resolve，模拟「定位尚未返回时用户点停止」。
// vi.hoisted 使 mock 工厂与测试体共享同一对象（vi.mock 会提升到文件顶部）。
const gps = vi.hoisted(() => ({ resolve: null }))

vi.mock('../api/location', () => ({
  getGpsCoords: () => new Promise((resolve) => { gps.resolve = resolve }),
  isGpsEnabled: () => true,
}))

vi.mock('../api/client', () => ({
  apiFetch: vi.fn(async () => ({ ok: false })),
  getToken: () => 'test-token',
  UNAUTHORIZED_EVENT: 'unauthorized',
}))

describe('useChat GPS 停止（P0-02 回归）', () => {
  beforeEach(() => {
    gps.resolve = null
    localStorage.clear()
  })

  it('定位尚未返回时点停止 → 状态完整收尾、锁释放、可再次发送', async () => {
    const { result } = renderHook(() => useChat())
    // 挂载时 loadHistory 是异步的，先冲刷一次
    await act(async () => {})

    // 发送 → 进入 GPS await 挂起（可控 Promise 不 resolve）
    await act(async () => {
      result.current.sendMessage('你好呀')
    })
    expect(result.current.isStreaming).toBe(true)

    // 定位窗口内点停止（修复前：abort 置位后 GPS 返回会走
    // try 外的提前 return，finally 不执行，界面永久卡「正在发送」）
    act(() => {
      result.current.stopStreaming()
    })

    // 定位返回 → 走「abort 即放弃」，finally 按纪元身份收尾
    await act(async () => {
      gps.resolve(null)
    })

    expect(result.current.isStreaming).toBe(false)
    expect(result.current.characterState).not.toBe('listening')
    // 空助手占位不再流式，且补上「已停止生成」文案（P0-02 尾巴：
    // 修复前留空气泡，用户看到一条什么都没有的回复，像 bug）
    const assistant = result.current.messages.find(m => m.role === 'assistant')
    expect(assistant).toBeTruthy()
    expect(assistant.streaming).toBe(false)
    expect(assistant.content).toBe('已停止生成')

    // 核心回归：abortRef 已释放——修复前它永久锁死，
    // 第二次发送被「防双重发送」守卫静默吞掉
    await act(async () => {
      result.current.sendMessage('再来一条')
    })
    await act(async () => {
      gps.resolve(null)
    })
    const userMsgs = result.current.messages.filter(m => m.role === 'user')
    expect(userMsgs.length).toBe(2)
    expect(result.current.isStreaming).toBe(false)
  })
})
