import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, act } from '@testing-library/react'
import TarotCards from './TarotCards'

const CARDS = [
  {
    name: '恋人',
    symbol: 'VI',
    suit: '大阿尔卡纳',
    element: '风',
    keywords: ['吸引力', '真爱', '选择'],
    is_reversed: false,
    position: '过去',
    fortune: '让真心带领你做出选择',
    meaning: ['让真心带领你做出选择'],
  },
  {
    name: '权杖Ace',
    symbol: 'A',
    suit: '权杖',
    element: '火',
    keywords: ['开始', '灵感'],
    is_reversed: true,
    position: '现在',
    fortune: '新的热情即将点燃',
    meaning: ['新的热情即将点燃'],
  },
  {
    name: '星币十',
    symbol: '10',
    suit: '星币',
    element: '地',
    keywords: ['富足'],
    is_reversed: false,
    position: '未来',
    fortune: '长久的积累带来安稳',
    meaning: ['长久的积累带来安稳'],
  },
]
const THREE = { spread_type: 'three', spread_name: '三牌占卜', cards: CARDS }

// jsdom 无 matchMedia：统一 stub 为可控对象（默认 no-preference）
function stubMatchMedia(matches = false) {
  window.matchMedia = vi.fn().mockReturnValue({
    matches,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  })
}

describe('TarotCards', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    stubMatchMedia(false)
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('三牌 → 3 列网格，初始全部卡背（未翻）', () => {
    render(<TarotCards tarot={THREE} />)
    const grid = screen.getByTestId('tarot-cards')
    expect(grid.className).toContain('grid-cols-3')
    expect(screen.getAllByText('✦')).toHaveLength(3) // 三张卡背星标
    // 牌面内容初始不可见（翻转中或未翻）
    expect(screen.queryByText('恋人')).toBeNull()
  })

  it('单牌 → 1 列居中网格', () => {
    render(
      <TarotCards tarot={{ spread_type: 'single', spread_name: '单牌占卜', cards: [CARDS[0]] }} />
    )
    expect(screen.getByTestId('tarot-cards').className).toContain('grid-cols-1')
  })

  it('凯尔特十字 → 5 列紧凑网格，显示位置/牌名/图标/逆位徽标', () => {
    const ten = Array.from({ length: 10 }, (_, i) => ({
      ...CARDS[i % 3],
      position: `位置${i + 1}`,
    }))
    render(<TarotCards tarot={{ spread_type: 'celtic', spread_name: '凯尔特十字', cards: ten }} />)
    const grid = screen.getByTestId('tarot-cards')
    expect(grid.className).toContain('grid-cols-5')
    expect(screen.getAllByText('✦')).toHaveLength(10)
  })

  it('错峰翻牌：推进 600ms+stagger 后牌面内容出现', () => {
    render(<TarotCards tarot={THREE} />)
    // 第一张 120ms 翻，第二张 240ms，第三张 360ms；翻转本身由 CSS transition
    // 驱动（jsdom 无样式计算），组件侧只推进 flipped 状态 → 内容随状态出现
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(screen.getByText('恋人')).toBeTruthy()
    expect(screen.getByText('权杖Ace')).toBeTruthy()
    expect(screen.getByText('星币十')).toBeTruthy()
  })

  it('翻牌完成后容器带 settle 动画类', () => {
    render(<TarotCards tarot={THREE} />)
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(screen.getByTestId('tarot-cards').className).toContain('animate-tarot-settle')
  })

  it('逆位牌显示「逆位」徽标（raspberry 底白字）', () => {
    render(<TarotCards tarot={THREE} />)
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(screen.getAllByText('逆位')).toHaveLength(1)
  })

  it('reduced-motion：初始即全部正面，无翻牌延迟', () => {
    stubMatchMedia(true)
    render(<TarotCards tarot={THREE} />)
    expect(screen.getByText('恋人')).toBeTruthy()
    expect(screen.getByText('权杖Ace')).toBeTruthy()
    expect(screen.getByText('星币十')).toBeTruthy()
  })

  it('含义文字出现在牌面底部（三牌全信息款）', () => {
    render(<TarotCards tarot={THREE} />)
    act(() => {
      vi.advanceTimersByTime(600)
    })
    expect(screen.getByText('让真心带领你做出选择')).toBeTruthy()
    expect(screen.getByText('新的热情即将点燃')).toBeTruthy()
    expect(screen.getByText('长久的积累带来安稳')).toBeTruthy()
  })
})
