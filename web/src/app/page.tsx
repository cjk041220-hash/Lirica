"use client";

import { motion } from "framer-motion";
import LivingPanel from "@/components/LivingPanel";

export default function Home() {
  return (
    <main className="relative w-full h-[100dvh] overflow-hidden max-w-[430px] mx-auto shadow-2xl bg-warm-paper">
      {/* ── Z-0：窗外风景（晨曦渐变占位）── */}
      <div
        id="layer-scenery"
        className="absolute inset-0 z-0"
        style={{
          background: "linear-gradient(to bottom, #FADADD, #C5E0E0, #E8F4F8)",
        }}
      />

      {/* ── Z-10：房间框架（窗框 + 木地板）── */}
      <div
        id="layer-room"
        className="absolute inset-0 z-10 pointer-events-none"
      >
        {/* 左窗框 */}
        <div className="absolute left-0 top-0 bottom-[30%] w-5 bg-[#D4C5B3]" />
        {/* 右窗框 */}
        <div className="absolute right-0 top-0 bottom-[30%] w-5 bg-[#D4C5B3]" />
        {/* 上窗框 */}
        <div className="absolute left-0 right-0 top-0 h-5 bg-[#D4C5B3]" />

        {/* 木地板 */}
        <div
          className="absolute left-0 right-0 bottom-0 h-[30%]"
          style={{ background: "linear-gradient(to bottom, #E8D5B7, #D4C5B3)" }}
        />
        {/* 地板与墙面的交界线 */}
        <div className="absolute left-0 right-0 bottom-[30%] h-[2px] bg-[#C4B5A3]" />
      </div>

      {/* ── Z-20：小汐（呼吸动画 + 治愈水色椭圆）── */}
      <motion.div
        id="layer-character"
        className="absolute bottom-[20%] left-1/2 -translate-x-1/2 z-20"
        animate={{ y: [0, -8, 0] }}
        transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }}
      >
        <div
          className="w-48 h-48 rounded-[50%] blur-md"
          style={{
            background:
              "radial-gradient(ellipse, rgba(136, 181, 181, 0.6), rgba(136, 181, 181, 0.15), transparent)",
          }}
        />
      </motion.div>

      {/* ── Z-50：交互面板（毛玻璃聊天区）── */}
      <LivingPanel />
    </main>
  );
}
