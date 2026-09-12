import { useState, useEffect, useCallback, useRef } from 'react'
import { apiFetch } from '../api/client'

// 与后端 GET /api/config 的 ConfigResponse 对齐：
// { model, temperature, max_tokens, thinking_intensity, available_models }
// thinking_intensity: 'low' | 'medium' | 'high' | 'max'（手动调温度后为 'custom'）
const defaultSettings = {
  model: 'deepseek-v4.1-flash',
  thinking_intensity: 'medium',
  temperature: 0.7,
  max_tokens: 8192,
  available_models: [],
}

export function useConfig() {
  const [settings, setSettings] = useState(defaultSettings)
  const debounceRef = useRef({})
  const updateSeqRef = useRef(0) // 丢弃过期响应（防止并发请求覆盖更新值）

  // 挂载时拉取后端配置
  useEffect(() => {
    apiFetch('/api/config')
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (data && typeof data === 'object') {
          setSettings((prev) => ({ ...prev, ...data }))
        }
      })
      .catch((e) => console.warn('[useConfig] fetch config failed:', e))
  }, [])

  // 卸载时清理防抖定时器
  useEffect(() => {
    const timers = debounceRef.current
    return () => Object.values(timers).forEach(clearTimeout)
  }, [])

  const updateSetting = useCallback((key, value) => {
    // 乐观更新：UI 立刻反映用户选择
    setSettings((prev) => ({ ...prev, [key]: value }))

    // 递增序列号，配合 send() 内校验丢弃过期响应
    const seq = ++updateSeqRef.current

    const send = () => {
      apiFetch('/api/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ [key]: value }),
      })
        .then((res) => (res.ok ? res.json() : null))
        .then((data) => {
          // 仅当本 send() 调用是最新的一次时才同步后端返回的完整配置，
          // 防止旧请求的响应覆盖后续更新的值
          if (data && typeof data === 'object' && seq === updateSeqRef.current) {
            setSettings((prev) => ({ ...prev, ...data }))
          }
        })
        .catch((e) => console.warn('[useConfig] update setting failed:', e))
    }

    // 温度滑杆拖动会连续触发 onChange —— 防抖 250ms 只发最终值，
    // 避免打爆 /api/config 限流（30 次/分钟）与后端 Agent 反复重建
    if (key === 'temperature') {
      clearTimeout(debounceRef.current[key])
      debounceRef.current[key] = setTimeout(send, 250)
    } else {
      send()
    }
  }, [])

  return { settings, updateSetting }
}
