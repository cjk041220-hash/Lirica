// 统一请求层（修复 P1-2）：所有 /api 请求经由此处，自动附带访问令牌。
//
// 后端配置 XIAOXI_API_TOKEN 后，未带令牌的请求一律 401。
// 令牌在设置页填写，存 localStorage（私有部署场景足够）。

const TOKEN_KEY = 'xiaoxi-token'

export const getToken = () => {
  try { return localStorage.getItem(TOKEN_KEY) || '' } catch { return '' }
}

export const setToken = (token) => {
  try {
    const t = (token || '').trim()
    if (t) localStorage.setItem(TOKEN_KEY, t)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {}
}

// 收到 401 时广播事件，设置页据此提示填写令牌
export const UNAUTHORIZED_EVENT = 'xiaoxi:unauthorized'

export async function apiFetch(url, opts = {}) {
  const token = getToken()
  const headers = { ...(opts.headers || {}) }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(url, { ...opts, headers })
  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
  }
  return res
}
