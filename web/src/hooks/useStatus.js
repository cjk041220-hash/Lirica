import { useState, useEffect, useCallback } from 'react'
import { apiFetch } from '../api/client'

/**
 * 小汐的今日状态（碎碎念）—— 来自后端 life_state 表。
 *
 * 拉取时机：挂载一次 + 标签页回前台 + 5 分钟低频轮询
 * （后端每 2 小时才刷新一次生命状态，无需高频）。
 *
 * @returns {{ mood: string|null, daily_event: string|null, refreshStatus: () => void }}
 */
export function useStatus() {
  const [status, setStatus] = useState({ mood: null, daily_event: null })

  const refreshStatus = useCallback(async () => {
    try {
      const res = await apiFetch('/api/status')
      if (res.ok) setStatus(await res.json())
    } catch {
      // 状态拉取失败不影响聊天主流程，静默
    }
  }, [])

  useEffect(() => {
    refreshStatus()
    const onVisible = () => {
      if (document.visibilityState === 'visible') refreshStatus()
    }
    document.addEventListener('visibilitychange', onVisible)
    const timer = setInterval(refreshStatus, 5 * 60 * 1000)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      clearInterval(timer)
    }
  }, [refreshStatus])

  return { ...status, refreshStatus }
}
