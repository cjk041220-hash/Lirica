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
  // v21 扩展字段：last_seen_at / active_session_id（presence 回显）、
  // unseen_era_id（小汐在独处时间线写了新话的切换信号）——默认 null
  // 与服务端「无」语义一致，避免旧后端响应缺字段时 undefined 穿透
  const [status, setStatus] = useState({
    mood: null,
    daily_event: null,
    last_seen_at: null,
    active_session_id: null,
    unseen_era_id: null,
  })

  const refreshStatus = useCallback(async () => {
    try {
      const res = await apiFetch('/api/status')
      if (res.ok) setStatus(await res.json())
    } catch (e) {
      // 状态拉取失败不影响聊天主流程
      console.warn('[useStatus] failed to refresh:', e)
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
