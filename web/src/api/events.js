import { apiFetch } from './client'

// 小汐的日历 — /api/events 客户端。
// 与 LLM 工具（tools/events.py）是一本账的两个入口：冷冷在这里亲手记的
// 和小汐在聊天里记下的共享去重，互相看得见、改得动（只读纪念日除外）。

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function parse(res) {
  let data = null
  try { data = await res.json() } catch { /* 非 JSON 响应（404 页面等） */ }
  return { ok: res.ok, status: res.status, data }
}

/** 月范围查询：{events: [...], count}；失败返回空列表（面板降级不白屏） */
export async function fetchEvents(from, to) {
  try {
    const res = await apiFetch(`/api/events?from=${from}&to=${to}`)
    if (!res.ok) return { events: [] }
    const data = await res.json()
    return { events: Array.isArray(data.events) ? data.events : [] }
  } catch (e) {
    console.warn('[events] fetch failed:', e)
    return { events: [] }
  }
}

/** 创建事件 → {ok, status, data}；409 = 相似提示（data.detail.similar） */
export const createEvent = (payload) =>
  apiFetch('/api/events', {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(payload),
  }).then(parse)

/** 更新事件（字段子集）→ {ok, status, data} */
export const updateEvent = (id, payload) =>
  apiFetch(`/api/events/${encodeURIComponent(id)}`, {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(payload),
  }).then(parse)

/** 软删事件（置墓碑：挡住小汐偷偷记回来）→ {ok, status, data} */
export const deleteEvent = (id) =>
  apiFetch(`/api/events/${encodeURIComponent(id)}`, { method: 'DELETE' }).then(parse)
