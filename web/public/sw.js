/// <reference lib="webworker" />

// 小汐 Service Worker — 接收 Web Push 通知并展示
// iOS Safari 16.4+ / Chrome / Firefox 均支持

/** @type {ServiceWorkerGlobalScope} */
const sw = /** @type {ServiceWorkerGlobalScope} */ (self);

// ── Push 事件：收到推送时显示通知 ──────────────────────────
sw.addEventListener("push", (event) => {
  if (!event.data) return;

  let payload;
  try {
    payload = event.data.json();
  } catch {
    payload = { title: "小汐", body: event.data.text() };
  }

  const { title, body, icon, badge, url } = payload;

  event.waitUntil(
    sw.registration.showNotification(title, {
      body: body || "",
      icon: icon || "/icon-192.png",
      badge: badge || "/badge-72.png",
      data: { url: url || "/" },
      // iOS 特性：自动播放默认通知音
      // vibrate 在 iOS 上无效果但在 Android 上有用
      vibrate: [100, 50, 100],
    })
  );
});

// ── Notification Click：点击通知时打开对应页面 ──────────────
sw.addEventListener("notificationclick", (event) => {
  event.notification.close();

  const url = event.notification.data?.url || "/";

  event.waitUntil(
    sw.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clients) => {
      // 如果已有打开的窗口，聚焦它
      for (const client of clients) {
        if (client.url.includes(url) && "focus" in client) {
          return client.focus();
        }
      }
      // 否则打开新窗口
      if (sw.clients.openWindow) {
        return sw.clients.openWindow(url);
      }
    })
  );
});

// ── Install：确保 SW 立即激活 ───────────────────────────────
sw.addEventListener("install", () => {
  sw.skipWaiting();
});

sw.addEventListener("activate", (event) => {
  event.waitUntil(sw.clients.claim());
});
