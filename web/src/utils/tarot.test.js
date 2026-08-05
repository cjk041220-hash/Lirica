import { describe, it, expect } from 'vitest'
import { isValidTarot } from './tarot'

const GOOD = {
  spread_type: 'three',
  spread_name: '三牌占卜',
  cards: [
    { name: '恋人', symbol: 'VI', suit: '大阿尔卡纳', element: '风', keywords: ['真爱'], is_reversed: false, position: '过去', fortune: 'x', meaning: ['x'] },
    { name: '权杖Ace', symbol: 'A', suit: '权杖', element: '火', keywords: [], is_reversed: true, position: '现在', fortune: 'y', meaning: ['y'] },
  ],
}

describe('isValidTarot', () => {
  it('合法 artifact → true', () => {
    expect(isValidTarot(GOOD)).toBe(true)
  })

  it('single / celtic 牌阵合法', () => {
    expect(isValidTarot({ ...GOOD, spread_type: 'single', cards: GOOD.cards.slice(0, 1) })).toBe(true)
    expect(isValidTarot({ ...GOOD, spread_type: 'celtic' })).toBe(true)
  })

  it('未知牌阵 → false', () => {
    expect(isValidTarot({ ...GOOD, spread_type: 'mega' })).toBe(false)
  })

  it('cards 缺失 / 空 / 非数组 → false', () => {
    expect(isValidTarot({ spread_type: 'three' })).toBe(false)
    expect(isValidTarot({ ...GOOD, cards: [] })).toBe(false)
    expect(isValidTarot({ ...GOOD, cards: 'nope' })).toBe(false)
  })

  it('card 缺 name / 缺 position → false（严格 shape 校验）', () => {
    const noName = { ...GOOD.cards[0] }
    delete noName.name
    expect(isValidTarot({ ...GOOD, cards: [noName] })).toBe(false)
    const noPos = { ...GOOD.cards[0] }
    delete noPos.position
    expect(isValidTarot({ ...GOOD, cards: [noPos] })).toBe(false)
  })

  it('null / undefined / 非对象 → false', () => {
    expect(isValidTarot(null)).toBe(false)
    expect(isValidTarot(undefined)).toBe(false)
    expect(isValidTarot('x')).toBe(false)
  })
})
