import { useState, useRef, useCallback, useEffect } from 'react'
import { apiFetch } from '../api/client'
import { getGpsCoords } from '../api/location'

let msgId = 0
const nextId = () => `msg-${++msgId}-${Date.now()}`

// 会话 ID 持久化（修复 P1-1）：刷新页面不再是"陌生人"，
// checkpoint 连续性与产品叙事"一直在网页里陪你"对齐。
const SESSION_KEY = 'xiaoxi-session'
const getSessionId = () => {
  try {
    const existing = localStorage.getItem(SESSION_KEY)
    if (existing) return existing
    // 后端校验 ^[a-z0-9-]{1,64}$：小写字母数字连字符
    const fresh = `s-${Date.now().toString(36)}`
    localStorage.setItem(SESSION_KEY, fresh)
    return fresh
  } catch {
    return `s-${Date.now().toString(36)}`
  }
}

// 工具调用 → 友好的状态提示文案（按后端工具名前缀匹配）
const toolHints = [
  { match: /weather/i, text: '小汐去看了看天气……' },
  { match: /map|poi|nearby|route|location|geo/i, text: '小汐铺开地图找了起来……' },
  { match: /search|tavily|web/i, text: '小汐潜进海里查资料……' },
  { match: /diary|memory|remember|recall/i, text: '小汐翻了翻自己的日记……' },
  { match: /tarot/i, text: '小汐在洗塔罗牌……' },
]
const hintFor = (name) =>
  toolHints.find(h => h.match.test(name || ''))?.text || '小汐在忙一会儿……'

// 工具结果摘要：原始输出较长，气泡里只折叠展示一小段
const summarizeOutput = (output) => {
  const text = String(output || '').trim()
  return text.length > 300 ? text.slice(0, 300) + '……' : text
}

export function useChat() {
  const [messages, setMessages] = useState([])
  const [isStreaming, setIsStreaming] = useState(false)
  const [characterState, setCharacterState] = useState('idle')
  const abortRef = useRef(null)
  const sessionId = useRef(getSessionId())

  // 刷新后从 checkpoint 恢复对话历史（修复 P1-1 的"刷新即失忆"）
  const loadHistory = useCallback(async () => {
    try {
      const res = await apiFetch(`/api/chat/history?session_id=${encodeURIComponent(sessionId.current)}`)
      if (!res.ok) return
      const data = await res.json()
      const history = (data.messages || [])
        .filter(m => (m.role === 'user' || m.role === 'assistant') && m.content)
        .map(m => ({ id: nextId(), role: m.role === 'user' ? 'user' : 'assistant', content: m.content }))
      if (history.length === 0) return
      // 仅在当前为空时注入，避免覆盖已到达的主动消息
      setMessages(prev => (prev.length === 0 ? history : prev))
    } catch {}
  }, [])

  // 挂载时拉一次历史
  useEffect(() => { loadHistory() }, [loadHistory])

  const sendMessage = useCallback(async (text) => {
    // 添加用户消息
    const userMsg = { id: nextId(), role: 'user', content: text }
    setMessages(prev => [...prev, userMsg])
    setIsStreaming(true)
    setCharacterState('listening')

    const assistantId = nextId()
    // 添加空的助手消息占位
    setMessages(prev => [...prev, { id: assistantId, role: 'assistant', content: '', streaming: true }])

    const controller = new AbortController()
    abortRef.current = controller

    // GPS 辅助（修复 P1-2）：带缓存的定位，拒绝/关闭时静默为 null
    const coords = await getGpsCoords()

    try {
      setCharacterState('thinking')
      const res = await apiFetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: text,
          session_id: sessionId.current,
          ...(coords || {}),
        }),
        signal: controller.signal,
      })

      // 401：需要访问令牌
      if (res.status === 401) {
        setMessages(prev =>
          prev.map(m =>
            m.id === assistantId
              ? { ...m, content: '呜……门没开。先在右上角设置里填一下访问令牌，再来找小汐玩吧。' }
              : m
          )
        )
        return
      }

      // 非 2xx（含限流 429 等）直接走错误兜底
      if (!res.ok || !res.body) {
        throw new Error(`HTTP ${res.status}`)
      }

      setCharacterState('talking')
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let streamDone = false

      while (true) {
        const { done, value } = await reader.read()
        if (done || streamDone) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          if (!line.startsWith('data: ')) continue
          const data = line.slice(6).trim()

          // 结束标记：跳出外层 while（此前只跳出内层 for，靠服务端关流兜底）
          if (data === '[DONE]') {
            streamDone = true
            break
          }

          try {
            const parsed = JSON.parse(data)

            // 工具调用事件（v0.4.5：后端不再下发入参，只有工具名）
            if (parsed.type === 'tool_start') {
              setMessages(prev => [...prev, {
                id: nextId(),
                role: 'system',
                type: 'tool_hint',
                toolName: parsed.name,
                content: hintFor(parsed.name),
              }])
              setCharacterState('thinking')
              continue
            }
            // 工具结果（修复 P2-5）：挂到最近一条同名提示上，可折叠查看
            if (parsed.type === 'tool_end') {
              const output = summarizeOutput(parsed.output)
              if (output) {
                setMessages(prev => {
                  for (let i = prev.length - 1; i >= 0; i--) {
                    if (prev[i].type === 'tool_hint' && prev[i].toolName === parsed.name && !prev[i].output) {
                      const updated = [...prev]
                      updated[i] = { ...updated[i], output }
                      return updated
                    }
                  }
                  return prev
                })
              }
              continue
            }

            // 角色状态事件（协议预留：后端可下发 {"type":"state","state":"happy"} 等切换立绘）
            if (parsed.type === 'state' && parsed.state) {
              setCharacterState(parsed.state)
              continue
            }

            // 追加文本内容
            if (parsed.content) {
              setMessages(prev =>
                prev.map(m =>
                  m.id === assistantId
                    ? { ...m, content: m.content + parsed.content }
                    : m
                )
              )
            }
          } catch {
            // 非 JSON 行，忽略
          }
        }
      }
    } catch (err) {
      if (err.name !== 'AbortError') {
        setMessages(prev =>
          prev.map(m =>
            m.id === assistantId
              ? { ...m, content: m.content || '……小汐好像遇到了什么问题。' }
              : m
          )
        )
      }
    } finally {
      setMessages(prev =>
        prev.map(m => m.id === assistantId ? { ...m, streaming: false } : m)
      )
      setIsStreaming(false)
      setCharacterState('idle')
      abortRef.current = null
    }
  }, [])

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  // 注入主动消息（后端心跳生成的 proactive message，来自 /api/push/unread）
  const receiveProactive = useCallback((items) => {
    if (!Array.isArray(items) || items.length === 0) return
    setMessages(prev => [
      ...prev,
      ...items.map(item => ({
        id: nextId(),
        role: 'system',
        type: 'proactive',
        content: item.content || item.text || '',
      })),
    ])
  }, [])

  return { messages, isStreaming, characterState, sendMessage, stopStreaming, receiveProactive, loadHistory }
}
