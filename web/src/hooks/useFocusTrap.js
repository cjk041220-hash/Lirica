import { useEffect, useRef } from 'react'

// 可聚焦元素选择器（排除 tabindex=-1 与禁用项）
const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),' +
  'textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'

/**
 * Dialog 焦点圈定 + 打开聚焦 + 关闭归还焦点（WCAG 4.1.2 / 2.4.3）。
 *
 * 用法：面板以「条件渲染」挂载（挂载=打开）时——
 *   const dialogRef = useFocusTrap()
 *   <div ref={dialogRef} tabIndex={-1} role="dialog" aria-modal ...>
 * 挂载时记录打开前的焦点元素并聚焦容器；Tab / Shift+Tab 在容器内循环；
 * 卸载时把焦点归还给打开前的元素（通常是触发它的顶栏按钮）。
 * 不处理 Esc——各面板已有 Esc 关闭 effect，避免重复监听。
 *
 * 用 capture 阶段监听，先于内部控件，确保焦点逃不出 dialog。
 */
export function useFocusTrap() {
  const ref = useRef(null)

  useEffect(() => {
    const node = ref.current
    if (!node) return

    // 打开前的焦点（点击顶栏按钮触发打开 → 该按钮即 activeElement）
    const previouslyFocused = document.activeElement

    const getFocusables = () =>
      [...node.querySelectorAll(FOCUSABLE)].filter(
        (el) => el.offsetParent !== null || el === document.activeElement,
      )

    // 打开：聚焦容器自身（tabIndex=-1），避免突兀地跳进某个控件；
    // 屏幕阅读器仍会因 role=dialog + aria-modal 播报对话框。
    node.focus()

    const onKeyDown = (e) => {
      if (e.key !== 'Tab') return
      const f = getFocusables()
      if (f.length === 0) {
        e.preventDefault()
        return
      }
      const first = f[0]
      const last = f[f.length - 1]
      const active = document.activeElement
      if (e.shiftKey) {
        // 在首元素 / 容器 / 外部 → 跳到末尾（圈定）
        if (active === first || !f.includes(active)) {
          e.preventDefault()
          last.focus()
        }
      } else if (active === last || !f.includes(active)) {
        // 在末元素 / 容器 / 外部 → 跳到开头（圈定）
        e.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown, true)
    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      // 关闭：焦点归还（仅当目标仍在 DOM）
      if (
        previouslyFocused &&
        typeof previouslyFocused.focus === 'function' &&
        document.contains(previouslyFocused)
      ) {
        previouslyFocused.focus()
      }
    }
  }, [])

  return ref
}
