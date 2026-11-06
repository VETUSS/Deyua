package com.spektra.android

/**
 * HDR 输出的共享编码层（阶段B，为 AVIF HDR 与 Ultra HDR JPEG 两个格式服务）。
 *
 * 数据链路：
 *   引擎 EDR 渲染（extended_dynamic_range=true, output_cctf_encoding=false）
 *   -> nativeRender 返回 rgba16（u16/通道，线性，1.0 = 65535）
 *   -> 本层还原为线性 RGB（ProPhoto 线性工作空间）
 *   -> 矩阵到 Rec.2020 线性 -> PQ(ST.2084) 编码 -> 0..1 的 PQ 值
 *   -> 后续封装进 AVIF（10bit）或作为 Ultra HDR JPEG 的 HDR 分量。
 *
 * 数学已在本地独立验证：PQ 查表（100/1000/10000 nit）逐点吻合，roundtrip 误差 ~1e-15；
 * ProPhoto->Rec.2020 矩阵白点 ≈ (1,1,1)。
 */
object HdrEncoding {

    // ---------------- ST.2084 (PQ) 传输函数 ----------------
    private const val PQ_M1 = 2610.0 / 16384.0        // 0.1593017578
    private const val PQ_M2 = 2523.0 / 4096.0 * 128.0 // 78.84375
    private const val PQ_C1 = 3424.0 / 4096.0         // 0.8359375
    private const val PQ_C2 = 2413.0 / 4096.0 * 32.0  // 18.8515625
    private const val PQ_C3 = 2392.0 / 4096.0 * 32.0  // 18.6875

    /** 线性亮度(相对, 0..1, 1=10000nit 基准) -> PQ 编码值 [0,1]。 */
    fun pqEncode(linear: Double): Double {
        if (linear <= 0.0) return 0.0
        val lm = Math.pow(linear, PQ_M1)
        return Math.pow((PQ_C1 + PQ_C2 * lm) / (1.0 + PQ_C3 * lm), PQ_M2)
    }

    /** PQ 编码值 [0,1] -> 线性亮度(相对, 1=10000nit 基准)。 */
    fun pqDecode(code: Double): Double {
        if (code <= 0.0) return 0.0
        val cm = Math.pow(code, 1.0 / PQ_M2)
        val n = cm - PQ_C1
        if (n <= 0.0) return 0.0
        return Math.pow(n / (PQ_C2 - PQ_C3 * cm), 1.0 / PQ_M1)
    }

    // ---------------- ProPhoto 线性 -> Rec.2020 线性 矩阵 ----------------
    // (ProPhoto->XYZ D50) x (Bradford D50->D65) x (XYZ D65->Rec.2020)
    // 结果已本地验证：白点 (1,1,1) -> (1.0000,1.0000,0.9999)
    private val PROPHOTO_TO_REC2020 = arrayOf(
        doubleArrayOf(1.200649, -0.057565, -0.143064),
        doubleArrayOf(-0.069942, 1.080628, -0.010686),
        doubleArrayOf(0.005542, -0.040782, 1.035191),
    )

    /** 线性 ProPhoto RGB -> 线性 Rec.2020 RGB（三个通道，0..1 线性）。 */
    fun prophotoToRec2020(r: Double, g: Double, b: Double): DoubleArray {
        val m = PROPHOTO_TO_REC2020
        return doubleArrayOf(
            m[0][0] * r + m[0][1] * g + m[0][2] * b,
            m[1][0] * r + m[1][1] * g + m[1][2] * b,
            m[2][0] * r + m[2][1] * g + m[2][2] * b,
        )
    }

    // ---------------- 定标 / mastering ----------------
    /**
     * EDR 线性数据（ProPhoto 线性，0..~1，1.0≈相纸最白）映射为绝对亮度。
     *
     * 定标决策：把 EDR 的 Y=1.0（正常打印的相纸白）锚定为 [referenceWhiteNits]，
     * 使 HDR 图在 SDR 屏上观感接近预览，同时暗部（EDR 扩展到 log2≈-14）在 PQ 域保留更多层级。
     * 相纸高光端 ~1.0，不透明白，因此这是"宽动态、深阴影"的 HDR，MaxCLL≈referenceWhiteNits。
     */
    const val REFERENCE_WHITE_NITS = 100.0  // SDR 基准白（可调，影响整体亮度）
    const val PQ_REFERENCE = 10000.0        // ST.2084 基准

    /** 线性相对亮度(Y in 0..1，ProPhoto 线性) -> 绝对亮度 nits。 */
    fun linearToNits(y: Double): Double = y * REFERENCE_WHITE_NITS

    /** 线性相对亮度 -> PQ 编码值 [0,1]。 */
    fun linearToPq(y: Double): Double = pqEncode(y * REFERENCE_WHITE_NITS / PQ_REFERENCE)

    // ---------------- rgba16 解码 ----------------
    /** 引擎 nativeRender 返回的 u16 值 -> 线性 float（1.0=65535）。 */
    fun u16ToLinear(v: Int): Double = (v and 0xFFFF).toDouble() / 65535.0

    /**
     * 把引擎 rgba16（u16 RGBA，ProPhoto 线性）逐像素转成 PQ 编码的 Rec.2020 值。
     * 返回每像素 3 个 Double（R,G,B），均为 PQ 域 [0,1]，供后续封装。
     */
    fun rgba16ToPqRec2020(rgba16: ShortArray, pxStart: Int): DoubleArray {
        val r = u16ToLinear(rgba16[pxStart].toInt())
        val g = u16ToLinear(rgba16[pxStart + 1].toInt())
        val b = u16ToLinear(rgba16[pxStart + 2].toInt())
        val rec = prophotoToRec2020(r, g, b)
        return doubleArrayOf(
            linearToPq(rec[0]),
            linearToPq(rec[1]),
            linearToPq(rec[2]),
        )
    }
}
