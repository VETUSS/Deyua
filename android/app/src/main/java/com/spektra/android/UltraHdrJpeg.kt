package com.spektra.android

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ultra HDR JPEG 封装（v0.8.41，Lightroom 高光恢复方案，libultrahdr 兼容布局）。
 *
 * 结构（Android Ultra HDR 规范 v1.1 / ISO 21496-1 兼容，字节对齐 Google libultrahdr 参考实现）：
 *   [SDR 主图 JPEG] + [APP1 XMP 主图：hdrgm:Version + GContainer 目录(Primary/GainMap)]
 *   + [APP2 MPF Index IFD] + [主图扫描段]
 *   + [gain map 8bit JPEG 次图] 内嵌 [APP1 XMP 次图：hdrgm 增益元数据]
 *
 * 关键（决定 Android 是否认的关键）：
 *   - libultrahdr 解码从「gain map 次图自己的 XMP」读增益元数据（GainMapMin/Max/Gamma/
 *     OffsetSDR/OffsetHDR/HDRCapacityMin/Max/BaseRenditionIsHDR），不是主图 XMP！
 *   - 主图 XMP 只放 Version(格式信号) + GContainer 目录，供定位增益图。
 *   - MPF：大端、3 标签(0xB000/0xB001/0xB002)、版本值 ASCII "0100"、偏移相对 TIFF。
 *
 * 高光恢复设计（Lightroom 做法）：
 *   - SDR 主图 = 当前胶片/相纸观感（高光滚到纸白），完全不变，非 HDR 设备看到的就是它；
 *   - gain map = 编码「胶片滚掉的高光被拉回」：从 SDR 亮度派生 Yhdr = 高光恢复曲线(Ysdr)，
 *     pixel_gain=(Yhdr+1/64)/(Ysdr+1/64)，HDR 屏据此把高光提亮，SDR 屏回退无变化。
 *   - 不再依赖引擎 EDR（其设计是深阴影+纸白封顶，gain map 必然平直、肉眼不可见）。
 *
 * gain map 按规范建议降到主图 1/16（每维 1/4），解码端双线性上采样。
 * XMP 的 GainMapMin/Max、HDRCapacityMin/Max 存 log2 值（Android 解码直接作为 log2 boost 使用）。
 */
object UltraHdrJpeg {

    private const val OFF_SDR = 1.0 / 64.0   // 0.015625，防除零（规范推荐值）
    private const val OFF_HDR = 1.0 / 64.0
    private const val XAP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    // ---- 单帧回退：从 SDR 亮度合成（没有第二帧时用）----
    /** 高光恢复峰值：相对 SDR 的最大线性亮度倍数（= GainMapMax 的线性值，可调） */
    const val HDR_PEAK = 4.0
    /** 高光恢复起点（线性亮度）：低于此不提升，保持 SDR/HDR 在中灰以下一致 */
    const val KNEE = 0.5
    /** 恢复打满点（线性亮度）：高光在此处达到 HDR_PEAK 全量（胶片滚掉的纸白附近，保证肉眼可见） */
    const val RECOVERY_TOP = 0.85

    // ---- 双帧：真·高光恢复 ----
    /**
     * 第二帧比第一帧多印的档数（print_exposure × 2^3 = ×8）。
     *
     * 3 这个数字是从相纸曲线反推的：有效范围约 3.1 档
     * （kodak_portra_endura 的 D-logE 在 8bit 250→16 之间跨 0.93 log10），
     * 所以一帧 +3 档正好把"原本压在趾部的那 3 档"整体推进直线段。
     * 再多会把暗部整块压死（第二帧只用来读高光，暗部仍取第一帧）。
     */
    const val HDR_EV_STEP = 3.0
    /** 抬升起点：低于它的像素 SDR 已经表达得出来，不该再提亮 */
    private const val LIFT_FROM = 225.0
    /** 全量点：到此已经完全是"印成纸白"的像素，增益全部生效 */
    private const val LIFT_FULL = 252.0

    /**
     * 「第二帧码值 H → 增益」查找表，**每张相纸一张**，32 档，索引 = H / 8。
     *
     * 由各相纸自己的 D-logE 曲线烘焙（tools 脚本 work/build_paper_luts.py）：
     * 对每个 logE 同时算出第一帧码值 S 与第二帧码值 H（第二帧多印 3 档），
     * 再按"真实亮度比纸白亮多少档"定增益。
     *
     * **为什么不共用一张表**：8 张相纸的纸白锚点从 8 到 111 不等（高反差相纸的
     * 趾部短得多）。而且 8bit 量化把整个趾部压成 254/255 两三个码值，运行时
     * 根本没法靠像素位置反推锚点 —— 离线试过，标定出来的锚点恒在 254 附近，
     * 误差 2.9 档，比不标定还差。所以老老实实按 print_stock 查表。
     *
     * 每张表最后一项（H≈252）：第二帧也顶白，即真实亮度至少还比纸白亮 3 档，
     * 那是下限而不是测量值，按 +3EV 的峰值给。
     *
     * 离线自验证（模拟量化到 8bit）：平均误差 0.30~0.45 档、最大 0.59 档。
     */
    private val GAIN_BY_PAPER: Map<String, DoubleArray> = mapOf(
        "fujifilm_crystal_archive_typeii" to doubleArrayOf(
            1.002, 1.002, 1.002, 1.002, 1.024, 1.097, 1.168, 1.237,
            1.307, 1.378, 1.451, 1.526, 1.602, 1.681, 1.763, 1.849,
            1.937, 2.027, 2.123, 2.223, 2.328, 2.439, 2.561, 2.692,
            2.836, 2.995, 3.175, 3.388, 3.653, 4.011, 4.583, 6.003,
        ),
        "kodak_2383" to doubleArrayOf(
            1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.002,
            1.002, 1.002, 1.002, 1.002, 1.002, 1.003, 1.057, 1.113,
            1.173, 1.236, 1.304, 1.376, 1.456, 1.544, 1.640, 1.748,
            1.869, 2.006, 2.170, 2.377, 2.648, 3.028, 3.668, 5.322,
        ),
        "kodak_2393" to doubleArrayOf(
            1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.002,
            1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.030, 1.084,
            1.141, 1.202, 1.267, 1.337, 1.414, 1.497, 1.590, 1.694,
            1.811, 1.947, 2.108, 2.311, 2.582, 2.960, 3.615, 5.336,
        ),
        "kodak_ektacolor_edge" to doubleArrayOf(
            1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.047, 1.104,
            1.157, 1.209, 1.261, 1.312, 1.362, 1.415, 1.468, 1.523,
            1.581, 1.640, 1.706, 1.774, 1.848, 1.929, 2.018, 2.117,
            2.232, 2.367, 2.529, 2.726, 2.983, 3.335, 3.950, 5.718,
        ),
        "kodak_endura_premier" to doubleArrayOf(
            1.014, 1.124, 1.305, 1.436, 1.546, 1.642, 1.730, 1.814,
            1.894, 1.970, 2.046, 2.120, 2.194, 2.269, 2.343, 2.420,
            2.498, 2.581, 2.664, 2.755, 2.846, 2.949, 3.054, 3.174,
            3.303, 3.446, 3.618, 3.821, 4.074, 4.409, 4.923, 6.143,
        ),
        "kodak_portra_endura" to doubleArrayOf(
            1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.002, 1.067,
            1.131, 1.195, 1.258, 1.321, 1.387, 1.454, 1.523, 1.595,
            1.671, 1.750, 1.834, 1.922, 2.018, 2.122, 2.235, 2.360,
            2.500, 2.658, 2.840, 3.058, 3.333, 3.722, 4.347, 6.037,
        ),
        "kodak_supra_endura" to doubleArrayOf(
            1.002, 1.002, 1.002, 1.002, 1.010, 1.083, 1.153, 1.223,
            1.293, 1.364, 1.436, 1.510, 1.586, 1.665, 1.747, 1.832,
            1.919, 2.012, 2.107, 2.207, 2.311, 2.424, 2.545, 2.676,
            2.819, 2.979, 3.161, 3.373, 3.635, 3.996, 4.565, 5.968,
        ),
        "kodak_ultra_endura" to doubleArrayOf(
            1.007, 1.007, 1.116, 1.244, 1.349, 1.440, 1.523, 1.601,
            1.675, 1.748, 1.819, 1.889, 1.961, 2.032, 2.105, 2.179,
            2.255, 2.333, 2.415, 2.502, 2.592, 2.692, 2.798, 2.915,
            3.046, 3.191, 3.358, 3.560, 3.816, 4.170, 4.713, 5.949,
        ),
    )
    /** 表里没有这张相纸（直扫底片 / 新加的相纸）时用 Portra Endura 的，中规中矩。 */
    private val GAIN_BY_H: DoubleArray get() = GAIN_BY_PAPER["kodak_portra_endura"]!!

    // MPF body（大端，libultrahdr 格式）："MPF\0"(4) + 端序(4) + IFD0off(4) + 标签数(2) + 3×12 + nextIFD(4) + 2×16 = 86
    private const val MPF_BODY_SIZE = 4 + 4 + 4 + 2 + 36 + 4 + 32

    /** 高光恢复曲线：线性亮度 y[0,1] -> 恢复后的 HDR 线性亮度。y<=KNEE 保持恒等。 */
    fun highlightRecover(y: Double): Double {
        if (y <= KNEE) return y
        val t = ((y - KNEE) / (RECOVERY_TOP - KNEE)).coerceIn(0.0, 1.0)
        val ss = t * t * (3.0 - 2.0 * t)   // smoothstep，过渡无断层
        return y * (1.0 + (HDR_PEAK - 1.0) * ss)
    }

    /** 单像素 gain：给定 SDR sRGB 像素 -> (Yhdr+1/64)/(Ysdr+1/64) */
    fun pixelGain(srgb: Int): Double {
        val r = srgbDecode((srgb ushr 16) and 0xFF)
        val g = srgbDecode((srgb ushr 8) and 0xFF)
        val b = srgbDecode(srgb and 0xFF)
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val yhdr = highlightRecover(y)
        return (yhdr + OFF_HDR) / (y + OFF_SDR)
    }

    // ------------------------------------------------------------------
    // 双帧：用"多印 3 档"的第二帧读回真实高光
    // ------------------------------------------------------------------

    /**
     * 为什么单帧做不到、第二帧做得到：
     *
     * 相纸的 D-logE 曲线在趾部（纸白一侧，logE 大约 < -0.8）是**平**的 ——
     * 8bit 从 255 一直到 254 覆盖了 4 档多曝光，全都印成同一个白。所以场景
     * 亮过"纸白临界"之后，信息不是被输出的 clamp 砍掉的，是在相纸曲线上就
     * 压扁了；上一版从 SDR 亮度反推曲线，只能把 255 附近那十来个码值拉开，
     * 拉开的是噪声，不是细节。
     *
     * 第二帧做的是同一张负片、同一台放大机，只把印相曝光加大 2^3 = 8 倍
     * （整张照片变暗）。原本落在趾部的高光因此被推进直线段，重新有了梯度：
     *   第一帧印成 255 的像素，在第二帧可能是 180 / 210 / 235 ——
     *   越暗说明它真实的亮度越高，这就是信息。
     *
     * 查表换算，而不是"码值差 ÷ 每档码值"：趾部里 246→254 就跨了整整一档，
     * 线性会让最亮处直接过冲到峰值。色度仍取自 SDR 主图 —— 两帧在
     * gain map 的 1/4 分辨率上做的是双线性上采样，色度也跟过去容易在过渡带
     * 出现彩色镶边。
     */
    private fun luma8(c: Int): Double =
        0.2126 * ((c ushr 16) and 0xFF) + 0.7152 * ((c ushr 8) and 0xFF) + 0.0722 * (c and 0xFF)

    /**
     * 双帧单像素增益：s 是第一帧（SDR 主图），h 是第二帧（多印 3 档），
     * [table] 是当前相纸那张 32 档表。
     *
     * 档与档之间线性插值。**不能**用"码值差 ÷ 每档码值"那种线性换算法：
     * 趾部里 246→254 就跨了整整一档，线性会让最亮处直接过冲到峰值。
     */
    private fun dualGain(s: Int, h: Int, table: DoubleArray, peak: Double): Double {
        val ys = luma8(s)
        if (ys <= LIFT_FROM) return 1.0
        val hn = luma8(h).coerceIn(0.0, 255.0)
        val pos = hn / 8.0 - 0.5
        val i0 = pos.toInt().coerceIn(0, table.size - 1)
        val i1 = (i0 + 1).coerceAtMost(table.size - 1)
        val t = (pos - i0).coerceIn(0.0, 1.0)
        val g = (table[i0] * (1.0 - t) + table[i1] * t).coerceIn(1.0, peak)
        // 225→252 之间用 smoothstep 接上去：纸白边缘不能有硬边，否则 HDR 屏上
        // 会看到一圈"发光的轮廓线"。
        val w = ((ys - LIFT_FROM) / (LIFT_FULL - LIFT_FROM)).coerceIn(0.0, 1.0)
        val ss = w * w * (3.0 - 2.0 * w)
        return 1.0 + (g - 1.0) * ss
    }

    /**
     * 把 SDR 位图转成 HDR 观感位图（预览/相册的「HDR 显示」，与导出 gain map 同一曲线）。
     * 逐通道 (c+off)*gain-off 应用，保留色相、提升高光。
     */
    fun hdrLook(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (i in px.indices) out[i] = applyHdrPixel(px[i])
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyHdrPixel(srgb: Int): Int {
        val r0 = srgbDecode((srgb ushr 16) and 0xFF)
        val g0 = srgbDecode((srgb ushr 8) and 0xFF)
        val b0 = srgbDecode(srgb and 0xFF)
        val y = 0.2126 * r0 + 0.7152 * g0 + 0.0722 * b0
        val gain = (highlightRecover(y) + OFF_HDR) / (y + OFF_SDR)
        val r = srgbEncode(((r0 + OFF_SDR) * gain - OFF_HDR).coerceIn(0.0, 1.0))
        val g = srgbEncode(((g0 + OFF_SDR) * gain - OFF_HDR).coerceIn(0.0, 1.0))
        val b = srgbEncode(((b0 + OFF_SDR) * gain - OFF_HDR).coerceIn(0.0, 1.0))
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** gain(线性, 1..peak) -> 8bit（对数域：字节0=增益1、255=峰值，规范约定） */
    private fun encodeLog2(gain: Double, peak: Double, log2Peak: Double): Int {
        val lg = Math.log(gain.coerceIn(1.0, peak)) / Math.log(2.0)
        return (lg / log2Peak * 255.0).toInt().coerceIn(0, 255)
    }

    /**
     * 编码为 Ultra HDR JPEG。
     *
     * [r27] `exifApp1` 是 [Exporter.exifApp1] 给出的 APP1 **载荷**
     * （"Exif\0\0" + TIFF，不含 FFE1 与长度）。以前这一档是 5 个格式里唯一
     * 不带配方/拍摄信息的：预编码字节直接走 saveBytes，绕过了 Exporter 里写
     * EXIF 的那条链。现在把它作为普通 APP1 段插在主图 SOS 之前 —— 与 XMP、
     * MPF 同一机制，不重写 JPEG、不动尾部的 gain map，容器偏移照常从最终
     * 数组反算，离线字节校验（work/check_uhdr.py）两项都通过。
     *
     * [r29] `highlightFrame` 非空时走**双帧**：它是同一张负片、印相多 [evStep]
     * 档的那一帧，用来读回被相纸趾部压平的真实高光（见 [dualGain] 的说明）。
     * 传 null 就退回上一版"从 SDR 亮度合成曲线"的做法 —— 那种做法不会有新
     * 信息（纸白就是天花板），但对没有 RAW 余量的片子是无害的默认。
     */
    fun encode(sdrBitmap: Bitmap, w: Int, h: Int, quality: Int = 90,
               exifApp1: ByteArray? = null,
               highlightFrame: Bitmap? = null,
               evStep: Double = HDR_EV_STEP,
               paperId: String? = null): ByteArray {
        val dual = highlightFrame != null
        val peak = if (dual) Math.pow(2.0, evStep) else HDR_PEAK
        val log2Peak = Math.log(peak) / Math.log(2.0)
        // 相纸 → 增益表的查表用哪一张。直扫底片（没有相纸）或新加的相纸落在默认表上。
        val gainTable = GAIN_BY_PAPER[paperId] ?: GAIN_BY_H
        val tableName = if (GAIN_BY_PAPER.containsKey(paperId)) paperId else "默认(portra_endura)"
        // ---------- 1. 主图 JPEG（SDR，保持胶片观感，非 HDR 端所见即所得）----------
        val mainBos = ByteArrayOutputStream()
        sdrBitmap.compress(Bitmap.CompressFormat.JPEG, quality, mainBos)
        val mainJpeg = mainBos.toByteArray()

        // ---------- 2. gain map：降到主图 1/16（每维 1/4，规范建议），解码端双线性上采样 ----------
        val gw = maxOf(1, w / 4); val gh = maxOf(1, h / 4)
        val small = Bitmap.createScaledBitmap(sdrBitmap, gw, gh, true)
        val spx = IntArray(gw * gh)
        small.getPixels(spx, 0, gw, 0, 0, gw, gh)
        small.recycle()
        // 第二帧同步缩到同一个 gw×gh —— 两帧必须逐像素对齐才能比。
        var hpx: IntArray? = null
        if (highlightFrame != null) {
            val hs = Bitmap.createScaledBitmap(highlightFrame, gw, gh, true)
            hpx = IntArray(gw * gh)
            hs.getPixels(hpx, 0, gw, 0, 0, gw, gh)
            hs.recycle()
        }
        val gmRgb = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        // 统计这张图的 gain 分布 —— 这是"HDR 到底有没有效"唯一能量化的证据。
        // 单帧曲线是 KNEE 0.5 以下恒等、RECOVERY_TOP 0.85 以上打满；双帧则看
        // 第二帧真正读出了多少超出纸白的档数。
        var gMax = 1.0
        var boosted = 0            // gain > 1.05（肉眼刚能分辨）
        var strong = 0             // gain > 1.5（明显更亮）
        var boostSum = 0.0
        if (hpx != null) {
            // 第二帧在纸白区仍然顶到 255 的像素 = 真实高光超过第二帧能测的范围（再亮 3 档以上），
            // 这些只能拿到"至少这么多"的下限，增益被 clamp 到峰值。
            var clipped2 = 0
            for (i in spx.indices) {
                val g = dualGain(spx[i], hpx[i], gainTable, peak)
                if (luma8(spx[i]) > LIFT_FULL && luma8(hpx[i]) > 253.0) clipped2++
                if (g > gMax) gMax = g
                if (g > 1.05) { boosted++; boostSum += g }
                if (g > 1.5) strong++
                val v = encodeLog2(g, peak, log2Peak)
                gmRgb.setPixel(i % gw, i / gw, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
            val n = spx.size.coerceAtLeast(1)
            Diagnostics.log("hdr", "双帧 增益图 ${gw}x$gh +${"%.1f".format(evStep)}EV · 相纸表=$tableName" +
                " · 增益>1.05 的 %.1f%% · >1.5 的 %.1f%% · 最大 %.2f · 均值 %.2f" +
                " · 第二帧仍顶白的 %.1f%%（超出可测范围，按峰值封顶）"
                    .format(boosted * 100.0 / n, strong * 100.0 / n, gMax,
                        if (boosted > 0) boostSum / boosted else 1.0, clipped2 * 100.0 / n))
        } else {
            for (i in spx.indices) {
                val g = pixelGain(spx[i])
                if (g > gMax) gMax = g
                if (g > 1.05) { boosted++; boostSum += g }
                if (g > 1.5) strong++
                val v = encodeLog2(g, peak, log2Peak)
                gmRgb.setPixel(i % gw, i / gw, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
            val n = spx.size.coerceAtLeast(1)
            Diagnostics.log("hdr", "单帧 增益图 ${gw}x$gh 增益>1.05 的像素 %.1f%% · >1.5 的 %.1f%%" +
                " · 最大 %.2f · 被提亮部分均值 %.2f（无第二帧：纸白封顶，只有提亮没有新细节）"
                    .format(boosted * 100.0 / n, strong * 100.0 / n, gMax,
                        if (boosted > 0) boostSum / boosted else 1.0))
        }
        val gmBos = ByteArrayOutputStream()
        gmRgb.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(80, 92), gmBos)
        val gainJpeg = gmBos.toByteArray()
        gmRgb.recycle()

        // ---------- 3. gain map 次图内嵌元数据 XMP（libultrahdr 从这里读增益元数据！）----------
        val secSeg = buildAppSegment(0xE1, XAP_NS.toByteArray(Charsets.US_ASCII) + buildXmpSecondary(log2Peak))
        val gainMapStream = insertBeforeSos(gainJpeg, secSeg)
        val gainMapSize = gainMapStream.size

        // ---------- 4. 主图 XMP：hdrgm:Version + GContainer 目录（Primary/GainMap）----------
        val primSeg = buildAppSegment(0xE1, XAP_NS.toByteArray(Charsets.US_ASCII) + buildXmpPrimary(gainMapSize))
        var primary = insertBeforeSos(mainJpeg, primSeg)
        // EXIF 与 XMP 都是 APP1，顺序无所谓，但必须在 SOS 之前；插完再算 MPF 偏移。
        if (exifApp1 != null && exifApp1.size in 7..65530) {
            primary = insertBeforeSos(primary, buildAppSegment(0xE1, exifApp1))
        }

        // ---------- 5. MPF Index IFD（libultrahdr 精确格式，偏移相对 TIFF）----------
        val mpfSegSize = MPF_BODY_SIZE + 4  // body(86) + FF E2 + len(2)
        val sosInMain = findMarker(primary, 0xDA)          // MPF 将插在此处
        val gainMapFileOffset = primary.size + mpfSegSize  // 拼上 MPF 后 gain map 的文件偏移
        val tiffOffset = sosInMain + 2 + 2 + 4                 // +FF E2, len, "MPF\0" -> 端序块(相对TIFF基准)
        val secondaryOffset = gainMapFileOffset - tiffOffset   // MPF 里 gain map 偏移（相对 TIFF）
        val primarySize = primary.size + mpfSegSize
        val app2 = buildMpfSegment(primarySize, 0, gainMapSize, secondaryOffset)
        val finalMain = insertBeforeSos(primary, app2)

        // ---------- 6. 拼接：主图 + gain map 次图（含其 XMP）----------
        return finalMain + gainMapStream
    }

    private fun srgbDecode(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun srgbEncode(c: Double): Int {
        val c2 = c.coerceIn(0.0, 1.0)
        val v = if (c2 <= 0.0031308) c2 * 12.92 else 1.055 * Math.pow(c2, 1.0 / 2.4) - 0.055
        return (v * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    /** 主图 XMP：hdrgm:Version(格式信号) + GContainer 目录（Primary + GainMap，含 Item:Length）。
     *  字节对齐 libultrahdr generateXmpForPrimaryImage（属性风格，无 xpacket 包装）。 */
    private fun buildXmpPrimary(gainMapStreamSize: Int): ByteArray {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="$gainMapStreamSize"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        return xmp.toByteArray(Charsets.UTF_8)
    }

    /** gain map 次图 XMP：实际增益元数据（libultrahdr 解码从次图 XMP 读这些字段）。
     *  GainMapMin/Max、HDRCapacityMin/Max 为 log2 值。字节对齐 libultrahdr generateXmpForSecondaryImage。
     *  [r29] `log2Peak` 由调用方给：单帧是 log2(4)=2.0，双帧是 log2(2^evStep)=evStep。 */
    private fun buildXmpSecondary(log2Peak: Double): ByteArray {
        val gmMaxLog2 = String.format(java.util.Locale.US, "%.6f", log2Peak)
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMin="0.000000" hdrgm:GainMapMax="$gmMaxLog2" hdrgm:Gamma="1.0" hdrgm:OffsetSDR="0.015625" hdrgm:OffsetHDR="0.015625" hdrgm:HDRCapacityMin="0.000000" hdrgm:HDRCapacityMax="$gmMaxLog2" hdrgm:BaseRenditionIsHDR="False"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        return xmp.toByteArray(Charsets.UTF_8)
    }

    /** 构造 APP 段：FF Ex + 长度(含自身) + payload */
    private fun buildAppSegment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return ByteArrayOutputStream().apply {
            write(0xFF); write(marker)
            write((len ushr 8) and 0xFF); write(len and 0xFF)
            write(payload)
        }.toByteArray()
    }

    /** 构造 MPF Index IFD 段（APP2, "MPF\0"），libultrahdr 精确格式（大端）。
     *  primarySize/primaryOffset：主图流；secondarySize/secondaryOffset：gain map 次图（offset 相对 TIFF）。 */
    private fun buildMpfSegment(primarySize: Int, primaryOffset: Int, secondarySize: Int, secondaryOffset: Int): ByteArray {
        val be = ByteOrder.BIG_ENDIAN
        val body = ByteArrayOutputStream()
        body.write("MPF".toByteArray(Charsets.US_ASCII)); body.write(0)
        // 端序块（4字节）："MM" + magic 0x2A00（libultrahdr 参考为大端）
        body.write(ByteBuffer.allocate(4).order(be).putInt(0x4D4D002A).array())
        // IFD0 offset = 8
        body.write(ByteBuffer.allocate(4).order(be).putInt(8).array())
        // 标签数 = 3
        body.write(ByteBuffer.allocate(2).order(be).putShort(3).array())
        // 标签1 0xB000(MPFVersion): type=7, count=4, 内联值=ASCII "0100"
        body.write(ByteBuffer.allocate(2).order(be).putShort(0xB000.toShort()).array())
        body.write(ByteBuffer.allocate(2).order(be).putShort(7).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(4).array())
        body.write("0100".toByteArray(Charsets.US_ASCII))
        // 标签2 0xB001(NumberOfImages): type=4, count=1, value=2
        body.write(ByteBuffer.allocate(2).order(be).putShort(0xB001.toShort()).array())
        body.write(ByteBuffer.allocate(2).order(be).putShort(4).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(1).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(2).array())
        // 标签3 0xB002(MPEntry): type=7, count=32, value=entries_offset(相对TIFF)=50
        body.write(ByteBuffer.allocate(2).order(be).putShort(0xB002.toShort()).array())
        body.write(ByteBuffer.allocate(2).order(be).putShort(7).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(32).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(50).array())
        // nextIFD = 0
        body.write(ByteBuffer.allocate(4).order(be).putInt(0).array())
        // MP Entry 1 (primary): attr=0x030000, size, offset=0, dep=0
        body.write(ByteBuffer.allocate(4).order(be).putInt(0x030000).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(primarySize).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(primaryOffset).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(0).array())
        // MP Entry 2 (gain map): attr=0x020000, size=secondarySize, offset=secondaryOffset, dep=0
        body.write(ByteBuffer.allocate(4).order(be).putInt(0x020000).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(secondarySize).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(secondaryOffset).array())
        body.write(ByteBuffer.allocate(4).order(be).putInt(0).array())
        val bodyBytes = body.toByteArray()
        val len = bodyBytes.size + 2
        // 完整段 = FF E2 + len(2) + body；len 含自身 2 字节
        return ByteArrayOutputStream().apply {
            write(0xFF); write(0xE2)
            write((len ushr 8) and 0xFF); write(len and 0xFF)
            write(bodyBytes)
        }.toByteArray()
    }

    /** 在 JPEG 第一个 SOS(FFDA) 前插入一个 APP 段 */
    private fun insertBeforeSos(jpeg: ByteArray, seg: ByteArray): ByteArray {
        val sos = findMarker(jpeg, 0xDA)
        return if (sos < 0) jpeg else ByteArrayOutputStream().apply {
            write(jpeg, 0, sos)
            write(seg)
            write(jpeg, sos, jpeg.size - sos)
        }.toByteArray()
    }

    private fun findMarker(jpeg: ByteArray, marker: Int): Int {
        var i = 2
        while (i < jpeg.size) {
            if (jpeg[i].toInt() and 0xFF != 0xFF) { i++; continue }
            var j = i + 1
            while (j < jpeg.size && (jpeg[j].toInt() and 0xFF) == 0xFF) j++
            if (j >= jpeg.size) return -1
            val m = jpeg[j].toInt() and 0xFF
            if (m == marker) return i
            if (m == 0xD9 || m == 0xDA) return -1
            val len = ((jpeg[j + 1].toInt() and 0xFF) shl 8) or (jpeg[j + 2].toInt() and 0xFF)
            // 段 = [FF][code][len_hi][len_lo][data len-2]，段起于 i(j-1)，下个 marker 起点 = j + len + 1
            i = j + len + 1
        }
        return -1
    }
}
