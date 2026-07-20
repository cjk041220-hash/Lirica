export default function Home() {
  return (
    <div className="relative w-full h-[100dvh] overflow-hidden bg-black max-w-[430px] mx-auto">
      {/* Z-0: 窗外风景 */}
      <div
        id="layer-scenery"
        className="absolute inset-0 z-0"
      />

      {/* Z-10: 房间框架图 */}
      <div
        id="layer-room"
        className="absolute inset-0 z-10 pointer-events-none"
      />

      {/* Z-20: 小汐动作图 */}
      <div
        id="layer-character"
        className="absolute bottom-[20%] left-1/2 -translate-x-1/2 z-20"
      />
    </div>
  );
}
