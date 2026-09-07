// 统一请求层（修复 P1-2）：所有 /api 请求经由此处，自动附带访问令牌。
//
// 后端配置 XIAOXI_API_TOKEN 后，未带令牌的请求一律 401。
// 令牌在设置页填写，存 localStorage + IndexedDB（三轨容灾）。

import { getLocalItem, setResilientItem, STORAGE_KEYS } from '../utils/storage'

export const TOKEN_KEY = STORAGE_KEYS.TOKEN

export const getToken = () => {
  return (getLocalItem(TOKEN_KEY) || '').trim()
}

export const setToken = (token) => {
  const t = (token || '').trim()
  setResilientItem(TOKEN_KEY, t)
  // 广播令牌更新事件，驱动全量面板自愈刷新
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(TOKEN_UPDATED_EVENT, { detail: { token: t } }))
  }
}

// 收到 401 时广播事件，设置页据此提示填写令牌
export const UNAUTHORIZED_EVENT = 'xiaoxi:unauthorized'
// 令牌被更新/恢复时广播事件，各面板据此重新加载数据
export const TOKEN_UPDATED_EVENT = 'xiaoxi:token-updated'

export async function apiFetch(url, opts = {}) {
  const token = getToken()
  const headers = { ...(opts.headers || {}) }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const finalOpts = { ...opts, headers }
  // 默认 15s 超时：防弱网下 Promise 永不 settle、UI 永远卡在加载中。
  // 调用方自带 signal 的场景（流式聊天的 AbortController）由其自己管理
  // 生命周期，不叠加超时——否则会掐掉正常的长工具链
  if (!opts.signal && typeof AbortSignal !== 'undefined' && AbortSignal.timeout) {
    finalOpts.signal = AbortSignal.timeout(15000)
  }
  const res = await fetch(url, finalOpts)
  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
  }
  return res
}
