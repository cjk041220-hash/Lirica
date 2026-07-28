const CACHE_NAME = 'xiaoxi-room-v3'

self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    // 清理旧版本缓存，避免立绘等静态资源长期占用磁盘
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    ).then(() => clients.claim())
  )
})

self.addEventListener('fetch', (event) => {
  // 不缓存 API 请求
  if (event.request.url.includes('/api/')) return
  // 不缓存 POST 等非 GET 请求
  if (event.request.method !== 'GET') return

  // 导航请求（index.html）network-first：发版后用户总拿到最新 HTML，
  // HTML 引用的带 hash 资源名永远匹配当前构建——根治「发版必须手动
  // bump CACHE_NAME，否则老用户静默吃旧包」。失败回退缓存兜底离线。
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request)
        .then(response => {
          if (response.ok) {
            const clone = response.clone()
            caches.open(CACHE_NAME).then(cache => cache.put(event.request, clone))
          }
          return response
        })
        .catch(() =>
          caches.match(event.request).then(cached => cached || caches.match('/'))
        )
    )
    return
  }

  // 静态资源（带 hash 的构建产物 / 立绘）保持 cache-first
  event.respondWith(
    caches.match(event.request).then(cached => {
      return cached || fetch(event.request).then(response => {
        if (response.ok) {
          const clone = response.clone()
          caches.open(CACHE_NAME).then(cache => cache.put(event.request, clone))
        }
        return response
      })
    }).catch(() => caches.match('/'))
  )
})

// 推送通知
self.addEventListener('push', (event) => {
  // JSON 解析兜底：坏载荷曾让整个 handler 抛异常中断，
  // showNotification 与 postMessage 都不执行 → 静默丢推送
  let data = {}
  try {
    data = event.data ? event.data.json() : {}
  } catch {
    data = {}  // 走下方默认文案
  }
  const title = data.title || '小汐'
  const body = data.body || '小汐想和你说说话。'

  // 合并到单个 waitUntil 链：先通知所有窗口，再展示通知，避免 SW 提前终止丢失 postMessage
  event.waitUntil(
    Promise.all([
      clients.matchAll({ type: 'window' }).then(list =>
        list.forEach(client => client.postMessage({ type: 'PUSH_MESSAGE', data }))
      ),
      self.registration.showNotification(title, {
        body,
        icon: '/icon-192.png',
        badge: '/icon-192.png',
        vibrate: [100, 50, 100],
      }),
    ])
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(
    clients.matchAll({ type: 'window' }).then(list => {
      if (list.length > 0) {
        list[0].focus()
      } else {
        clients.openWindow('/')
      }
    })
  )
})
