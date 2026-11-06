"""
验证 r37 的 16 bit 调色路径：同一根曲线，8 bit 输入 vs 16 bit 输入。

复刻 Layer2 的两条路径：
  · 旧（8 bit）：u16 --shr 8--> u8 --> tone LUT(256) --> 曲线 LUT(256) --> +Bayer
  · 新（16 bit）：u16 --> tone LUT(1024, 插值) --> 曲线 LUT(1024, 插值) --> +Bayer

输出：台阶统计 + 一张并排对比图（上半旧、下半新，右侧是放大 6 倍的天空）。
"""
import os
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))   # 图写在这里，脚本路径全相对自身
CP = 5

# ---------------------------------------------------------------- 曲线（照抄 Layer2）

def curve_lut(ys, samples=256):
    xs = np.zeros(CP + 2)
    yy = np.zeros(CP + 2)
    xs[0], yy[0] = 0.0, 0.0
    for i in range(CP):
        xs[i + 1] = (i + 1) / (CP + 1)
        yy[i + 1] = min(max(ys[i], 0.0), 1.0)
    xs[-1], yy[-1] = 1.0, 1.0
    for i in range(1, len(yy)):
        yy[i] = max(yy[i], yy[i - 1])
    n = len(xs)
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
    t = np.clip((x - xs[k]) / hseg, 0, 1)
    t2, t3 = t * t, t * t * t
    v = ((2 * t3 - 3 * t2 + 1) * yy[k] + (t3 - 2 * t2 + t) * hseg * m[k]
         + (-2 * t3 + 3 * t2) * yy[k + 1] + (t3 - t2) * hseg * m[k + 1])
    return np.clip(v, 0, 1)

def sample_at(lut, v):
    t = np.asarray(v, dtype=np.float64) * (lut.size - 1)
    i = np.clip(t.astype(np.int64), 0, lut.size - 2)
    f = t - i
    return lut[i] + (lut[i + 1] - lut[i]) * f

# ---------------------------------------------------------------- 参数：幅度偏大的那种

CONTRAST = 35          # 面板 +35（对比）
CURVE = [0.05, 0.28, 0.50, 0.72, 0.95]

BAYER8 = np.array([
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
], dtype=np.float64).reshape(8, 8) / 64.0 - 0.5

def dither(h, w):
    tile = np.tile(BAYER8, (h // 8 + 1, w // 8 + 1))[:h, :w]
    return tile

def tone_then_curve(u, samples):
    """u: 0–1 浮点输入 → 0–1 输出。步骤与 Layer2 的 1–4 + 7 一致。"""
    lut_tone = np.zeros(samples)
    ck = 2.0 ** (CONTRAST / 100.0 * 1.15)
    xi = np.arange(samples) / (samples - 1)
    v = 0.5 + (xi - 0.5) * ck          # 对比：绕 0.5 线性拉伸
    lut_tone = np.clip(v, 0, 1)
    lut_curve = curve_lut(CURVE, samples)
    a = sample_at(lut_tone, u) if samples > 256 else lut_tone[
        np.clip((np.asarray(u) * 255 + 0.5).astype(int), 0, 255)]
    b = sample_at(lut_curve, a) if samples > 256 else lut_curve[
        np.clip((a * 255 + 0.5).astype(int), 0, 255)]
    return b

def path_old(u16, h, w):
    u8 = u16 >> 8                                  # 旧的 renderToBitmap：直接右移
    u = u8.astype(np.float64) / 255.0
    v = tone_then_curve(u, 256)
    return np.clip((v * 255 + dither(h, w)).astype(int), 0, 255)

def path_new(u16, h, w):
    u = u16.astype(np.float64) / 65535.0
    v = tone_then_curve(u, 1024)
    return np.clip((v * 255 + dither(h, w)).astype(int), 0, 255)

# ---------------------------------------------------------------- 造一张"天空"

H, W = 240, 720
# 16 bit 的平滑渐变 + 一点点起伏（真实天空不会是纯 ramp，但纯 ramp 最能暴露台阶）
x = np.linspace(0.58, 0.86, W)                    # 落在曲线最陡的那一段
grad = np.tile(x, (H, 1))
u16 = np.clip(np.round(grad * 65535), 0, 65535).astype(np.int32)

old = path_old(u16, H, W)
new = path_new(u16, H, W)

def stats(px, name):
    row = px[H // 2]
    dv = np.diff(row)
    steps = np.abs(dv)
    print('  %-6s 相邻差 max=%d  >=2 的占比 %.1f%%   唯一值 %d/256   空级 %d'
          % (name, steps.max(), 100 * (steps >= 2).mean(),
             len(np.unique(row)), 256 - len(np.unique(row))))

print('曲线 %s  对比 +%d' % (CURVE, CONTRAST))
print('一行 %d 像素里的台阶：' % W)
stats(old, '旧 8bit')
stats(new, '新 16bit')
print()
# 视觉上真正要紧的是"连续几个像素完全一样"——那就是一条色带
def run_lengths(row):
    rl, cur = [], 1
    for i in range(1, len(row)):
        if row[i] == row[i - 1]:
            cur += 1
        else:
            rl.append(cur); cur = 1
    rl.append(cur)
    return np.array(rl)

for name, px in (('旧 8bit', old), ('新 16bit', new)):
    rl = run_lengths(px[H // 2])
    print('  %-6s 最长连续同值 %d 像素，平均游程 %.2f' % (name, rl.max(), rl.mean()))

# ---------------------------------------------------------------- 出图

def jumpmap(px, thr=2):
    """把「相邻像素跳了 >= thr 个码值」的位置标红 —— 色阶断层就是这些位置连成的线。"""
    row = px[H // 2].astype(int)
    img = np.full((46, W, 3), 246, np.uint8)
    n = 0
    for i in range(1, W):
        if abs(row[i] - row[i - 1]) >= thr:
            img[:, i] = (206, 46, 46)
            n += 1
    return Image.fromarray(img), n

def panel(px, label):
    return Image.fromarray(px.astype(np.uint8), 'L').convert('RGB')

gap = 10
canvas = Image.new('RGB', (W, H * 2 + gap + 44), (18, 18, 18))
canvas.paste(panel(old, 'old'), (0, 22))
canvas.paste(panel(new, 'new'), (0, H + gap + 36))
from PIL import ImageDraw
dr = ImageDraw.Draw(canvas)
dr.text((6, 6), 'OLD  8 bit path (u16 >> 8, then curve)', fill=(240, 200, 120))
dr.text((6, H + gap + 24), 'NEW  16 bit path (curve in u16, dither at the end)', fill=(140, 220, 160))

# 右侧放大 6 倍的一组窄条，看断层
zoom_w = 200
crop_box = (W - zoom_w, H // 2 - 6, W, H // 2 + 26)
zooms = []
for px in (old, new):
    im = Image.fromarray(px.astype(np.uint8), 'L').convert('RGB').crop(crop_box)
    zooms.append(im.resize((zoom_w * 3, 32 * 6), Image.NEAREST))
zcanvas = Image.new('RGB', (zoom_w * 3 * 2 + gap, 32 * 6 + 44), (18, 18, 18))
zcanvas.paste(zooms[0], (0, 22))
zcanvas.paste(zooms[1], (zoom_w * 3 + gap, 22))
dz = ImageDraw.Draw(zcanvas)
dz.text((6, 6), 'OLD  x6', fill=(240, 200, 120))
dz.text((zoom_w * 3 + gap + 6, 6), 'NEW  x6', fill=(140, 220, 160))

out = os.path.join(HERE, 'banding_16bit.jpg')
canvas.save(out, quality=94)
zcanvas.save(os.path.join(HERE, 'banding_zoom.jpg'), quality=94)

# 去趋势图：台阶本身就是这条曲线上的锯齿
dh, dw = 40, W
m_old, n_old = jumpmap(old)
m_new, n_new = jumpmap(new)
dc = Image.new('RGB', (dw, 46 * 2 + gap + 56), (18, 18, 18))
dc.paste(m_old, (0, 26))
dc.paste(m_new, (0, 46 + gap + 48))
dd = ImageDraw.Draw(dc)
dd.text((6, 8), 'OLD  8 bit: %d places where a neighbour jumps >= 2 levels' % n_old, fill=(240, 200, 120))
dd.text((6, 46 + gap + 30), 'NEW  16 bit: %d' % n_new, fill=(140, 220, 160))
dc.save(os.path.join(HERE, 'banding_detrend.jpg'), quality=94)
print('写出 detrend')
print()
print('写出', out)
