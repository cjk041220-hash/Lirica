import { useState, useRef, useCallback } from 'react'

let msgId = 0
const nextId = () => `msg-${++msgId}-${Date.now()}`

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

export function useChat() {
  const [messages, setMessages] = useState([])
  const [isStreaming, setIsStreaming] = useState(false)
  const [characterState, setCharacterState] = useState('idle')
  const abortRef = useRef(null)
  const sessionId = useRef(`session-${Date.now()}`)

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

    try {
      setCharacterState('thinking')
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: text, session_id: sessionId.current }),
        signal: controller.signal,
      })

      // 非 2xx（含限流 429 / 认证 401 等）直接走错误兜底
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

            // 工具调用事件
            if (parsed.type === 'tool_start') {
              setMessages(prev => [...prev, {
                id: nextId(),
                role: 'system',
                type: 'tool_hint',
                content: hintFor(parsed.name),
              }])
              setCharacterState('thinking')
              continue
            }
            if (parsed.type === 'tool_end') continue

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

  return { messages, isStreaming, characterState, sendMessage, stopStreaming, receiveProactive }
}
