import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// 小汐回复的 Markdown 渲染组件（修复 P2-5）：
// react-markdown 默认不渲染原始 HTML（无 dangerouslySetInnerHTML），
// 列表/路线分段/塔罗牌面不再挤成一团。用户消息保持纯文本。
const mdComponents = {
  p: ({ children }) => <p className="mb-1.5 last:mb-0">{children}</p>,
  ul: ({ children }) => <ul className="list-disc pl-4 mb-1.5 space-y-0.5 last:mb-0">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal pl-4 mb-1.5 space-y-0.5 last:mb-0">{children}</ol>,
  li: ({ children }) => <li className="leading-[1.65]">{children}</li>,
  strong: ({ children }) => <strong className="font-medium text-ink-tea">{children}</strong>,
  em: ({ children }) => <em className="italic">{children}</em>,
  h1: ({ children }) => <p className="font-medium mb-1.5">{children}</p>,
  h2: ({ children }) => <p className="font-medium mb-1.5">{children}</p>,
  h3: ({ children }) => <p className="font-medium mb-1">{children}</p>,
  blockquote: ({ children }) => <blockquote className="border-l-2 border-healing-aqua/30 pl-2 opacity-75 mb-1.5 last:mb-0">{children}</blockquote>,
  code: ({ children }) => <code className="bg-ink-tea/5 rounded px-1 py-0.5 text-[13px]">{children}</code>,
  pre: ({ children }) => <pre className="bg-ink-tea/5 rounded-lg p-2.5 mb-1.5 overflow-x-auto text-[13px] last:mb-0">{children}</pre>,
  hr: () => <hr className="border-ink-tea/10 my-2" />,
  a: ({ href, title, children }) => (
    <a href={href} title={title} target="_blank" rel="noopener noreferrer"
       className="underline decoration-healing-aqua/40 hover:decoration-healing-aqua/80 text-healing-aqua">
      {children}
    </a>
  ),
  table: ({ children }) => (
    <div className="overflow-x-auto mb-1.5 last:mb-0">
      <table className="border-collapse text-[13px]">{children}</table>
    </div>
  ),
  th: ({ children }) => <th className="border border-ink-tea/10 px-2 py-1 bg-ink-tea/3 text-left font-medium">{children}</th>,
  td: ({ children }) => <td className="border border-ink-tea/10 px-2 py-1">{children}</td>,
}

export default function MessageBubble({ message }) {
  const { role, content, type } = message

  if (type === 'tool_hint') {
    return (
      <div className="animate-fade-in-up py-1.5 flex items-start gap-2 pl-1">
        <div className="w-1 h-1 rounded-full bg-healing-aqua/40 mt-1.5 shrink-0" />
        <div className="min-w-0">
          <p className="text-[11px] text-ink-tea/30 italic">{content}</p>
          {message.output && (
            <details className="mt-0.5">
              <summary className="text-[10px] text-ink-tea/25 cursor-pointer select-none hover:text-ink-tea/40">
                看看找到了什么
              </summary>
              <p className="text-[10px] text-ink-tea/35 leading-relaxed whitespace-pre-wrap mt-1 bg-ink-tea/3 rounded-lg px-2 py-1.5 max-h-32 overflow-y-auto">
                {message.output}
              </p>
            </details>
          )}
        </div>
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
        <div className={`text-[15px] leading-[1.75] py-0.5 ${isProactive ? 'text-ink-tea/40' : 'text-ink-tea/85'}`}>
          {isProactive ? (
            <p className="whitespace-pre-wrap">{content}</p>
          ) : (
            <ReactMarkdown remarkPlugins={[remarkGfm]} components={mdComponents}>
              {content || ''}
            </ReactMarkdown>
          )}
          {message.streaming && (
            <span className="inline-block w-[3px] h-[14px] bg-healing-aqua/70 ml-1 rounded-full animate-shimmer align-middle" />
          )}
        </div>
      </div>
    </div>
  )
}
