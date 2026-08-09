import { useState, useRef } from 'react'
import Heart from './Heart'
import IconStop from '../assets/icons/IconStop'

const MAX_LEN = 2000 // 与后端 _MAX_MESSAGE_LENGTH 一致，物理阻断超长输入（否则 422 + 通用错误文案）

export default function ChatInput({ onSend, isStreaming, onStop }) {
  const [text, setText] = useState('')
  const [focused, setFocused] = useState(false)
  // 发送微反馈（2026-08-09 商业级 2.4）：发送瞬间从按钮绽开三颗
  // 小爱心向左上飘（消息飞去的方向），强化「话说出去了」的确认感
  const [sendBurst, setSendBurst] = useState([])
  const inputRef = useRef(null)

  const triggerSendBurst = () => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const now = Date.now()
    setSendBurst(
      Array.from({ length: 3 }, (_, i) => ({
        id: now + i,
        dx: -10 - Math.random() * 22,
        dy: -(22 + Math.random() * 16),
        r: (Math.random() - 0.5) * 40,
        size: 6 + Math.random() * 3,
        delay: i * 0.05,
      }))
    )
  }

  const handleSend = () => {
    const trimmed = text.trim()
    if (!trimmed || isStreaming) return
    onSend(trimmed)
    triggerSendBurst()
    setText('')
    inputRef.current?.focus()
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div className="px-4 pb-3 pt-1.5 shrink-0">
      {text.length >= MAX_LEN - 100 && (
        <div className="text-right text-[10px] text-plum/40 pb-0.5 pr-2">
          {text.length}/{MAX_LEN}
        </div>
      )}
      <div
        className={`relative flex items-center gap-2.5 rounded-full px-4 h-12 transition-all duration-300 ${
          focused ? 'bg-white/70 shadow-[0_2px_12px_rgba(242,120,159,0.14)]' : 'bg-white/40'
        } border border-white/50`}
      >
        {/* 发送爱心迸发（onAnimationEnd 自清理） */}
        {sendBurst.length > 0 && (
          <span
            className="absolute right-3 top-1/2 pointer-events-none"
            onAnimationEnd={() => setSendBurst([])}
            aria-hidden
          >
            {sendBurst.map((h) => (
              <span
                key={h.id}
                className="heart-burst absolute text-strawberry"
                style={{
                  '--dx': `${h.dx.toFixed(1)}px`,
                  '--dy': `${h.dy.toFixed(1)}px`,
                  '--r': `${h.r.toFixed(1)}deg`,
                  animation: `heartBurst 0.8s cubic-bezier(0.16, 1, 0.3, 1) ${h.delay}s backwards`,
                }}
              >
                <Heart size={h.size} />
              </span>
            ))}
          </span>
        )}
        <input
          ref={inputRef}
          type="text"
          value={text}
          maxLength={MAX_LEN}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          placeholder="和小汐说说话……"
          /* 16px 是 iOS 聚焦不自动缩放页面的阈值（15px 会触发整页放大）；
             enterkeyhint 让软键盘回车键显示「发送」而非通用换行 */
          enterKeyHint="send"
          className="flex-1 bg-transparent text-[16px] text-plum placeholder:text-plum/25 outline-none leading-relaxed"
        />

        {isStreaming ? (
          <button
            onClick={onStop}
            className="btn-press w-9 h-9 rounded-full bg-plum/80 flex items-center justify-center shrink-0 shadow-sm"
            aria-label="停止"
          >
            <IconStop size={14} className="text-white" />
          </button>
        ) : (
          <button
            onClick={handleSend}
            disabled={!text.trim()}
            className={`btn-press w-9 h-9 rounded-full flex items-center justify-center shrink-0 shadow-sm transition-all duration-300 ${
              text.trim()
                ? 'bg-gradient-to-br from-strawberry to-raspberry text-white shadow-[0_2px_10px_rgba(194,71,123,0.35)]'
                : 'bg-plum/8 text-plum/20'
            }`}
            aria-label="发送"
          >
            <Heart size={15} />
          </button>
        )}
      </div>
    </div>
  )
}
