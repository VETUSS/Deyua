"""
验证 r38 的「拖动期降半分辨率」没有几何/色彩偏差。

Layer2.apply16 新增 step 参数：
  step=1 → 全分辨率
  step=2 → 隔行隔列取一个，输出 (ceil(w/2), ceil(h/2))

要证明的不是"看起来差不多"，而是：**半分辨率的每个像素，等于全分辨率里
对应位置那个像素**（源坐标相同、暗角半径相同、色彩链相同），差值只该来自
抖动相位（8×8 Bayer 在两种步长下取到的相位不同），也就是 ≤1 个码值。

若这里出现 2 以上的差，就说明降采样把暗角或取样点挪了半个像素 —— 松手那一
瞬间画面会"跳"一下，正是这次改动最该避免的观感问题。

本脚本逐行复刻 Layer2 的 buildToneLut / buildCurveLut / buildZoneMats 与 rows16，
用同一份参数分别跑 step=1 和 step=2，比较重叠区域。
"""
import os
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
CP = 5
LINEAR = [(i + 1) / (CP + 1) for i in range(CP)]


# ---------------------------------------------------------------- 照抄 Layer2

def smoothstep(e0, e1, x):
    if e0 == e1:
        return np.where(x < e0, 0.0, 1.0)
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def curve_lut(ys, samples=256):
    n = CP + 2
    xs = np.zeros(n)
    yy = np.zeros(n)
    xs[0], yy[0] = 0.0, 0.0
    for i in range(CP):
        xs[i + 1] = (i + 1) / (CP + 1)
        yy[i + 1] = min(max(ys[i], 0.0), 1.0)
    xs[-1], yy[-1] = 1.0, 1.0
    for i in range(1, n):
        yy[i] = max(yy[i], yy[i - 1])
    d = np.diff(yy) / np.diff(xs)
    m = np.zeros(n)
    m[0], m[-1] = d[0], d[-2]
    for i in range(1, n - 1):
        m[i] = (d[i - 1] + d[i]) / 2
    for i in range(n - 1):
        if abs(d[i]) < 1e-9:
            m[i] = m[i + 1] = 0.0
        else:
            a, b = m[i] / d[i], m[i + 1] / d[i]
            s = a * a + b * b
            if s > 9.0:
                t = 3.0 / np.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
    x = np.arange(samples) / (samples - 1)
    k = np.clip(np.searchsorted(xs, x, side='right') - 1, 0, n - 2)
    hseg = xs[k + 1] - xs[k]
    t = np.clip((x - xs[k]) / hseg, 0.0, 1.0)
    t2, t3 = t * t, t * t * t
    v = ((2 * t3 - 3 * t2 + 1) * yy[k] + (t3 - 2 * t2 + t) * hseg * m[k]
         + (-2 * t3 + 3 * t2) * yy[k + 1] + (t3 - t2) * hseg * m[k + 1])
    return np.clip(v, 0.0, 1.0)


def sample(lut, v):
    """Layer2.sampleAt：等距表 + 线性插值。"""
    x = np.clip(v, 0.0, 1.0) * (len(lut) - 1)
    i = np.clip(x.astype(np.int32), 0, len(lut) - 2)
    f = x - i
    return lut[i] + (lut[i + 1] - lut[i]) * f


def build_tone_lut(c, n=1024):
    t = c['temp'] / 100.0
    ti = c['tint'] / 100.0
    gr = 1 + 0.32 * t + 0.10 * ti
    gg = 1 - 0.10 * abs(t) - 0.26 * ti
    gb = 1 - 0.32 * t + 0.10 * ti
    norm = 0.2126 * gr + 0.7152 * gg + 0.0722 * gb
    gains = [gr / norm, gg / norm, gb / norm]
    expo = 2.0 ** c['exposure']
    ck = 2.0 ** (c['contrast'] / 100.0 * 1.15)
    bright = c['brightness'] / 100.0 * 0.18
    hl, sh = c['highlights'] / 100.0, c['shadows'] / 100.0
    bp, wp = c['blacks'] / 100.0 * 0.18, c['whites'] / 100.0 * 0.18
    v0 = np.arange(n) / (n - 1)
    out = []
    for ch in range(3):
        v = v0 * gains[ch] * expo
        if ck != 1.0:
            v = 0.5 + (v - 0.5) * ck
        v = v + bright
        if hl != 0:
            w = smoothstep(0.35, 1.0, v)
            v = v + (hl * 0.35 * w * (v - 0.35) if hl < 0 else hl * 0.30 * w * (1 - v))
        if sh != 0:
            w = smoothstep(0.65, 0.0, v)
            v = v + (sh * 0.35 * w * (1 - v) if sh > 0 else sh * 0.45 * w * v)
        if bp != 0:
            v = (v - bp) / (1 - bp)
        if wp != 0:
            v = v / (1 - wp)
        out.append(np.clip(v, 0.0, 1.0))
    return out


def zone_matrix(hue, sat):
    ang = hue / 100.0 * 40.0 * np.pi / 180.0
    cs, sn = np.cos(ang), np.sin(ang)
    h = np.array([
        [0.213 + 0.787 * cs - 0.213 * sn, 0.715 - 0.715 * cs - 0.715 * sn, 0.072 - 0.072 * cs + 0.928 * sn],
        [0.213 - 0.213 * cs + 0.143 * sn, 0.715 + 0.285 * cs + 0.140 * sn, 0.072 - 0.072 * cs - 0.283 * sn],
        [0.213 - 0.213 * cs - 0.787 * sn, 0.715 - 0.715 * cs + 0.715 * sn, 0.072 + 0.928 * cs + 0.072 * sn],
    ])
    if abs(sat) < 0.01:
        return h
    s = min(max(1 + sat / 100.0, 0.0), 2.0)
    st = np.array([
        [0.2126 + 0.7874 * s, 0.7152 - 0.7152 * s, 0.0722 - 0.0722 * s],
        [0.2126 - 0.2126 * s, 0.7152 + 0.2848 * s, 0.0722 - 0.0722 * s],
        [0.2126 - 0.2126 * s, 0.7152 - 0.7152 * s, 0.0722 + 0.9278 * s],
    ])
    return st @ h


def build_zone_mats(c):
    ms = zone_matrix(c['shHue'], c['shSat'])
    mm = zone_matrix(c['mdHue'], c['mdSat'])
    mh = zone_matrix(c['hiHue'], c['hiSat'])
    mats = np.zeros((33, 3, 3))
    off = np.zeros(33)
    for i in range(33):
        l = i / 32.0
        ws = smoothstep(0.5, 0.0, l)
        wh = smoothstep(0.5, 1.0, l)
        wm = max(1 - ws - wh, 0.0)
        rest = max(1 - ws - wm - wh, 0.0)
        mats[i] = ms * ws + mm * wm + mh * wh + np.eye(3) * rest
        off[i] = (c['shLum'] * ws + c['mdLum'] * wm + c['hiLum'] * wh) / 100.0 * 0.12
    return mats, off


BAYER8 = np.array([
    [0, 32, 8, 40, 2, 34, 10, 42],
    [48, 16, 56, 24, 50, 18, 58, 26],
    [12, 44, 4, 36, 14, 46, 6, 38],
    [60, 28, 52, 20, 62, 30, 54, 22],
    [3, 35, 11, 43, 1, 33, 9, 41],
    [51, 19, 59, 27, 49, 17, 57, 25],
    [15, 47, 7, 39, 13, 45, 5, 37],
    [63, 31, 55, 23, 61, 29, 53, 21],
], dtype=np.float64) / 64.0 - 0.5


def run16(px, w, h, c, step=1):
    """复刻 Layer2.apply16 的 rows16。px: (h, w, 3) float 0..1"""
    lutA = build_tone_lut(c, 1024)
    lutB = [curve_lut(c['curve'], 1024)] * 3  # 本例只用 RGB 复合曲线
    zoneMat, zoneOff = build_zone_mats(c)
    satK = 1 + c['saturation'] / 100.0
    vigAmt = c['vignette'] / 100.0 * 0.85
    vigMid = min(max(c['vignetteMid'] / 100.0, 0.02), 0.98)
    vigMid2 = vigMid * vigMid
    vig = abs(vigAmt) > 0.001

    ow = (w + step - 1) // step
    oh = (h + step - 1) // step
    cx, cy = (w - 1) * 0.5, (h - 1) * 0.5
    invCx = 1 / cx if cx > 0 else 0.0
    invCy = 1 / cy if cy > 0 else 0.0

    ys = np.arange(oh) * step
    xs = np.arange(ow) * step
    src = px[np.ix_(ys, xs)]                       # (oh, ow, 3) —— 采样点按源坐标

    r = sample(lutA[0], src[:, :, 0])
    g = sample(lutA[1], src[:, :, 1])
    b = sample(lutA[2], src[:, :, 2])

    if satK != 1.0:
        l = 0.2126 * r + 0.7152 * g + 0.0722 * b
        r, g, b = l + (r - l) * satK, l + (g - l) * satK, l + (b - l) * satK

    l2 = 0.2126 * r + 0.7152 * g + 0.0722 * b
    zi = np.clip((l2 * 32).astype(np.int32), 0, 32)
    m = zoneMat[zi]                                 # (oh, ow, 3, 3)
    o = zoneOff[zi][:, :, None]
    rgb = np.stack([r, g, b], axis=-1)
    n = np.einsum('...ij,...j->...i', m, rgb) + o

    out = np.zeros_like(n)
    for ch in range(3):
        out[:, :, ch] = sample(lutB[ch], n[:, :, ch])

    if vig:
        dx = (xs[None, :] - cx) * invCx            # 按源坐标算半径
        dy = (ys[:, None] - cy) * invCy
        r2 = dx * dx + dy * dy
        t = np.clip((r2 - vigMid2) / (1 - vigMid2), 0, 1)
        s = t * t * (3 - 2 * t)
        out = out * (1 - vigAmt * s)[:, :, None]

    # 抖动相位按**输出坐标**取（与 Kotlin 一致）
    by = (np.arange(oh) & 7)[:, None]
    bx = (np.arange(ow) & 7)[None, :]
    d = BAYER8[by, bx][:, :, None]
    return np.clip(np.floor(out * 255 + d), 0, 255).astype(np.int16)


# ---------------------------------------------------------------- 图像

def make_frame(w, h):
    """一张有灰阶、有颜色、四角压暗的合成图（16 bit 尺度）。"""
    y, x = np.mgrid[0:h, 0:w]
    u, v = x / (w - 1), y / (h - 1)
    # 低频彩云 + 横向灰阶，模拟"天空 + 地面"
    px = np.zeros((h, w, 3))
    px[:, :, 0] = 0.10 + 0.75 * u * (0.55 + 0.45 * v)
    px[:, :, 1] = 0.12 + 0.70 * u * (0.50 + 0.50 * (1 - v))
    px[:, :, 2] = 0.16 + 0.62 * (1 - u) * (0.6 + 0.4 * v)
    px += 0.04 * np.sin(u * 9) [:, :, None]
    return np.clip(px, 0, 1)


CASES = {
    'A 全套（曝光/对比/饱和/曲线/暗角）': dict(
        temp=18.0, tint=-8.0, exposure=0.6, contrast=42.0, brightness=0.0,
        highlights=-60.0, shadows=25.0, blacks=-10.0, whites=8.0, saturation=22.0,
        shHue=0.0, shSat=0.0, shLum=0.0, mdHue=0.0, mdSat=0.0, mdLum=0.0,
        hiHue=0.0, hiSat=0.0, hiLum=0.0, vignette=65.0, vignetteMid=50.0,
        curve=[0.06, 0.22, 0.52, 0.80, 0.96],
    ),
    'B 只有暗角（查几何有没有挪）': dict(
        temp=0.0, tint=0.0, exposure=0.0, contrast=0.0, brightness=0.0,
        highlights=0.0, shadows=0.0, blacks=0.0, whites=0.0, saturation=0.0,
        shHue=0.0, shSat=0.0, shLum=0.0, mdHue=0.0, mdSat=0.0, mdLum=0.0,
        hiHue=0.0, hiSat=0.0, hiLum=0.0, vignette=100.0, vignetteMid=35.0,
        curve=LINEAR,
    ),
}

if __name__ == '__main__':
    W, H = 640, 480
    frame = make_frame(W, H)
    report = []
    for name, cfg in CASES.items():
        full = run16(frame, W, H, cfg, step=1)
        half = run16(frame, W, H, cfg, step=2)
        sub = full[::2, ::2][:half.shape[0], :half.shape[1]]
        diff = np.abs(full[::2, ::2][:half.shape[0], :half.shape[1]].astype(np.int32)
                      - half.astype(np.int32))
        lines = [f'{name}',
                 f'  全分辨率 {full.shape[1]}x{full.shape[0]}  半分辨率 {half.shape[1]}x{half.shape[0]}',
                 f'  同位置最大差 = {diff.max()}  均值 = {diff.mean():.3f}  '
                 f'差 ≥2 的比例 = {(diff >= 2).mean() * 100:.4f}%']
        report.append('\n'.join(lines))
        print('\n'.join(lines))

    # 拼一张对比图：左全分辨率、右半分辨率（放大 2 倍回原尺寸）
    for name, cfg in CASES.items():
        full = run16(frame, W, H, cfg, step=1).astype(np.uint8)
        half = run16(frame, W, H, cfg, step=2).astype(np.uint8)
        big = np.repeat(np.repeat(half, 2, axis=0), 2, axis=1)[:H, :W]
        cat = np.concatenate([full, big], axis=1)
        Image.fromarray(cat).save(os.path.join(HERE, 'step2_%s.jpg' % ('A' if 'A ' in name else 'B')),
                                  quality=92)

    ok = True
    for name, cfg in CASES.items():
        full = run16(frame, W, H, cfg, step=1)
        half = run16(frame, W, H, cfg, step=2)
        d = np.abs(full[::2, ::2][:half.shape[0], :half.shape[1]].astype(np.int32)
                   - half.astype(np.int32))
        if d.max() > 1:
            ok = False
    print('\n结论:', '半分辨率只是抽样，几何与色彩与全分辨率一致（差 ≤1 码值，来自抖动相位）'
          if ok else '！！半分辨率与全分辨率不一致，降档会让松手瞬间画面跳变')
