export default function MessageBubble({ message }) {
  const { role, content, type } = message

  if (type === 'tool_hint') {
    return (
      <div className="animate-fade-in-up py-1.5 flex items-center gap-2 pl-1">
        <div className="w-1 h-1 rounded-full bg-healing-aqua/40" />
        <p className="text-[11px] text-ink-tea/30 italic">{content}</p>
      </div>
    )
  }

  if (role === 'user') {
    return (
      <div className="flex justify-end animate-fade-in-up">
        <div className="max-w-[75%] bg-white/45 backdrop-blur-sm rounded-[18px] rounded-br-[6px] px-4 py-2.5 shadow-[0_1px_4px_rgba(92,84,77,0.04)]">
          <p className="text-[15px] leading-[1.7] text-ink-tea whitespace-pre-wrap">{content}</p>
        </div>
      </div>
    )
  }

  const isProactive = type === 'proactive'
  return (
    <div className="flex animate-fade-in-up">
      <div className="flex gap-2.5 max-w-[85%]">
        <div className={`w-[2.5px] rounded-full shrink-0 my-1 ${isProactive ? 'bg-healing-aqua/20' : 'bg-healing-aqua/45'}`} />
        <p className={`text-[15px] leading-[1.75] whitespace-pre-wrap py-0.5 ${isProactive ? 'text-ink-tea/40' : 'text-ink-tea/85'}`}>
          {content}
          {message.streaming && (
            <span className="inline-block w-[3px] h-[14px] bg-healing-aqua/70 ml-1 rounded-full animate-shimmer align-middle" />
          )}
        </p>
      </div>
    </div>
  )
}
