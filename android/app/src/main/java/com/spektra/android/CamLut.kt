package com.spektra.android

import android.content.Context
import java.io.File

/**
 * 取景器用的「胶片外观查找表」。
 *
 * ## 为什么不逐帧调引擎
 *
 * 引擎一帧要走完 冲负片 → 印相纸 → 扫描 三段计算图，还要先 `spk_open` 把 float RGB
 * 上传成纹理。按预览尺寸（长边 1600）实测是几十到上百毫秒一帧 —— 接到相机上就是
 * 每秒 5～10 帧，正是"一帧一帧很卡"的那种观感，而且它和相机预览抢同一块 GPU。
 *
 * 所以取景器不走引擎，改走**一次烘焙 + 每帧采样**：把一个 33×33×33 的色立方送进引擎渲一遍，
 * 得到"任意输入色 → 冲洗后的输出色"的映射表，之后每帧只是着色器里查一次 3D 表。
 *
 * ## 为什么要关掉那些效果才敢烘焙
 *
 * 光晕（halation）、颗粒（grain）、眩光（glare）、层间效应（dir couplers）、对比度蒙版
 * 全都是**空间相关**的：一个像素的输出取决于邻居。这类处理在色立方上没法表达，而且会把
 * 相邻色块糊在一起，烘出来的表就是错的。全部关掉之后，剩下的（密度曲线、相纸、滤片、
 * 扫描）逐像素独立，色立方才成立。
 *
 * 关掉的这几样恰好也是**位置相关、与颜色无关**的观感成分，所以取景器用自己的着色器补
 * （见 [CamGlView]）—— 颜色由引擎负责，质感由着色器负责。
 *
 * ## 可变的东西（r69）
 *
 * [CamLook] 里的四项都是**真实暗房会动的旋钮**，而且全都进表：
 *  · 增感/减感：`exposure_compensation_ev`（显影时间）；
 *  · 密度曲线：`density_curve_gamma`（相纸反差）；
 *  · CC 滤片：`m_filter_shift` / `y_filter_shift`（放大机下的品红/黄滤片，校色的标准手段）。
 *
 * 所以拖动它们之后，取景器里看到的**就是**成片会变成的样子 —— 不是给画面刷一层假滤镜。
 */
object CamLut {

    /** 色立方每边的采样数。33 是这类表的通用档位：8 bit 下三线性插值误差约 1 个码值。 */
    const val N = 33

    /** 表格式版本。烘焙参数一改就要 +1，否则旧缓存会被当成新格式读进来。 */
    private const val VER = "v3"

    /** 一张表 33³ × RGBA8 = 143,748 字节。 */
    const val BYTES = N * N * N * 4

    /**
     * 取景器可调的四项。全部是暗房语言：
     *
     * @param pushEv 增感/减感（EV）。+1 相当于显影时间加一档：更亮、反差更大、颗粒更粗。
     * @param gamma 密度曲线反差。1 是这卷的标准冲洗。
     * @param ccM 品红–绿 校正滤片（CC 单位）。负=偏绿，正=偏品红。
     * @param ccY 黄–蓝 校正滤片（CC 单位）。负=偏蓝，正=偏黄。
     */
    data class CamLook(
        val pushEv: Float = 0f,
        val gamma: Float = 1f,
        val ccM: Float = 0f,
        val ccY: Float = 0f,
    ) {
        val isNeutral: Boolean
            get() = kotlin.math.abs(pushEv) < 0.01f && kotlin.math.abs(gamma - 1f) < 0.005f &&
                kotlin.math.abs(ccM) < 0.2f && kotlin.math.abs(ccY) < 0.2f

        /** 缓存键：四舍五入到能区分的精度，避免滑杆微动就换一个文件。 */
        fun key(): String = "${(pushEv * 10).toInt()}_${(gamma * 100).toInt()}_" +
            "${(ccM * 10).toInt()}_${(ccY * 10).toInt()}"
    }

    /** 恒等表：预览退回相机原色。烘焙失败、引擎未就绪时用它，取景器不至于黑屏。 */
    fun identity(): ByteArray {
        val out = ByteArray(BYTES)
        var i = 0
        for (b in 0 until N) for (g in 0 until N) for (r in 0 until N) {
            out[i++] = (r * 255 / (N - 1)).toByte()
            out[i++] = (g * 255 / (N - 1)).toByte()
            out[i++] = (b * 255 / (N - 1)).toByte()
            out[i++] = 0xFF.toByte()
        }
        return out
    }

    private fun cacheFile(ctx: Context, film: String, print: String, look: CamLook): File =
        File(File(ctx.filesDir, "cam_lut"), "${film}__${print}__${look.key()}__$VER.bin")

    /** 读缓存；没有或长度不对返回 null。 */
    fun cached(ctx: Context, film: String, print: String, look: CamLook): ByteArray? = runCatching {
        val f = cacheFile(ctx, film, print, look)
        if (f.length() != BYTES.toLong()) null else f.readBytes()
    }.getOrNull()

    /**
     * 烘焙一张表（阻塞，必须在后台线程 + 引擎闸门里调用）。
     *
     * @return 33³ RGBA8，顺序为 `((b*N + g)*N + r)*4`；失败返回 null。
     */
    fun bake(eng: SpektraEngine, film: String, print: String, look: CamLook): ByteArray? {
        val n = N
        // 33 层色块按 6×6 摆成一张小图：每格 33×33 内容 + 1 像素间隙，外圈留 2 像素，
        // 正好 208×208（4 的倍数、偶数）。三个考虑：
        //  ① 图小 → 烘焙快（十万像素级，几十毫秒），换胶片不用等；
        //  ② 有间隙 → 任何一个在块边界做 clamp / tile 对齐的 kernel，也渗不进被采样的
        //     那 33×33 内部；
        //  ③ 边长避开奇数与质数，少踩驱动的特殊路径。
        val grid = 6
        val pitch = n + 1                       // 34
        val pad = 2
        val side = pad * 2 + grid * pitch       // 208
        val px = FloatArray(side * side * 3)
        var k = 0
        for (y in 0 until side) {
            val ty = ((y - pad).coerceAtLeast(0) / pitch).coerceIn(0, grid - 1)
            val g = ((y - pad) - ty * pitch).coerceIn(0, n - 1)
            for (x in 0 until side) {
                val tx = ((x - pad).coerceAtLeast(0) / pitch).coerceIn(0, grid - 1)
                val r = ((x - pad) - tx * pitch).coerceIn(0, n - 1)
                // 层号 b 与格子一一对应：b = ty * grid + tx
                val b = (ty * grid + tx).coerceIn(0, n - 1)
                px[k++] = r / (n - 1).toFloat()
                px[k++] = g / (n - 1).toFloat()
                px[k++] = b / (n - 1).toFloat()
            }
        }

        val t0 = System.currentTimeMillis()
        val sess = eng.open(px, side, side, 3, params(film, print, look))
        if (sess == 0L) {
            Diagnostics.log("cam", "LUT 烘焙失败：open 返回 0（$film / $print）${eng.lastError().take(160)}")
            return null
        }
        val frame = try {
            // 测光交给引擎自己算：色立方的分布近似均匀，解出来就是"中性场景"的印相曝光。
            runCatching { eng.solve(sess, "exposure") }
            eng.renderFrame(sess, "live", quiet = true)
        } finally {
            runCatching { eng.sessionRelease(sess) }
        } ?: run {
            Diagnostics.log("cam", "LUT 烘焙失败：渲染返回空（$film / $print）")
            return null
        }
        val tBake = System.currentTimeMillis() - t0

        // 输出必须与输入等尺寸。真要是不等，宁可退回原色也不要按比例猜 ——
        // 比例一变，采到的就不是那个色了。
        if (frame.w != side || frame.h != side) {
            Diagnostics.log("cam", "LUT 烘焙失败：输出 ${frame.w}x${frame.h} ≠ 输入 ${side}x$side")
            return null
        }

        val out = ByteArray(BYTES)
        var o = 0
        val rowStride = frame.w * 4
        for (b in 0 until n) {
            val tx = b % grid
            val ty = b / grid
            val baseY = pad + ty * pitch
            for (g in 0 until n) {
                val rowBase = (baseY + g) * rowStride
                for (r in 0 until n) {
                    val idx = rowBase + (pad + tx * pitch + r) * 4
                    out[o++] = u16To8(frame.px[idx])
                    out[o++] = u16To8(frame.px[idx + 1])
                    out[o++] = u16To8(frame.px[idx + 2])
                    out[o++] = 0xFF.toByte()
                }
            }
        }

        // 退化检查。烘焙出错时引擎不会报错，只会安静地给出一张全黑或全平的图 ——
        // 那时取景器会变成一块灰板，用户以为相机坏了。宁可退回恒等表。
        val why = sanity(out)
        if (why != null) {
            Diagnostics.log("cam", "LUT 退化（$why），退回相机原色（$film / $print）")
            return null
        }
        Diagnostics.log("cam", "LUT 烘焙完成 ${tBake}ms ${side}x$side → ${N}³（$film / $print）look=${look.key()}")
        return out
    }

    /** u16（0..65535）→ u8。用乘 255 除 65535 而不是右移 8：右移会把 65534 也削成 255。 */
    private fun u16To8(v: Short): Byte {
        val x = v.toInt() and 0xFFFF
        return ((x * 255 + 32767) / 65535).coerceIn(0, 255).toByte()
    }

    /** 存盘（先写临时文件再改名，避免写一半被当成有效缓存）。 */
    fun save(ctx: Context, film: String, print: String, look: CamLook, lut: ByteArray): Boolean = runCatching {
        val f = cacheFile(ctx, film, print, look)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(lut)
        if (f.exists()) f.delete()
        tmp.renameTo(f)
    }.getOrDefault(false)

    /**
     * 退化检查：黑端是否被抬得太高、白端是否压得太低、中灰是否贴边。
     *
     * 阈值给得很宽 —— 反转片的黑能压到底、黑白片的中灰可以偏、去碳层胶片整体偏青。
     * 这里只拦"整张表就一个色"这类明显的坏表。
     */
    private fun sanity(lut: ByteArray): String? {
        fun at(r: Int, g: Int, b: Int, c: Int): Int =
            lut[((b * N + g) * N + r) * 4 + c].toInt() and 0xFF
        val black = at(0, 0, 0, 1)
        val mid = at(16, 16, 16, 1)
        val white = at(N - 1, N - 1, N - 1, 1)
        if (black > 120) return "黑端 $black"
        if (white < 90) return "白端 $white"
        if (mid < 8 || mid > 248) return "中灰 $mid"
        if (white - black < 40) return "动态范围只有 ${white - black}"
        return null
    }

    /**
     * 烘焙参数。
     *
     * 除了片基/相纸与 [CamLook] 那四项，其余全部固定：取景器只回答"这卷片子什么味道"，
     * 不承担主界面里做的其它调整（增感以外的曝光、片边、日期后背都不在表里）。
     */
    private fun params(film: String, print: String, look: CamLook): String = buildString {
        append("{\"film_stock\":\"").append(film).append("\",")
        append("\"print_stock\":\"").append(print).append("\",")
        append("\"digital_intermediate\":false,")
        // —— 可调的四项 ——
        append("\"exposure_compensation_ev\":").append(fmt(look.pushEv)).append(",")
        append("\"density_curve_gamma\":").append(fmt(look.gamma.coerceIn(0.5f, 2f))).append(",")
        append("\"m_filter_shift\":").append(fmt(look.ccM)).append(",")
        append("\"y_filter_shift\":").append(fmt(look.ccY)).append(",")
        append("\"camera_filter\":\"\",")
        append("\"auto_exposure_method\":\"center_weighted\",")
        append("\"lens_blur_um\":0.0,")
        append("\"film_format_mm\":35.0,")
        // 与主界面同一条色彩域：栅格照片按 sRGB 编码值喂进去、引擎先线性化。
        // 相机的 YUV/JPEG 也是 sRGB 编码，两边的输入口径因此完全一致。
        append("\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,")
        append("\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true,")
        // —— 以下几项必须关：它们都是空间相关处理，色立方上无法表达 ——
        append("\"halation_active\":false,\"halation_boost_ev\":0.0,\"halation_amount\":0.0,")
        append("\"antihalation_removed\":false,\"halation_headroom_ev\":0.0,")
        append("\"halation_scatter_amount\":0.0,")
        append("\"grain_active\":false,\"grain_amount\":0.0,\"grain_sublayers_active\":false,")
        append("\"glare_active\":false,\"glare_amount\":0.0,")
        append("\"dir_couplers_active\":false,\"dir_couplers_amount\":0.0,")
        append("\"contrast_mask_active\":false,\"contrast_mask_core\":0.0,")
        append("\"contrast_mask_highlights\":0.0,\"contrast_mask_shadows\":0.0,")
        append("\"contrast_mask_scale\":0.05,")
        // 片边 / 日期后背 / 直扫底片 / 数字中间片也都不在取景器里
        append("\"overscan_active\":false,\"date_imprint_active\":false,")
        append("\"scan_film\":false,\"extended_dynamic_range\":false,")
        append("\"scanner_white_correction\":true,\"scanner_black_correction\":true")
        append("}")
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    /** 取表里某个色（分量 0..1）作为色卡用的小色块。给胶片选择条画预览用。 */
    fun sample(lut: ByteArray, r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * (N - 1)).toInt()
        val gi = (g.coerceIn(0f, 1f) * (N - 1)).toInt()
        val bi = (b.coerceIn(0f, 1f) * (N - 1)).toInt()
        val i = ((bi * N + gi) * N + ri) * 4
        return (0xFF shl 24) or
            ((lut[i].toInt() and 0xFF) shl 16) or
            ((lut[i + 1].toInt() and 0xFF) shl 8) or
            (lut[i + 2].toInt() and 0xFF)
    }

    /**
     * 胶片缩略色卡：用表里的五个点（暗部→高光）拉一条渐变。
     *
     * 为什么可以这么用：色立方本身就是"输入色 → 输出色"的完整映射，取对角线上五个点
     * 得到的就是这卷片子的灰阶响应，横着铺开就是一张色卡 —— 比凭空画一个色块可信得多。
     */
    fun swatch(lut: ByteArray): IntArray {
        val xs = floatArrayOf(0.05f, 0.22f, 0.45f, 0.7f, 0.95f)
        return IntArray(xs.size) { i ->
            val v = xs[i]
            // 混一点冷色进去，让色卡不只是灰阶：胶片的中性色本来就带偏色
            val warm = 0.03f * (1f - v)
            sample(lut, v, v - warm * 0.4f, v - warm)
        }
    }

    /** 后台烘焙 + 缓存读写。 */
    fun obtain(ctx: Context, eng: SpektraEngine?, film: String, print: String, look: CamLook): ByteArray? {
        cached(ctx, film, print, look)?.let { return it }
        if (eng == null) return null
        val lut = withEngineGate(20_000L, "cam lut bake") { bake(eng, film, print, look) } ?: return null
        save(ctx, film, print, look, lut)
        return lut
    }
}
