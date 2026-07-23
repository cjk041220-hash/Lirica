import { useState, useEffect, useCallback } from 'react'
import { apiFetch } from '../api/client'

export function usePush() {
  const [subscribed, setSubscribed] = useState(false)
  const [unread, setUnread] = useState([])
  // 环境能力检测：SW + PushManager（HTTP localhost 允许推送；不支持时 UI 显示灰态）
  const [supported] = useState(() =>
    typeof navigator !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window
  )

  // 检查未读消息
  const checkUnread = useCallback(async () => {
    try {
      const res = await apiFetch('/api/push/unread')
      if (!res.ok) return
      const data = await res.json()
      setUnread(data.messages || data || [])
    } catch {}
  }, [])

  // 订阅推送
  const subscribe = useCallback(async () => {
    if (!supported) return false
    try {
      const vapidRes = await apiFetch('/api/push/vapid-key')
      if (!vapidRes.ok) return false
      const { key, enabled } = await vapidRes.json()
      // 后端未配置 VAPID 时 key 为 null / enabled 为 false，不尝试订阅
      if (!key || enabled === false) return false

      const registration = await navigator.serviceWorker.ready
      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: key,
      })

      const res = await apiFetch('/api/push/subscribe', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(subscription),
      })
      if (!res.ok) {
        await subscription.unsubscribe().catch(() => {})
        return false
      }

      setSubscribed(true)
      return true
    } catch {
      return false
    }
  }, [supported])

  // 取消订阅
  const unsubscribe = useCallback(async () => {
    try {
      const registration = await navigator.serviceWorker.ready
      const subscription = await registration.pushManager.getSubscription()
      if (subscription) {
        // 后端 DELETE /api/push/subscribe 接受 PushSubscription JSON body
        await apiFetch('/api/push/subscribe', {
          method: 'DELETE',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(subscription),
        })
        await subscription.unsubscribe()
      }
      setSubscribed(false)
    } catch {}
  }, [])

  // 标记已读（乐观更新 UI，再同步后端）
  const markRead = useCallback(async (ids) => {
    if (!Array.isArray(ids) || ids.length === 0) return
    setUnread(prev => prev.filter(m => !ids.includes(m.id)))
    try {
      await apiFetch('/api/push/read', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message_ids: ids }),
      })
    } catch {}
  }, [])

  // 挂载：拉未读 + 恢复浏览器已有订阅状态
  useEffect(() => {
    checkUnread()
    if (supported) {
      navigator.serviceWorker.ready
        .then(reg => reg.pushManager.getSubscription())
        .then(sub => setSubscribed(!!sub))
        .catch(() => {})
    }
  }, [checkUnread, supported])

  // 监听 SW 转发的推送事件 → 重新拉取未读
  useEffect(() => {
    if (!supported) return
    const onMessage = (event) => {
      if (event.data?.type === 'PUSH_MESSAGE') checkUnread()
    }
    navigator.serviceWorker.addEventListener('message', onMessage)
    return () => navigator.serviceWorker.removeEventListener('message', onMessage)
  }, [checkUnread, supported])

  // 轮询兜底送达：每 60 秒拉一次未读主动消息。
  // 未订阅系统通知（或后端未配 VAPID）时，这是页面保持打开期间
  // 主动消息（随机搭话/生理期关心）进入对话的唯一通道——
  // 否则用户必须刷新页面才看得到，心跳消息形同石沉大海。
  // 限流余量充足（/api/push 允许 30 次/分钟）。
  useEffect(() => {
    const timer = setInterval(checkUnread, 60_000)
    const onVisible = () => { if (document.visibilityState === 'visible') checkUnread() }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [checkUnread])

  return { supported, subscribed, unread, subscribe, unsubscribe, markRead, checkUnread }
}
