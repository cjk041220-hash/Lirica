# attic/ — 一次性脚本归档

这里放「跑过一次就完成使命」的维护脚本，保留备查，不在日常维护路径上。
归档日期：2026-08-08（体检工程优化 C3，自 scripts/ 迁入）。

| 脚本 | 原用途 |
|------|--------|
| `gen_assets.py` | 生成 PWA 图标 + 立绘 PNG→WebP 压缩（资源已产出入库） |
| `trim_sprites.py` | 立绘预处理（四角 flood-fill 抠白底） |
| `fix_sleeping_sprite.py` | 睡姿立绘定位修正（一次性工具） |

注意：这些脚本的工作目录假设与硬编码路径仍指向仓库根 / `web/public`，
若需要重跑，请先核对脚本内的路径常量。历史引用见
`docs/launch-check/05-visual.md`（记录的是归档前位置，不 retro 更新）。
