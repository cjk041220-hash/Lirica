import { memo } from 'react'
import SkyBackground from './SkyBackground'
import CharacterSprite from './CharacterSprite'
import FloatingHearts from './FloatingHearts'

// memo：props 只有 characterState（原始值）+ moodLight（三族枚举）。
// 聊天流式期间 App 每个 token 都重渲染，不 memo 的话整间房
//（含 46 颗星 + 立绘 6 个 state）跟着空转——体检 🟠-3。
// 天空自带时段钟（SkyBackground 内），被 memo 截断父级重渲染后
// 仍能按时段切换
function RoomScene({ characterState = 'idle', moodLight = 'neutral' }) {
  return (
    <div className="absolute inset-0 overflow-hidden">
      <SkyBackground moodLight={moodLight} />

      {/* Z-5: 漂浮爱心（天空之上、窗框之下） */}
      <FloatingHearts />

      {/* Z-10: 房间框架——蜜桃橙太抢，大面积改用更柔的奶粉色系 */}
      <div className="absolute inset-0 z-10 pointer-events-none">
        {/* 左窗框（粉刷浅粉木） */}
        <div className="absolute left-0 top-0 bottom-[30%] w-[18px] bg-gradient-to-r from-[#FFE9F0] to-[#F8D3E0] shadow-[inset_-2px_0_4px_rgba(110,74,86,0.06)]" />
        {/* 右窗框 */}
        <div className="absolute right-0 top-0 bottom-[30%] w-[18px] bg-gradient-to-l from-[#FFE9F0] to-[#F8D3E0] shadow-[inset_2px_0_4px_rgba(110,74,86,0.06)]" />
        {/* 上窗框 */}
        <div className="absolute top-0 left-0 right-0 h-[18px] bg-gradient-to-b from-[#FFE9F0] to-[#F8D3E0] shadow-[inset_0_-2px_4px_rgba(110,74,86,0.06)]" />
        {/* 窗框内边高光 */}
        <div className="absolute left-[18px] top-[18px] right-[18px] bottom-[30%] border border-white/30 rounded-b-sm" />

        {/* 牛奶粉地板 */}
        <div className="absolute bottom-0 left-0 right-0 h-[30%] bg-gradient-to-b from-[#FFF0F4] via-[#FBDFE9] to-[#F3C6D6]" />
        {/* 地板纹理线 */}
        <div className="absolute bottom-[20%] left-0 right-0 h-px bg-blush/30" />
        <div className="absolute bottom-[10%] left-0 right-0 h-px bg-blush/20" />
        {/* 地板与墙分隔线 */}
        <div className="absolute bottom-[30%] left-0 right-0 h-[2px] bg-blush/60 shadow-[0_1px_3px_rgba(110,74,86,0.05)]" />

        {/* 窗光在地板上的一汪蜜桃暖斑（静态，不抢立绘） */}
        <div
          className="absolute bottom-[5%] left-1/2 -translate-x-1/2 w-[72%] h-[18%] rounded-[50%]"
          style={{
            background: 'radial-gradient(ellipse, rgba(255,201,163,0.22) 0%, transparent 70%)',
          }}
        />

        {/* 心情驱光（U1）：同一汪地板光斑上的暖金/冷蓝叠层，随小汐
            心情族交叉淡入——中性族两层皆隐（回到静态蜜桃斑）。
            幅度刻意克制（透明度 ±0.1 量级），不破坏粉色基调；
            reduced-motion 下全局 transition 归零，退化为瞬时切换 */}
        <div
          className="absolute bottom-[5%] left-1/2 -translate-x-1/2 w-[72%] h-[18%] rounded-[50%] transition-opacity duration-[2500ms]"
          style={{
            background: 'radial-gradient(ellipse, rgba(255,214,150,0.20) 0%, transparent 70%)',
            opacity: moodLight === 'warm' ? 1 : 0,
          }}
        />
        <div
          className="absolute bottom-[5%] left-1/2 -translate-x-1/2 w-[72%] h-[18%] rounded-[50%] transition-opacity duration-[2500ms]"
          style={{
            background: 'radial-gradient(ellipse, rgba(146,158,220,0.18) 0%, transparent 70%)',
            opacity: moodLight === 'cool' ? 1 : 0,
          }}
        />

        {/* 环境光 — 窗户透进来的柔光 */}
        <div className="absolute top-[18px] left-[18px] right-[18px] h-[40%] bg-gradient-to-b from-white/5 to-transparent" />
      </div>

      {/* Z-12: 两粒蜂蜜金亮星，错相呼吸（复用天空的 sparkle 关键帧） */}
      <span
        aria-hidden
        className="absolute z-[12] pointer-events-none"
        style={{
          top: '24%',
          left: '27%',
          width: 9,
          height: 9,
          borderRadius: '50%',
          background:
            'linear-gradient(transparent 42%, rgba(233,180,76,0.9) 50%, transparent 58%), linear-gradient(90deg, transparent 42%, rgba(233,180,76,0.9) 50%, transparent 58%)',
          animation: 'sparkle 4.2s ease-in-out infinite',
        }}
      />
      <span
        aria-hidden
        className="absolute z-[12] pointer-events-none"
        style={{
          top: '40%',
          right: '24%',
          width: 7,
          height: 7,
          borderRadius: '50%',
          background:
            'linear-gradient(transparent 42%, rgba(233,180,76,0.8) 50%, transparent 58%), linear-gradient(90deg, transparent 42%, rgba(233,180,76,0.8) 50%, transparent 58%)',
          animation: 'sparkle 5.6s ease-in-out infinite',
          animationDelay: '1.4s',
        }}
      />

      {/* Z-20: 小汐 */}
      <CharacterSprite state={characterState} />

      {/* Z-30: 仅半醒态叠加一缕浮动 Zzz（站姿头侧）。
          沉睡态的 Zzz 已烘进立绘原图，不再叠加以免重复+错位。 */}
      {characterState === 'halfawake' && (
        <div className="absolute top-[15%] right-[34%] z-30 pointer-events-none" aria-hidden>
          <span className="absolute text-sm font-medium animate-zzz text-strawberry/30">z</span>
          <span
            className="absolute text-base font-medium animate-zzz text-strawberry/20"
            style={{ animationDelay: '0.9s', left: '12px', top: '-14px' }}
          >
            z
          </span>
        </div>
      )}

      {/* 整体暗角 */}
      <div
        className="absolute inset-0 z-[15] pointer-events-none"
        style={{ boxShadow: 'inset 0 0 60px rgba(110,74,86,0.05)' }}
      />
    </div>
  )
}

export default memo(RoomScene)
