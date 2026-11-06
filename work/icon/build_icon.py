# -*- coding: utf-8 -*-
"""
Deyva 图标 · 定稿渲染（r39）。

设计：一格 35mm 负片悬在暗房里，画格内是「影调曲线」——App 影调页的那条曲线，
连同它中间那颗被选中的控制点。三个形状、两个颜色，48dp 下依然分得清主次。

输出（全部从 1024 母版重采样，不缩放位图）：
  · mipmap-*/ic_launcher.png        旧版方形（圆角底）
  · mipmap-*/ic_launcher_round.png  旧版圆形
  · mipmap-*/ic_launcher_background.png  自适应 · 背景层（满幅暖调暗场）
  · mipmap-*/ic_launcher_foreground.png  自适应 · 前景层（负片 + 曲线，透明底）

自适应图标的硬约束：可视安全区是 108dp 画布中心直径 66dp 的圆。前景必须整体
落在里面，否则圆形遮罩会把画幅的四个角切掉。3:2 的矩形半对角 = 宽 × 0.601，
所以自适应版画幅宽最多 0.61/0.601/2 ≈ 0.507 —— 与旧版 0.78 的构图是两套尺寸，
不是简单缩放。这里用 ZOOM 参数统一控制。
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 1024
ROOT      = (0x0B, 0x0A, 0x08)
PAPER     = (0xF3, 0xED, 0xE3)
ACCENT    = (0xFF, 0xA5, 0x3C)
ACCENT_LT = (0xFF, 0xC7, 0x7A)
ACCENT_DK = (0xE0, 0x6A, 0x14)

# 画幅几何（以图标边长为 1）
W = 0.780                 # 画幅宽；高 = 2/3 宽
H = W * 2 / 3
SW = 0.048                # 边框粗细
RAD = 0.017               # 外圆角
MARGIN = 0.105            # 曲线距画幅内缘的留白（相对画幅宽）
# 纸边不是纯白：纯白会把注意力从琥珀曲线抢到边框上，暖纸色才让中间那道光成为焦点
FRAME_TOP = (0xFB, 0xF6, 0xEC)
FRAME_MID = (0xE9, 0xE0, 0xD1)
FRAME_BOT = (0xBE, 0xB2, 0x9F)
DOT = False           # 不画控制点：48dp 下它退化成一个糊点，96dp 像一颗球
TAPER = 0.38          # 线宽沿路径渐变（两端细、中间实）

# 自适应版：把整套几何缩到安全圆内
ZOOM_ADAPTIVE = 0.640


def over(dst, src, a):
    a = a[:, :, None] if a.ndim == 2 else a
    return dst * (1 - a) + src * a


def vgrad(S, stops):
    out = np.zeros((S, S, 3), dtype=np.float32)
    for ch in range(3):
        out[:, :, ch] = np.interp(np.linspace(0, 1, S), [s[0] for s in stops],
                                  [s[1][ch] for s in stops])[:, None]
    return out


def radial(S, cx, cy, r, power=2.0):
    yy, xx = np.mgrid[0:S, 0:S]
    d = np.sqrt(((xx + 0.5) / S - cx) ** 2 + ((yy + 0.5) / S - cy) ** 2)
    return np.clip(1.0 - d / r, 0, 1) ** power


def mask_rrect(S, x0, y0, x1, y1, r):
    m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(m).rounded_rectangle([x0 * S, y0 * S, x1 * S, y1 * S], radius=max(1, r * S), fill=255)
    return np.asarray(m, dtype=np.float32) / 255.0


def mask_ring(S, x0, y0, x1, y1, r, sw):
    a = mask_rrect(S, x0 - sw / 2, y0 - sw / 2, x1 + sw / 2, y1 + sw / 2, r + sw / 2)
    b = mask_rrect(S, x0 + sw / 2, y0 + sw / 2, x1 - sw / 2, y1 - sw / 2, max(0.002, r - sw / 2))
    return np.clip(a - b, 0, 1)


def backdrop(S, zoom=1.0, full=True):
    """暗房：暖炭黑 + 中心偏上的安全灯余晖。"""
    base = vgrad(S, [(0.0, (0x1B, 0x17, 0x12)), (0.52, ROOT), (1.0, (0x07, 0x06, 0x05))])
    base = over(base, np.array([0x40, 0x29, 0x13], np.float32), radial(S, 0.5, 0.44, 0.82, 2.4) * 0.55)
    # 极淡的横向"桌面反光"：让底色不是一块死黑
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / S
    sheen = np.exp(-((yy - 0.78) ** 2) / (2 * 0.10 ** 2)) * 0.06
    base = over(base, np.array([0xFF, 0xB4, 0x60], np.float32), sheen)
    return base


def stroke_path_mask(S, pts, w, taper=0.0):
    """
    沿解析法线偏移生成闭合多边形，再填成 mask。

    为什么不用 ImageDraw.line(width=…)：PIL 的粗线是逐段画的，相邻段的接缝处
    会漏出一个缺口 —— 260 个采样点累积起来就是一圈"锯齿"，在图标上非常明显。
    这里一条闭合多边形只填一次，边缘天然干净。
    """
    left, right = [], []
    n = len(pts)
    for i, p in enumerate(pts):
        if i == 0:
            t = (pts[1][0] - p[0], pts[1][1] - p[1])
        elif i == n - 1:
            t = (p[0] - pts[-2][0], p[1] - pts[-2][1])
        else:
            t = (pts[i + 1][0] - pts[i - 1][0], pts[i + 1][1] - pts[i - 1][1])
        L = max(1e-9, (t[0] ** 2 + t[1] ** 2) ** 0.5)
        nx, ny = -t[1] / L, t[0] / L
        # taper>0 时线宽沿路径变化：两端细、中间实，像一笔扫过去的流光
        u = i / (n - 1)
        ww = w * (1.0 - taper + taper * (0.55 + 0.45 * np.sin(np.pi * u) ** 0.7))
        left.append((p[0] + nx * ww / 2, p[1] + ny * ww / 2))
        right.append((p[0] - nx * ww / 2, p[1] - ny * ww / 2))
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    d.polygon([(x * S, y * S) for x, y in left + right[::-1]], fill=255)
    for p in (pts[0], pts[-1]):        # 端头补圆
        r = w * (1.0 - taper * 0.45) / 2 * S
        d.ellipse([p[0] * S - r, p[1] * S - r, p[0] * S + r, p[1] * S + r], fill=255)
    return np.asarray(m, dtype=np.float32) / 255.0


def curve_pts(x0, y0, x1, y1, n=260):
    """一条温和的 S 曲线（等价于影调曲线里最常用的对比曲线）。

    (x0,y1) 左下 → (x1,y0) 右上；smoothstep 让两端平、中间陡。
    """
    out = []
    for i in range(n):
        t = i / (n - 1)
        s = t * t * (3 - 2 * t)
        e = 0.62 * s + 0.38 * t
        out.append((x0 + (x1 - x0) * t, y1 + (y0 - y1) * e))
    return out


def art_layer(zoom=1.0, shadow=True):
    """
    返回 (rgb, alpha)：负片画幅 + 曲线，透明底。zoom 缩放整套几何后居中。
    """
    img_rgb = np.zeros((S, S, 3), dtype=np.float32)
    img_a = np.zeros((S, S), dtype=np.float32)

    w, h = W * zoom, H * zoom
    sw = SW * zoom
    rad = max(0.002, RAD * zoom)
    x0, x1 = 0.5 - w / 2, 0.5 + w / 2
    y0, y1 = 0.5 - h / 2, 0.5 + h / 2

    # 窗口：暖黑，左下有一点安全灯余晖（避免死平）
    win = mask_rrect(S, x0, y0, x1, y1, rad)
    wg = vgrad(S, [(0.0, (0x1A, 0x16, 0x12)), (1.0, (0x0B, 0x09, 0x07))])
    wg = over(wg, np.array([0x4A, 0x2C, 0x12], np.float32), radial(S, 0.34, 0.72, 0.55, 2.2) * win * 0.5)
    img_rgb = over(img_rgb, wg, win)
    img_a = np.clip(img_a + win, 0, 1)

    # ---- 曲线 ----
    m = MARGIN * w                     # 曲线距画幅内缘的留白
    ix0, ix1 = x0 + sw / 2 + m, x1 - sw / 2 - m
    iy0, iy1 = y0 + sw / 2 + m * 0.9, y1 - sw / 2 - m * 0.9
    pts = curve_pts(ix0, iy0, ix1, iy1)

    cw = 0.036 * zoom                  # 曲线粗细
    cm = stroke_path_mask(S, pts, cw, TAPER)
    cm = np.clip(cm, 0, 1)
    # 只让曲线落在窗口里（不影响画幅边缘）
    cg = vgrad(S, [(0.0, ACCENT_LT), (0.55, ACCENT), (1.0, (0xE8, 0x7A, 0x22))])
    img_rgb = over(img_rgb, cg, cm)

    # 曲线柔光：和 App 里那层 6px 光晕同一个意思
    glow = np.asarray(Image.fromarray((cm * 255).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(0.018 * S)), np.float32) / 255.0
    img_rgb = over(img_rgb, ACCENT, np.clip(glow - cm, 0, 1) * 0.45)

    # ---- 控制点：炭黑挖空 + 琥珀实心 ----
    if DOT:
        cx = 0.5
        cy = iy1 + (iy0 - iy1) * 0.5        # 曲线中点（t=0.5 时 e=0.5）
        rr = 0.044 * zoom
        gap = 0.019 * zoom
        gmask = Image.new("L", (S, S), 0)
        gd = ImageDraw.Draw(gmask)
        gd.ellipse([(cx - rr - gap) * S, (cy - rr - gap) * S,
                    (cx + rr + gap) * S, (cy + rr + gap) * S], fill=255)
        gm = np.asarray(gmask, dtype=np.float32) / 255.0
        dmask = Image.new("L", (S, S), 0)
        dd = ImageDraw.Draw(dmask)
        dd.ellipse([(cx - rr) * S, (cy - rr) * S, (cx + rr) * S, (cy + rr) * S], fill=255)
        dm = np.asarray(dmask, dtype=np.float32) / 255.0

        img_rgb = over(img_rgb, np.array([0x12, 0x0F, 0x0C], np.float32), gm)     # 先挖掉底
        # 再放琥珀点：本体 ACCENT，左上一点高光（和滑杆"当前刻度"同一个打光方向）
        img_rgb = over(img_rgb, np.array([0xFF, 0xA5, 0x3C], np.float32), dm)
        hl = radial(S, cx - 0.008 * zoom, cy - 0.010 * zoom, rr * 1.5, 1.6) * dm
        img_rgb = over(img_rgb, np.array([0xFF, 0xDA, 0xAA], np.float32), hl * 0.55)

    # ---- 画幅边框（最后画，压住曲线端点）----
    ring = mask_ring(S, x0, y0, x1, y1, rad, sw)
    fg = vgrad(S, [(0.0, FRAME_TOP), (0.42, FRAME_MID), (1.0, FRAME_BOT)])
    img_rgb = over(img_rgb, fg, ring)
    img_a = np.clip(img_a + ring, 0, 1)

    # 内缘一道极细的暖反光：光从画格里泄到纸边上
    inner = mask_ring(S, x0 + sw * 0.52, y0 + sw * 0.52, x1 - sw * 0.52, y1 - sw * 0.52,
                      max(0.002, rad - sw * 0.5), sw * 0.16)
    img_rgb = over(img_rgb, np.array([0xFF, 0xC2, 0x74], np.float32), inner * 0.25)
    return img_rgb, img_a


def compose(bg_zoom=1.0, art_zoom=1.0, plate="square", size=1024, art_only=False):
    bg = backdrop(S)
    rgb, a = art_layer(art_zoom)

    if art_only:
        out = np.dstack([rgb, a * 255])
    else:
        canvas = bg * 1.0
        # 画幅的投影：先在背景上压一层柔化后的 alpha
        sh = np.asarray(Image.fromarray((a * 255).astype(np.uint8)).filter(
            ImageFilter.GaussianBlur(0.022 * S)), np.float32) / 255.0
        sh = np.roll(np.roll(sh, int(0.012 * S), 0), int(0.006 * S), 1)
        canvas = over(canvas, np.array([0, 0, 0], np.float32), sh * 0.55)
        canvas = over(canvas, rgb, a)
        out = np.dstack([canvas, np.full((S, S), 255, np.float32)])

    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGBA").resize((size, size), Image.LANCZOS)
    if plate == "none":
        return img
    k = 4
    m = Image.new("L", (size * k, size * k), 0)
    d = ImageDraw.Draw(m)
    if plate == "circle":
        d.ellipse([0, 0, size * k - 1, size * k - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, size * k - 1, size * k - 1], radius=int(size * k * 0.2235), fill=255)
    m = m.resize((size, size), Image.LANCZOS)
    res = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    res.paste(img, (0, 0), m)
    return res


DENS = [("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
        ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432)]


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    res = "D:/xm/deyva_src/android/app/src/main/res"

    for dens, legacy, adapt in DENS:
        d = os.path.join(res, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        compose(plate="square", size=legacy).save(os.path.join(d, "ic_launcher.png"))
        compose(plate="circle", size=legacy).save(os.path.join(d, "ic_launcher_round.png"))
        compose(art_only=True, art_zoom=ZOOM_ADAPTIVE, size=adapt).save(
            os.path.join(d, "ic_launcher_foreground.png"))
        Image.fromarray(np.clip(backdrop(S), 0, 255).astype(np.uint8)).resize(
            (adapt, adapt), Image.LANCZOS).save(os.path.join(d, "ic_launcher_background.png"))

    # 预览：大图 / 小图 / 圆形遮罩，验证安全区
    prev = Image.new("RGB", (760, 300), (0x14, 0x14, 0x14))
    prev.paste(compose(plate="square", size=256), (24, 22))
    prev.paste(compose(plate="circle", size=128), (300, 22))
    prev.paste(compose(plate="square", size=64), (452, 22))
    prev.paste(compose(plate="square", size=48), (532, 22))
    # 自适应版按圆形遮罩裁一次，看四角会不会被切
    ad = compose(plate="none", size=216, art_only=False)
    ad2 = compose(art_only=True, art_zoom=ZOOM_ADAPTIVE, size=216)
    prev.paste(compose(plate="circle", size=128), (300, 160))
    prev.save(os.path.join(here, "preview.png"))

    # 自适应图层的独立检查图（前景叠在背景上，圆形裁切）
    fg = compose(art_only=True, art_zoom=ZOOM_ADAPTIVE, size=432)
    bgi = Image.fromarray(np.clip(backdrop(S), 0, 255).astype(np.uint8)).resize((432, 432), Image.LANCZOS)
    comp = bgi.convert("RGBA")
    comp.alpha_composite(fg)
    mask = Image.new("L", (432 * 4, 432 * 4), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, 432 * 4 - 1, 432 * 4 - 1], fill=255)
    mask = mask.resize((432, 432), Image.LANCZOS)
    cropped = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    cropped.paste(comp, (0, 0), mask)
    cropped.save(os.path.join(here, "adaptive_circle.png"))
    print("done")
