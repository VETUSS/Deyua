package com.spektra.android

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * 数字中间片的**显示视图**（r49）：Cineon 码值 → 可显示的三通道值。
 *
 * 为什么必须有这一层：
 *
 * 引擎里的 DI（`spk_di_encode`）写出来的是 **Cineon log 码值**，不是画面。那是
 * 交给调色台的中间片格式 —— 参考白 685、片基 95，两端都留了余量。以前 Android 端
 * 把这些码值直接当成显示值画出来，于是白点只到 171/255、中灰 117/255、片基 45/255：
 * 整张图又灰又闷，用户看到的就是「一开数字中间片就死黑」。（r49 之前还有一个更严重的
 * 换算 bug，白点被压到 74/255，那是另一半原因，已在 spk_di_encode.comp 里修掉。）
 *
 * 这一份是 macOS 端 `Canvas/CineonLUT.swift` 的移植 —— 那边画布也是先过这个视图
 * 再显示，所以两端看到的是同一张图。四步：
 *
 *  1. **解码**：Kodak Cineon（白 685 / 黑 95 / 300 codes per decade），与引擎编码
 *     用的是同一组常数，解出来 1.0 = 参考白、0 = 片基。
 *  2. **在亮度上做 tone**：到拐点前**恒等**（DI 自己的反差原样保留，视图不额外加对比），
 *     以上走 RFC-023 的 m=2 平滑肩 `ΔH/√(H²+Δ²)`，把最高码（灰上 +6.2 档）压到
 *     白点下方 1/16 档。拐点是**解出来的**，不是拍的。整段按 `x·T(Y)/Y` 施加，
 *     所以颜色只改变亮度、不改色相。
 *  3. **path to white**：亮度压下去以后仍然出界的颜色（饱和度极高的灯、花），
 *     在 Oklab 里**保持色相与明度**降彩度，直到落进 [0,1]；而不是逐通道硬裁 ——
 *     逐通道裁会把高饱和色相拧歪（红变橙、蓝变紫），那是"数码味"的主要来源。
 *  4. **编码**：sRGB。
 *
 * 引擎侧我们用的是 `output_color_space = sRGB`，所以 DI 的码值是 sRGB 三原色；
 * macOS 用的是 ProPhoto。这里只把矩阵/亮度行换成 BT.709 的那一套，其余常数一致。
 */
internal object DiView {

    private const val WHITE = 685.0
    private const val BLACK = 95.0
    private const val PER_DECADE = 300.0
    private const val MAX_CODE = 1023.0
    private val BLACK_OFFSET = Math.pow(10.0, (BLACK - WHITE) / PER_DECADE)

    /** 码值 0…1 → 线性，1.0 在参考白、0.0 在片基。 */
    fun decode(code: Double): Double {
        val c = code.coerceIn(0.0, 1.0)
        return (Math.pow(10.0, (c * MAX_CODE - WHITE) / PER_DECADE) - BLACK_OFFSET) / (1.0 - BLACK_OFFSET)
    }

    // ---- tone ----

    private const val GREY = 0.18
    private val WHITE_STOPS = Math.log(1.0 / GREY) / Math.log(2.0)
    private val CEILING_STOPS = Math.log(decode(1.0) / GREY) / Math.log(2.0)
    private const val MARGIN = 1.0 / 16.0

    /** RFC-023 的 m=2 肩部：`Δ·room/√(room²+Δ²)`。 */
    private fun shoulder(delta: Double, room: Double): Double =
        delta * room / sqrt(room * room + delta * delta)

    /**
     * 拐点（灰上多少档）：取**最软的**那个 —— 把最高码压到白点下方 1/16 档，
     * 且不低于灰点。二分，落地值随拐点单调上升。
     */
    val knee: Double = run {
        fun landing(k: Double) = k + shoulder(CEILING_STOPS - k, WHITE_STOPS - k)
        val target = WHITE_STOPS - MARGIN
        if (landing(0.0) >= target) {
            0.0
        } else {
            var lo = 0.0
            var hi = WHITE_STOPS - 1e-9
            repeat(200) {
                val mid = (lo + hi) / 2.0
                if (landing(mid) < target) lo = mid else hi = mid
            }
            (lo + hi) / 2.0
        }
    }

    /** 线性亮度进、线性亮度出。 */
    fun tone(y: Double): Double {
        if (y <= 0.0) return 0.0
        val u = Math.log(y / GREY) / Math.log(2.0)
        if (u <= knee) return y
        return GREY * Math.pow(2.0, knee + shoulder(u - knee, WHITE_STOPS - knee))
    }

    // ---- 颜色 ----

    /** BT.709/sRGB 的亮度行（引擎的 DI 就是按 sRGB 三原色编码的）。 */
    private val Y_ROW = doubleArrayOf(0.2126, 0.7152, 0.0722)

    private val REC709_TO_XYZ65 = doubleArrayOf(
        0.4123907993, 0.3575843394, 0.1804807884,
        0.2126390059, 0.7151686788, 0.0721923154,
        0.0193308187, 0.1191947798, 0.9505321522,
    )
    private val OKLAB_M1 = doubleArrayOf(
        0.8189330101, 0.3618667424, -0.1288597137,
        0.0329845436, 0.9293118715, 0.0361456387,
        0.0482003018, 0.2643662691, 0.6338517070,
    )
    private val OKLAB_M2 = doubleArrayOf(
        0.2104542553, 0.7936177850, -0.0040720468,
        1.9779984951, -2.4285922050, 0.4505937099,
        0.0259040371, 0.7827717662, -0.8086757660,
    )

    private fun mul(m: DoubleArray, v: DoubleArray, out: DoubleArray) {
        for (i in 0..2) out[i] = m[3 * i] * v[0] + m[3 * i + 1] * v[1] + m[3 * i + 2] * v[2]
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[3 * i + k] * b[3 * k + j]
            o[3 * i + j] = s
        }
        return o
    }

    private fun inv(m: DoubleArray): DoubleArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        val id = if (det == 0.0) 0.0 else 1.0 / det
        return doubleArrayOf(
            (e * i - f * h) * id, (c * h - b * i) * id, (b * f - c * e) * id,
            (f * g - d * i) * id, (a * i - c * g) * id, (c * d - a * f) * id,
            (d * h - e * g) * id, (b * g - a * h) * id, (a * e - b * d) * id,
        )
    }

    private val TO_LMS = mul(OKLAB_M1, REC709_TO_XYZ65)     // 线性 sRGB → Oklab LMS
    private val FROM_LMS = inv(TO_LMS)
    private val M2_INV = inv(OKLAB_M2)

    private fun inside(r: Double, g: Double, b: Double): Boolean =
        r >= -1e-9 && g >= -1e-9 && b >= -1e-9 && r <= 1 + 1e-9 && g <= 1 + 1e-9 && b <= 1 + 1e-9

    /**
     * 落进 [0,1]：保持 Oklab 的色相与明度，只降彩度。出界的像素才走二分（40 次），
     * 正常像素一次比较就返回 —— 预览是逐像素循环，这一步不能对每颗都做。
     */
    private fun pathToWhite(rgb: DoubleArray) {
        if (inside(rgb[0], rgb[1], rgb[2])) return
        val lms = DoubleArray(3)
        mul(TO_LMS, rgb, lms)
        val lab = DoubleArray(3)
        mul(
            OKLAB_M2,
            doubleArrayOf(cbrt(lms[0]), cbrt(lms[1]), cbrt(lms[2])),
            lab
        )
        val l3 = DoubleArray(3)
        val back = DoubleArray(3)
        fun at(s: Double) {
            mul(M2_INV, doubleArrayOf(lab[0], lab[1] * s, lab[2] * s), l3)
            val c = doubleArrayOf(l3[0] * l3[0] * l3[0], l3[1] * l3[1] * l3[1], l3[2] * l3[2] * l3[2])
            mul(FROM_LMS, c, back)
        }
        at(0.0)
        if (!inside(back[0], back[1], back[2])) {
            for (i in 0..2) rgb[i] = back[i].coerceIn(0.0, 1.0)
            return
        }
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2.0
            at(mid)
            if (inside(back[0], back[1], back[2])) lo = mid else hi = mid
        }
        at(lo)
        for (i in 0..2) rgb[i] = back[i].coerceIn(0.0, 1.0)
    }

    private fun cbrt(v: Double): Double = Math.cbrt(v)

    /** 线性 sRGB → 8bit sRGB 编码值（0…255，未量化到整数）。 */
    private fun srgbEncode(v: Double): Double {
        val c = v.coerceIn(0.0, 1.0)
        return if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
    }

    /**
     * 一张 DI 位图 → 可显示位图。
     *
     * 输入是引擎吐出来的 Cineon 码值（8bit 里就是 0…255），输出是 sRGB 编码值。
     * 不可逆、也不需要逆 —— 显示用的视图，与 macOS 画布同一个函数。
     */
    fun apply(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(px.size)
        val rgb = DoubleArray(3)
        for (i in px.indices) {
            val p = px[i]
            // 解码：8bit 码值只有 256 种，逐像素 pow 三次没必要，直接查表
            rgb[0] = DECODE[(p ushr 16) and 0xFF]
            rgb[1] = DECODE[(p ushr 8) and 0xFF]
            rgb[2] = DECODE[p and 0xFF]
            // 亮度：颜色只按亮度上 tone，色相与饱和度保持（per-channel 曲线会把
            // 亮皮肤的色相推黄，RFC-029 §6.2 量过 +13.6°）
            val y = Y_ROW[0] * rgb[0] + Y_ROW[1] * rgb[1] + Y_ROW[2] * rgb[2]
            if (y > 0.0) {
                val s = tone(y) / y
                rgb[0] *= s; rgb[1] *= s; rgb[2] *= s
            } else {
                rgb[0] = 0.0; rgb[1] = 0.0; rgb[2] = 0.0
            }
            pathToWhite(rgb)
            val r = (srgbEncode(rgb[0]) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            val g = (srgbEncode(rgb[1]) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            val b = (srgbEncode(rgb[2]) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 码值 0…255 → 线性（见 [decode]）。 */
    private val DECODE = DoubleArray(256) { decode(it / 255.0) }
}
