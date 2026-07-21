"use client";

import { Send } from "lucide-react";

export default function LivingPanel() {
  return (
    <div
      className="absolute bottom-0 left-0 right-0 z-50 w-full h-[40dvh] backdrop-blur-md rounded-t-3xl flex flex-col"
      style={{
        background: "var(--glass-bg)",
        borderTop: "1px solid var(--glass-border)",
      }}
    >
      {/* ── 聊天展示区 ── */}
      <div className="flex-1 overflow-y-auto px-4 pt-5 pb-2 space-y-4">
        {/* 小汐气泡：无背景 + 左侧茶墨色指示线 */}
        <div className="flex items-start gap-3 max-w-[85%]">
          <div
            className="w-[3px] self-stretch rounded-full flex-shrink-0 mt-1"
            style={{ background: "var(--color-ink-tea)", opacity: 0.4 }}
          />
          <p
            className="text-[15px] leading-relaxed"
            style={{ color: "var(--color-ink-tea)" }}
          >
            冷冷，你回来啦。映海今天很安静哦。
          </p>
        </div>

        {/* 小汐气泡 2 */}
        <div className="flex items-start gap-3 max-w-[85%]">
          <div
            className="w-[3px] self-stretch rounded-full flex-shrink-0 mt-1"
            style={{ background: "var(--color-ink-tea)", opacity: 0.4 }}
          />
          <p
            className="text-[15px] leading-relaxed"
            style={{ color: "var(--color-ink-tea)" }}
          >
            小汐一直在等你呢~ 今天外面天气好像不错 ✨
          </p>
        </div>

        {/* 冷冷气泡：微弱白底 + 右对齐 */}
        <div className="flex justify-end">
          <div className="max-w-[75%] bg-white/30 rounded-2xl px-4 py-2.5">
            <p
              className="text-[15px] leading-relaxed"
              style={{ color: "var(--color-ink-tea)" }}
            >
              小汐，我今天好累呀……
            </p>
          </div>
        </div>

        {/* 小汐气泡 3 */}
        <div className="flex items-start gap-3 max-w-[85%]">
          <div
            className="w-[3px] self-stretch rounded-full flex-shrink-0 mt-1"
            style={{ background: "var(--color-ink-tea)", opacity: 0.4 }}
          />
          <p
            className="text-[15px] leading-relaxed"
            style={{ color: "var(--color-ink-tea)" }}
          >
            哎呀，辛苦啦……要不要靠在映海的岸边休息一会儿？小汐去给你找点好听的音乐 🐋
          </p>
        </div>
      </div>

      {/* ── 输入框区 ── */}
      <div className="px-4 pb-5 pt-2">
        <div className="flex items-center gap-3">
          {/* 极简圆角输入框 */}
          <input
            type="text"
            placeholder="和冷冷说说话……"
            readOnly
            className="flex-1 h-11 rounded-full px-5 text-[15px] outline-none placeholder:text-ink-tea/30"
            style={{
              background: "rgba(255, 255, 255, 0.25)",
              color: "var(--color-ink-tea)",
              boxShadow: "inset 0 1px 3px rgba(0,0,0,0.04)",
            }}
          />

          {/* 发送按钮：治愈水色圆形 */}
          <button
            className="flex items-center justify-center w-11 h-11 rounded-full flex-shrink-0 transition-opacity hover:opacity-80"
            style={{ background: "var(--color-healing-aqua)" }}
          >
            <Send size={18} color="#FCFAF8" />
          </button>
        </div>
      </div>
    </div>
  );
}
