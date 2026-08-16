import { lazy, memo, Suspense } from 'react'
import MapNavButtons from './MapNavButtons'
import TarotCards from './TarotCards'

// 小汐回复的 Markdown 渲染组件（修复 P2-5）：
// react-markdown 默认不渲染原始 HTML（无 dangerouslySetInnerHTML），
// 列表/路线分段/塔罗牌面不再挤成一团。用户消息保持纯文本。
const mdComponents = {
  p: ({ children }) => <p className="mb-1.5 last:mb-0">{children}</p>,
  ul: ({ children }) => <ul className="list-disc pl-4 mb-1.5 space-y-0.5 last:mb-0">{children}</ul>,
  ol: ({ children }) => (
    <ol className="list-decimal pl-4 mb-1.5 space-y-0.5 last:mb-0">{children}</ol>
  ),
  li: ({ children }) => <li className="leading-[1.65]">{children}</li>,
  strong: ({ children }) => <strong className="font-medium text-plum">{children}</strong>,
  em: ({ children }) => <em className="italic">{children}</em>,
  h1: ({ children }) => <p className="font-medium mb-1.5">{children}</p>,
  h2: ({ children }) => <p className="font-medium mb-1.5">{children}</p>,
  h3: ({ children }) => <p className="font-medium mb-1">{children}</p>,
  blockquote: ({ children }) => (
    <blockquote className="border-l-2 border-strawberry/30 pl-2 opacity-75 mb-1.5 last:mb-0">
      {children}
    </blockquote>
  ),
  code: ({ children }) => (
    <code className="bg-plum/5 rounded px-1 py-0.5 text-[13px]">{children}</code>
  ),
  pre: ({ children }) => (
    <pre className="bg-plum/5 rounded-lg p-2.5 mb-1.5 overflow-x-auto text-[13px] last:mb-0">
      {children}
    </pre>
  ),
  hr: () => <hr className="border-plum/10 my-2" />,
  a: ({ href, title, children }) => (
    <a
      href={href}
      title={title}
      target="_blank"
      rel="noopener noreferrer"
      className="underline decoration-strawberry/40 hover:decoration-strawberry/80 text-strawberry"
    >
      {children}
    </a>
  ),
  table: ({ children }) => (
    <div className="overflow-x-auto mb-1.5 last:mb-0">
      <table className="border-collapse text-[13px]">{children}</table>
    </div>
  ),
  th: ({ children }) => (
    <th className="border border-plum/10 px-2 py-1 bg-plum/3 text-left font-medium">{children}</th>
  ),
  td: ({ children }) => <td className="border border-plum/10 px-2 py-1">{children}</td>,
}

// 懒加载：react-markdown 生态按需拉取（见 Markdown.jsx）
const Markdown = lazy(() => import('./Markdown'))

// memo（评审 F1）：流式期间 useChat 每 40ms flush 触发 messages.map
// 全量重渲染——非流式条目的 message 引用在 flush 时保持稳定
//（prev.map 只替换流式那条），浅比较 memo 让历史气泡的
// react-markdown 解析全部 bail-out，只剩流式那条真渲染。
// 唯一 prop 是 message 对象，mdComponents 是模块级常量，无内联
// 函数 props，memo 语义安全。
function MessageBubble({ message }) {
  const { role, content, type } = message

  if (type === 'tool_hint') {
    return (
      <div className="cv-bubble animate-fade-in-up py-1.5 flex items-start gap-2 pl-1">
        <div className="w-1 h-1 rounded-full bg-strawberry/40 mt-1.5 shrink-0" />
        <div className="min-w-0">
          <p className="text-[11px] text-plum/60 italic">{content}</p>
          {message.output && (
            <details className="mt-0.5">
              <summary className="text-[10px] text-plum/55 cursor-pointer select-none hover:text-plum/75">
                看看找到了什么
              </summary>
              <p className="text-[10px] text-plum/60 leading-relaxed whitespace-pre-wrap break-words mt-1 bg-plum/3 rounded-lg px-2 py-1.5 max-h-32 overflow-y-auto">
                {message.output}
              </p>
            </details>
          )}
          {/* 塔罗可视化：tarot 数据合法才渲染（isValidTarot 已挡脏数据）；
             无数据时回退纯文本折叠块，协议行为不变 */}
          {message.tarot && <TarotCards tarot={message.tarot} />}
          {/* 地图联动：nav 合法才渲染（buildNavTargets 内部校验，null 不出现） */}
          {message.nav && <MapNavButtons nav={message.nav} />}
        </div>
      </div>
    )
  }

  if (role === 'user') {
    return (
      // 方向感入场（商业级 1.4）：用户的话从右侧滑入
      <div className="cv-bubble flex justify-end animate-fade-in-right">
        {/* 无 backdrop-blur-sm（2026-08-09 卡顿修复）：气泡嵌在滚动容器里，
            列表每滚一帧每个可见气泡的模糊都要对背后内容重采样——滚动路径上
            最贵的操作。面板底已是近不透明奶油色，微差的不透明度足够分层 */}
        <div className="max-w-[75%] bg-white/55 rounded-[18px] rounded-br-[6px] px-4 py-2.5 shadow-[0_1px_4px_rgba(110,74,86,0.05)]">
          {/* break-words：whitespace-pre-wrap 只在空格/换行处折行，连续
              长串（长链接、无空格粘贴）会横向撑破气泡 */}
          <p className="text-[15px] leading-[1.7] text-plum whitespace-pre-wrap break-words">{content}</p>
        </div>
      </div>
    )
  }

  // v21：独处消息的判定扩展到档案回放路径——注入的消息带 type='proactive'，
  // 回放的 solo 消息（msg_type='solo'，来自 /api/sessions/{id}/messages）走
  // 同样的弱化样式 + 时间戳，让「小汐的独处时间线」读起来像日记
  const isProactive = type === 'proactive' || message.msg_type === 'solo'
  // 时间戳 chip：只有后端带 created_at（"YYYY-MM-DD HH:MM"）的独处消息
  // 才有——[5:16] 切出 "MM-DD HH:MM"，她不在的那段时间一目了然
  const timeChip = isProactive && message.created_at
    ? String(message.created_at).slice(5, 16)
    : ''
  return (
    // 方向感入场（商业级 1.4）：小汐的话从左侧滑入
    <div className="cv-bubble flex animate-fade-in-left">
      <div className="flex gap-2.5 max-w-[85%]">
        <div
          className={`w-[2.5px] rounded-full shrink-0 my-1 ${isProactive ? 'bg-strawberry/20' : 'bg-strawberry/45'}`}
        />
        <div
          className={`text-[15px] leading-[1.75] py-0.5 break-words ${isProactive ? 'text-plum/40' : 'text-plum/85'}`}
        >
          {timeChip && <p className="text-[10px] text-plum/30 mb-0.5">{timeChip}</p>}
          {isProactive ? (
            <p className="whitespace-pre-wrap break-words">{content}</p>
          ) : (
            <Suspense fallback={null}>
              <Markdown components={mdComponents}>{content || ''}</Markdown>
            </Suspense>
          )}
          {message.streaming && (
            <span className="inline-block w-[3px] h-[14px] bg-strawberry/70 ml-1 rounded-full animate-shimmer align-middle" />
          )}
        </div>
      </div>
    </div>
  )
}

export default memo(MessageBubble)
