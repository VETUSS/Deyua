"""核验 r35 的分区印相（RFC-024 对比度蒙版）标定。

四件事：
1. 复算 mask_delta：core 内恒 0、单调、|delta| <= amount、reach 处 = amount/sqrt2。
2. 复算网格 + 高斯 + 双线性取样，与 contrast_mask.cpp / spk_mask_epilogue.comp 同构。
3. 用相纸真实密度曲线（kodak_portra_endura）把 delta 换算成密度，列出旧/新标定
   在纸上的实际变化。
4. 拿上游的一张真实成片（screenshots/natural_halation.png 右半）当输入，把四段
   结果并排画出来：原样 / 旧标定 1.5 档 / 新标定 1.5 档 / 新标定 3.0 档。
"""
import json
import math
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))        # 仓库根
PROFILE = os.path.join(ROOT, 'android/app/src/main/assets/engine/profiles/'
                             'kodak_portra_endura.json')
PHOTO = os.path.join(ROOT, 'upstream/screenshots/natural_halation.png')

# ---- 相纸 ---------------------------------------------------------------
_d = json.load(open(PROFILE, encoding='utf-8'))['data']
_L = np.array(_d['log_exposure'])
_D = np.array([row[1] for row in _d['density_curves']])     # 绿通道，近似亮度
L_MID = float(np.interp(-math.log10(0.18), _D, _L))         # 中灰 D=0.745
D_WHITE = 0.05
_lo = int(np.argmax(_D > 0.02))                             # 单调段，用来反查曝光
_hi = int(np.argmax(_D > 1.95))
_Lm, _Dm = _L[_lo:_hi], _D[_lo:_hi]


def density_at(log10_e):
    return np.interp(log10_e, _L, _D)


def exposure_of_density(d):
    return np.interp(d, _Dm, _Lm)


# ---- 两套标定 -----------------------------------------------------------
LIMIT = 6.0
OLD_REACH = 4.0
NEW_REACH = 1.0


def room_for(amount):
    """旧标定：反解增益上界，让膝点外 4 档处正好抬 amount 档。"""
    delta = amount * LIMIT / math.sqrt(LIMIT * LIMIT - amount * amount)
    t = max(OLD_REACH - delta, 1e-3)
    return OLD_REACH * t / math.sqrt(OLD_REACH * OLD_REACH - t * t)


def g(d, h):
    d = np.asarray(d, dtype=np.float64)
    return d * h / np.sqrt(h * h + d * d)


def delta_old(b, amount, core):
    b = np.asarray(b, dtype=np.float64)
    if amount <= 0.0:
        return np.zeros_like(b)
    h = room_for(amount)
    lo = np.maximum(-core - b, 0.0)      # 高光侧（b < -core）
    hi = np.maximum(b - core, 0.0)       # 阴影侧（b > +core）
    d = (lo - g(lo, h)) - (hi - g(hi, h))
    return d * LIMIT / np.sqrt(LIMIT * LIMIT + d * d)


def delta_new(b, amount, core, reach=NEW_REACH):
    """spk_mask.glsl 的 mask_delta，逐项相同。"""
    b = np.asarray(b, dtype=np.float64)
    if amount <= 0.0:
        return np.zeros_like(b)
    lo = np.maximum(-core - b, 0.0)
    hi = np.maximum(b - core, 0.0)
    return (g(lo, reach) - g(hi, reach)) * (amount / reach)


# ---- 1 · 公式校验 -------------------------------------------------------
def check_curves():
    ok = True
    for amount in (0.3, 1.5, 3.0):
        for i in range(-800, 801):
            b = i / 100.0
            d = float(delta_new(b, amount, 1.0))
            if abs(d) > amount + 1e-6:
                print('  FAIL |delta| > amount at b=%.2f' % b); ok = False
            if d < -1e-9 and b < 1.0:
                print('  FAIL 高光侧符号反了 at b=%.2f' % b); ok = False
            if abs(b) <= 1.0 and d != 0.0:
                print('  FAIL core 内非 0 at b=%.2f' % b); ok = False
        got = float(delta_new(-(1.0 + NEW_REACH), amount, 1.0))
        want = amount / math.sqrt(2.0)
        if abs(got - want) > 1e-6:
            print('  FAIL reach 处 %.4f != %.4f' % (got, want)); ok = False
        far = float(delta_new(-9.0, amount, 1.0))
        if abs(far - amount) > 0.02 * amount:
            print('  FAIL 远端 %.4f 未逼近 %.4f' % (far, amount)); ok = False
        print('  amount %.1f: 膝点外 1 档 %+.3f（应为 %.3f）· 纸白 %+.3f · 远端 %+.3f'
              % (amount, got, want, float(delta_new(-1.83, amount, 1.0)), far))
    return ok


# ---- 2 · 相纸上的可见量 -------------------------------------------------
def report_table():
    print('\n  x(档)   D原样   旧:Δ档   D旧     新:Δ档   D新')
    for b in (-1.00, -1.30, -1.60, -1.83, -2.20, -3.00):
        dp = float(density_at(L_MID + b * math.log10(2)))
        do = float(delta_old(b, 1.5, 1.0))
        dn = float(delta_new(b, 1.5, 1.0))
        print('  %+5.2f  %.3f  %+.3f   %.3f   %+.3f   %.3f'
              % (b, dp, do, density_at(L_MID + (b + do) * math.log10(2)),
                 dn, density_at(L_MID + (b + dn) * math.log10(2))))
    print('  最大值（amount=3，纸白 -1.83 档）：旧 %+.3f 档 → D %.3f · 新 %+.3f 档 → D %.3f'
          % (float(delta_old(-1.83, 3.0, 1.0)),
             density_at(L_MID + (-1.83 + float(delta_old(-1.83, 3.0, 1.0))) * math.log10(2)),
             float(delta_new(-1.83, 3.0, 1.0)),
             density_at(L_MID + (-1.83 + float(delta_new(-1.83, 3.0, 1.0))) * math.log10(2))))


# ---- 3 · 网格与高斯（与主机侧同构） -------------------------------------
def mask_field(x_img, scale=0.03, gw_min=512):
    h, w = x_img.shape
    long_edge = max(w, h)
    cells = min(max(gw_min, int(math.ceil(4.0 / max(scale, 1e-4)))), long_edge)
    gw = max(1, int(round(w * cells / long_edge)))
    gh = max(1, int(round(h * cells / long_edge)))
    gx = np.empty((gh, gw))
    for cy in range(gh):
        y0 = (cy * h) // gh
        y1 = min(max(((cy + 1) * h) // gh, y0 + 1), h)
        for cx in range(gw):
            x0 = (cx * w) // gw
            x1 = min(max(((cx + 1) * w) // gw, x0 + 1), w)
            gx[cy, cx] = x_img[y0:y1, x0:x1].mean()
    return gx, gw, gh, cells


def gauss_blur(src, sigma):
    """截断窗口、按实际窗口归一化 —— 与 contrast_mask.cpp 的 gaussian 一致。"""
    r = max(1, int(math.ceil(4.0 * sigma)))
    k = np.array([math.exp(-0.5 * (float(i) ** 2) / (sigma * sigma)) for i in range(-r, r + 1)])
    out = src
    for axis in (0, 1):
        pad = [(r, r) if a == axis else (0, 0) for a in range(2)]
        p = np.pad(out, pad, mode='edge')
        acc = np.zeros_like(out)
        wsum = 0.0
        for t in range(2 * r + 1):
            sl = [slice(None)] * 2
            sl[axis] = slice(t, t + out.shape[axis])
            acc = acc + k[t] * p[tuple(sl)]
            wsum += k[t]
        out = acc / wsum
    return out


def upsample(grid, h, w):
    gh, gw = grid.shape
    ys = np.clip((np.arange(h) + 0.5) * gh / h - 0.5, 0, gh - 1)
    xs = np.clip((np.arange(w) + 0.5) * gw / w - 0.5, 0, gw - 1)
    y0 = np.floor(ys).astype(int); x0 = np.floor(xs).astype(int)
    y1 = np.minimum(y0 + 1, gh - 1); x1 = np.minimum(x0 + 1, gw - 1)
    ty = (ys - y0)[:, None]; tx = (xs - x0)[None, :]
    return ((grid[y0][:, x0] * (1 - tx) + grid[y0][:, x1] * tx) * (1 - ty)
            + (grid[y1][:, x0] * (1 - tx) + grid[y1][:, x1] * tx) * ty)


# ---- 4 · 拿真实成片做对比 ----------------------------------------------
def load_photo(width=760):
    im = Image.open(PHOTO).convert('RGB')
    w, h = im.size
    im = im.crop((int(w * 0.52), int(h * 0.055), w, h))   # 右半「After」，切掉角标
    w, h = im.size
    im = im.resize((width, int(round(h * width / w))), Image.LANCZOS)
    srgb = np.asarray(im, dtype=np.float64) / 255.0
    lin = np.where(srgb <= 0.04045, srgb / 12.92, ((srgb + 0.055) / 1.055) ** 2.4)
    lum = 0.2126 * lin[:, :, 0] + 0.7152 * lin[:, :, 1] + 0.0722 * lin[:, :, 2]
    return srgb, lin, lum


def build_image(path):
    srgb, lin, lum = load_photo()
    h, w = lum.shape
    # 把这张成片当作「一张印好的照片」：每通道反射率 → 密度 → 曝光 → 蒙版坐标 b
    d_obs = np.clip(-np.log10(np.clip(lin, 1e-4, None)) + D_WHITE, D_WHITE, 2.05)
    l_obs = exposure_of_density(d_obs)
    l_lum = exposure_of_density(np.clip(-np.log10(np.clip(lum, 1e-4, None)) + D_WHITE,
                                        D_WHITE, 2.05))
    b = (l_lum - L_MID) / math.log10(2)

    gx, gw, gh, cells = mask_field(b, 0.03)
    sigma = max(0.5, 0.03 * cells)
    base = upsample(gauss_blur(gx, sigma), h, w)

    def result(amount, fn):
        if amount <= 0.0:
            return srgb
        # 内核里 k = exp2(delta) 对三个通道是同一个数，所以这里也整通道共用
        dlt = fn(base, amount, 1.0)[:, :, None]
        d_new = density_at(l_obs + dlt * math.log10(2))
        y_new = np.clip(10.0 ** (-(d_new - D_WHITE)), 0.0, 1.0)
        out = np.where(y_new <= 0.0031308, 12.92 * y_new, 1.055 * y_new ** (1 / 2.4) - 0.055)
        return np.clip(out, 0.0, 1.0)

    tiles = [('原样', srgb),
             ('旧标定 1.5 档', result(1.5, delta_old)),
             ('新标定 1.5 档', result(1.5, delta_new)),
             ('新标定 3.0 档', result(3.0, delta_new))]

    gap = 8
    canvas = Image.new('RGB', (w * len(tiles) + gap * (len(tiles) + 1), h + gap * 2), (22, 22, 24))
    for i, (_, img) in enumerate(tiles):
        canvas.paste(Image.fromarray((img * 255.0 + 0.5).astype(np.uint8), 'RGB'),
                     (gap + i * (w + gap), gap))
    canvas.save(path, quality=92)

    bright = b < np.percentile(b, 15)
    for name, amount, fn in (('旧 1.5', 1.5, delta_old), ('新 1.5', 1.5, delta_new),
                             ('新 3.0', 3.0, delta_new)):
        dd = fn(base[bright], amount, 1.0)
        d0 = np.interp(L_MID + b[bright] * math.log10(2), _L, _D)
        d1 = np.interp(L_MID + (b[bright] + dd) * math.log10(2), _L, _D)
        print('  最亮 15%%（天空）：%s → 平均 %+.3f 档，密度 %+.3f（%.3f → %.3f）'
              % (name, dd.mean(), (d1 - d0).mean(), d0.mean(), d1.mean()))
    return path


if __name__ == '__main__':
    print('== 1. 公式 ==')
    ok = check_curves()
    print('   结果：%s' % ('通过' if ok else '失败'))
    print('== 2. 相纸上的可见量（core 1 档）==')
    report_table()
    print('== 3. 真实成片 ==')
    print('   ->', build_image(os.path.join(HERE, 'mask_calibration.jpg')))
