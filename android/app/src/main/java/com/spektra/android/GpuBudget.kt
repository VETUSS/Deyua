package com.spektra.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlin.math.min
import kotlin.math.sqrt

//
// 这台设备一次能渲染多少像素，以及导出该走哪条路。
//
// 引擎的每个平面都是 `w * h * 3 * 4` 字节的 float32，并且**整块**绑成一个
// SSBO；超过上限的 dispatch 在 Adreno 上不只是报错，而是直接把 VkDevice 弄丢
// （之后连小图都渲不出来，必须重建引擎）。
//
// [r40] 三条约束取小：
//  1. GPU 单块缓冲 —— 硬上限，超了不是慢，是崩；
//  2. Java 堆 —— 渲染结果要落成 Frame16（8 B/px）再转 Bitmap（4 B/px）；
//  3. 用户选的导出台阶 —— 比源图小就直接按那个尺寸渲染，不做「先渲全幅再缩」。
//
// [r40.2] 第 1 条以前直接读 `capabilities.backend.max_storage_buffer_range`，
// 这是一个**假数**：Adreno 830 报 2147483647（2 GiB），那是 32 位类型的钳位，
// 不是"能分配多少"。于是 28.1 MP 的片边画布被判定"装得下"，导出走到最后一
// 步才炸：
//  · 一次 5780×4868 的导出 → `Vulkan compute dispatch failed (VkResult -4)`
//    （设备丢失，日志 20:13 那次）；
//  · 更早一次同样尺寸"成功"了，但下半幅从未被写入 —— 成品只有上面一小条。
// 两者是同一个病：画布太大，GPU 在压力边缘上不是崩就是静默丢写。
//
// 现在改为两条**能验算**的上限，取小：
//  · 实测线：任何一次导出失败都按 0.8 记下"死线"，以后不再越过它，成功后
//    再逐步放开（持久化在 SharedPreferences，跨会话有效）；
//  · 画布不许比原片更大：片边是"从照片里让出来的一圈边"，不该凭空把 22 MP
//    的画面包装成 28 MP 的画布 —— 那一圈边本身没有更多信息，却要按画布面积
//    付 GPU 内存和 dispatch 时间。
//
object GpuBudget {

    /** 平面：3 通道 × float32。 */
    const val BYTES_PER_PX = 12.0

    /** Frame16（rgba16，8 B/px）+ ARGB_8888 Bitmap（4 B/px）。 */
    private const val HEAP_BYTES_PER_PX = 12.0

    /**
     * 片边画布相对帧面积的倍率，拿不到布局时用它兜底。
     * 135 半幅实测 1.5282（画布比画面多一圈片边与齿孔）。
     */
    const val FALLBACK_OVERSCAN_AREA = 1.53

    /**
     * r40.4 试过"超 16 MP 交给引擎分带"，r40.5 撤回（分带执行器丢带）。
     * 这个常量留着只为让日志和旧记录能对上，不再参与判定。
     */
    const val AUTO_STRIP_PX = 16_000_000L

    /** 与引擎 `kAutoStripRows` 同值：分带时的带高（行）。 */
    const val AUTO_STRIP_ROWS = 1024

    /** 只用硬上限的这个比例：中间平面、驱动内部缓冲、以及取整损耗都要留余量。 */
    private const val HEADROOM = 0.95

    /** 低于这个数就当引擎没报（老引擎/异常后端），退回 128 MiB 的保守值。 */
    private const val MIN_REPORTED_RANGE = 32L * 1024 * 1024
    private const val FALLBACK_RANGE = 128L * 1024 * 1024

    // ---- 实测死线 ----------------------------------------------------------
    private const val PREF = "gpu_budget"
    private const val K_BAD = "dead_canvas_px"
    private const val K_GOOD = "best_canvas_px"
    private const val K_VC = "learned_at_version"

    /** 越过死线要退多少：留 20% 余量，避免在"刚好炸"的尺寸上反复试探。 */
    private const val BACKOFF = 0.80

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var deadPx = 0L
    @Volatile private var bestPx = 0L

    /** 启动时挂一次，读回上几次会话学到的死线。 */
    fun attach(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        deadPx = p.getLong(K_BAD, 0L)
        bestPx = p.getLong(K_GOOD, 0L)
        // [r40.5] 死线是"某个版本的引擎 + 那一刻的池状态"测出来的，两者都变了
        // 它就只是一个过时的数字。r40.4 里它把导出压到 19.7 MP，而真正的问题
        // 从来不是这个尺寸 —— 所以换版本就重测一次，别让上一版的结论继续压着。
        val vc = runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else info.versionCode.toLong()
        }.getOrDefault(0L)
        if (vc > 0L && vc != p.getLong(K_VC, 0L)) {
            deadPx = 0L
            bestPx = 0L
            p.edit().remove(K_BAD).remove(K_GOOD).putLong(K_VC, vc).apply()
        }
        prefs = p
    }

    /** 给日志/诊断面板看的一行账。 */
    fun learnedNote(): String =
        "实测上限：死线=${if (deadPx > 0) "${deadPx / 1000000}MP" else "无"} " +
            "跑通过的最大画布=${if (bestPx > 0) "${bestPx / 1000000}MP" else "无"}"

    /**
     * 记一次实测结果（`px` = 这次要渲染的**画布**像素数，不是帧）。
     *
     * 失败就把死线压到这个尺寸以下并持久化；成功且比记住的死线还大，说明
     * 之前那次多半是偶发（过热、后台占用），把死线撤掉。
     */
    fun noteCanvas(px: Long, ok: Boolean) {
        if (px <= 0) return
        val p = prefs
        if (ok) {
            if (px > bestPx) {
                bestPx = px
                p?.edit()?.putLong(K_GOOD, px)?.apply()
            }
            if (deadPx in 1 until px) {
                deadPx = 0L
                p?.edit()?.remove(K_BAD)?.apply()
            }
        } else if (deadPx == 0L || px < deadPx) {
            deadPx = px
            p?.edit()?.putLong(K_BAD, px)?.apply()
        }
    }

    /** 单块 SSBO 的可用字节数：驱动上报值与实测死线取小。 */
    fun usableRangeBytes(engine: SpektraEngine): Long {
        val reported = engine.maxStorageBufferRange
        val range = if (reported >= MIN_REPORTED_RANGE) reported else FALLBACK_RANGE
        var bytes = (range * HEADROOM).toLong()
        if (deadPx > 0) {
            val learned = (deadPx * BACKOFF * BYTES_PER_PX).toLong()
            if (learned < bytes) bytes = learned
        }
        return bytes
    }

    /**
     * 一帧最多能有多少像素（`overscanArea` = 片边画布面积倍率，关片边时为 1）。
     */
    fun maxRenderPixels(engine: SpektraEngine, overscanArea: Double = 1.0): Long {
        val area = if (overscanArea > 1.0) overscanArea else 1.0
        return (usableRangeBytes(engine) / BYTES_PER_PX / area).toLong().coerceAtLeast(1L)
    }

    /**
     * 导入时最多解多少像素。
     *
     * 取「GPU 装得下」与「堆放得下渲染结果」的较小者。这个值超过相机原生
     * 像素时，调用方不做缩放 —— 那时 `full` 档就是原生分辨率。
     *
     * 单位是**像素**而不是长边：同样 3800 长边，3:2 是 9.6 MP、4:3 是 10.8 MP，
     * 差 12%。比例要等 RAW 头读出来才知道，所以换算成长边这一步放在 native 侧做。
     */
    fun workPixels(engine: SpektraEngine, heapBytes: Long): Long {
        val pxGpu = maxRenderPixels(engine, 1.0)
        val pxHeap = (heapBytes * 0.55 / HEAP_BYTES_PER_PX).toLong()
        return min(pxGpu, pxHeap).coerceAtLeast(1L)
    }

    /** 给日志看的一个长边估计（按 3:2 折算，只用于人读）。 */
    fun estimateEdge(pixels: Long): Int =
        sqrt(pixels.toDouble() * 1.5).toInt()

    /** 导出该怎么渲：tier + 临时 live 长边 + 给用户看的一句话。null = 原样走 full。 */
    data class ExportPlan(val tier: String, val liveEdge: Int, val note: String?)

    /**
     * 导出**渲染之前**判定，绝不先试一次会顶破上限的 full。
     *
     * `overscanArea` 由调用方从当前布局的 frame→canvas 倍率外推（倍率与 tier
     * 尺寸无关，所以用 live 的布局算 full 的账也是准的）。
     *
     * 返回 null 表示预算够、且用户没选更小的台阶 —— 此时 `full` 就是源图尺寸，
     * 也就是相机原生分辨率（前提是导入时没缩）。
     */
    fun planExport(
        engine: SpektraEngine,
        srcW: Int, srcH: Int,
        kept: Double,
        overscanArea: Double,
        targetEdge: Int,
    ): ExportPlan? {
        if (srcW <= 0 || srcH <= 0) return null
        val area = if (overscanArea > 1.0) overscanArea else 1.0
        val longEdge = maxOf(srcW, srcH).toDouble()
        val srcPx = srcW.toDouble() * srcH
        // 片边会先把照片裁到门幅，再给画布加一圈边：full 档的**画布**像素 =
        // 源像素 × 裁剩面积 × 画布/帧。少乘任何一项，预算都是错的（漏乘 kept
        // 会把画布高估一成，从而把导出尺寸白白压低一成）。
        val framePx = srcPx * (if (kept in 0.0..1.0) kept else 1.0)
        val canvasPx = framePx * area

        // 两条上限取小，单位都是**画布**像素：
        //  · 机体上限（驱动值 ∩ 实测死线）；
        //  · 画布不许比原片更大 —— 片边不该让成品比相机原片还大。
        val capDevicePx = usableRangeBytes(engine) / BYTES_PER_PX
        val capByDevice = capDevicePx < srcPx
        val capPx = if (capByDevice) capDevicePx else srcPx
        val budget = capPx * BYTES_PER_PX

        val frameBytes = canvasPx * BYTES_PER_PX
        val wanted = if (targetEdge > 0) min(longEdge, targetEdge.toDouble()) else longEdge
        val overBudget = frameBytes > budget

        // 预算够、且用户要的就是全尺寸：交回 null，走 full。
        if (!overBudget && wanted >= longEdge - 1.0) return null

        // 画布面积 ∝ 长边²，反解出刚好落进预算的长边，再与用户选的台阶取小。
        val safe = if (overBudget) longEdge * sqrt(budget / frameBytes) else longEdge
        val edge = min(wanted, safe).toInt().coerceIn(640, 16384)

        val note = if (overBudget) {
            val loss = (1.0 - edge / longEdge) * 100.0
            if (capByDevice) {
                val needMiB = frameBytes / 1048576.0
                val capMiB = budget / 1048576.0
                "全分辨率需要 ${"%.0f".format(needMiB)} MiB 画布，本机安全上限 " +
                    "${"%.0f".format(capMiB)} MiB；本次按 $edge 长边出图" +
                    "（少 ${"%.1f".format(loss)}% 线性分辨率）"
            } else {
                // 画布比原片大：这一圈片边是"让"出来的，不是"变"出来的。
                "片边画布 ${"%.1f".format(canvasPx / 1e6)} MP 比原片 " +
                    "${"%.1f".format(srcPx / 1e6)} MP 还大；本次按 $edge 长边出图，" +
                    "画布与照片同尺寸（少 ${"%.1f".format(loss)}% 线性分辨率）"
            }
        } else {
            "按所选尺寸 $edge 长边直接渲染"
        }
        return ExportPlan("live", edge, note)
    }

    /**
     * 这个错误是不是「设备已经废了」。
     *
     * 设备丢失之后同一 VkDevice 上的任何 dispatch 都会失败，包括刚失败那一张的
     * 小尺寸重试 —— 所以降档重试没有意义，只能重建引擎。
     */
    fun isDeviceLost(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return listOf("VkResult -4", "DEVICE_LOST", "device lost", "compute dispatch failed")
            .any { message.contains(it, ignoreCase = true) }
    }
}
