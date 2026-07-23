import IconSettings from '../assets/icons/IconSettings'

export default function SettingsButton({ onClick }) {
  return (
    <button
      onClick={onClick}
      className="icon-btn absolute top-4 right-4 z-[60] w-9 h-9 rounded-full glass-panel flex items-center justify-center"
      aria-label="设置"
    >
      <IconSettings size={16} className="text-ink-tea/60" />
    </button>
  )
}
