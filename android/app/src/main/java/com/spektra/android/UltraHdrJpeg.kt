package com.spektra.android

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

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
    /**
     * 全画面整体的逆色调映射抬升（r49）。
     *
     * 旧版只抬高光（sRGB 196 以上，也就是线性 0.55 以上），中间调和暗部一律 1.0×。
     * 那样导出的 Ultra HDR 在 HDR 屏上**只有一小撮高光在动**，整张图的"存在感"和
     * SDR 一样 —— 用户说的"HDR 效果不够好"有一半是这个原因。
     *
     * 真实 HDR 照片的 gain 不是从白点才开始涨的：BT.2408 的参考白是 203 nit，而
     * 一张晴天的漫反射白墙就能到 300~500 nit。所以这里按逆色调映射（HDRTVNet /
     * HDRTVDM 的 global adjustment 那一路）给整条上段加一个平缓的增益斜坡：
     * 白点 +18%、中高调 +9%、暗部 0。
     *
     * 取 0.18 而不是更大：这一层是"质感"，不是"亮度"。再大就会变成把 SDR 图直接
     * 提亮，在 HDR 屏上看着发灰、在系统自动降档时还会偏。
     */
    private const val GLOBAL_LIFT = 0.18
    /**
     * 预览用的展开峰值（r48）。
     *
     * 导出用 [HDR_PEAK]=4×，那是给真 HDR 屏的；SDR 屏上把 4× 塞回 0~1 只能压回原样
     * 或者糊白。没有 HDR 面板时预览降到 1.5×，最亮处刚好落在 shoulder 顶端，
     * 看到的是"高光更亮、但仍有层次"。
     *
     * 有 HDR 面板时**不再降档**：预览直接出 scRGB 的 F16 位图（[hdrLookHdr]），
     * 像素值可以真的超过 1.0，屏幕上有多少 headroom 就用多少。
     */
    private const val PREVIEW_PEAK = 1.5

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
    private const val LIFT_FROM = 196.0
    /** 全量点：到此已经完全是"印成纸白"的像素，增益全部生效 */
    private const val LIFT_FULL = 250.0

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

    /**
     * 展开量 s ∈ [0,1]：SDR 线性亮度 y 在 HDR 里拿到多少比例的峰值余量。
     *
     * 两项取 **max** 而不是相加：
     *   - 高光那一项（smoothstep，KNEE=0.5 起、白点打满）负责"纸白以上"的恢复；
     *   - 全局那一项（[GLOBAL_LIFT]，从暗部平缓升到白点）负责整张图的逆色调映射。
     * 相加会在 0.9 以上叠出超过峰值的增益，再 clamp 就又是一条平台 —— 前面为了
     * 消掉平台绕了两版，不能再从另一头造一个。
     */
    private fun shape(y: Double, peak: Double): Double {
        if (peak <= 1.0) return 0.0
        return maxOf(shapeHighlight(y), shapeGlobal(y, peak)).coerceIn(0.0, 1.0)
    }

    /** 高光那一项：KNEE 起、白点打满的 smoothstep。 */
    private fun shapeHighlight(y: Double): Double {
        if (y <= KNEE) return 0.0
        val t = ((y - KNEE) / (1.0 - KNEE)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** 全局那一项：从 0.03 平缓升到白点的 [GLOBAL_LIFT]（折算成 shape 的比例）。 */
    private fun shapeGlobal(y: Double, peak: Double): Double {
        if (peak <= 1.0 || y <= 0.03) return 0.0
        return GLOBAL_LIFT / (peak - 1.0) * smooth01((y - 0.03) / 0.92)
    }

    /**
     * 高光恢复曲线：SDR 线性亮度 y ∈ [0,1] → HDR 场景线性亮度。
     *
     *   y ≤ 0.03          恒等（暗部不动，否则 HDR 的黑会发灰）
     *   以上              y × (1 + (peak-1)·shape(y, peak))
     *
     * [r48] 打满点从 0.85 改到 1.0：旧版在 0.85 就打满并一直保持，0.85~1.0
     * （sRGB 240~255，正是纸白附近的天空、灯、水面反光）拿到完全相同的增益 ——
     * 在 HDR 屏上是一块平掉的发光面，在 SDR 预览里是一条硬边。
     * [r49] 再加全局项 [GLOBAL_LIFT]，见 [shape]。
     */
    fun highlightRecover(y: Double, peak: Double = HDR_PEAK): Double =
        y * (1.0 + (peak - 1.0) * shape(y, peak))

    /** 单像素 gain：给定 SDR sRGB 像素 -> (Yhdr+1/64)/(Ysdr+1/64) */
    fun pixelGain(srgb: Int, peak: Double = HDR_PEAK): Double {
        val y = lumaLin(srgb)
        return (highlightRecover(y, peak) + OFF_HDR) / (y + OFF_SDR)
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
        // --- 纸白以上：第二帧读回来的那一段 ---
        var core = 1.0
        if (ys > LIFT_FROM) {
            val hn = luma8(h).coerceIn(0.0, 255.0)
            val pos = hn / 8.0 - 0.5
            val i0 = pos.toInt().coerceIn(0, table.size - 1)
            val i1 = (i0 + 1).coerceAtMost(table.size - 1)
            val t = (pos - i0).coerceIn(0.0, 1.0)
            // 在 **log2** 域里插值：增益是乘性量（1× 到 6×），线性插值会把高段拉得
            // 比低段"快"，同一张渐变里步长不均匀 —— 那是"过渡不自然"的来源之一。
            val g0 = Math.log(table[i0].coerceAtLeast(1.0)) / Math.log(2.0)
            val g1 = Math.log(table[i1].coerceAtLeast(1.0)) / Math.log(2.0)
            val g = Math.pow(2.0, g0 * (1.0 - t) + g1 * t).coerceIn(1.0, peak)
            // 起抬窗口：196→250 之间 smoothstep，坡散进整段高光而不是画一道亮圈。
            val w = ((ys - LIFT_FROM) / (LIFT_FULL - LIFT_FROM)).coerceIn(0.0, 1.0)
            core = Math.pow(2.0, w * w * (3.0 - 2.0 * w) * Math.log(g) / Math.log(2.0))
        }
        // --- [r49] 整张图的逆色调映射 ---
        // 旧版在 ys<=196 时直接 return 1.0，于是中间调、暗部、皮肤、天空**一个像素
        // 都不进 gain map**：HDR 屏上只有一小撮高光在动，其余和 SDR 一模一样 ——
        // 这就是"HDR 效果不够"最直接的原因。现在把 core 折算进 shape，再和全局项
        // 取 max，两项不会叠加超过峰值。
        if (peak <= 1.0) return 1.0
        val sc = ((core - 1.0) / (peak - 1.0)).coerceIn(0.0, 1.0)
        val sg = shapeGlobal(lumaLin(s), peak)
        return 1.0 + (peak - 1.0) * maxOf(sc, sg)
    }

    /**
     * 把 SDR 位图转成 HDR 观感位图（预览/相册的「HDR 显示」，与导出 gain map 同一曲线）。
     *
     * [r48] 三处改动，都是为了"过渡自然"：
     *  1. **不再硬 clamp 到 1.0**。旧版把 (c+off)*gain 直接 coerceIn(0,1)，于是所有
     *     被提亮的高光都撞成纯白 —— 屏幕上看不到"HDR"，只看到一块块发白，边界还是
     *     硬的。现在过一遍显示端 shoulder：0.86 以下恒等，以上以 1.0 为渐近线平滑
     *     压回（C1），纸白附近从 255 落到约 250，换来的是整整一段高光重新有了层次。
     *  2. **高光去饱和**。参考 AgX 的做法：越接近纯白，颜色越向同亮度的中性灰收。
     *     现实里极亮的物体就是"发白"的，这一步能消掉 HDR 预览里最常见的霓虹色斑
     *     和彩色镶边，也是"看起来像照片"而不是"看起来像被拉亮"的关键。
     *  3. **抖动**。8bit 输出、且上面两步都会压扁渐变；不加抖动会在天空、墙面这种
     *     大面积平滑渐变上留下等高线。这里用 8×8 有序抖动，正负半个码值。
     */
    fun hdrLook(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) out[row + x] = applyHdrPixel(px[row + x], x, y)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 真 HDR 预览（r49）：把预览写成 **RGBA_F16 + EXTENDED_SRGB（scRGB）** 位图。
     *
     * 为什么必须换位图格式：SDR 的 8bit 位图最高就是 1.0，所谓"HDR 模拟"只能把高光
     * 压回去，屏幕上永远看不到比 SDR 白更亮的东西 —— 那正是"HDR 效果不明显"的
     * 根本原因，跟曲线怎么调无关。scRGB 的线性值可以到 1.4、2.0 甚至 4.0，只要窗口
     * 处在 COLOR_MODE_HDR，这些值就真的比界面白更亮（观片台的灯箱用的是同一套机制，
     * 见 LightTableHdr.kt）。
     *
     * 这里**不过显示端 shoulder**：HDR 的面板有自己的 tone mapping，我们再压一次
     * 就等于两套 tone mapper 叠着用（darktable AgX 的文档里专门警告过这件事），
     * 结果一定是发灰。唯一的整形是"高光去饱和"：SDR 里被裁成纯色的灯、反光，
     * 一乘 3~4 倍就是霓虹色；真实世界里极亮的东西是往白里走的。
     *
     * 失败（没有 F16 支持、内存不够）返回 null，调用方退回 [hdrLook] 的 SDR 模拟。
     */
    fun hdrLookHdr(src: Bitmap): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT < 26) null else {
            val w = src.width; val h = src.height
            val bmp = Bitmap.createBitmap(
                w, h, Bitmap.Config.RGBA_F16, true,
                ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
            )
            val buf = LongBuffer.allocate(w * h)
            val strip = 64
            val px = IntArray(w * strip)
            var row = 0
            while (row < h) {
                val rows = minOf(strip, h - row)
                src.getPixels(px, 0, w, 0, row, w, rows)
                for (i in 0 until w * rows) {
                    val s = px[i]
                    val r0 = S2L[(s ushr 16) and 0xFF]
                    val g0 = S2L[(s ushr 8) and 0xFF]
                    val b0 = S2L[s and 0xFF]
                    val yl = 0.2126 * r0 + 0.7152 * g0 + 0.0722 * b0
                    val gain = (highlightRecover(yl, HDR_PEAK) + OFF_HDR) / (yl + OFF_SDR)
                    var r = (r0 + OFF_SDR) * gain - OFF_HDR
                    var g = (g0 + OFF_SDR) * gain - OFF_HDR
                    var b = (b0 + OFF_SDR) * gain - OFF_HDR
                    val y2 = 0.2126 * r + 0.7152 * g + 0.0722 * b
                    // 只有超过 SDR 白（1.0）的部分才去饱和，中间调一点不碰
                    val wD = 0.45 * smooth01((y2 - 1.0) / (HDR_PEAK - 1.0))
                    if (wD > 0.0) {
                        r += (y2 - r) * wD; g += (y2 - g) * wD; b += (y2 - b) * wD
                    }
                    buf.put(
                        LtHdr.pack(
                            r.coerceIn(0.0, HDR_PEAK).toFloat(),
                            g.coerceIn(0.0, HDR_PEAK).toFloat(),
                            b.coerceIn(0.0, HDR_PEAK).toFloat()
                        )
                    )
                }
                row += rows
            }
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            bmp
        }
    }.getOrNull()

    /** 显示端 shoulder 的拐点：线性 0.88 以上开始压（sRGB 约 240，纸白附近）。 */
    private const val DISPLAY_KNEE = 0.88

    /** 线性场景亮度 → 可显示亮度：拐点以下恒等，以上指数渐近 1.0（C1 连续）。 */
    private fun displayTone(y: Double): Double {
        if (y <= DISPLAY_KNEE) return y
        val head = 1.0 - DISPLAY_KNEE
        return DISPLAY_KNEE + head * (1.0 - Math.exp(-(y - DISPLAY_KNEE) / head))
    }

    private fun applyHdrPixel(srgb: Int, x: Int, y: Int): Int {
        val r0 = S2L[(srgb ushr 16) and 0xFF]
        val g0 = S2L[(srgb ushr 8) and 0xFF]
        val b0 = S2L[srgb and 0xFF]
        val ys = 0.2126 * r0 + 0.7152 * g0 + 0.0722 * b0
        // 预览用低一档的峰值（见 PREVIEW_PEAK）：4× 展开在 SDR 屏上压不回来。
        // 曲线与导出同一条（[highlightRecover]），只是峰值不同 —— 之前这里是手抄
        // 的一份副本，r49 加全局项时它会被漏掉，预览和导出就又不一致了。
        val gain = (highlightRecover(ys, PREVIEW_PEAK) + OFF_HDR) / (ys + OFF_SDR)
        val r1 = (r0 + OFF_SDR) * gain - OFF_HDR
        val g1 = (g0 + OFF_SDR) * gain - OFF_HDR
        val b1 = (b0 + OFF_SDR) * gain - OFF_HDR
        // 按亮度定 shoulder，再等比缩三通道：色相不变（在没到去饱和那一步之前）
        val y1 = 0.2126 * r1 + 0.7152 * g1 + 0.0722 * b1
        val yd = displayTone(y1)
        val s = if (y1 > 1e-7) yd / y1 else 0.0
        // 高光去饱和：只动 72% 以上，最多 45%，全程平滑；中间调一点不碰
        val wDesat = 0.45 * smooth01((yd - 0.72) / 0.28)
        fun ch(v: Double): Int {
            val c = (v * s).coerceIn(0.0, 1.2)
            val mixed = c + (yd - c) * wDesat
            return srgbEncodeD(mixed.coerceIn(0.0, 1.0), x, y)
        }
        val r = ch(r1); val g = ch(g1); val b = ch(b1)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun smooth01(t: Double): Double {
        val v = t.coerceIn(0.0, 1.0)
        return v * v * (3.0 - 2.0 * v)
    }

    /** gain(线性, 1..peak) -> 8bit（对数域：字节0=增益1、255=峰值，规范约定） */
    private fun encodeLog2(gain: Double, peak: Double, log2Peak: Double, x: Int, y: Int): Int {
        val lg = Math.log(gain.coerceIn(1.0, peak)) / Math.log(2.0)
        return (lg / log2Peak * 255.0 + dither(x, y)).roundToInt().coerceIn(0, 255)
    }

    // 8×8 有序抖动（Bayer 矩阵）：把 -0.5…+0.5 个最低位的偏移铺在方块里。
    // gain map 只有 8bit、又是 1/4 分辨率（解码端双线性放大），不抖的话在天空、
    // 墙面这类大面积渐变上会看到一圈圈等高线 —— 这是 HDR 图最常见的"脏"。
    private val BAYER8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /** ±0.5 个码值的有序抖动（替代 +0.5 的四舍五入）。 */
    private fun dither(x: Int, y: Int): Double =
        (BAYER8[((y and 7) shl 3) or (x and 7)] + 0.5) / 64.0 - 0.5

    /** 与 [srgbEncode] 同一条编码曲线，但量化前叠抖动。 */
    private fun srgbEncodeD(c: Double, x: Int, y: Int): Int {
        val v = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
        return (v * 255.0 + dither(x, y)).roundToInt().coerceIn(0, 255)
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
        // 相纸 → 增益表的查表用哪一张。直扫底片（没有相纸）或新加的相纸落在默认表上。
        val gainTable = GAIN_BY_PAPER[paperId] ?: GAIN_BY_H
        val tableName = if (GAIN_BY_PAPER.containsKey(paperId)) paperId else "默认(portra_endura)"
        // ---------- 1. 主图 JPEG（SDR，保持胶片观感，非 HDR 端所见即所得）----------
        val mainBos = ByteArrayOutputStream()
        sdrBitmap.compress(Bitmap.CompressFormat.JPEG, quality, mainBos)
        val mainJpeg = mainBos.toByteArray()

        // ---------- 2. gain map：1/4 分辨率，**在线性光里**做 4×4 盒式平均 ----------
        //
        // [r49] 旧做法是"先把两帧都缩到 1/4，再逐像素查表算增益"。那是错的两次：
        //   a) 双线性缩小把一颗镜面高光的能量摊进 15 个暗邻居，缩完的码值掉到
        //      纸白以下，查表得到的增益只有 1.5× —— HDR 里最该亮的那一点点没了；
        //   b) 缩小是在**码值域**做的，而 gain 是乘性量，先平均码值再算比值，
        //      等于对一个指数量做算术平均，中间调会系统性地偏。
        // 现在按"一个 gain map 像素 = 主图 4×4 块"累加**线性光量**：
        //     gain = Σ(Y_hdr + off) / Σ(Y_sdr + off)
        // 光量守恒，解码端双线性放大正好把这 4×4 的能量摊回同样的面积 —— 高光的
        // 亮度没有丢，只是精度降到 1/4 网格，这正是 gain map 该有的行为。
        val gw = maxOf(1, w / 4); val gh = maxOf(1, h / 4)
        val nCell = gw * gh
        val sumS = DoubleArray(nCell)
        val sumH = DoubleArray(nCell)
        var clipped2 = 0L          // 第二帧仍顶 255：真实高光超出第二帧可测范围
        // 条带读取：24 MP 一次性 getPixels 是 96 MB × 2，条带只要 1~2 MB，
        // 且对 8bit 位图逐行读的代价可以忽略。
        val strip = 128
        val px1 = IntArray(w * strip)
        val px2 = if (dual) IntArray(w * strip) else null
        var row = 0
        while (row < h) {
            val rows = minOf(strip, h - row)
            sdrBitmap.getPixels(px1, 0, w, 0, row, w, rows)
            if (px2 != null) highlightFrame!!.getPixels(px2, 0, w, 0, row, w, rows)
            for (yy in 0 until rows) {
                val base = yy * w
                val cellRow = ((row + yy) * gh / h) * gw
                for (xx in 0 until w) {
                    val s = px1[base + xx]
                    val yl = lumaLin(s) + OFF_SDR      // 先加 off 再累加，与规范公式同域
                    val g = if (px2 != null) dualGain(s, px2[base + xx], gainTable, peak)
                            else pixelGain(s, peak)
                    if (px2 != null && luma8(s) > LIFT_FULL && luma8(px2[base + xx]) > 253.0) clipped2++
                    val cell = cellRow + xx * gw / w
                    sumS[cell] += yl
                    sumH[cell] += yl * g
                }
            }
            row += rows
        }
        // 逐格求增益，顺便统计分布 —— 这是"HDR 到底有没有效"唯一能量化的证据。
        val gCell = DoubleArray(nCell)
        var gMax = 1.0
        var boosted = 0            // gain > 1.05（肉眼刚能分辨）
        var strong = 0             // gain > 1.5（明显更亮）
        var boostSum = 0.0
        for (i in 0 until nCell) {
            val g = if (sumS[i] > 0.0) (sumH[i] / sumS[i]).coerceIn(1.0, peak) else 1.0
            gCell[i] = g
            if (g > gMax) gMax = g
            if (g > 1.05) { boosted++; boostSum += g }
            if (g > 1.5) strong++
        }
        // GainMapMax 用**实测**最大值（不足 1 档按 1 档算）：8bit 量化步长因此更细，
        // 天空这种缓变区域少一圈等高线。声明值必须和这里的编码除数一致，否则解码端
        // 读出来的增益会整体偏（这正是"元数据与像素不同步"那类最难查的 bug）。
        val gmMaxLog2 = Math.max(1.0, Math.log(gMax) / Math.log(2.0))
        val gmPx = IntArray(nCell)
        for (i in 0 until nCell) {
            val v = encodeLog2(gCell[i], peak, gmMaxLog2, i % gw, i / gw)
            gmPx[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val gmRgb = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        gmRgb.setPixels(gmPx, 0, gw, 0, 0, gw, gh)
        val n = nCell.coerceAtLeast(1)
        if (dual) {
            Diagnostics.log("hdr", "双帧 增益图 ${gw}x$gh +${"%.1f".format(evStep)}EV · 相纸表=$tableName" +
                " · 增益>1.05 的 %.1f%% · >1.5 的 %.1f%% · 最大 %.2f · 均值 %.2f" +
                " · 第二帧仍顶白的 %.1f%%（超出可测范围，按峰值封顶）· GainMapMax %.2f 档"
                    .format(boosted * 100.0 / n, strong * 100.0 / n, gMax,
                        if (boosted > 0) boostSum / boosted else 1.0,
                        clipped2 * 100.0 / n, gmMaxLog2))
        } else {
            Diagnostics.log("hdr", "单帧 增益图 ${gw}x$gh 增益>1.05 的像素 %.1f%% · >1.5 的 %.1f%%" +
                " · 最大 %.2f · 被提亮部分均值 %.2f（无第二帧：纸白封顶，只有提亮没有新细节）" +
                " · GainMapMax %.2f 档"
                    .format(boosted * 100.0 / n, strong * 100.0 / n, gMax,
                        if (boosted > 0) boostSum / boosted else 1.0, gmMaxLog2))
        }
        val gmBos = ByteArrayOutputStream()
        gmRgb.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(80, 92), gmBos)
        val gainJpeg = gmBos.toByteArray()
        gmRgb.recycle()

        // ---------- 3. gain map 次图内嵌元数据 XMP（libultrahdr 从这里读增益元数据！）----------
        val secSeg = buildAppSegment(0xE1, XAP_NS.toByteArray(Charsets.US_ASCII) + buildXmpSecondary(gmMaxLog2))
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

    /**
     * sRGB 8bit → 线性，预计算 256 项。
     *
     * 预览（1080p 约 200 万像素）和导出（2400 万像素）都是逐像素循环，每个像素要
     * 3 次 pow —— 这是整条 HDR 路径里最贵的一项。查表把 pow 全部省掉，同样的代码
     * 在 24 MP 导出时从"几秒"降到"几百毫秒"。
     */
    private val S2L = DoubleArray(256) { srgbDecode(it) }

    /** 8bit 像素的线性亮度（Y）。 */
    private fun lumaLin(c: Int): Double =
        0.2126 * S2L[(c ushr 16) and 0xFF] +
            0.7152 * S2L[(c ushr 8) and 0xFF] +
            0.0722 * S2L[c and 0xFF]

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
