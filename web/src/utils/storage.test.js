import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  getLocalItem,
  setResilientItem,
  removeResilientItem,
  initResilientStorage,
  STORAGE_KEYS,
  idbGet,
  idbSet,
} from './storage'
import { setToken, getToken, TOKEN_UPDATED_EVENT } from '../api/client'

describe('三轨多层容灾存储 (storage.js & client.js)', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('setResilientItem 写入 localStorage', () => {
    setResilientItem(STORAGE_KEYS.TOKEN, 'my-test-token')
    expect(getLocalItem(STORAGE_KEYS.TOKEN)).toBe('my-test-token')

    removeResilientItem(STORAGE_KEYS.TOKEN)
    expect(getLocalItem(STORAGE_KEYS.TOKEN)).toBe('')
  })

  it('setToken 触发 TOKEN_UPDATED_EVENT 广播', () => {
    const handler = vi.fn()
    window.addEventListener(TOKEN_UPDATED_EVENT, handler)

    setToken('new-token-123')
    expect(getToken()).toBe('new-token-123')
    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler.mock.calls[0][0].detail).toEqual({ token: 'new-token-123' })

    window.removeEventListener(TOKEN_UPDATED_EVENT, handler)
  })

  it('initResilientStorage 降级安全：无 IndexedDB 时安全退出', async () => {
    const res = await initResilientStorage()
    expect(res).toEqual({ tokenRestored: false, sessionRestored: false })
  })
})
