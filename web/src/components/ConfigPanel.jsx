import IconClose from '../assets/icons/IconClose'

// 后端 available_models 拉取失败时的兜底
const fallbackModels = [
  { id: 'deepseek-v4-flash', name: 'DeepSeek V4 Flash', description: '轻量快速' },
  { id: 'deepseek-v4-pro', name: 'DeepSeek V4 Pro', description: '深度推理' },
]

// 与后端 _THINKING_PRESETS 的键一致
const thinkingLevels = [
  { value: 'low', label: '轻柔' },
  { value: 'medium', label: '适中' },
  { value: 'high', label: '深度' },
  { value: 'max', label: '全力' },
]

export default function ConfigPanel({ config, push, onClose }) {
  const { settings, updateSetting } = config

  const models = (settings.available_models?.length ? settings.available_models : fallbackModels)
  // 模型卡片短标签：'DeepSeek V4 Flash' → 'V4 Flash'
  const shortLabel = (m) => (m.name || m.id).replace(/^DeepSeek\s*/i, '')
  const isCustomIntensity = settings.thinking_intensity === 'custom'
  const temperature = Number(settings.temperature) || 0

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-ink-tea/15 overlay-enter" onClick={onClose} />

      <div className="panel-enter relative w-full rounded-t-[24px] p-6 pb-9 max-h-[88%] overflow-y-auto chat-scroll"
        style={{ background: 'rgba(252,250,248,0.94)', backdropFilter: 'blur(20px)', boxShadow: '0 -12px 48px rgba(92,84,77,0.1)' }}>

        <div className="flex items-center justify-between mb-7">
          <h2 className="text-[13px] font-medium text-ink-tea/80 tracking-widest uppercase">设置</h2>
          <button onClick={onClose} className="btn-press w-7 h-7 rounded-full bg-ink-tea/5 flex items-center justify-center" aria-label="关闭设置">
            <IconClose size={13} className="text-ink-tea/50" />
          </button>
        </div>

        {/* 模型切换 */}
        <div className="mb-6">
          <label className="text-[11px] text-ink-tea/40 mb-2.5 block tracking-wide">模型</label>
          <div className="flex gap-2.5">
            {models.map(m => (
              <button
                key={m.id}
                onClick={() => updateSetting('model', m.id)}
                className={`flex-1 py-3 px-4 rounded-2xl text-left transition-all duration-250 ${
                  settings.model === m.id
                    ? 'bg-healing-aqua/12 border-[1.5px] border-healing-aqua/40 shadow-[0_2px_8px_rgba(136,181,181,0.12)]'
                    : 'bg-ink-tea/3 border-[1.5px] border-transparent'
                }`}
              >
                <span className={`text-[13px] block ${settings.model === m.id ? 'text-healing-aqua font-medium' : 'text-ink-tea/60'}`}>
                  {shortLabel(m)}
                </span>
                <span className="text-[10px] text-ink-tea/30 mt-0.5 block line-clamp-2">{m.description || m.id}</span>
              </button>
            ))}
          </div>
        </div>

        {/* 思考强度 */}
        <div className="mb-6">
          <div className="flex items-center justify-between mb-2.5">
            <label className="text-[11px] text-ink-tea/40 tracking-wide">思考强度</label>
            {isCustomIntensity && <span className="text-[10px] text-ink-tea/30">自定义（随温度）</span>}
          </div>
          <div className="flex gap-1.5 bg-ink-tea/3 rounded-xl p-1">
            {thinkingLevels.map(level => (
              <button
                key={level.value}
                onClick={() => updateSetting('thinking_intensity', level.value)}
                className={`flex-1 py-2 rounded-lg text-[12px] transition-all duration-200 ${
                  settings.thinking_intensity === level.value
                    ? 'bg-white text-healing-aqua shadow-sm font-medium'
                    : 'text-ink-tea/40'
                }`}
              >
                {level.label}
              </button>
            ))}
          </div>
        </div>

        {/* 温度 */}
        <div>
          <div className="flex items-center justify-between mb-2.5">
            <label className="text-[11px] text-ink-tea/40 tracking-wide">温度</label>
            <span className="text-[11px] text-healing-aqua font-medium">{temperature.toFixed(1)}</span>
          </div>
          <input
            type="range"
            min="0" max="2" step="0.1"
            value={temperature}
            onChange={e => updateSetting('temperature', parseFloat(e.target.value))}
            className="w-full h-1 rounded-full appearance-none bg-ink-tea/8 accent-healing-aqua cursor-pointer"
            aria-label="温度"
          />
          <div className="flex justify-between mt-1.5">
            <span className="text-[9px] text-ink-tea/20">精确</span>
            <span className="text-[9px] text-ink-tea/20">创意</span>
          </div>
        </div>

        {/* 推送通知 */}
        <div className="mt-6 pt-5 border-t border-ink-tea/6">
          <div className="flex items-center justify-between">
            <div className="pr-3">
              <label className="text-[11px] text-ink-tea/40 tracking-wide block">消息通知</label>
              <span className="text-[10px] text-ink-tea/30 leading-relaxed block mt-0.5">小汐主动找你时，给你递个消息</span>
            </div>
            <button
              role="switch"
              aria-checked={push.subscribed}
              aria-label="消息通知开关"
              disabled={!push.supported}
              onClick={() => (push.subscribed ? push.unsubscribe() : push.subscribe())}
              className={`btn-press relative w-11 h-6.5 rounded-full transition-colors duration-200 shrink-0 ${
                push.subscribed ? 'bg-healing-aqua' : 'bg-ink-tea/10'
              } ${push.supported ? '' : 'opacity-40 cursor-not-allowed'}`}
              style={{ width: 44, height: 26 }}
            >
              <span
                className="absolute top-[3px] w-5 h-5 rounded-full bg-white shadow-sm transition-all duration-200"
                style={{ left: push.subscribed ? 21 : 3 }}
              />
            </button>
          </div>
          {!push.supported && (
            <p className="text-[10px] text-ink-tea/25 mt-1.5">当前浏览器或环境不支持推送通知</p>
          )}
        </div>
      </div>
    </div>
  )
}
