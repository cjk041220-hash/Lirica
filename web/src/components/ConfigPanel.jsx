import { useState, useEffect } from 'react'
import IconClose from '../assets/icons/IconClose'
import { getToken, setToken, UNAUTHORIZED_EVENT } from '../api/client'
import { isGpsEnabled, setGpsEnabled } from '../api/location'
import { useFocusTrap } from '../hooks/useFocusTrap'

// 后端 available_models 拉取失败时的兜底
const fallbackModels = [
  { id: 'deepseek-v4-flash', name: 'DeepSeek V4 Flash', description: '轻量快速' },
  { id: 'deepseek-v4-pro', name: 'DeepSeek V4 Pro', description: '深度推理' },
]

// 与后端 _THINKING_PRESETS 的键一致。
// 文案诚实化（修复 P2-4）：这组档位实际映射 temperature（回复的
// 随机性/创造性），不是模型推理深度，所以叫"回复风格"而不是"思考强度"。
const styleLevels = [
  { value: 'low', label: '稳重' },
  { value: 'medium', label: '自然' },
  { value: 'high', label: '发散' },
  { value: 'max', label: '灵感' },
]

export default function ConfigPanel({ config, push, onClose }) {
  const { settings, updateSetting } = config
  const dialogRef = useFocusTrap()

  // 访问令牌（修复 P1-2）：后端启用 XIAOXI_API_TOKEN 后必填
  const [tokenInput, setTokenInput] = useState(getToken())
  const [tokenSaved, setTokenSaved] = useState(false)
  const [needToken, setNeedToken] = useState(false)
  useEffect(() => {
    const onUnauthorized = () => setNeedToken(true)
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized)
  }, [])

  // Esc 关闭（dialog 语义的键盘出口；焦点圈定留待后续完善）
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const saveToken = () => {
    const trimmed = tokenInput.trim()
    setToken(trimmed)
    setTokenInput(trimmed)
    setTokenSaved(true)
    setNeedToken(false)
    setTimeout(() => setTokenSaved(false), 2000)
  }

  // 位置辅助开关
  const [gpsOn, setGpsOn] = useState(isGpsEnabled())
  const toggleGps = () => {
    const next = !gpsOn
    setGpsOn(next)
    setGpsEnabled(next)
  }

  const models = (settings.available_models?.length ? settings.available_models : fallbackModels)
  // 模型卡片短标签：'DeepSeek V4 Flash' → 'V4 Flash'
  const shortLabel = (m) => ((m && (m.name || m.id)) || '').replace(/^DeepSeek\s*/i, '')
  const isCustomIntensity = settings.thinking_intensity === 'custom'
  const temperature = Number(settings.temperature) || 0

  return (
    <div className="absolute inset-0 z-[100] flex items-end">
      <div className="absolute inset-0 bg-ink-tea/15 overlay-enter" onClick={onClose} />

      <div className="panel-enter relative w-full rounded-t-[24px] p-6 pb-9 max-h-[88%] overflow-y-auto chat-scroll"
        ref={dialogRef} tabIndex={-1}
        role="dialog" aria-modal="true" aria-label="设置"
        style={{ background: 'rgba(252,250,248,0.94)', backdropFilter: 'blur(20px)', boxShadow: '0 -12px 48px rgba(92,84,77,0.1)' }}>

        <div className="flex items-center justify-between mb-7">
          <h2 className="text-[13px] font-medium text-ink-tea/80 tracking-widest uppercase">设置</h2>
          <button onClick={onClose} className="btn-press w-7 h-7 rounded-full bg-ink-tea/5 flex items-center justify-center" aria-label="关闭设置">
            <IconClose size={13} className="text-ink-tea/50" />
          </button>
        </div>

        {/* 访问令牌（仅后端启用认证时需要）*/}
        <div className="mb-6">
          <label className="text-[11px] text-ink-tea/40 mb-2.5 block tracking-wide">访问令牌</label>
          {needToken && (
            <p className="text-[11px] text-[#c47a5a] mb-2">小汐收到了 401——服务器启用了认证，填一下令牌就能继续聊啦。</p>
          )}
          <div className="flex gap-2">
            <input
              type="password"
              value={tokenInput}
              onChange={e => setTokenInput(e.target.value)}
              placeholder="本机使用可留空；部署后填 XIAOXI_API_TOKEN"
              className="flex-1 min-w-0 px-3 py-2 rounded-xl bg-ink-tea/3 text-[12px] text-ink-tea/80 placeholder:text-ink-tea/25 outline-none focus:bg-ink-tea/5"
              aria-label="访问令牌"
            />
            <button
              onClick={saveToken}
              className="btn-press shrink-0 px-3.5 py-2 rounded-xl bg-healing-aqua/12 text-healing-aqua text-[12px] font-medium"
            >
              {tokenSaved ? '已保存' : '保存'}
            </button>
          </div>
          <p className="text-[10px] text-ink-tea/25 mt-1.5">只保存在你的浏览器里，用于和小汐的服务器核对身份。</p>
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

        {/* 回复风格（原"思考强度"，实为 temperature 预设，文案诚实化）*/}
        <div className="mb-6">
          <div className="flex items-center justify-between mb-2.5">
            <label className="text-[11px] text-ink-tea/40 tracking-wide">回复风格</label>
            {isCustomIntensity && <span className="text-[10px] text-ink-tea/30">自定义（随温度）</span>}
          </div>
          <div className="flex gap-1.5 bg-ink-tea/3 rounded-xl p-1">
            {styleLevels.map(level => (
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
          <p className="text-[10px] text-ink-tea/25 mt-1.5">调节的是回复的随机性与想象力，不是推理时长。</p>
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

        {/* 位置辅助 */}
        <div className="mt-5 pt-5 border-t border-ink-tea/6">
          <div className="flex items-center justify-between">
            <div className="pr-3">
              <label className="text-[11px] text-ink-tea/40 tracking-wide block">位置辅助</label>
              <span className="text-[10px] text-ink-tea/30 leading-relaxed block mt-0.5">让小汐知道你在哪，查"附近"和早安天气更准</span>
            </div>
            <button
              role="switch"
              aria-checked={gpsOn}
              aria-label="位置辅助开关"
              onClick={toggleGps}
              className={`btn-press relative rounded-full transition-colors duration-200 shrink-0 ${
                gpsOn ? 'bg-healing-aqua' : 'bg-ink-tea/10'
              }`}
              style={{ width: 44, height: 26 }}
            >
              <span
                className="absolute top-[3px] w-5 h-5 rounded-full bg-white shadow-sm transition-all duration-200"
                style={{ left: gpsOn ? 21 : 3 }}
              />
            </button>
          </div>
          <p className="text-[10px] text-ink-tea/25 mt-1.5">坐标只发给小汐的服务器，缓存 30 分钟，不会告诉别人。</p>
        </div>
      </div>
    </div>
  )
}
