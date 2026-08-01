import { useCallback, useEffect, useState } from 'react'

// 可聚焦元素选择器（排除 tabindex=-1 与禁用项）
const FOCUSABLE =
  'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),' +
  'textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'

// 嵌套层级栈（模块级，按挂载顺序）：多层 dialog 共存时（日历面板 →
// 日期详情 sheet → 记/改事件 sheet），只有「包含当前焦点的最顶层 trap」
// 处理 Tab，外层让路——否则外层 trap 会把焦点圈回底层面板，焦点逃出
// 内层 sheet（体检 F-5）。面板互斥、sheet 嵌套，栈模型恰好贴合。
const trapStack = []

/**
 * Dialog 焦点圈定 + 打开聚焦 + 关闭归还焦点（WCAG 4.1.2 / 2.4.3）。
 *
 * 用法：返回 callback ref，挂到 role="dialog" aria-modal 容器：
 *   const dialogRef = useFocusTrap()
 *   <div ref={dialogRef} tabIndex={-1} role="dialog" aria-modal ...>
 * callback ref 使「条件渲染 = 打开」的容器（如日历的日期详情 sheet）
 * 也能在挂载瞬间激活陷阱、卸载瞬间归还焦点——固定 deps [] 的旧实现
 * 对延迟挂载的容器失效（体检 F-5 的结构性根因）。
 *
 * 挂载时记录打开前的焦点元素并聚焦容器；Tab / Shift+Tab 在容器内循环；
 * 卸载时把焦点归还给打开前的元素（通常是触发它的顶栏按钮）。
 * 不处理 Esc——各面板已有 Esc 关闭 effect，避免重复监听。
 *
 * 用 capture 阶段监听，先于内部控件，确保焦点逃不出 dialog。
 */
export function useFocusTrap() {
  const [node, setNode] = useState(null)
  const ref = useCallback((n) => setNode(n), [])

  useEffect(() => {
    if (!node) return

    // 打开前的焦点（点击顶栏按钮触发打开 → 该按钮即 activeElement）
    const previouslyFocused = document.activeElement
    const entry = { node }
    trapStack.push(entry)

    const getFocusables = () =>
      [...node.querySelectorAll(FOCUSABLE)].filter(
        (el) => el.offsetParent !== null || el === document.activeElement,
      )

    // 打开：聚焦容器自身（tabIndex=-1），避免突兀地跳进某个控件；
    // 屏幕阅读器仍会因 role=dialog + aria-modal 播报对话框。
    node.focus()

    const onKeyDown = (e) => {
      if (e.key !== 'Tab') return
      // 嵌套让路：包含当前焦点的更顶层 trap 存在时，本层不处理
      const active = document.activeElement
      const top = [...trapStack]
        .reverse()
        .find((t) => t.node === active || t.node.contains(active))
      if (top && top !== entry) return

      const f = getFocusables()
      if (f.length === 0) {
        e.preventDefault()
        return
      }
      const first = f[0]
      const last = f[f.length - 1]
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
      const idx = trapStack.indexOf(entry)
      if (idx >= 0) trapStack.splice(idx, 1)
      // 关闭：焦点归还（仅当目标仍在 DOM）
      if (
        previouslyFocused &&
        typeof previouslyFocused.focus === 'function' &&
        document.contains(previouslyFocused)
      ) {
        previouslyFocused.focus()
      }
    }
  }, [node])

  return ref
}
