export default function IconSend({ size = 18, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
      <path
        d="M5 12.5L19 5.5L14.5 19L11.5 13.5L5 12.5Z"
        fill="currentColor"
        stroke="currentColor"
        strokeWidth="1"
        strokeLinejoin="round"
      />
      <path
        d="M11.5 13.5L19 5.5"
        stroke="white"
        strokeWidth="0.8"
        strokeLinecap="round"
        opacity="0.5"
      />
    </svg>
  )
}
