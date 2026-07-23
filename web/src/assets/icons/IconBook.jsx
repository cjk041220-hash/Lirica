export default function IconBook({ size = 18, className = '' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" className={className}>
      <path
        d="M12 6.5C10.5 5 8.5 4.5 5 4.5c-.83 0-1.5.67-1.5 1.5v11c0 .83.67 1.5 1.5 1.5 3.5 0 5.5.5 7 2 1.5-1.5 3.5-2 7-2 .83 0 1.5-.67 1.5-1.5V6c0-.83-.67-1.5-1.5-1.5-3.5 0-5.5.5-7 2z"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
      <path d="M12 6.5V19" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}
