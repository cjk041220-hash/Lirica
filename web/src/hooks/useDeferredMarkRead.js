import { useCallback, useEffect, useRef } from 'react'

/**
 * 主动消息已读门控（P1-15）：后台标签页不标已读——用户没看到的消息
 * 不该算已读。可见时立即标；隐藏时挂起，回到前台补发；补发失败回滚
 * 到挂起队列，下次可见再试。
 *
 * 关键不变量（2026-08-09 P1 修复）：返回的 promise 只在「真实 ack 完成」
 * 后才 settle——隐藏期间一直 pending。调用方（App.jsx）在 promise settle
 * 后把 id 移出防重集合；若隐藏时假成功 resolve(true)，id 会被提前移出，
 * 后台 60s 轮询拉到仍未读的消息时会重复注入到聊天流（且每来一条新消息
 * 复利式放大）。失败也不 settle：重试期间防重标记必须保留。
 *
 * @param {(ids: number[]) => Promise<boolean>} markRead 后端已读同步
 * @returns {(ids: number[]) => Promise<boolean>} 门控后的已读调用
 */
export function useDeferredMarkRead(markRead) {
  // 挂起队列元素为 { ids, resolve }：补发成功 resolve(true)，失败保持 pending 重试
  const pendingRef = useRef([])
  const markReadRef = useRef(markRead)
  markReadRef.current = markRead

  const deferMarkRead = useCallback((ids) => {
    if (!Array.isArray(ids) || ids.length === 0) return Promise.resolve(true)
    if (document.visibilityState === 'visible') return markReadRef.current(ids)
    // 隐藏：不标已读，也不假成功——挂起 promise 到回前台真实 ack 完成
    return new Promise((resolve) => {
      pendingRef.current.push({ ids, resolve })
    })
  }, [])

  // 页面回到前台 → 补发挂起的已读
  useEffect(() => {
    const flush = () => {
      if (document.visibilityState !== 'visible') return
      const batch = pendingRef.current
      if (batch.length === 0) return
      pendingRef.current = []
      batch.forEach(({ ids, resolve }) => {
        markReadRef.current(ids).then(
          (ok) => {
            if (ok) resolve(true)
            // 失败 → 回队保持 pending，下次可见再试（防重标记不能提前解除）
            else pendingRef.current.push({ ids, resolve })
          },
          // 网络异常等同失败：回队重试，不让 promise reject 炸到调用方
          () => pendingRef.current.push({ ids, resolve })
        )
      })
    }
    document.addEventListener('visibilitychange', flush)
    return () => document.removeEventListener('visibilitychange', flush)
  }, [])

  return deferMarkRead
}
