import { useCallback, useEffect, useRef } from 'react'

/**
 * 主动消息已读门控（P1-15）：后台标签页不标已读——用户没看到的消息
 * 不该算已读。可见时立即标；隐藏时挂起，回到前台补发；补发失败回滚
 * 到挂起队列，下次可见再试。
 *
 * @param {(ids: number[]) => Promise<boolean>} markRead 后端已读同步
 * @returns {(ids: number[]) => Promise<boolean>} 门控后的已读调用
 */
export function useDeferredMarkRead(markRead) {
  const pendingRef = useRef([])
  const markReadRef = useRef(markRead)
  markReadRef.current = markRead

  const deferMarkRead = useCallback((ids) => {
    if (!Array.isArray(ids) || ids.length === 0) return Promise.resolve(true)
    if (document.visibilityState === 'visible') return markReadRef.current(ids)
    pendingRef.current.push(...ids)
    return Promise.resolve(true)
  }, [])

  // 页面回到前台 → 补发挂起的已读
  useEffect(() => {
    const flush = () => {
      if (document.visibilityState !== 'visible') return
      const ids = pendingRef.current
      if (ids.length === 0) return
      pendingRef.current = []
      markReadRef.current(ids).then(ok => {
        if (!ok) pendingRef.current.push(...ids)  // 失败回滚，下次可见再试
      })
    }
    document.addEventListener('visibilitychange', flush)
    return () => document.removeEventListener('visibilitychange', flush)
  }, [])

  return deferMarkRead
}