import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// 展示体「站酷快乐体」自托管（体检 T1-3 第二阶段，回收 B7 的 CSP 外链
// 白名单）：fontsource 分片包（OFL 协议），93 个 unicode-range 子集，
// 浏览器只下载页面用到的字形。此前从 Google Fonts 外链——隐私陪伴
// 应用每次加载都把 IP 送给 Google，且外链字体进不了同源缓存，破坏
// PWA 离线叙事。
import '@fontsource/zcool-kuaile'
import './index.css'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary.jsx'
import { initResilientStorage } from './utils/storage'

// 启动时初始化多层容灾存储自愈
initResilientStorage().catch(() => {})

// 标签页隐藏 → 暂停全部 CSS 动画（评审 F8，配合 index.css 的
// html.anim-paused 规则）：后台标签页不再白烧 GPU/电量，
// 回前台从暂停帧继续。usePresence 的 idle 判定是另一套（立绘状态），
// 这里只管动画播放态，职责不重叠。
document.addEventListener('visibilitychange', () => {
  document.documentElement.classList.toggle('anim-paused', document.hidden)
})

// Service Worker 只在生产构建注册：sw.js 的缓存策略会干扰开发模式
// HMR（改动的模块命中旧缓存，改了不生效），且注册错误不该被静默吞掉。
// dev 环境顺带反注册历史残留的 SW，避免上一次 build 的缓存继续作怪。
if ('serviceWorker' in navigator) {
  if (import.meta.env.PROD) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('/sw.js').catch((err) => console.warn('[sw] 注册失败:', err))
    })
  } else {
    navigator.serviceWorker
      .getRegistrations()
      .then((regs) => regs.forEach((r) => r.unregister()))
      .catch(() => {})
  }
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>
)

// 启动页谢幕（2026-08-09 商业级 3.1）：首帧提交后淡出移除。
// rAF 保证 #root 已画出第一帧再揭盖；transitionend 未触发的环境
//（reduced-motion 把过渡归零→立即触发，但保险起见）用超时兜底
requestAnimationFrame(() => {
  const splash = document.getElementById('splash')
  if (!splash) return
  splash.classList.add('hide')
  splash.addEventListener('transitionend', () => splash.remove(), { once: true })
  setTimeout(() => splash.remove(), 600)
})
