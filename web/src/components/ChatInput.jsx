import { useState, useRef } from 'react'
import Heart from './Heart'
import IconStop from '../assets/icons/IconStop'

const MAX_LEN = 2000  // 与后端 _MAX_MESSAGE_LENGTH 一致，物理阻断超长输入（否则 422 + 通用错误文案）

export default function ChatInput({ onSend, isStreaming, onStop }) {
  const [text, setText] = useState('')
  const [focused, setFocused] = useState(false)
  const inputRef = useRef(null)

  const handleSend = () => {
    const trimmed = text.trim()
    if (!trimmed || isStreaming) return
    onSend(trimmed)
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
      <div className={`flex items-center gap-2.5 rounded-full px-4 h-12 transition-all duration-300 ${
        focused
          ? 'bg-white/70 shadow-[0_2px_12px_rgba(242,120,159,0.14)]'
          : 'bg-white/40'
      } border border-white/50`}>
        <input
          ref={inputRef}
          type="text"
          value={text}
          maxLength={MAX_LEN}
          onChange={e => setText(e.target.value)}
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
