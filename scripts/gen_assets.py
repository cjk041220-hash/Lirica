"""一次性资源工具：生成 PWA 图标 + 立绘 PNG→WebP 压缩。

用法：backend/.venv/Scripts/python.exe scripts/gen_assets.py
"""
import platform
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WEB = Path(__file__).resolve().parent.parent / "web"
PUBLIC = WEB / "public"

# 按平台选择中文字体
_sys = platform.system()
if _sys == "Windows":
    FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"
    FONT_REG = "C:/Windows/Fonts/msyh.ttc"
elif _sys == "Darwin":
    FONT_BOLD = "/System/Library/Fonts/PingFang.ttc"
    FONT_REG = "/System/Library/Fonts/PingFang.ttc"
else:  # Linux
    FONT_BOLD = "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc"
    FONT_REG = "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"

AQUA = (136, 181, 181)       # healing-aqua #88B5B5
AQUA_DEEP = (122, 165, 165)
PAPER = (252, 250, 248)      # warm-paper #FCFAF8


def make_icon(size: int, out: Path):
    """圆角水色底 + 白色'汐'字。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    radius = int(size * 0.23)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=AQUA)

    # 底部一点深色渐变感（简单叠加一个半透明深色圆角矩形下半部）
    shade = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shade)
    sd.rounded_rectangle(
        [0, int(size * 0.55), size - 1, size - 1],
        radius=radius,
        fill=AQUA_DEEP + (70,),
    )
    img = Image.alpha_composite(img, shade)
    d = ImageDraw.Draw(img)

    font = ImageFont.truetype(FONT_BOLD, int(size * 0.56))
    text = "汐"
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (size - tw) / 2 - bbox[0]
    y = (size - th) / 2 - bbox[1] - size * 0.015
    # 柔和阴影
    d.text((x + size * 0.008, y + size * 0.012), text, font=font, fill=(70, 90, 90, 90))
    d.text((x, y), text, font=font, fill=PAPER)

    img.save(out, "PNG")
    print(f"[icon] {out.name}  {out.stat().st_size // 1024} KB")


def sprites_to_webp():
    sprite_dir = PUBLIC / "sprites"
    pngs = sorted(sprite_dir.glob("*.png"))
    if not pngs:
        print("[sprites] 无 PNG 立绘，跳过")
        return
    total_old = total_new = 0
    for p in pngs:
        old_size = p.stat().st_size
        total_old += old_size
        img = Image.open(p).convert("RGBA")
        out = p.with_suffix(".webp")
        img.save(out, "WEBP", quality=85, method=4)
        total_new += out.stat().st_size
        print(f"[sprite] {p.name} ({old_size // 1024} KB) -> {out.name} ({out.stat().st_size // 1024} KB)")
        p.unlink()  # WebP 已生成，删除原 PNG
    print(f"[sprites] 合计 {total_old // 1024 // 1024} MB -> {total_new // 1024 // 1024} MB "
          f"（节省 {(1 - total_new / total_old) * 100:.0f}%）")


if __name__ == "__main__":
    make_icon(192, PUBLIC / "icon-192.png")
    make_icon(512, PUBLIC / "icon-512.png")
    sprites_to_webp()
    print("done")
