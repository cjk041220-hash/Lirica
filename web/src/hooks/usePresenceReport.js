import { useEffect, useRef, useCallback } from 'react'
import { apiFetch } from '../api/client'

// 在线状态上报（2026-08-09 era 会话路由的数据源）：
//
// 后端据此知道冷冷「在不在 / 活跃在哪个会话」，决定主动消息追加进
// 当前对话（在线）还是新开/追加「小汐的独处时间线」（离线）。
// 用户裁定：关页面即离线 + 45 分钟兜底——
//   - pagehide（关闭/后台/移动端杀页）→ sendBeacon 上报 leaving，
//     后端立即判定离线；
//   - 挂载 / 回前台 / 每 2 分钟 / 会话切换 → 上报在线 + 当前会话 id
//     （2 分钟心跳维持 last_seen 新鲜，供手机杀进程等无信号场景的
//     45 分钟兜底判定）。
//
// 全部失败静默（console.warn），上报是旁路，不阻塞聊天主流程。
const REPORT_INTERVAL = 2 * 60 * 1000

export function usePresenceReport(sessionId) {
  // 回调与事件里读最新 sessionId（不依赖闭包捕获的过期值）
  const sessionIdRef = useRef(sessionId)
  sessionIdRef.current = sessionId

  const report = useCallback(async (sid, leaving) => {
    try {
      await apiFetch('/api/presence', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: sid || '', leaving }),
      })
    } catch (e) {
      console.warn('[usePresenceReport] report failed:', e)
    }
  }, [])

  useEffect(() => {
    // 页面关闭/隐藏：sendBeacon 尽力上报 leaving。Content-Type 必须
    // 显式 application/json——sendBeacon 默认 text/plain，FastAPI 会
    // 按 content-type 拒解 JSON body（415）
    const onPageHide = () => {
      try {
        const payload = JSON.stringify({
          session_id: sessionIdRef.current || '',
          leaving: true,
        })
        navigator.sendBeacon?.(
          '/api/presence',
          new Blob([payload], { type: 'application/json' })
        )
      } catch (e) {
        console.warn('[usePresenceReport] beacon failed:', e)
      }
    }

    const reportAlive = () => report(sessionIdRef.current, false)

    // 挂载即上报：era 路由第一时间知道「我回来了」
    reportAlive()
    // 常驻心跳：页面一直开着（几小时无交互）也要维持 last_seen 新鲜，
    // 否则会被 45 分钟兜底误判离线、新开一个她就在眼前的 era 窗口
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') reportAlive()
    }, REPORT_INTERVAL)
    const onVisible = () => {
      if (document.visibilityState === 'visible') reportAlive()
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('pagehide', onPageHide)

    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('pagehide', onPageHide)
    }
  }, [report, sessionId])
}
