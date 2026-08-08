// 塔罗 artifact 校验 — 与 mapLink.isValidNav 同风格：前端严格 shape 校验，
// 脏数据 → false → 不渲染卡片、回退纯文本（后端只做宽松类型检查）
export function isValidTarot(t) {
  if (!t || typeof t !== 'object') return false
  const { spread_type, cards } = t
  if (spread_type !== 'single' && spread_type !== 'three' && spread_type !== 'celtic') return false
  if (!Array.isArray(cards) || cards.length === 0) return false
  return cards.every(
    (c) =>
      c &&
      typeof c === 'object' &&
      typeof c.name === 'string' &&
      c.name.length > 0 &&
      typeof c.position === 'string' &&
      c.position.length > 0
  )
}
