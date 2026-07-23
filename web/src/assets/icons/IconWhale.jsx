export default function IconWhale({ size = 20, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
      <path
        d="M4 14c0-4 3.5-7 8-7 3 0 5.5 1.5 7 3.5.8 1 1.2 2.2 1 3.5-.3 2-2 3.5-4.5 4-1.5.3-3 .2-4.5-.2"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
      <path d="M7 17.5c-1.5.5-3 .3-4-1" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
      <circle cx="9" cy="12" r="1" fill="currentColor" />
      <path d="M16 7c.5-1.5 1.5-2.5 3-3" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
      <path d="M17.5 8c1-1 2.2-1.5 3.5-1.5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  )
}
