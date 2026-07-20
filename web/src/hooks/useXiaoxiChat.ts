"use client";

import { useCallback, useRef, useState } from "react";

/* ------------------------------------------------------------------ */
/*  类型                                                               */
/* ------------------------------------------------------------------ */

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

interface SseToolStart {
  type: "tool_start";
  name: string;
  input: unknown;
}

interface SseToolEnd {
  type: "tool_end";
  name: string;
  output: string;
}

interface SseContentChunk {
  content: string;
}

type SseEvent = SseContentChunk | SseToolStart | SseToolEnd;

const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8000";

/* ------------------------------------------------------------------ */
/*  Hook                                                               */
/* ------------------------------------------------------------------ */

export function useXiaoxiChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const sessionIdRef = useRef("");
  const abortRef = useRef<AbortController | null>(null);

  /* 发送消息并流式接收 */
  const sendMessage = useCallback(async (message: string, sessionId?: string) => {
    const text = message.trim();
    if (!text || isStreaming) return;

    // 维护 session_id
    if (sessionId) sessionIdRef.current = sessionId;

    // 1. 追加用户消息
    const userMsg: ChatMessage = { role: "user", content: text };
    setMessages((prev) => [...prev, userMsg]);

    // 2. 追加 AI 占位消息
    setMessages((prev) => [...prev, { role: "assistant", content: "" }]);

    setIsStreaming(true);

    const controller = new AbortController();
    abortRef.current = controller;

    try {
      const response = await fetch(`${API_BASE}/api/chat/stream`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          message: text,
          session_id: sessionIdRef.current,
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        const errorText = await response.text().catch(() => "");
        throw new Error(errorText || `HTTP ${response.status}`);
      }

      if (!response.body) {
        throw new Error("浏览器不支持 ReadableStream");
      }

      // 3. 读取 SSE 流
      const reader = response.body.getReader();
      const decoder = new TextDecoder("utf-8");
      let buffer = "";

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });

        // 按 SSE 双换行切分事件
        const parts = buffer.split("\n\n");
        // 最后一段可能不完整，留到下次
        buffer = parts.pop() ?? "";

        for (const part of parts) {
          const line = part.trim();
          if (!line.startsWith("data: ")) continue;

          const payload = line.slice(6).trim();

          // 流结束
          if (payload === "[DONE]") {
            setIsStreaming(false);
            return;
          }

          // 安全解析 JSON
          let event: SseEvent;
          try {
            event = JSON.parse(payload) as SseEvent;
          } catch {
            // JSON 解析失败 → 跳过本次
            continue;
          }

          // 屏蔽工具事件
          if ("type" in event && (event.type === "tool_start" || event.type === "tool_end")) {
            continue;
          }

          // 追加文本内容
          const chunk = (event as SseContentChunk).content;
          if (chunk) {
            setMessages((prev) => {
              const updated = [...prev];
              const last = updated[updated.length - 1];
              if (last && last.role === "assistant") {
                updated[updated.length - 1] = {
                  ...last,
                  content: last.content + chunk,
                };
              }
              return updated;
            });
          }
        }
      }

      // reader 正常结束（无 [DONE] 的情况）
      setIsStreaming(false);
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") {
        // 用户主动中止 — 保留已接收的部分
        setIsStreaming(false);
        return;
      }

      // 网络 / 其他错误 — 在最后一条消息中追加错误提示
      const errorMessage =
        err instanceof Error ? err.message : "未知错误";
      setMessages((prev) => {
        const updated = [...prev];
        const last = updated[updated.length - 1];
        if (last && last.role === "assistant") {
          updated[updated.length - 1] = {
            ...last,
            content:
              last.content +
              (last.content ? "\n\n" : "") +
              `（连接中断：${errorMessage}）`,
          };
        }
        return updated;
      });
      setIsStreaming(false);
    }
  }, [isStreaming]);

  /* 中止流 */
  const abort = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setIsStreaming(false);
  }, []);

  /* 清空消息 */
  const clearMessages = useCallback(() => {
    setMessages([]);
  }, []);

  return {
    messages,
    isStreaming,
    sendMessage,
    abort,
    clearMessages,
  } as const;
}
