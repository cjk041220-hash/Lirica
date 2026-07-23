import { useState, useEffect, useCallback, useRef } from 'react'

// 与后端 GET /api/config 的 ConfigResponse 对齐：
// { model, temperature, max_tokens, thinking_intensity, available_models }
// thinking_intensity: 'low' | 'medium' | 'high' | 'max'（手动调温度后为 'custom'）
const defaultSettings = {
  model: 'deepseek-v4-flash',
  thinking_intensity: 'medium',
  temperature: 0.7,
  max_tokens: 8192,
  available_models: [],
}

export function useConfig() {
  const [settings, setSettings] = useState(defaultSettings)
  const debounceRef = useRef({})

  // 挂载时拉取后端配置
  useEffect(() => {
    fetch('/api/config')
      .then(res => (res.ok ? res.json() : null))
      .then(data => {
        if (data && typeof data === 'object') {
          setSettings(prev => ({ ...prev, ...data }))
        }
      })
      .catch(() => {})
  }, [])

  // 卸载时清理防抖定时器
  useEffect(() => {
    const timers = debounceRef.current
    return () => Object.values(timers).forEach(clearTimeout)
  }, [])

  const updateSetting = useCallback((key, value) => {
    // 乐观更新：UI 立刻反映用户选择
    setSettings(prev => ({ ...prev, [key]: value }))

    const send = () => {
      // 只发送变更字段（后端为部分更新语义）。
      // 响应返回合并后的完整配置 —— 用它同步 state，
      // 因为选择思考强度预设会联动修改 temperature / max_tokens。
      fetch('/api/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ [key]: value }),
      })
        .then(res => (res.ok ? res.json() : null))
        .then(data => {
          if (data && typeof data === 'object') {
            setSettings(prev => ({ ...prev, ...data }))
          }
        })
        .catch(() => {})
    }

    // 温度滑杆拖动会连续触发 onChange —— 防抖 250ms 只发最终值，
    // 避免打爆 /api/config 限流（10 次/分钟）与后端 Agent 反复重建
    if (key === 'temperature') {
      clearTimeout(debounceRef.current[key])
      debounceRef.current[key] = setTimeout(send, 250)
    } else {
      send()
    }
  }, [])

  return { settings, updateSetting }
}
