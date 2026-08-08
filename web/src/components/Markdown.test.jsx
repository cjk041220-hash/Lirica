import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import Markdown from './Markdown'

// 体检 5.22：markdown 渲染——代码块/换行/列表/粗体，无原始语法露出
const MD = `# 标题
- 第一项
- **加粗项**

\`\`\`python
print("hi")
\`\`\`

1. 有序一
2. 有序二`

describe('Markdown 渲染', () => {
  it('列表/粗体/代码块/标题渲染为语义标签，无原始语法', () => {
    render(<Markdown>{MD}</Markdown>)
    expect(screen.getByRole('heading', { level: 1 })).toBeTruthy()
    expect(document.querySelectorAll('ul, ol').length).toBeGreaterThanOrEqual(2)
    expect(document.querySelectorAll('li').length).toBeGreaterThanOrEqual(4)
    expect(screen.getByText('加粗项').tagName).toBe('STRONG')
    expect(screen.getByText('print("hi")')).toBeTruthy()
    expect(document.querySelector('pre code')).toBeTruthy()
    // 无原始语法露出
    const txt = document.body.textContent
    expect(txt).not.toContain('**')
    expect(txt).not.toContain('```')
    expect(txt).not.toContain('## ')
  })

  it('普通换行文本照常渲染', () => {
    render(<Markdown>{'第一行\n第二行'}</Markdown>)
    expect(screen.getByText(/第一行/)).toBeTruthy()
  })
})
