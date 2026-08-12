import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useChat } from './useChat'

// 可控 GPS Promise：测试里手动 resolve，模拟「定位尚未返回时用户点停止」。
// vi.hoisted 使 mock 工厂与测试体共享同一对象（vi.mock 会提升到文件顶部）。
const gps = vi.hoisted(() => ({ resolve: null }))
// apiFetch mock 同样 hoisted：SSE 批量测试要 mockResolvedValueOnce 注入可控流
const apiFetchMock = vi.hoisted(() => vi.fn(async () => ({ ok: false })))

vi.mock('../api/location', () => ({
  getGpsCoords: () =>
    new Promise((resolve) => {
      gps.resolve = resolve
    }),
  isGpsEnabled: () => true,
}))

vi.mock('../api/client', () => ({
  apiFetch: apiFetchMock,
  getToken: () => 'test-token',
  UNAUTHORIZED_EVENT: 'unauthorized',
}))

// 可控 SSE 流：测试手动 emit 每一行，模拟「网络把 JSON 拆成多个 chunk」
function controllableStream() {
  const encoder = new TextEncoder()
  let controller
  const stream = new ReadableStream({
    start(c) {
      controller = c
    },
  })
  return {
    stream,
    emit(line) {
      controller.enqueue(encoder.encode(line))
    },
    close() {
      controller.close()
    },
  }
}

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
    const assistant = result.current.messages.find((m) => m.role === 'assistant')
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
    const userMsgs = result.current.messages.filter((m) => m.role === 'user')
    expect(userMsgs.length).toBe(2)
    expect(result.current.isStreaming).toBe(false)
  })
})

describe('useChat SSE 批量刷新（P1-14）', () => {
  beforeEach(() => {
    gps.resolve = null
    localStorage.clear()
  })

  it('token 先入缓冲，40ms 后才批量提交', async () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('hi')
    })
    await act(async () => {
      gps.resolve(null)
    }) // 放行 GPS → 进入 fetch 读流
    await act(async () => {
      s.emit('data: {"content":"你"}\n')
    })

    // 未推进定时器 → 内容仍在缓冲，未提交
    expect(result.current.messages.find((m) => m.role === 'assistant').content).toBe('')

    // 推进 40ms → 批量提交
    await act(async () => {
      vi.advanceTimersByTime(40)
    })
    expect(result.current.messages.find((m) => m.role === 'assistant').content).toBe('你')

    // 流结束 → finally 兜底 flush，不丢字
    await act(async () => {
      s.emit('data: {"content":"好"}\n')
      s.emit('data: [DONE]\n')
      s.close()
    })
    await act(async () => {
      await sendPromise
    })
    expect(result.current.messages.find((m) => m.role === 'assistant').content).toBe('你好')
    vi.useRealTimers()
  })

  it('流式错误事件前先 flush 缓冲，不丢已到内容', async () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('hi')
    })
    await act(async () => {
      gps.resolve(null)
    }) // 放行 GPS → 进入 fetch 读流
    await act(async () => {
      s.emit('data: {"content":"一半"}\n')
      s.emit('data: {"type":"error","content":"出错了"}\n')
      s.close()
    })
    await act(async () => {
      await sendPromise
    })
    const assistant = result.current.messages.find((m) => m.role === 'assistant')
    expect(assistant.content).toBe('一半\n\n（出错了）')
    vi.useRealTimers()
  })

  it('tool_end 携带 nav → 与 output 同一次挂到 tool_hint（地图按钮数据）', async () => {
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('尖沙咀在哪里')
    })
    await act(async () => {
      gps.resolve(null)
    })
    await act(async () => {
      s.emit('data: {"type":"tool_start","name":"map"}\n')
      s.emit(
        'data: {"type":"tool_end","name":"map","output":"📍 尖沙咀站：114.172,22.297","nav":{"name":"尖沙咀站","lat":22.297,"lon":114.172,"coord":"gcj02"}}\n'
      )
      s.emit('data: [DONE]\n')
      s.close()
    })
    await act(async () => {
      await sendPromise
    })

    const hint = result.current.messages.find((m) => m.type === 'tool_hint')
    expect(hint.output).toBe('📍 尖沙咀站：114.172,22.297')
    expect(hint.nav).toEqual({ name: '尖沙咀站', lat: 22.297, lon: 114.172, coord: 'gcj02' })
  })

  it('tool_end nav 非法（lat 缺失）→ 不挂 nav，output 正常挂', async () => {
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('天气')
    })
    await act(async () => {
      gps.resolve(null)
    })
    await act(async () => {
      s.emit('data: {"type":"tool_start","name":"weather"}\n')
      s.emit(
        'data: {"type":"tool_end","name":"weather","output":"🌤️ 晴","nav":{"name":"x","lon":114.1}}\n'
      )
      s.emit('data: [DONE]\n')
      s.close()
    })
    await act(async () => {
      await sendPromise
    })

    const hint = result.current.messages.find((m) => m.type === 'tool_hint')
    expect(hint.output).toBe('🌤️ 晴')
    expect(hint.nav).toBeUndefined()
  })

  it('tool_end 携带 tarot → 卡片数据与 output 同一次挂到 tool_hint', async () => {
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('帮我算一卦')
    })
    await act(async () => {
      gps.resolve(null)
    })
    await act(async () => {
      s.emit('data: {"type":"tool_start","name":"tarot"}\n')
      s.emit(
        'data: {"type":"tool_end","name":"tarot","output":"🐋 小汐去映海里看了一下……","tarot":{"spread_type":"three","spread_name":"三牌占卜","cards":[{"name":"恋人","symbol":"VI","position":"过去"},{"name":"魔术师","symbol":"I","position":"现在"},{"name":"女皇","symbol":"III","position":"未来"}]}}\n'
      )
      s.emit('data: [DONE]\n')
      s.close()
    })
    await act(async () => {
      await sendPromise
    })

    const hint = result.current.messages.find((m) => m.type === 'tool_hint')
    expect(hint.tarot.cards).toHaveLength(3)
    expect(hint.tarot.spread_type).toBe('three')
    expect(hint.output).toBeTruthy() // 与卡片同一次原子挂载
  })

  it('tool_end tarot 脏数据（缺 name）→ 不挂 tarot，output 正常挂', async () => {
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('再算一卦')
    })
    await act(async () => {
      gps.resolve(null)
    })
    await act(async () => {
      s.emit('data: {"type":"tool_start","name":"tarot"}\n')
      s.emit(
        'data: {"type":"tool_end","name":"tarot","output":"🐋 文本","tarot":{"spread_type":"three","spread_name":"三牌占卜","cards":[{"symbol":"VI","position":"过去"}]}}\n'
      )
      s.emit('data: [DONE]\n')
      s.close()
    })
    await act(async () => {
      await sendPromise
    })

    const hint = result.current.messages.find((m) => m.type === 'tool_hint')
    expect(hint.tarot).toBeUndefined()
    expect(hint.output).toBeTruthy()
  })
})

describe('useChat 读取超时兜底（审计 P1-2）', () => {
  beforeEach(() => {
    gps.resolve = null
    localStorage.clear()
  })

  it('读流 45s 无字节 → 按早断处理，附「连接中断」文案（代理掐断不发 FIN）', async () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('hi')
    })
    await act(async () => {
      gps.resolve(null)
    })
    await act(async () => {
      s.emit('data: {"content":"半句"}\n')
    })

    // 此后流永久静默（reader.read() 永不 resolve）：
    // 推进 45s 触发无字节兜底，而不是永久卡 streaming
    await act(async () => {
      vi.advanceTimersByTime(45_000)
    })
    await act(async () => {
      await sendPromise
    })

    const assistant = result.current.messages.find((m) => m.role === 'assistant')
    expect(assistant.content).toContain('半句')
    expect(assistant.content).toContain('连接中断')
    expect(assistant.streaming).toBe(false)
    expect(result.current.isStreaming).toBe(false)
    vi.useRealTimers()
  })

  it('后端 ping 心跳到达 → 重置超时计时，不误杀（45s 内持续有字节）', async () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useChat())
    await act(async () => {})

    const s = controllableStream()
    apiFetchMock.mockResolvedValueOnce({ ok: true, status: 200, body: s.stream })
    let sendPromise
    act(() => {
      sendPromise = result.current.sendMessage('hi')
    })
    await act(async () => {
      gps.resolve(null)
    })

    // 模拟慢流：每 20s 来一个 ping（后端 _SSE_PING_INTERVAL 同款节奏），
    // 累计远超 45s 也不该触发无字节兜底——ping 不进 feed，但到达 reader
    for (let i = 0; i < 3; i++) {
      await act(async () => {
        vi.advanceTimersByTime(20_000)
        s.emit(': ping\n')
      })
    }
    await act(async () => {
      s.emit('data: {"content":"来了"}\n')
      s.emit('data: [DONE]\n')
      s.close()
    })
    await act(async () => {
      await sendPromise
    })

    const assistant = result.current.messages.find((m) => m.role === 'assistant')
    expect(assistant.content).toBe('来了')
    expect(assistant.content).not.toContain('连接中断')
    vi.useRealTimers()
  })
})
