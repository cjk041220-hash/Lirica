export default function IconCalendar({ size = 18, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
      {/* 日历本 */}
      <rect
        x="3.5" y="5" width="17" height="15.5" rx="3"
        stroke="currentColor" strokeWidth="1.5"
      />
      {/* 装订环 */}
      <path
        d="M8 3v3.5M16 3v3.5"
        stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"
      />
      {/* 分隔线 */}
      <path
        d="M3.5 10h17"
        stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"
      />
      {/* 记着事的小点 */}
      <circle cx="8.5" cy="14.5" r="1.1" fill="currentColor" />
      <circle cx="12" cy="14.5" r="1.1" fill="currentColor" />
      <circle cx="8.5" cy="17.5" r="1.1" fill="currentColor" />
    </svg>
  )
}
