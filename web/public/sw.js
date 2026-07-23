const CACHE_NAME = 'xiaoxi-room-v2'

self.addEventListener('install', (event) => {
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
  const data = event.data ? event.data.json() : {}
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
