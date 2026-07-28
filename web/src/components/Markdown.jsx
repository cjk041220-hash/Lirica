import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// 单独成文件，供 MessageBubble 用 React.lazy 动态导入：
// 把 react-markdown + remark-gfm 整条生态（micromark/mdast/remark…）
// 移出主 bundle，仅在首次渲染助手 Markdown 气泡时按需加载。
export default function Markdown({ components, children }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
      {children}
    </ReactMarkdown>
  )
}
