"""立绘预处理 v2：抠白底 + 去淡晕 + 把"横向"立绘裁成竖向，使所有立绘在竖向槽位里按高度充满。

根因：object-contain 在窄高槽位里，横向图会按"宽度"适配 → 角色被压得很小；
只有竖向图才会按"高度"适配 → 角色显大。idle/happy/sleeping 的角色画幅偏横，
故裁成以角色为中心的竖向窗口（保留全高，收窄两侧），与其余竖向立绘一致。
幂等：竖向图（比例<0.85）不裁；已透明底再次运行无副作用。
"""
from pathlib import Path
from PIL import Image
from PIL.ImageDraw import floodfill

SP = Path(__file__).resolve().parent.parent / "web" / "public" / "sprites"
# 这些立绘由专用脚本（fix_sleeping_sprite.py）从原图重切，含完整横躺画幅，
# 绝不能被本脚本的「竖向裁切」再切坏，也不需再抠底——跳过。
SKIP = {"sleeping", "halfawake"}
TOL = 28
ALPHA_DENOISE = 40     # 低于此 alpha 视为背景淡晕，清零
MARGIN = 6
TARGET_RATIO = 0.70    # 目标 宽/高（竖向），与 speaking/thinking 等一致


def process(p: Path):
    im = Image.open(p).convert("RGBA")
    W, H = im.size
    for seed in [(0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)]:
        floodfill(im, seed, (0, 0, 0, 0), thresh=TOL)
    # 去除角色边缘/背景的淡晕，避免它撑大 bbox
    r, g, b, a = im.split()
    a = a.point(lambda v: 0 if v < ALPHA_DENOISE else v)
    im = Image.merge("RGBA", (r, g, b, a))

    bbox = im.getbbox()
    if not bbox:
        return (W, H, im.size, "empty")
    l, t, rr, bb = bbox
    bw, bh = rr - l, bb - t

    # 横向角色 → 裁成竖向窗口（保留全高，按目标比例取宽，居中于角色）
    note = "portrait(ok)"
    if bw / bh > 0.85:
        new_w = int(bh * TARGET_RATIO)
        if new_w < bw:
            cx = (l + rr) // 2
            nl = max(0, cx - new_w // 2)
            if nl + new_w > W:
                nl = W - new_w
            l, rr = nl, nl + new_w
            bw = new_w
            note = f"landscape->portrait crop"

    l = max(0, l - MARGIN); t = max(0, t - MARGIN)
    rr = min(W, rr + MARGIN); bb = min(H, bb + MARGIN)
    im = im.crop((l, t, rr, bb))
    im.save(p, "WEBP", quality=88, method=4)
    return (W, H, im.size, note)


if __name__ == "__main__":
    for p in sorted(SP.glob("*.webp")):
        if p.stem in SKIP:
            print(f"{p.name:16s} skipped (专用脚本处理，避免竖裁切坏横躺画幅)")
            continue
        W, H, (nw, nh), note = process(p)
        print(f"{p.name:16s} {W}x{H} -> {nw}x{nh} (ratio {nw/nh:.2f})  [{note}]")
    print("done")
