import SkyBackground from './SkyBackground'
import CharacterSprite from './CharacterSprite'

export default function RoomScene({ characterState = 'idle' }) {
  return (
    <div className="absolute inset-0 overflow-hidden">
      <SkyBackground />

      {/* Z-10: 房间框架 */}
      <div className="absolute inset-0 z-10 pointer-events-none">
        {/* 左窗框 */}
        <div className="absolute left-0 top-0 bottom-[30%] w-[18px] bg-gradient-to-r from-wood to-wood/80 shadow-[inset_-2px_0_4px_rgba(0,0,0,0.06)]" />
        {/* 右窗框 */}
        <div className="absolute right-0 top-0 bottom-[30%] w-[18px] bg-gradient-to-l from-wood to-wood/80 shadow-[inset_2px_0_4px_rgba(0,0,0,0.06)]" />
        {/* 上窗框 */}
        <div className="absolute top-0 left-0 right-0 h-[18px] bg-gradient-to-b from-wood to-wood/80 shadow-[inset_0_-2px_4px_rgba(0,0,0,0.06)]" />
        {/* 窗框内边高光 */}
        <div className="absolute left-[18px] top-[18px] right-[18px] bottom-[30%] border border-white/10 rounded-b-sm" />

        {/* 木地板 */}
        <div className="absolute bottom-0 left-0 right-0 h-[30%] bg-gradient-to-b from-wood-light via-wood-light/95 to-wood" />
        {/* 地板纹理线 */}
        <div className="absolute bottom-[20%] left-0 right-0 h-px bg-wood-border/30" />
        <div className="absolute bottom-[10%] left-0 right-0 h-px bg-wood-border/20" />
        {/* 地板与墙分隔线 */}
        <div className="absolute bottom-[30%] left-0 right-0 h-[2px] bg-wood-border/60 shadow-[0_1px_3px_rgba(0,0,0,0.05)]" />

        {/* 环境光 — 窗户透进来的柔光 */}
        <div className="absolute top-[18px] left-[18px] right-[18px] h-[40%] bg-gradient-to-b from-white/5 to-transparent" />
      </div>

      {/* Z-20: 小汐 */}
      <CharacterSprite state={characterState} />

      {/* Z-30: 仅半醒态叠加一缕浮动 Zzz（站姿头侧）。
          沉睡态的 Zzz 已烘进立绘原图，不再叠加以免重复+错位。 */}
      {characterState === 'halfawake' && (
        <div className="absolute top-[15%] right-[34%] z-30 pointer-events-none" aria-hidden>
          <span className="absolute text-sm font-medium animate-zzz text-healing-aqua/30">z</span>
          <span
            className="absolute text-base font-medium animate-zzz text-healing-aqua/20"
            style={{ animationDelay: '0.9s', left: '12px', top: '-14px' }}
          >z</span>
        </div>
      )}

      {/* 整体暗角 */}
      <div className="absolute inset-0 z-[15] pointer-events-none"
        style={{ boxShadow: 'inset 0 0 60px rgba(92,84,77,0.04)' }} />
    </div>
  )
}
