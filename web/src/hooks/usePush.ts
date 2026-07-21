"use client";

import { useCallback, useEffect, useRef, useState } from "react";

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000";

/* ------------------------------------------------------------------ */
/*  类型                                                               */
/* ------------------------------------------------------------------ */

interface UnreadMessage {
  id: string;
  content: string;
  created_at: string;
}

interface PushState {
  /** 推送是否已启用（VAPID 密钥已配置） */
  enabled: boolean;
  /** 用户是否已订阅推送 */
  subscribed: boolean;
  /** 正在加载中 */
  loading: boolean;
  /** 未读的主动消息 */
  unreadMessages: UnreadMessage[];
}

/* ------------------------------------------------------------------ */
/*  工具函数                                                           */
/* ------------------------------------------------------------------ */

function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const rawData = atob(base64);
  return Uint8Array.from([...rawData].map((c) => c.charCodeAt(0)));
}

/* ------------------------------------------------------------------ */
/*  Hook                                                               */
/* ------------------------------------------------------------------ */

export function usePush() {
  const [state, setState] = useState<PushState>({
    enabled: false,
    subscribed: false,
    loading: true,
    unreadMessages: [],
  });

  const vapidKeyRef = useRef<string>("");
  const subscriptionRef = useRef<PushSubscription | null>(null);

  /* ── 初始化：获取 VAPID 公钥 + 注册 Service Worker ───── */
  useEffect(() => {
    let cancelled = false;

    async function init() {
      // 检查浏览器是否支持
      if (!("serviceWorker" in navigator) || !("PushManager" in window)) {
        setState((s) => ({ ...s, enabled: false, loading: false }));
        return;
      }

      try {
        // 获取 VAPID 公钥
        const res = await fetch(`${API_BASE}/api/push/vapid-key`);
        const data = await res.json();

        if (!data.enabled || !data.key) {
          setState((s) => ({ ...s, enabled: false, loading: false }));
          return;
        }

        vapidKeyRef.current = data.key;

        // 注册 Service Worker
        const reg = await navigator.serviceWorker.register("/sw.js");

        // 检查是否已有订阅
        const sub = await reg.pushManager.getSubscription();
        subscriptionRef.current = sub;

        if (sub) {
          // 已有订阅，同步到后端
          await syncSubscription(sub);
        }

        if (!cancelled) {
          setState((s) => ({
            ...s,
            enabled: true,
            subscribed: !!sub,
            loading: false,
          }));
        }
      } catch (err) {
        console.warn("[usePush] 初始化失败:", err);
        if (!cancelled) {
          setState((s) => ({ ...s, loading: false }));
        }
      }
    }

    init();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ── 同步订阅信息到后端 ─────────────────────────────── */
  const syncSubscription = useCallback(async (sub: PushSubscription) => {
    const json = sub.toJSON();
    try {
      await fetch(`${API_BASE}/api/push/subscribe`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          endpoint: json.endpoint,
          keys: {
            p256dh: json.keys?.p256dh,
            auth: json.keys?.auth,
          },
        }),
      });
    } catch (err) {
      console.warn("[usePush] 订阅同步失败:", err);
    }
  }, []);

  /* ── 请求推送权限并订阅 ─────────────────────────────── */
  const subscribe = useCallback(async (): Promise<boolean> => {
    if (!vapidKeyRef.current) return false;

    try {
      // 请求通知权限
      const permission = await Notification.requestPermission();
      if (permission !== "granted") return false;

      // 订阅推送
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(vapidKeyRef.current),
      });

      subscriptionRef.current = sub;
      await syncSubscription(sub);

      setState((s) => ({ ...s, subscribed: true }));
      return true;
    } catch (err) {
      console.warn("[usePush] 订阅失败:", err);
      return false;
    }
  }, [syncSubscription]);

  /* ── 取消订阅 ───────────────────────────────────────── */
  const unsubscribe = useCallback(async () => {
    const sub = subscriptionRef.current;
    if (!sub) return;

    try {
      const json = sub.toJSON();
      await fetch(
        `${API_BASE}/api/push/subscribe?endpoint=${encodeURIComponent(json.endpoint || "")}`,
        { method: "DELETE" }
      );
      await sub.unsubscribe();
      subscriptionRef.current = null;
      setState((s) => ({ ...s, subscribed: false }));
    } catch (err) {
      console.warn("[usePush] 取消订阅失败:", err);
    }
  }, []);

  /* ── 拉取未读主动消息 ───────────────────────────────── */
  const fetchUnread = useCallback(async () => {
    try {
      const res = await fetch(`${API_BASE}/api/push/unread`);
      const data = await res.json();
      setState((s) => ({ ...s, unreadMessages: data.messages || [] }));
      return data.messages as UnreadMessage[];
    } catch {
      return [];
    }
  }, []);

  /* ── 标记消息已读 ───────────────────────────────────── */
  const markRead = useCallback(async (messageIds: string[]) => {
    try {
      await fetch(`${API_BASE}/api/push/read`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message_ids: messageIds }),
      });
      setState((s) => ({
        ...s,
        unreadMessages: s.unreadMessages.filter((m) => !messageIds.includes(m.id)),
      }));
    } catch (err) {
      console.warn("[usePush] 标记已读失败:", err);
    }
  }, []);

  return {
    ...state,
    subscribe,
    unsubscribe,
    fetchUnread,
    markRead,
  } as const;
}
