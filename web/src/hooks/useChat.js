import { useState, useRef, useCallback, useEffect } from 'react'
import { apiFetch, getToken, UNAUTHORIZED_EVENT } from '../api/client'
import { getGpsCoords, isGpsEnabled } from '../api/location'
import { isValidNav } from '../utils/mapLink'
import { isValidTarot } from '../utils/tarot'

// iOS Safari <17.4 的 fetch + ReadableStream 会整段缓冲、生成完毕才
// 一次性吐出（WebKit 17.4 起才真增量）——打字机效果全毁，用户盯着
// 冻结界面十几秒。此情形改走 XHR onprogress 增量读取（该 API WebKit
// 一直支持）。无可靠特性检测（旧版有 ReadableStream 但不增量吐），
// 只能 UA 判版本。ua 可注入，留给将来单测。
export function needsXhrStreaming(
  ua = typeof navigator !== 'undefined' ? navigator.userAgent : ''
) {
  const isIOS =
    /iP(hone|od|ad)/.test(ua) ||
    // iPadOS 13+ 伪装成 Mac UA，靠触控点数量识破
    (typeof navigator !== 'undefined' &&
      navigator.platform === 'MacIntel' &&
      navigator.maxTouchPoints > 1)
  if (!isIOS) return false
  if (/CriOS|FxiOS|EdgiOS|OPiOS/.test(ua)) return false // 第三方 iOS 浏览器不在本次兜底范围
  if (!/Safari/.test(ua)) return false
  const m = ua.match(/OS (\d+)_(\d+)/)
  if (!m) return false
  return +m[1] + +m[2] / 100 < 17.4
}

let msgId = 0
const nextId = () => `msg-${++msgId}-${Date.now()}`

// 新会话 id 的单调序号尾缀：Date.now() 只有毫秒分辨率，
// 同毫秒连点「开一段新聊天」会撞 id（后端会视为同一会话）
let sessionSeq = 0

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
  toolHints.find((h) => h.match.test(name || ''))?.text || '小汐在忙一会儿……'

// 工具结果摘要：原始输出较长，气泡里只折叠展示一小段
const summarizeOutput = (output) => {
  const text = String(output || '').trim()
  return text.length > 300 ? text.slice(0, 300) + '……' : text
}

export function useChat() {
  const [messages, setMessages] = useState([])
  const [isStreaming, setIsStreaming] = useState(false)
  const [characterState, setCharacterState] = useState('idle')
  // 每完成一条助手消息就递增的时间戳：日历面板据此「聊完即刷」
  //（这轮对话里小汐可能记下/改过/删过日程）。sendMessage 是零依赖
  // useCallback，外部拿不到回调，只能走这种返回值通道
  const [lastDoneAt, setLastDoneAt] = useState(0)
  const abortRef = useRef(null)
  const happyTimerRef = useRef(null) // 「刚聊完→开心」的回落定时器
  // 会话 ID：state（供 SessionsPanel 高亮当前会话）+ 镜像 ref
  //（sendMessage/stopStreaming 等零依赖回调里读 ref，避免闭包过期值）
  const [sessionId, _setSessionId] = useState(getSessionId)
  const sessionIdRef = useRef(sessionId)
  // 会话纪元：切换会话时 +1。旧流被 abort 后 finally 里的立绘/
  // streaming 收尾动作只对当前纪元生效，避免给新会话演一出伤心
  const epochRef = useRef(0)
  // 本会话用户已发消息数（深夜唤醒序列用）。必须是 state 而非 ref：
  // App.jsx 的 displayState useMemo 依赖它触发重算，ref 对象引用恒定，
  // 值变不触发重算（此前靠 characterState 变化间接重算才碰巧工作）
  const [userMsgCount, setUserMsgCount] = useState(0)

  // 刷新后恢复对话历史（修复 P1-1 的"刷新即失忆"）：
  // 优先读持久对话档案（迁移 v8）；档案为空的旧会话（v8 之前
  // 建立、还没有新消息）回退 checkpoint 历史兜底
  const loadHistory = useCallback(async () => {
    const sid = sessionIdRef.current
    const toLocal = (list) =>
      (list || [])
        .filter((m) => (m.role === 'user' || m.role === 'assistant') && m.content)
        .map((m) => ({ id: nextId(), role: m.role, content: m.content }))

    let history = []
    try {
      const res = await apiFetch(`/api/sessions/${encodeURIComponent(sid)}/messages`)
      if (res.ok) history = toLocal((await res.json()).messages)
    } catch (e) {
      console.warn('[useChat] archive load failed:', e)
    }
    if (history.length === 0) {
      try {
        const res = await apiFetch(`/api/chat/history?session_id=${encodeURIComponent(sid)}`)
        if (res.ok) history = toLocal((await res.json()).messages)
      } catch (e) {
        console.warn('[useChat] failed to load history:', e)
      }
    }
    if (history.length === 0) return
    // 仅在当前为空时注入，避免覆盖已到达的主动消息
    setMessages((prev) => (prev.length === 0 ? history : prev))
    // 回填用户消息数：刷新/恢复后深夜唤醒序列才有正确基准
    setUserMsgCount(history.filter((m) => m.role === 'user').length)
  }, [])

  // 挂载时拉一次历史
  useEffect(() => {
    loadHistory()
  }, [loadHistory])

  // 卸载时清掉「开心回落」定时器（防微杜渐：App 根组件永不卸载，但 hook 应保持自洽）
  useEffect(() => () => clearTimeout(happyTimerRef.current), [])

  const sendMessage = useCallback(async (text) => {
    // 防止双重发送：用 abortRef 而非 isStreaming——本回调依赖数组为空，
    // 闭包里的 isStreaming 永远是挂载时的 false（守卫形同虚设）；
    // abortRef 在 GPS await 之前就同步置位，才是覆盖整个发送窗口的真锁
    if (abortRef.current) return

    // 创建 AbortController 必须早于 setIsStreaming(true)，
    // 否则用户在 GPS 获取期间的停止操作会因 abortRef 为空而丢失。
    const controller = new AbortController()
    abortRef.current = controller
    const myEpoch = epochRef.current // 本次发送所属的会话纪元

    // 添加用户消息
    const userMsg = { id: nextId(), role: 'user', content: text }
    setMessages((prev) => [...prev, userMsg])
    // 深夜唤醒序列：记录用户发送的消息数
    setUserMsgCount((c) => c + 1)
    setIsStreaming(true)
    clearTimeout(happyTimerRef.current)
    setCharacterState('listening')

    const assistantId = nextId()
    // 添加空的助手消息占位
    setMessages((prev) => [
      ...prev,
      { id: assistantId, role: 'assistant', content: '', streaming: true },
    ])

    // 只有流正常读完才算「聊成功」—— 401 / 网络错误 / 中断都不算，
    // 失败时立绘不演开心（气泡报错 + 立绘卖萌是情绪错位）。
    // 声明必须在 try 外：finally 要引用它，try 块内的 let 在 finally
    // 里不可见（ReferenceError，P0-02 单测实锤过一次）
    let succeeded = false
    // P1-14：内容 token 缓冲也必须在 try 外——finally 的 flush() 要引用
    // 它（同 succeeded 的教训）。40ms 定时批量提交，避免每 token 一次
    // setMessages + 全列表重渲染 + Markdown 重渲染。
    let contentBuffer = ''
    let flushTimer = null
    // 流式打字的呼吸节奏（U2）：匀速 40ms 打印像机器倒字——按刚
    // 展示内容的句读决定下一次 flush 的停顿：句读长停（200ms，
    // 句号/问号/感叹号/省略号/换行），短读小停（120ms，逗号/顿号/
    // 分号），其余保持 40ms。状态机只在这一层，不动传输与缓冲结构
    let nextFlushDelay = 40
    const flush = () => {
      flushTimer = null
      if (!contentBuffer) return
      const chunk = contentBuffer
      contentBuffer = ''
      const tail = chunk.slice(-1)
      nextFlushDelay = '。！？!?…～~\n'.includes(tail)
        ? 200
        : '，、；：,;'.includes(tail)
          ? 120
          : 40
      setMessages((prev) =>
        prev.map((m) => (m.id === assistantId ? { ...m, content: m.content + chunk } : m))
      )
    }
    const scheduleFlush = () => {
      if (flushTimer) return
      flushTimer = setTimeout(flush, nextFlushDelay)
    }

    try {
      // GPS 辅助（修复 P1-2）：带缓存的定位，拒绝/关闭时静默为 null。
      // P0-02 回归：GPS await 必须住在 try/finally 里——修复前它在
      // try 之外，用户在定位窗口点停止会走「abort 即 return」提前退出，
      // finally 不执行：abortRef 永久锁死（后续发送全被吞）、isStreaming
      // 与空助手占位卡 streaming、立绘卡 listening，页面永远「正在发送」。
      // 现在提前 return 仍会进 finally 按纪元身份收尾，与流中停止同语义
      //（无内容气泡留空、立绘难过一阵回平静）。
      const coords = await getGpsCoords()

      // GPS 窗口期间用户切了会话或点了停止 → 放弃本次发送。
      // 不拦截的话，await 返回后代码会继续把这条消息发给（新的）
      // sessionIdRef.current，用户切会话前输入的话会幽灵般窜进新会话
      if (controller.signal.aborted) return

      setCharacterState('thinking')

      // 请求体（fetch / XHR 两传输共用）
      const body = JSON.stringify({
        message: text,
        session_id: sessionIdRef.current,
        ...(coords || {}),
        // GPS 开关信号随请求下发：关闭时后端立即清掉缓存坐标
        // （否则旧坐标按 TTL 最长 8h 仍在注入——「关了还在用」）
        gps_enabled: isGpsEnabled(),
      })

      let buffer = ''
      let streamDone = false
      let streamError = false // 收到 type=error 事件 → 本轮不算成功

      // 共享行解析：fetch（增量 reader）与 XHR（onprogress 增量）都把
      // 文本片段喂进来，SSE 行语义一处维护，两传输行为完全一致
      const feed = (chunkText) => {
        buffer += chunkText
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
              setMessages((prev) => [
                ...prev,
                {
                  id: nextId(),
                  role: 'system',
                  type: 'tool_hint',
                  toolName: parsed.name,
                  content: hintFor(parsed.name),
                },
              ])
              setCharacterState('thinking')
              continue
            }
            // 工具结果（修复 P2-5）：挂到最近一条同名提示上，可折叠查看。
            // nav（地图目的地）、tarot（塔罗卡片，后端 content_and_artifact
            // 契约）与 output 同一次 setMessages 挂上——二次挂载会被
            // !prev[i].output 挡掉，必须一次到位才保证「有卡片/按钮就有数据」的原子性
            if (parsed.type === 'tool_end') {
              const output = summarizeOutput(parsed.output)
              const nav = isValidNav(parsed.nav) ? parsed.nav : null
              const tarot = isValidTarot(parsed.tarot) ? parsed.tarot : null
              // 一次 setMessages 挂 output/nav/tarot——二次挂载会被
              // !prev[i].output 挡掉，必须一次到位保证「有卡片就有数据」的原子性
              if (output || nav || tarot) {
                setMessages((prev) => {
                  for (let i = prev.length - 1; i >= 0; i--) {
                    if (
                      prev[i].type === 'tool_hint' &&
                      prev[i].toolName === parsed.name &&
                      !prev[i].output
                    ) {
                      const updated = [...prev]
                      updated[i] = {
                        ...updated[i],
                        ...(output ? { output } : {}),
                        ...(nav ? { nav } : {}),
                        ...(tarot ? { tarot } : {}),
                      }
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

            // 失败事件（agent 未就绪 / 流内异常）：与「正常回复」区分开——
            // succeeded 保持 false，finally 里立绘回 idle 不演 happy
            // （气泡报错 + 立绘卖萌是情绪错位）。错误事件是终结性的。
            if (parsed.type === 'error') {
              streamError = true
              streamDone = true
              flush() // 先把缓冲的内容提交，再拼错误文案
              const msg = parsed.content || '……小汐好像遇到了什么问题。'
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantId
                    ? { ...m, content: m.content ? m.content + '\n\n（' + msg + '）' : msg }
                    : m
                )
              )
              break
            }

            // 追加文本内容（先入缓冲，40ms 批量提交）
            if (parsed.content) {
              contentBuffer += parsed.content
              scheduleFlush()
            }
          } catch (e) {
            // 非 JSON 行，忽略
            console.warn('[useChat] SSE parse error:', e)
          }
        }
      }

      // 401 提示（fetch 路径经 apiFetch 已广播 UNAUTHORIZED_EVENT，
      // XHR 绕过统一请求层，需手工补发同一事件）
      const showUnauthorized = (dispatchEvent) => {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? { ...m, content: '呜……门没开。先在右上角设置里填一下访问令牌，再来找小汐玩吧。' }
              : m
          )
        )
        if (dispatchEvent) window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
      }

      if (needsXhrStreaming()) {
        // iOS Safari <17.4 fetch 流式整段缓冲 → XHR onprogress 增量兜底。
        // 生命周期仍挂在 controller 上（用户停止 / 切会话 abortRef 语义不变）
        let unauthorized = false
        await new Promise((resolve, reject) => {
          const xhr = new XMLHttpRequest()
          xhr.open('POST', '/api/chat/stream', true)
          xhr.setRequestHeader('Content-Type', 'application/json')
          const token = getToken()
          if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
          let seen = 0
          let xhrStopped = false // [DONE] 早停 / 401 / 错误中止 → 区别于用户停止
          let talking = false
          xhr.onprogress = () => {
            if (xhr.status === 401) {
              unauthorized = true
              xhrStopped = true
              xhr.abort()
              return
            }
            if (xhr.status >= 400) {
              // reject 必须先于 abort：abort 在个别浏览器同步触发 onabort，
              // 若顺序颠倒 Promise 会先被 resolve 吞掉错误
              xhrStopped = true
              reject(new Error(`HTTP ${xhr.status}`))
              xhr.abort()
              return
            }
            if (!talking) {
              setCharacterState('talking')
              talking = true
            }
            // responseText 每次访问都会全量重解码，单次读取入局部变量
            // 避免 slice+length 双重解码（长流下类 O(n²)）
            const text = xhr.responseText
            if (text.length > seen) {
              feed(text.slice(seen))
              seen = text.length
            }
            if (streamDone) {
              xhrStopped = true
              xhr.abort()
            } // [DONE] 后早停，不等关流
          }
          xhr.onload = () => {
            if (xhrStopped) return
            const text = xhr.responseText
            if (text.length > seen) feed(text.slice(seen)) // 收尾残段
            resolve()
          }
          xhr.onerror = () => reject(new Error('network'))
          xhr.onabort = () => {
            // 用户停止（controller.abort）→ 等价 fetch 的 AbortError，
            // catch 分支据此不误报错误文案；其余中止原因已在上面各有归宿
            if (xhrStopped) resolve()
            else reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))
          }
          controller.signal.addEventListener('abort', () => xhr.abort())
          xhr.send(body)
        })
        if (unauthorized) {
          showUnauthorized(true)
          return
        }
      } else {
        const res = await apiFetch('/api/chat/stream', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body,
          signal: controller.signal,
        })

        // 401：需要访问令牌
        if (res.status === 401) {
          showUnauthorized(false)
          return
        }

        // 非 2xx（含限流 429 等）直接走错误兜底
        if (!res.ok || !res.body) throw new Error(`HTTP ${res.status}`)

        setCharacterState('talking')
        const reader = res.body.getReader()
        const decoder = new TextDecoder()
        while (!streamDone) {
          const { done, value } = await reader.read()
          if (done) break
          feed(decoder.decode(value, { stream: true }))
        }
      }
      // 当流因异常断开（非 [DONE] 收尾）时，buffer 里可能残留最后一条
      // 完整的 data: 行（末尾缺换行符）。直接丢弃会导致该 chunk 的文本
      // 永远不到达助手气泡，造成「丢字」。flush 一把兜底。
      if (buffer.startsWith('data: ') && buffer !== 'data: [DONE]') {
        const data = buffer.slice(6).trim()
        try {
          const parsed = JSON.parse(data)
          if (parsed.content) {
            contentBuffer += parsed.content
            flush()
          }
          if (parsed.type === 'error') streamError = true
        } catch {
          /* 非完整 JSON 行，丢弃 */
        }
      }
      // 早断检测：流读完（done）却没收到 [DONE]、也没有 error 事件——
      // 连接被中途切断（后端重启/代理掐断）。半截回复不能算聊成功，
      // 且必须让用户知道话没说完，否则违背「只有流正常读完才算成功」
      // 的 invariant（立绘还会对半句话演 6 秒开心）
      if (!streamDone && !streamError) {
        streamError = true
        flush() // 先提交已到内容，再拼中断文案
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? { ...m, content: (m.content || '……') + '\n\n（连接中断了，小汐的话没说完……）' }
              : m
          )
        )
      }
      // 流正常读完（收到 [DONE]）且无 error 事件才算成功
      succeeded = !streamError
    } catch (err) {
      flush() // 兜底：任何已到内容先提交（AbortError 也提交，不丢字）
      if (err.name !== 'AbortError') {
        // 已有部分文字时「追加」中断提示，而不是仅在内容为空时填兜底文案——
        // 否则用户看到一条被截断的回复却毫不知情
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? {
                  ...m,
                  content: m.content
                    ? m.content + '\n\n（连接出了问题，小汐的话没说完……）'
                    : '……小汐好像遇到了什么问题。',
                }
              : m
          )
        )
      }
    } finally {
      clearTimeout(flushTimer)
      flush() // 兜底：残留缓冲全量提交，再收尾
      // 锁按身份释放：只清自己的 controller。切换会话时 changeSession
      // 已同步置空并可能装上新会话的 controller，无条件置空会误废它
      if (abortRef.current === controller) abortRef.current = null
      // 发送期间切换了会话 → 旧流的立绘/streaming 收尾属于上一纪元，
      // 跳过（消息追加本就按 id map，对新列表是 no-op）
      if (epochRef.current === myEpoch) {
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? {
                  ...m,
                  streaming: false,
                  // P0-02 尾巴：停止且尚未生成内容 → 空占位补「已停止生成」。
                  // 只有「用户停止」会命中（错误/401/早断路径都已写内容）
                  content: !succeeded && !m.content ? '已停止生成' : m.content,
                }
              : m
          )
        )
        setIsStreaming(false)
        // 聊成功 → 小汐开心一阵再归于平静；失败 → 难过一阵再归于平静
        clearTimeout(happyTimerRef.current)
        if (succeeded) {
          setLastDoneAt(Date.now())
          setCharacterState('happy')
          happyTimerRef.current = setTimeout(() => setCharacterState('idle'), 6000)
        } else {
          setCharacterState('sad')
          happyTimerRef.current = setTimeout(() => setCharacterState('idle'), 6000)
        }
      }
    }
  }, [])

  const stopStreaming = useCallback(() => {
    abortRef.current?.abort()
  }, [])

  // ── 会话切换（对话历史功能）──────────────────────────────

  // 切换底层会话：掐断进行中的流、换 id、清空界面。
  // 旧流被 abort 后其 finally 因纪元不匹配不再动界面状态
  const changeSession = useCallback((id) => {
    epochRef.current += 1
    abortRef.current?.abort()
    // 同步释放锁，不等旧流的 finally——旧流的 finally 按身份释放
    //（abortRef.current === controller）不会误清新会话的 controller。
    // 若不置空，旧流卡在 GPS await（最长 10s）期间新会话的发送会被
    // 「防双重发送」守卫静默吞掉，用户的话从输入框消失且无回应
    abortRef.current = null
    sessionIdRef.current = id
    _setSessionId(id)
    try {
      localStorage.setItem(SESSION_KEY, id)
    } catch {}
    clearTimeout(happyTimerRef.current)
    setMessages([])
    setIsStreaming(false)
    setCharacterState('idle')
    setUserMsgCount(0)
  }, [])

  // 开一段新聊天（满足后端 ^[a-z0-9-]{1,64}$；序号尾缀防同毫秒撞 id）
  const newSession = useCallback(() => {
    changeSession(`s-${Date.now().toString(36)}-${(sessionSeq++).toString(36)}`)
  }, [changeSession])

  // 回到一段旧聊天：载入档案消息，可直接续聊（session_id 就是
  // LangGraph thread_id，checkpoint 还在时小汐本人也接得上）
  const switchSession = useCallback(
    async (id) => {
      if (id === sessionIdRef.current) return
      changeSession(id)
      const myEpoch = epochRef.current
      try {
        const res = await apiFetch(`/api/sessions/${encodeURIComponent(id)}/messages`)
        if (!res.ok) return
        const msgs = ((await res.json()).messages || [])
          .filter((m) => (m.role === 'user' || m.role === 'assistant') && m.content)
          .map((m) => ({ id: nextId(), role: m.role, content: m.content }))
        if (msgs.length === 0) return
        // fetch 返回前用户又切了会话 → 丢弃过期结果，不往新会话上盖
        if (epochRef.current !== myEpoch) return
        // 仅在当前为空时注入，避免覆盖期间到达的主动消息（同 loadHistory 守卫）
        setMessages((prev) => (prev.length === 0 ? msgs : prev))
        // 深夜唤醒序列依赖 userMsgCount：按载入的用户消息数回填，
        // 避免回到旧会话时立绘装睡
        setUserMsgCount(msgs.filter((m) => m.role === 'user').length)
      } catch (e) {
        console.warn('[useChat] switchSession failed:', e)
      }
    },
    [changeSession]
  )

  // 注入主动消息（后端心跳生成的 proactive message，来自 /api/push/unread）
  const receiveProactive = useCallback((items) => {
    if (!Array.isArray(items) || items.length === 0) return
    setMessages((prev) => [
      ...prev,
      ...items.map((item) => ({
        id: nextId(),
        role: 'system',
        type: 'proactive',
        content: item.content || item.text || '',
      })),
    ])
    // 小汐主动说话了 → 立绘高兴一阵。abortRef 非空 = 聊天流程进行中，
    // 此时不打断 listening/thinking/talking 的流程态
    if (!abortRef.current) {
      clearTimeout(happyTimerRef.current)
      setCharacterState('happy')
      happyTimerRef.current = setTimeout(() => setCharacterState('idle'), 6000)
    }
  }, [])

  return {
    messages,
    isStreaming,
    characterState,
    sendMessage,
    stopStreaming,
    receiveProactive,
    userMsgCount,
    sessionId,
    newSession,
    switchSession,
    lastDoneAt,
  }
}
