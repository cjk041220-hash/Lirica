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

// Service Worker 只在生产构建注册：sw.js 的缓存策略会干扰开发模式
// HMR（改动的模块命中旧缓存，改了不生效），且注册错误不该被静默吞掉。
// dev 环境顺带反注册历史残留的 SW，避免上一次 build 的缓存继续作怪。
if ('serviceWorker' in navigator) {
  if (import.meta.env.PROD) {
    window.addEventListener('load', () => {
      navigator.serviceWorker
        .register('/sw.js')
        .catch(err => console.warn('[sw] 注册失败:', err))
    })
  } else {
    navigator.serviceWorker
      .getRegistrations()
      .then(regs => regs.forEach(r => r.unregister()))
      .catch(() => {})
  }
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
