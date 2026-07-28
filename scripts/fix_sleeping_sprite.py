"""重切 sleeping 立绘：从原始白底原图抠出干净透明、不裁切的横躺睡姿。

为什么单独处理：通用 trim_sprites.py 用「四角 flood-fill 容差 28」抠白底，
爬不过原画白底上那圈柔和投影/暗角，残留成半透明矩形（深夜房间里看就是一个
浮空方块）；又把它当竖立绘裁成 0.70 竖窗，切掉了怀里的小鲸鱼玩偶。

睡姿穿白裙、白腿，不能按颜色「扣白」（会连裙子腿一起抠穿）——白底与白裙
只能靠「连通性」区分：外白底连到画布边缘，白裙被蓝色线稿轮廓封闭在内。
故本脚本沿整条边做 flood-fill，容差抬高以爬过暗角渐变，到饱和轮廓即停，
保留轮廓内的白裙；且不做竖向裁切，保留完整横躺画幅。

源图在工程外的素材库（路径含中文，故写进 .py 以 UTF-8 字面量保存，
命令行不带中文，规避 Windows 控制台编码污染）。幂等：可反复运行。
"""
from pathlib import Path
from PIL import Image
from PIL.ImageDraw import floodfill

# 原始白底原图（工程外素材库）
SRC = Path(r"E:\小汐\public\assets\character\sleeping.png")
# 输出（工程内，ASCII 路径）
OUT = Path(__file__).resolve().parent.parent / "web" / "public" / "sprites" / "sleeping.webp"

# 抠白容差：要能爬过白底→暗角的渐变，又在蓝色线稿轮廓处停下。
# 轮廓是深色/饱和色，与白相差远大于此值，不会漏进白裙。
FLOOD_TOL = 96
ALPHA_DENOISE = 45   # 低于此 alpha 的残晕清零
MARGIN = 8


def main():
    im = Image.open(SRC).convert("RGBA")
    W, H = im.size

    # 沿整条边取种子（四角 + 四边中点 + 边上等距点），覆盖暗角各处的局部色
    seeds = [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1),
             (W // 2, 0), (W // 2, H - 1), (0, H // 2), (W - 1, H // 2)]
    step = max(1, min(W, H) // 12)
    seeds += [(x, 0) for x in range(0, W, step)]
    seeds += [(x, H - 1) for x in range(0, W, step)]
    seeds += [(0, y) for y in range(0, H, step)]
    seeds += [(W - 1, y) for y in range(0, H, step)]
    for s in seeds:
        floodfill(im, s, (0, 0, 0, 0), thresh=FLOOD_TOL)

    # 清残晕
    r, g, b, a = im.split()
    a = a.point(lambda v: 0 if v < ALPHA_DENOISE else v)
    im = Image.merge("RGBA", (r, g, b, a))

    # 裁透明边，保留完整横躺画幅（不做竖向裁切！）
    bbox = im.getbbox()
    if not bbox:
        raise SystemExit("抠图后为空，检查容差")
    l, t, rr, bb = bbox
    l = max(0, l - MARGIN); t = max(0, t - MARGIN)
    rr = min(W, rr + MARGIN); bb = min(H, bb + MARGIN)
    im = im.crop((l, t, rr, bb))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    im.save(OUT, "WEBP", quality=90, method=4)
    nw, nh = im.size
    print(f"sleeping.png {W}x{H} -> sleeping.webp {nw}x{nh} (ratio {nw / nh:.2f}, 无竖裁)")


if __name__ == "__main__":
    main()
