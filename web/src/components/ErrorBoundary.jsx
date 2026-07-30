import { Component } from 'react'

/**
 * 全局错误边界：任何渲染异常（markdown 组件抛错、立绘映射意外等）
 * 在没有边界时会整页白屏。兜底为友好的角色化文案 + 刷新按钮。
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false }
  }

  static getDerivedStateFromError() {
    return { hasError: true }
  }

  componentDidCatch(error, info) {
    console.error('[ErrorBoundary]', error, info)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="h-full w-full flex flex-col items-center justify-center gap-3 bg-cream text-plum p-8 text-center">
          <div className="text-5xl">🐋</div>
          <p className="text-base">呜……小汐晕倒了。</p>
          <p className="text-sm text-plum/50">刷新一下页面再试试好不好？</p>
          <button
            onClick={() => window.location.reload()}
            className="mt-2 px-5 py-2 rounded-full bg-strawberry text-white text-sm shadow-sm"
          >
            刷新页面
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
