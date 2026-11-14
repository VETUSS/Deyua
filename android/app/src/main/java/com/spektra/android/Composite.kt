package com.spektra.android

import android.graphics.Bitmap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 两张画面合成一张"底片"。
 *
 * 这两种玩法在物理上是同一件事的两种排布：
 *  - **双重曝光**：同一格片门曝两次，两次的光**相加**（不是叠加图层），所以先回到
 *    线性光再加，加完再编码回 sRGB；暗部两次加起来变亮、高光相加会顶到相纸上限，
 *    和真的按两次快门一样。
 *  - **半格组**：一格 135 里放两格 18×24，中间隔 1 mm，整条 37×24 —— 引擎的
 *    `overscan_pair` 就是按这个比例切两扇门的，所以合成完交给它就行。
 *
 * 两张源都按 cover 铺满目标框（不拉伸变形），取最中间的那块；采样是双线性，
 * 免得缩下来的边出现锯齿。
 */
object Composite {

    /** 引擎里半格对的推进量（kPairAdvanceMM = 19 mm，门 18 + 间隔 1） */
    private const val PAIR_ADVANCE_MM = 19f
    private const val HALF_GATE_ALONG_MM = 18f
    private const val HALF_GATE_ACROSS_MM = 24f

    /** 半格对的整幅比例：37 × 24 */
    const val PAIR_ASPECT = (HALF_GATE_ALONG_MM + PAIR_ADVANCE_MM) / HALF_GATE_ACROSS_MM

    /**
     * 双重曝光：b 以 [weightB] 的曝光量叠在 a 上。
     *
     * [linearIn] 为 true 表示两张都是线性光（RAW 解码出来的），直接相加；
     * 否则按 sRGB 编码值解释，先线性化再加。
     */
    fun doubleExposure(
        a: Triple<FloatArray, Int, Int>,
        b: Triple<FloatArray, Int, Int>,
        weightB: Float = 1f,
        linearIn: Boolean = false,
    ): Triple<FloatArray, Int, Int> {
        val (pa, aw, ah) = a
        val out = FloatArray(aw * ah * 3)
        // a 原样进入
        System.arraycopy(pa, 0, out, 0, min(pa.size, out.size))
        val (pb, bw, bh) = b
        val w = weightB.coerceIn(0f, 2f)

        // b 按 cover 铺满同一张画幅，居中取样
        val k = max(aw.toFloat() / bw, ah.toFloat() / bh)
        val sw = aw / k
        val sh = ah / k
        val sx0 = (bw - sw) / 2f
        val sy0 = (bh - sh) / 2f

        for (y in 0 until ah) {
            val v = sy0 + (y + 0.5f) * sh / ah
            for (x in 0 until aw) {
                val u = sx0 + (x + 0.5f) * sw / aw
                val i = (y * aw + x) * 3
                for (c in 0..2) {
                    val vb = sample(pb, bw, bh, u, v, c)
                    val va = out[i + c]
                    out[i + c] = if (linearIn) va + vb * w else addSrgb(va, vb * w)
                }
            }
        }
        return Triple(out, aw, ah)
    }

    /**
     * 半格组：a 在左格，b 在右格，整张 37×24（横着拿）。
     *
     * 每格都是 18×24 的竖幅（半格竖着拿）。两张各自 cover 进自己的门里，
     * 中间的 1 mm 不画任何东西 —— 引擎会把那道缝当片基。
     */
    fun halfFramePair(
        a: Triple<FloatArray, Int, Int>,
        b: Triple<FloatArray, Int, Int>,
        maxWidth: Int = 2400,
    ): Triple<FloatArray, Int, Int> {
        val unit = min(maxWidth / (HALF_GATE_ALONG_MM + PAIR_ADVANCE_MM), 1600f / HALF_GATE_ACROSS_MM)
        val dh = max(64, (HALF_GATE_ACROSS_MM * unit).roundToInt())
        val dw = max(64, (PAIR_ASPECT * dh).roundToInt())
        val gateW = (HALF_GATE_ALONG_MM / (HALF_GATE_ALONG_MM + PAIR_ADVANCE_MM) * dw).roundToInt()
        val adv = dw - gateW * 2

        val out = FloatArray(dw * dh * 3)
        blitFit(a, out, dw, dh, 0, 0, gateW, dh)
        blitFit(b, out, dw, dh, gateW + adv, 0, gateW, dh)
        return Triple(out, dw, dh)
    }

    /** 把 src 按 cover 铺进 dst 的一个矩形（双线性） */
    private fun blitFit(
        src: Triple<FloatArray, Int, Int>,
        dst: FloatArray, dw: Int, dh: Int,
        rx: Int, ry: Int, rw: Int, rh: Int,
    ) {
        val (ps, sw, sh) = src
        if (rw <= 0 || rh <= 0) return
        val k = max(rw.toFloat() / sw, rh.toFloat() / sh)
        val cu = rw / k
        val cv = rh / k
        val u0 = (sw - cu) / 2f
        val v0 = (sh - cv) / 2f
        for (y in 0 until rh) {
            val dy = ry + y
            if (dy < 0 || dy >= dh) continue
            val v = v0 + (y + 0.5f) * cv / rh
            for (x in 0 until rw) {
                val dx = rx + x
                if (dx < 0 || dx >= dw) continue
                val u = u0 + (x + 0.5f) * cu / rw
                val o = (dy * dw + dx) * 3
                dst[o] = sample(ps, sw, sh, u, v, 0)
                dst[o + 1] = sample(ps, sw, sh, u, v, 1)
                dst[o + 2] = sample(ps, sw, sh, u, v, 2)
            }
        }
    }

    /** Bitmap → 0–1 的 sRGB 浮点，给"第一张是 RAW、先落到显示空间"用 */
    fun bitmapToFloats(b: Bitmap): Triple<FloatArray, Int, Int> {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val f = FloatArray(w * h * 3)
        var i = 0
        for (p in px) {
            f[i++] = ((p shr 16) and 0xFF) / 255f
            f[i++] = ((p shr 8) and 0xFF) / 255f
            f[i++] = (p and 0xFF) / 255f
        }
        return Triple(f, w, h)
    }

    fun toBitmap(p: FloatArray, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0 || p.size < w * h * 3) return null
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = (p[i * 3].coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (p[i * 3 + 1].coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (p[i * 3 + 2].coerceIn(0f, 1f) * 255f).roundToInt()
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun sample(p: FloatArray, w: Int, h: Int, u: Float, v: Float, c: Int): Float {
        val x = u - 0.5f
        val y = v - 0.5f
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)
        val xa = x0.coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1)
        val p00 = p[(ya * w + xa) * 3 + c]
        val p10 = p[(ya * w + x1) * 3 + c]
        val p01 = p[(y1 * w + xa) * 3 + c]
        val p11 = p[(y1 * w + x1) * 3 + c]
        val a = p00 + (p10 - p00) * fx
        val b = p01 + (p11 - p01) * fx
        return a + (b - a) * fy
    }

    private fun toLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()

    private fun toSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else (1.055 * Math.pow(v.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

    private fun addSrgb(a: Float, b: Float): Float =
        toSrgb(toLinear(a.coerceIn(0f, 1f)) + toLinear(b.coerceIn(0f, 1f))).coerceIn(0f, 1f)
}
