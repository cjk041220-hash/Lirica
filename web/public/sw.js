const CACHE_NAME = 'xiaoxi-room-v4'

self.addEventListener('install', () => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    // 清掉旧版本缓存（v3 等）：既退役手动 bump，也回收旧 /assets/* 孤儿，
    // 让 v4 接管后立绘/资源从网络重取一份新鲜的
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
    ).then(() => clients.claim())
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  // 不缓存 API 请求
  if (req.url.includes('/api/')) return
  // 不缓存 POST 等非 GET 请求
  if (req.method !== 'GET') return

  const path = new URL(req.url).pathname

  // 导航请求（index.html）network-first：发版后用户总拿到最新 HTML，
  // HTML 引用的带 hash 资源名永远匹配当前构建。失败回退缓存兜底离线。
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then(response => {
          if (response.ok) {
            const clone = response.clone()
            caches.open(CACHE_NAME).then(cache => cache.put(req, clone))
          }
          return response
        })
        .catch(() =>
          caches.match(req).then(cached => cached || caches.match('/'))
        )
    )
    return
  }

  // 不可变构建产物（vite 哈希，内容变则 URL 变）→ cache-first，免无谓回源
  if (path.startsWith('/assets/')) {
    event.respondWith(
      caches.open(CACHE_NAME).then(cache =>
        cache.match(req).then(cached =>
          cached || fetch(req).then(response => {
            if (response.ok) {
              const clone = response.clone()
              cache.put(req, clone)
            }
            return response
          })
        )
      )
    )
    return
  }

  // 可变静态（立绘 / 图标 / manifest 等无哈希资源）→ stale-while-revalidate：
  // 有缓存先立即返回，同时后台回源并用新内容「原地覆盖」同 URL 旧缓存——
  // 故立绘更新无需改文件名、无需手动 bump，最迟一次刷新后即对老用户生效。
  // revalidate 用 event.waitUntil 保活，避免 SW 在回源完成前被回收。
  const cacheP = caches.open(CACHE_NAME)
  const netP = fetch(req)
  event.waitUntil(
    netP.then(response => {
      if (response && response.ok) {
        const clone = response.clone()
        return cacheP.then(cache => cache.put(req, clone))
      }
      return undefined
    }).catch(() => undefined)
  )
  event.respondWith(
    cacheP.then(cache => cache.match(req)).then(cached =>
      cached || netP.catch(() => caches.match('/'))
    )
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
