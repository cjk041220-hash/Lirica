import { useState, useEffect, useCallback } from 'react'
import { apiFetch } from '../api/client'

export function usePush() {
  const [subscribed, setSubscribed] = useState(false)
  const [unread, setUnread] = useState([])
  // 环境能力检测：SW + PushManager（HTTP localhost 允许推送；不支持时 UI 显示灰态）
  const [supported] = useState(
    () =>
      typeof navigator !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window
  )
  // iOS 16.4+ 的 Web Push 只对「已添加到主屏幕」的 standalone PWA 生效：
  // 普通 Safari 标签页里 PushManager 存在（supported 为 true）但
  // subscribe() 必抛 NotAllowedError。检测出来换成「添加到主屏幕」引导，
  // 而不是让开关点了静默失败
  const [iosInstallNeeded] = useState(() => {
    if (typeof navigator === 'undefined') return false
    const ua = navigator.userAgent
    const isIos =
      /iP(hone|od|ad)/.test(ua) ||
      // iPadOS 13+ 伪装成 Mac UA，靠触控点数量识破
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
    if (!isIos) return false
    const standalone =
      ('standalone' in navigator && navigator.standalone) ||
      window.matchMedia('(display-mode: standalone)').matches
    return !standalone
  })
  // 通知权限被拒（系统级 denied / subscribe 抛 NotAllowedError）→ 引导去设置里开
  const [permissionDenied, setPermissionDenied] = useState(false)
  // 订阅失败的具体原因（2026-08-09：subscribe 有多个静默 return false 路径——
  // VAPID 未配 / 服务端拒绝 / subscribe 抛错。此前这些路径「点了没反应」，
  // 用户只会得到一句「开关点不动」。错误显性化：任何点击都有可见解释）
  const [subscribeError, setSubscribeError] = useState('')

  // 检查未读消息
  const checkUnread = useCallback(async () => {
    try {
      const res = await apiFetch('/api/push/unread')
      if (!res.ok) return
      const data = await res.json()
      // 函数式更新 + id 浅比较 bail-out：未读没变就不换新引用，
      // 否则 60s 轮询每次都逼整棵 App 重渲染一次（体检 🟠-4）
      setUnread((prev) => {
        const next = Array.isArray(data.messages) ? data.messages : []
        if (next.length === prev.length && next.every((m, i) => m.id === prev[i].id)) return prev
        return next
      })
    } catch (e) {
      console.warn('[usePush] fetch unread failed:', e)
    }
  }, [])

  // 订阅推送
  const subscribe = useCallback(async () => {
    if (!supported || iosInstallNeeded) return false
    setSubscribeError('')
    // Safari 规范要求「先请求通知权限、再 subscribe」，且权限请求必须
    // 留在用户手势闭包内——故放在本函数第一个 await 之前（开关点击
    // onClick → 本调用是同步直进，手势链未断）。Chrome 系靠
    // userVisibleOnly 订阅代弹权限框侥幸可用，Safari 不先请求会直接
    // NotAllowedError——这是 iOS 推送链路最容易静默断掉的一环
    if (typeof Notification === 'undefined') return false
    let perm = Notification.permission
    if (perm === 'default') perm = await Notification.requestPermission()
    if (perm !== 'granted') {
      if (perm === 'denied') setPermissionDenied(true)
      return false
    }
    try {
      const vapidRes = await apiFetch('/api/push/vapid-key')
      if (!vapidRes.ok) {
        setSubscribeError('订阅请求没送达服务器（网络或令牌问题），刷新后再试')
        return false
      }
      const { key, enabled } = await vapidRes.json()
      // 后端未配置 VAPID 时 key 为 null / enabled 为 false，不尝试订阅
      if (!key || enabled === false) {
        setSubscribeError('服务器还没开通消息推送，小汐暂时递不了话')
        return false
      }

      // serviceWorker.ready 在「从未注册过 SW」的环境（如本地 dev 模式
      // 故意反注册）会永久 pending——点击后无声无息卡死。10s 超时兜底，
      // 把「卡死」变成一句看得见的解释
      const registration = await Promise.race([
        navigator.serviceWorker.ready,
        new Promise((_, reject) =>
          setTimeout(() => reject(Object.assign(new Error('serviceWorker.ready 超时'), { name: 'ReadyTimeout' })), 10_000)
        ),
      ])
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
        await subscription
          .unsubscribe()
          .catch((e) => console.warn('[usePush] cleanup subscribe failed:', e))
        setSubscribeError('订阅请求被服务器拒绝了（检查访问令牌/网络），刷新后再试')
        return false
      }

      setSubscribed(true)
      return true
    } catch (e) {
      // iOS 普通标签页 / 权限被系统撤回：subscribe 抛 NotAllowedError
      if (e?.name === 'NotAllowedError') setPermissionDenied(true)
      else if (e?.name === 'ReadyTimeout') {
        setSubscribeError('推送服务初始化超时（10 秒没等来 Service Worker），刷新页面再试')
      } else {
        setSubscribeError(`订阅失败（${e?.message || '未知原因'}），刷新页面再试一次`)
      }
      console.warn('[usePush] subscribe failed:', e)
      return false
    }
  }, [supported, iosInstallNeeded])

  // 取消订阅
  const unsubscribe = useCallback(async () => {
    try {
      const registration = await navigator.serviceWorker.ready
      const subscription = await registration.pushManager.getSubscription()
      if (subscription) {
        // 后端 DELETE /api/push/subscribe 接受 query 或 body 两种 endpoint。
        // 部分反代/CDN 会剥离 DELETE 请求的 body → 取消订阅静默失败，
        // 故优先走 query，body 一并发送作兼容兜底
        await apiFetch(
          `/api/push/subscribe?endpoint=${encodeURIComponent(subscription.endpoint)}`,
          {
            method: 'DELETE',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(subscription),
          }
        )
        await subscription.unsubscribe()
      }
      setSubscribed(false)
    } catch (e) {
      console.warn('[usePush] unsubscribe failed:', e)
    }
  }, [])

  // 标记已读（乐观更新 UI，再同步后端）。返回同步是否成功：
  // 失败时调用方（App.jsx）回滚 injectedRef，让下一轮 60s 轮询
  // 重新拉到该未读并重试 markRead——否则消息在后端永久挂未读，
  // 挤占 LIMIT 10 的未读名额，挤掉更新的主动消息
  const markRead = useCallback(async (ids) => {
    if (!Array.isArray(ids) || ids.length === 0) return true
    setUnread((prev) => prev.filter((m) => !ids.includes(m.id)))
    try {
      const res = await apiFetch('/api/push/read', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message_ids: ids }),
      })
      return res.ok
    } catch (e) {
      console.warn('[usePush] markRead failed:', e)
      return false
    }
  }, [])

  // 挂载：拉未读 + 恢复浏览器已有订阅状态
  useEffect(() => {
    checkUnread()
    if (supported) {
      navigator.serviceWorker.ready
        .then((reg) => reg.pushManager.getSubscription())
        .then((sub) => setSubscribed(!!sub))
        .catch((e) => console.warn('[usePush] getSubscription failed:', e))
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
  // 用递归 setTimeout 而非 setInterval：慢网络下前一次 fetch 未完成时
  // 不会堆积并发请求，避免多响应竞态 + 带宽挤占。
  useEffect(() => {
    let timerId = null
    let unmounted = false

    const scheduleNext = () => {
      if (unmounted) return
      timerId = setTimeout(() => {
        checkUnread().finally(() => scheduleNext())
      }, 60_000)
    }
    scheduleNext()

    const onVisible = () => {
      if (!unmounted && document.visibilityState === 'visible') checkUnread()
    }
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      unmounted = true
      clearTimeout(timerId)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [checkUnread])

  return {
    supported,
    iosInstallNeeded,
    permissionDenied,
    subscribeError,
    subscribed,
    unread,
    subscribe,
    unsubscribe,
    markRead,
    checkUnread,
  }
}
