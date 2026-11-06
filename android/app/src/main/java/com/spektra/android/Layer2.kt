package com.spektra.android

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Layer 2 —— 扫描侧调色（r33）。
 *
 * 上游 SpektraLab 把整条链路分成两层：
 *   · Layer 1 是**物理冲洗**（负片显影 → 相纸印相 → 扫描），跑在引擎里；
 *   · Layer 2 是**扫描之后的调色**，跑在客户端的着色器里，从不进引擎。
 *     源码注释原话：`Layer 2 never leaves the app: the canvas runs it in a WebGL2 fragment shader.`
 *
 * Deyva 一直没有这一层，所以「色温 / 曲线 / 高光阴影」这些在 Lightroom 里理所当然的东西
 * 在这里一个都没有。这个文件把它补上，但换了个实现方式：不做 GPU 着色器，改为在 Bitmap
 * 上做 CPU 批处理。
 *
 * 为什么不用 GPU（`RuntimeShader` / AGSL）：
 *  · `RuntimeShader` 要 API 33，本应用 minSdk 24，等于一半设备没有；
 *  · 更麻烦的是导出：Ultra HDR 的 gain map 要从「调色之后的 SDR 主图」算，
 *    GPU 路径拿回像素还要过一次 readPixels，反而绕远。
 * CPU 版本在 1280 长边的预览上一帧约 6–15 ms，够实时拖动。
 *
 * ── 顺序 ──────────────────────────────────────────────────────────
 * 严格照上游 `adjustments.ts` 的固定次序，不能调换：
 *
 *   白平衡 → 曝光/对比/亮度 → 高光/阴影 → 黑白场 → 饱和度
 *          → 色彩平衡 → 曲线 → 暗角
 *
 * ── 怎么做到快 ────────────────────────────────────────────────────
 * 上面 8 步里，第 1–4 步都是**逐通道的一维标量函数**，所以整套预先烘成三张 256 项的
 * 查找表（[buildToneLut]），每个像素只查 3 次表。曲线同理（[curveLut]）。
 * 真正跨通道的只剩「饱和度」和「色彩平衡」两处，其中色彩平衡按亮度分三区，
 * 三区的矩阵**按亮度量化成 33 级预先混合好**，每像素只做一次 3×3 乘。
 *
 * ── 内存 ──────────────────────────────────────────────────────────
 * 导出全尺寸时一张 6000×4000 的图是 2400 万像素，整块取进 IntArray 要 96 MB，
 * 中端机直接 OOM。所以按「不超过 200 万像素」切条带，逐条 `getPixels → 处理 →
 * setPixels`，峰值内存压在 16 MB 以内。预览（1280 长边）永远只有一条。
 */
object Layer2 {

    /** 曲线内部可拖控制点数（两端固定，只动中间 5 个）。 */
    const val CP = 5

    /** 线性曲线：控制点正好落在对角线上。 */
    val LINEAR: List<Float> = List(CP) { (it + 1).toFloat() / (CP + 1).toFloat() }

    /** 单条条带最多多少像素。200 万 ≈ 18 MB（读 + 写各一份 Int）。 */
    private const val STRIP_PX = 2_000_000

    // ================================================================ 参数

    /**
     * 一套 Layer 2 参数。
     *
     * 全部用「人类单位」而不是 0–1：色温是 0–100 的冷暖刻度、曝光是 EV、对比是百分比。
     * 这样配方 JSON 里存下来的数字，用户自己看得懂，也能直接抄进别的软件。
     *
     * 每一项的 0 都等于「不改变画面」，所以 [isIdentity] 只要全部为 0 且曲线是线性，
     * 就可以整段跳过 —— 没调色的人不该为这个功能付任何性能代价。
     */
    data class Cfg(
        // ---- 白平衡（0 = 维持冲洗出来的色温）----
        val temp: Float = 0f,          // -100 冷 … +100 暖
        val tint: Float = 0f,          // -100 绿 … +100 品红
        // ---- 影调 ----
        val exposure: Float = 0f,      // EV，-2…+2
        val contrast: Float = 0f,      // -100…+100
        val brightness: Float = 0f,    // -100…+100
        val highlights: Float = 0f,    // -100 压暗高光 … +100 提亮高光
        val shadows: Float = 0f,       // -100 压暗阴影 … +100 提亮阴影
        val blacks: Float = 0f,        // -100 抬黑（褪色）… +100 压黑
        val whites: Float = 0f,        // -100 压白 … +100 提白
        // ---- 色彩 ----
        val saturation: Float = 0f,    // -100 黑白 … +100 浓
        // 三区色彩平衡：色相偏移、饱和、明度（各 -100…+100）
        val shHue: Float = 0f, val shSat: Float = 0f, val shLum: Float = 0f,
        val mdHue: Float = 0f, val mdSat: Float = 0f, val mdLum: Float = 0f,
        val hiHue: Float = 0f, val hiSat: Float = 0f, val hiLum: Float = 0f,
        // ---- 曲线（4 条，每条 5 个控制点的 y 值）----
        val curveRgb: List<Float> = LINEAR,
        val curveR: List<Float> = LINEAR,
        val curveG: List<Float> = LINEAR,
        val curveB: List<Float> = LINEAR,
        // ---- 暗角 ----
        val vignette: Float = 0f,      // -100 提亮四角 … +100 压暗四角
        val vignetteMid: Float = 50f,  // 50 是自然的中点
    ) {
        /** 完全没调过色：可以整段跳过。 */
        val isIdentity: Boolean
            get() = abs(temp) < 0.01f && abs(tint) < 0.01f &&
                abs(exposure) < 0.001f && abs(contrast) < 0.01f && abs(brightness) < 0.01f &&
                abs(highlights) < 0.01f && abs(shadows) < 0.01f &&
                abs(blacks) < 0.01f && abs(whites) < 0.01f &&
                abs(saturation) < 0.01f &&
                abs(shHue) < 0.01f && abs(shSat) < 0.01f && abs(shLum) < 0.01f &&
                abs(mdHue) < 0.01f && abs(mdSat) < 0.01f && abs(mdLum) < 0.01f &&
                abs(hiHue) < 0.01f && abs(hiSat) < 0.01f && abs(hiLum) < 0.01f &&
                abs(vignette) < 0.01f &&
                curveRgb == LINEAR && curveR == LINEAR && curveG == LINEAR && curveB == LINEAR

        /** 这一组有没有被改过 —— 面板的节标题靠它点亮小圆点。 */
        fun touched(section: Int): Boolean = when (section) {
            0 -> abs(temp) > 0.01f || abs(tint) > 0.01f
            1 -> abs(exposure) > 0.001f || abs(contrast) > 0.01f || abs(brightness) > 0.01f ||
                abs(highlights) > 0.01f || abs(shadows) > 0.01f ||
                abs(blacks) > 0.01f || abs(whites) > 0.01f
            2 -> abs(saturation) > 0.01f ||
                abs(shHue) + abs(shSat) + abs(shLum) +
                abs(mdHue) + abs(mdSat) + abs(mdLum) +
                abs(hiHue) + abs(hiSat) + abs(hiLum) > 0.01f
            3 -> curveRgb != LINEAR || curveR != LINEAR || curveG != LINEAR || curveB != LINEAR
            4 -> abs(vignette) > 0.01f
            else -> !isIdentity
        }

        // ---- 配方持久化：存成 JSON 对象，缺键取默认值（旧配方向前兼容）----
        fun toJson(): JSONObject = JSONObject().apply {
            put("temp", temp.toDouble()); put("tint", tint.toDouble())
            put("exp", exposure.toDouble()); put("ct", contrast.toDouble())
            put("br", brightness.toDouble()); put("hi", highlights.toDouble())
            put("sh", shadows.toDouble()); put("bk", blacks.toDouble())
            put("wh", whites.toDouble()); put("sat", saturation.toDouble())
            put("shH", shHue.toDouble()); put("shS", shSat.toDouble()); put("shL", shLum.toDouble())
            put("mdH", mdHue.toDouble()); put("mdS", mdSat.toDouble()); put("mdL", mdLum.toDouble())
            put("hiH", hiHue.toDouble()); put("hiS", hiSat.toDouble()); put("hiL", hiLum.toDouble())
            put("vg", vignette.toDouble()); put("vgm", vignetteMid.toDouble())
            put("cRgb", JSONArray(curveRgb)); put("cR", JSONArray(curveR))
            put("cG", JSONArray(curveG)); put("cB", JSONArray(curveB))
        }

        companion object {
            fun fromJson(o: JSONObject?): Cfg {
                if (o == null) return Cfg()
                fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
                fun curve(k: String): List<Float> {
                    val a = o.optJSONArray(k) ?: return LINEAR
                    if (a.length() != CP) return LINEAR
                    return List(CP) { i -> a.optDouble(i, LINEAR[i].toDouble()).toFloat() }
                }
                return Cfg(
                    temp = f("temp", 0f), tint = f("tint", 0f),
                    exposure = f("exp", 0f), contrast = f("ct", 0f), brightness = f("br", 0f),
                    highlights = f("hi", 0f), shadows = f("sh", 0f),
                    blacks = f("bk", 0f), whites = f("wh", 0f), saturation = f("sat", 0f),
                    shHue = f("shH", 0f), shSat = f("shS", 0f), shLum = f("shL", 0f),
                    mdHue = f("mdH", 0f), mdSat = f("mdS", 0f), mdLum = f("mdL", 0f),
                    hiHue = f("hiH", 0f), hiSat = f("hiS", 0f), hiLum = f("hiL", 0f),
                    curveRgb = curve("cRgb"), curveR = curve("cR"),
                    curveG = curve("cG"), curveB = curve("cB"),
                    vignette = f("vg", 0f), vignetteMid = f("vgm", 50f),
                )
            }
        }
    }

    // ================================================================ 曲线

    /**
     * 曲线控制点 → 查找表。
     *
     * [n] 是表长：8 bit 路径用 256（一输入级一项，无需插值），16 bit 路径用 [LUT16]，
     * 查表时再线性插值 —— 65536 项的表建一次要十几毫秒，拖动时每帧重建不值得。
     *
     * 用 Fritsch–Carlson 单调三次 Hermite 而不是普通 Catmull-Rom：后者在控制点拉得
     * 很陡时会**过冲**，出一条反相的亮边（暗部被压到负、高光冲到 1 以上再裁掉），
     * 看起来像脏点。单调插值保证曲线永远不往回走，这是图像曲线编辑器的硬要求。
     *
     * 另外这里会顺序修复非单调输入：用户把第 3 个点拖到第 2 个点下面时，
     * 不会得到一条打结的曲线，而是自动把它顶平 —— 与 Lightroom 的行为一致。
     */
    fun curveLut(ys: List<Float>, samples: Int = 256): FloatArray {
        val n = CP + 2
        val xs = DoubleArray(n)
        val yy = DoubleArray(n)
        xs[0] = 0.0; yy[0] = 0.0
        for (i in 0 until CP) {
            xs[i + 1] = (i + 1).toDouble() / (CP + 1).toDouble()
            yy[i + 1] = ys.getOrElse(i) { LINEAR[i] }.toDouble().coerceIn(0.0, 1.0)
        }
        xs[n - 1] = 1.0; yy[n - 1] = 1.0
        for (i in 1 until n) if (yy[i] < yy[i - 1]) yy[i] = yy[i - 1]

        val d = DoubleArray(n - 1)
        for (i in 0 until n - 1) d[i] = (yy[i + 1] - yy[i]) / (xs[i + 1] - xs[i])
        val m = DoubleArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = (d[i - 1] + d[i]) / 2.0
        for (i in 0 until n - 1) {
            if (abs(d[i]) < 1e-9) {
                m[i] = 0.0; m[i + 1] = 0.0
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val t = 3.0 / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }

        val lut = FloatArray(samples)
        var k = 0
        for (i in 0 until samples) {
            val x = i / (samples - 1.0)
            while (k < n - 2 && x > xs[k + 1]) k++
            val hseg = xs[k + 1] - xs[k]
            val t = ((x - xs[k]) / hseg).coerceIn(0.0, 1.0)
            val t2 = t * t
            val t3 = t2 * t
            val v = (2 * t3 - 3 * t2 + 1) * yy[k] +
                (t3 - 2 * t2 + t) * hseg * m[k] +
                (-2 * t3 + 3 * t2) * yy[k + 1] +
                (t3 - t2) * hseg * m[k + 1]
            lut[i] = v.toFloat().coerceIn(0f, 1f)
        }
        return lut
    }

    // ================================================================ 主入口

    /**
     * 对一张 Bitmap 施加整套 Layer 2。
     *
     * [out] 传进来的旧 Bitmap 若尺寸一致且可变，会被**就地覆写**再返回：拖动滑杆时
     * 每帧都会走一次，1280×960 的 ARGB_8888 是 4.9 MB，不复用的话 GC 会跟着拖动一直跑。
     * 调用方拿到的引用可能和上次是同一个对象，所以 Compose 那边要靠版本号触发重绘
     * （见 MainActivity 的 `toneRev`）。
     */
    fun apply(src: Bitmap, c: Cfg, out: Bitmap? = null): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        if (c.isIdentity) return src

        val lutA = buildToneLut(c)                 // 3 × 256：白平衡/曝光/对比/亮度/高光阴影/黑白场
        val lutB = buildCurveLut(c)                // 3 × 256：RGB 复合曲线再串单通道曲线
        val satK = 1f + c.saturation / 100f
        val zone = buildZoneMats(c)                // 33 × 9 混合矩阵 + 33 个明度偏移
        val zoneMat = zone.first
        val zoneOff = zone.second
        val vigAmt = c.vignette / 100f * 0.85f
        val vigMid = (c.vignetteMid / 100f).coerceIn(0.02f, 0.98f)
        val vigMid2 = vigMid * vigMid
        val vig = abs(vigAmt) > 0.001f

        val dst = if (out != null && !out.isRecycled && out.isMutable &&
            out.width == w && out.height == h
        ) out else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // 暗角按归一化椭圆半径算：中心 (cx,cy)，到四角 r² = 1
        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        val invCx = if (cx > 0f) 1f / cx else 0f
        val invCy = if (cy > 0f) 1f / cy else 0f

        val ctx = Args(
            w = w, srcW = w, outW = w, step = 1,
            cx = cx, cy = cy, invCx = invCx, invCy = invCy,
            aR = lutA[0], aG = lutA[1], aB = lutA[2],
            bR = lutB[0], bG = lutB[1], bB = lutB[2],
            satK = satK, zoneMat = zoneMat, zoneOff = zoneOff,
            vigAmt = vigAmt, vigMid2 = vigMid2, vig = vig,
        )

        val rowsPerStrip = (STRIP_PX / w).coerceIn(1, h)
        // 缓冲只分配一次，各条带共用。6000×4000 的图有 12 条，每条 8 MB ——
        // 逐条新建等于往 GC 里灌近 100 MB，而内容每次都会被覆盖，复用没有任何风险。
        val buf = IntArray(w * rowsPerStrip)
        var y = 0
        while (y < h) {
            val rows = minOf(rowsPerStrip, h - y)
            src.getPixels(buf, 0, w, 0, y, w, rows)
            runBuckets(buf, rows, y, ctx)
            dst.setPixels(buf, 0, w, 0, y, w, rows)
            y += rows
        }
        return dst
    }

    // ================================================================ 16 bit 入口

    /**
     * 16 bit 路径的表长。
     *
     * 8 bit 路径一张 256 项的表正好是「一输入级一项」，不需要插值；16 bit 有 65536 级，
     * 照做的话每帧要建 3×65536 项的表，拖动时不划算。1024 项 + 线性插值的误差在
     * 8 bit 输出上不到 0.1 个码值 —— 比抖动本身还小一个数量级。
     */
    private const val LUT16 = 1024

    private const val U16_TO_UNIT = 1f / 65535f

    /**
     * 对引擎原样的 rgba16 施加 Layer 2，直接出 8 bit Bitmap；16 bit 数据不够时返回 null。
     *
     * 这条路径存在的理由只有一条：**大幅曲线不能在 8 bit 上做**。
     *
     * 引擎（`nativeRender`）给的是每通道 u16，而旧的 `renderToBitmap` 一句 `v shr 8`
     * 就把它砍成 8 bit，之后拉曲线只能在 256 级里挪。曲线斜率大于 1 的地方，相邻输入级
     * 会被映射到输出相差 2–6 个码值 —— 这就是成片上的色阶断层（Photoshop 8 位模式拉
     * 曲线同理，它靠 16 位模式 + dither 掩盖）。这里保留 16 bit 到最后一刻：同一根曲线
     * 作用在 65536 级上是连续的，只有最后落到 8 bit 时才有 1 个码值的舍入，交给抖动打散。
     *
     * [px] 是 RGBA 交错的 u16，即 `nativeRender` 的原样输出（长度 w*h*4）。
     *
     * [step] 是**采样步长**：1 = 全分辨率（默认），2 = 隔行隔列取一个，输出只有四分之一像素。
     * 拖动滑杆时如果上一帧算得慢，调用方会把这一轮降到 2，先保证跟手，松手后再出全图
     * （画面会清楚一档，这正是 Lightroom 那类的做法）。降采样只影响输出尺寸，几何
     * （暗角半径）与抖动相位都按**源坐标**算，所以半分辨率帧和全分辨率帧的位置是对齐的，
     * 不会出现"松手一瞬间暗角挪了半格"。
     *
     * [cancel] 每 8 行问一次：返回 true 就立刻放弃这一帧。拖动时参数每 16 ms 变一次，
     * 上一帧还没算完就已经过时了 —— 不主动退出的话，好几个整帧计算会叠在一起抢 CPU，
     * 越拖越慢。返回 null 表示这一帧被放弃（调用方当作"没算出东西"，不要拿去显示）。
     */
    fun apply16(
        px: ShortArray, w: Int, h: Int, c: Cfg,
        step: Int = 1, cancel: (() -> Boolean)? = null,
    ): Bitmap? {
        if (w <= 0 || h <= 0) return null
        if (px.size < w * h * 4) return null
        if (c.isIdentity) return null      // 没调色：调用方直接用 8 bit 原图，零成本

        val st = step.coerceIn(1, 4)
        val ow = (w + st - 1) / st
        val oh = (h + st - 1) / st

        val lutA = buildToneLut(c, LUT16)
        val lutB = buildCurveLut(c, LUT16)
        val satK = 1f + c.saturation / 100f
        val zone = buildZoneMats(c)
        val vigAmt = c.vignette / 100f * 0.85f
        val vigMid = (c.vignetteMid / 100f).coerceIn(0.02f, 0.98f)
        val vig = abs(vigAmt) > 0.001f

        val cx = (w - 1) * 0.5f
        val cy = (h - 1) * 0.5f
        val ctx = Args(
            w = ow, srcW = w, outW = ow, step = st,
            cx = cx, cy = cy,
            invCx = if (cx > 0f) 1f / cx else 0f, invCy = if (cy > 0f) 1f / cy else 0f,
            aR = lutA[0], aG = lutA[1], aB = lutA[2],
            bR = lutB[0], bG = lutB[1], bB = lutB[2],
            satK = satK, zoneMat = zone.first, zoneOff = zone.second,
            vigAmt = vigAmt, vigMid2 = vigMid * vigMid, vig = vig,
        )

        val dst = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val rowsPerStrip = (STRIP_PX / ow).coerceIn(1, oh)
        val buf = IntArray(ow * rowsPerStrip)
        val aborted = java.util.concurrent.atomic.AtomicBoolean(false)
        var y = 0
        while (y < oh) {
            val rows = minOf(rowsPerStrip, oh - y)
            runBuckets16(px, buf, y, rows, ctx, cancel, aborted)
            if (aborted.get()) return null      // 这一帧已过期：别交半张图出去
            dst.setPixels(buf, 0, ow, 0, y, ow, rows)
            y += rows
        }
        return dst
    }

    /** 与 [runBuckets] 同一套切分，只是源缓冲是交错的 u16，且可能按步长降采样。 */
    private fun runBuckets16(
        px: ShortArray, out: IntArray, yBase: Int, rows: Int, c: Args,
        cancel: (() -> Boolean)?, aborted: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        if (rows < 64 || threads == 1) {
            rows16(px, out, yBase, 0, rows, c, cancel, aborted)
            return
        }
        val band = (rows + threads - 1) / threads
        val workers = ArrayList<Thread>(threads)
        for (ti in 0 until threads) {
            val r0 = ti * band
            val r1 = minOf(rows, r0 + band)
            if (r0 >= r1) break
            val t = Thread { rows16(px, out, yBase, r0, r1, c, cancel, aborted) }
            t.isDaemon = true
            t.priority = Thread.NORM_PRIORITY
            t.start()
            workers.add(t)
        }
        for (t in workers) runCatching { t.join() }
    }

    /** 16 bit 版的逐行主体；步骤与顺序和 [processRows] 一一对应。 */
    private fun rows16(
        px: ShortArray, out: IntArray, yBase: Int, r0: Int, r1: Int, c: Args,
        cancel: (() -> Boolean)?, aborted: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        val srcW = c.srcW
        val outW = c.outW
        val st = c.step
        val aR = c.aR; val aG = c.aG; val aB = c.aB
        val bR = c.bR; val bG = c.bG; val bB = c.bB
        val satK = c.satK
        val zoneMat = c.zoneMat; val zoneOff = c.zoneOff
        val bayer = BAYER8
        for (r in r0 until r1) {
            // 每 8 行问一次"还要不要算"。拖动时这一帧往往已经过时，早点退出能把
            // CPU 让给刚生成的那一帧，手感差别的来源就在这里。
            if (cancel != null && (r and 7) == 0 && cancel()) { aborted.set(true); return }
            val yOut = yBase + r
            val ySrc = yOut * st                 // 源坐标：暗角半径按它算
            val dy = (ySrc - c.cy) * c.invCy
            val dy2 = dy * dy
            val srcBase = ySrc * srcW * 4
            val rowOut = r * outW
            val brow = (yOut and 7) shl 3        // 抖动相位按输出坐标，降采样也保持完整 8×8
            for (x in 0 until outW) {
                val sx = x * st
                val si = srcBase + sx * 4
                // ---- 1–4 · 白平衡 / 曝光 / 对比 / 亮度 / 高光阴影 / 黑白场 ----
                var rr = sampleAt(aR, (px[si].toInt() and 0xFFFF) * U16_TO_UNIT)
                var gg = sampleAt(aG, (px[si + 1].toInt() and 0xFFFF) * U16_TO_UNIT)
                var bb = sampleAt(aB, (px[si + 2].toInt() and 0xFFFF) * U16_TO_UNIT)

                // ---- 5 · 饱和度 ----
                if (satK != 1f) {
                    val l = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                    rr = l + (rr - l) * satK
                    gg = l + (gg - l) * satK
                    bb = l + (bb - l) * satK
                }

                // ---- 6 · 色彩平衡 ----
                val l2 = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                val zi = (l2 * 32f).toInt().coerceIn(0, 32)
                val mi = zi * 9
                val o = zoneOff[zi]
                val nr = zoneMat[mi] * rr + zoneMat[mi + 1] * gg + zoneMat[mi + 2] * bb + o
                val ng = zoneMat[mi + 3] * rr + zoneMat[mi + 4] * gg + zoneMat[mi + 5] * bb + o
                val nb = zoneMat[mi + 6] * rr + zoneMat[mi + 7] * gg + zoneMat[mi + 8] * bb + o

                // ---- 7 · 曲线 ----
                rr = sampleAt(bR, nr.coerceIn(0f, 1f))
                gg = sampleAt(bG, ng.coerceIn(0f, 1f))
                bb = sampleAt(bB, nb.coerceIn(0f, 1f))

                // ---- 8 · 暗角 ----
                if (c.vig) {
                    val dx = (sx - c.cx) * c.invCx
                    val r2 = dx * dx + dy2
                    val t = ((r2 - c.vigMid2) / (1f - c.vigMid2)).coerceIn(0f, 1f)
                    val s = t * t * (3f - 2f * t)
                    val k = 1f - c.vigAmt * s
                    rr *= k; gg *= k; bb *= k
                }

                // 最后才是 8 bit：只剩 1 个码值的舍入，抖动把它变成看不出方向的噪点
                val d = bayer[brow or (x and 7)]
                val ri = (rr * 255f + d).toInt().coerceIn(0, 255)
                val gi = (gg * 255f + d).toInt().coerceIn(0, 255)
                val bi = (bb * 255f + d).toInt().coerceIn(0, 255)
                out[rowOut + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }

    /** 一次调色运算里所有不变的量，避免在行循环里传十几个参数。 */
    private class Args(
        val w: Int,
        /** 源图宽度（16 bit 路径降采样时与 [outW] 不同；8 bit 路径两者相等）。 */
        val srcW: Int,
        /** 输出宽度（每行写多少个像素）。 */
        val outW: Int,
        /** 采样步长；8 bit 路径恒为 1。 */
        val step: Int,
        val cx: Float, val cy: Float, val invCx: Float, val invCy: Float,
        val aR: FloatArray, val aG: FloatArray, val aB: FloatArray,
        val bR: FloatArray, val bG: FloatArray, val bB: FloatArray,
        val satK: Float, val zoneMat: FloatArray, val zoneOff: FloatArray,
        val vigAmt: Float, val vigMid2: Float, val vig: Boolean,
    )

    /** 把一条条带的若干行分给几个线程并行跑。 */
    private fun runBuckets(px: IntArray, rows: Int, yBase: Int, c: Args) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        if (rows < 64 || threads == 1) {
            processRows(px, 0, rows, yBase, c)
            return
        }
        val band = (rows + threads - 1) / threads
        val workers = ArrayList<Thread>(threads)
        for (ti in 0 until threads) {
            val r0 = ti * band
            val r1 = minOf(rows, r0 + band)
            if (r0 >= r1) break
            val t = Thread { processRows(px, r0, r1, yBase, c) }
            t.isDaemon = true
            t.priority = Thread.NORM_PRIORITY
            t.start()
            workers.add(t)
        }
        for (t in workers) runCatching { t.join() }
    }

    /**
     * 处理条带内 [r0, r1) 行。
     *
     * [yBase] 是这条条带在整图里的起始行 —— 暗角的径向距离和抖动矩阵都按**整图坐标**算，
     * 否则每一条条带都会从自己顶部重新开始，接缝处能看出一条横向的亮度台阶。
     */
    private fun processRows(px: IntArray, r0: Int, r1: Int, yBase: Int, c: Args) {
        val w = c.w
        val aR = c.aR; val aG = c.aG; val aB = c.aB
        val bR = c.bR; val bG = c.bG; val bB = c.bB
        val satK = c.satK
        val zoneMat = c.zoneMat; val zoneOff = c.zoneOff
        val bayer = BAYER8
        for (r in r0 until r1) {
            val yAbs = yBase + r
            val dy = (yAbs - c.cy) * c.invCy
            val dy2 = dy * dy
            val row = r * w
            val brow = (yAbs and 7) shl 3
            for (x in 0 until w) {
                val p = px[row + x]
                var rr = ((p shr 16) and 0xFF) * (1f / 255f)
                var gg = ((p shr 8) and 0xFF) * (1f / 255f)
                var bb = (p and 0xFF) * (1f / 255f)

                // ---- 1–4 · 白平衡 / 曝光 / 对比 / 亮度 / 高光阴影 / 黑白场 ----
                rr = aR[(rr * 255f + 0.5f).toInt().coerceIn(0, 255)]
                gg = aG[(gg * 255f + 0.5f).toInt().coerceIn(0, 255)]
                bb = aB[(bb * 255f + 0.5f).toInt().coerceIn(0, 255)]

                // ---- 5 · 饱和度（跨通道，只能逐像素算）----
                if (satK != 1f) {
                    val l = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                    rr = l + (rr - l) * satK
                    gg = l + (gg - l) * satK
                    bb = l + (bb - l) * satK
                }

                // ---- 6 · 色彩平衡（三区，按亮度查预先混合好的矩阵）----
                val l2 = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
                val zi = (l2 * 32f).toInt().coerceIn(0, 32)
                val mi = zi * 9
                val o = zoneOff[zi]
                val nr = zoneMat[mi] * rr + zoneMat[mi + 1] * gg + zoneMat[mi + 2] * bb + o
                val ng = zoneMat[mi + 3] * rr + zoneMat[mi + 4] * gg + zoneMat[mi + 5] * bb + o
                val nb = zoneMat[mi + 6] * rr + zoneMat[mi + 7] * gg + zoneMat[mi + 8] * bb + o

                // ---- 7 · 曲线 ----
                rr = bR[(nr * 255f + 0.5f).toInt().coerceIn(0, 255)]
                gg = bG[(ng * 255f + 0.5f).toInt().coerceIn(0, 255)]
                bb = bB[(nb * 255f + 0.5f).toInt().coerceIn(0, 255)]

                // ---- 8 · 暗角 ----
                if (c.vig) {
                    val dx = (x - c.cx) * c.invCx
                    val r2 = dx * dx + dy2
                    val t = ((r2 - c.vigMid2) / (1f - c.vigMid2)).coerceIn(0f, 1f)
                    val s = t * t * (3f - 2f * t)
                    // 减号：vigAmt 为正 = 压暗四角（黑色暗角），为负 = 提亮（白色暗角）。
                    // 写成加号会让"加暗角"把四角照亮，看成片第一眼就会觉得哪儿不对但说不出。
                    val k = 1f - c.vigAmt * s
                    rr *= k; gg *= k; bb *= k
                }

                // 抖动：给 8 bit 输出加半个码值的有序噪声，把曲线拉伸后的色阶断层打散
                val d = bayer[brow or (x and 7)]
                val ri = (rr * 255f + d).toInt().coerceIn(0, 255)
                val gi = (gg * 255f + d).toInt().coerceIn(0, 255)
                val bi = (bb * 255f + d).toInt().coerceIn(0, 255)
                px[row + x] = (p and -0x1000000) or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }

    // ================================================================ 查找表

    /**
     * 步骤 1–4 合成三张 256 项表。
     *
     * 白平衡是「把整条曲线按通道乘一个增益」，所以它先作用在输入上；之后的高光/阴影/
     * 黑白场都是对**同一个值**做一维映射，可以串在同一次循环里。
     *
     * 用 sRGB 编码值（Bitmap 里的原始 0–255）而不是线性光：这是 Lightroom、Photoshop
     * 曲线的做法，用户预期也是那个手感。物理上不严格，但调色本来就是在显示域做的主观决定。
     */
    private fun buildToneLut(c: Cfg, samples: Int = 256): Array<FloatArray> {
        // 白平衡：暖 = 加红减蓝；品红 = 减绿。最后按亮度归一，避免一调色温整体忽明忽暗。
        val t = c.temp / 100f
        val ti = c.tint / 100f
        val gr = 1f + 0.32f * t + 0.10f * ti
        val gg = 1f - 0.10f * abs(t) - 0.26f * ti
        val gb = 1f - 0.32f * t + 0.10f * ti
        val norm = 0.2126f * gr + 0.7152f * gg + 0.0722f * gb
        val gains = floatArrayOf(
            if (norm > 0.01f) gr / norm else 1f,
            if (norm > 0.01f) gg / norm else 1f,
            if (norm > 0.01f) gb / norm else 1f,
        )

        val expo = 2.0.pow(c.exposure.toDouble()).toFloat()
        val ck = 2.0.pow((c.contrast / 100f * 1.15f).toDouble()).toFloat()
        val bright = c.brightness / 100f * 0.18f
        val hl = c.highlights / 100f
        val sh = c.shadows / 100f
        val bp = c.blacks / 100f * 0.18f
        val wp = c.whites / 100f * 0.18f

        val out = Array(3) { FloatArray(samples) }
        val denom = (samples - 1).toFloat()
        for (ch in 0..2) {
            val gain = gains[ch]
            val arr = out[ch]
            for (i in 0 until samples) {
                var v = (i / denom) * gain
                v *= expo
                // 对比：绕 0.5 的线性拉伸。超出 0–1 的部分留到最后统一裁，
                // 但高光/阴影的权重函数会先看到真实值，所以压暗不会被过早截断。
                if (ck != 1f) v = 0.5f + (v - 0.5f) * ck
                v += bright
                // 高光 / 阴影。
                //
                // 这里两个方向的公式是**不对称**的，原因很实在：
                //  · 提亮时按 (1-v) 加权 —— 越接近纸白越提不动（本来就到顶了，硬提只会整片糊白）；
                //  · 压暗时不能也按 (1-v) —— 那会让纯白 255 一点都压不下来，而"把过曝的天空
                //    拉回来"恰恰是高光滑杆最主要的用途。所以压的时候按「到 0.35 的距离」收缩。
                //
                // 权重区间的起点也调过：高光从 0.35 起、阴影到 0.65 止，两段有重叠。
                // 如果死守 0.5 分界，中灰附近会出现一段"拉高光没反应、拉阴影也没反应"的死区。
                if (hl != 0f) {
                    val wgt = smoothstep(0.35f, 1f, v)
                    v += if (hl < 0f) hl * 0.35f * wgt * (v - 0.35f)
                         else hl * 0.30f * wgt * (1f - v)
                }
                if (sh != 0f) {
                    val wgt = smoothstep(0.65f, 0f, v)
                    v += if (sh > 0f) sh * 0.35f * wgt * (1f - v)
                         else sh * 0.45f * wgt * v
                }
                // 黑场 / 白场：都是「重设端点」，一个公式同时覆盖压与抬
                if (bp != 0f) v = (v - bp) / (1f - bp)
                if (wp != 0f) v /= (1f - wp)
                arr[i] = v.coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * 曲线：RGB 复合曲线在前，单通道曲线在后，两级串联烘成一张表。
     *
     * 串联时对前一级的结果做**插值取样**而不是四舍五入到表项：16 bit 路径的表只有
     * [LUT16] 项，若在这里先量化一次，等于把刚保住的那 8 位精度又扔掉一截。
     */
    private fun buildCurveLut(c: Cfg, samples: Int = 256): Array<FloatArray> {
        val rgb = curveLut(c.curveRgb, samples)
        val r = curveLut(c.curveR, samples)
        val g = curveLut(c.curveG, samples)
        val b = curveLut(c.curveB, samples)
        val out = Array(3) { FloatArray(samples) }
        for (i in 0 until samples) {
            out[0][i] = sampleAt(r, rgb[i])
            out[1][i] = sampleAt(g, rgb[i])
            out[2][i] = sampleAt(b, rgb[i])
        }
        return out
    }

    /**
     * 三区色彩平衡 → 33 组预先混合的 3×3 矩阵 + 明度偏移。
     *
     * 为什么量化到 33 级：三区的权重是亮度的连续函数，如果每像素都现算三个矩阵再混合，
     * 单是混合就要 27 次乘加，比后面所有步骤加起来都贵。33 级在 8 bit 输出上已经看不出
     * 台阶（每级约 8 个码值，而矩阵本身随亮度变化很缓）。
     */
    private fun buildZoneMats(c: Cfg): Pair<FloatArray, FloatArray> {
        val mat = FloatArray(33 * 9)
        val off = FloatArray(33)
        val ms = zoneMatrix(c.shHue, c.shSat)
        val mm = zoneMatrix(c.mdHue, c.mdSat)
        val mh = zoneMatrix(c.hiHue, c.hiSat)
        for (i in 0..32) {
            val l = i / 32f
            val ws = smoothstep(0.5f, 0f, l)
            val wh = smoothstep(0.5f, 1f, l)
            val wm = (1f - ws - wh).coerceAtLeast(0f)
            val base = i * 9
            for (k in 0..8) {
                val identityTerm = if (k == 0 || k == 4 || k == 8) 1f else 0f
                mat[base + k] = ms[k] * ws + mm[k] * wm + mh[k] * wh +
                    identityTerm * (1f - ws - wm - wh).coerceAtLeast(0f)
            }
            off[i] = (c.shLum * ws + c.mdLum * wm + c.hiLum * wh) / 100f * 0.12f
        }
        return mat to off
    }

    /**
     * 一个区的色相旋转 + 饱和度矩阵。
     *
     * 色相用 W3C filter 的标准 hue-rotate 矩阵（绕灰轴旋转，不是 HSV 的色相环），
     * 好处是纯线性的，可以和三区权重直接线性混合；换成 HSV 就得每像素做一次
     * rgb→hsv→rgb 往返，贵且会在灰轴附近出现色相跳变。
     */
    private fun zoneMatrix(hue: Float, sat: Float): FloatArray {
        val ang = (hue / 100f).toDouble() * 40.0 * PI / 180.0
        val cs = cos(ang).toFloat()
        val sn = sin(ang).toFloat()
        val h = floatArrayOf(
            0.213f + 0.787f * cs - 0.213f * sn,
            0.715f - 0.715f * cs - 0.715f * sn,
            0.072f - 0.072f * cs + 0.928f * sn,
            0.213f - 0.213f * cs + 0.143f * sn,
            0.715f + 0.285f * cs + 0.140f * sn,
            0.072f - 0.072f * cs - 0.283f * sn,
            0.213f - 0.213f * cs - 0.787f * sn,
            0.715f - 0.715f * cs + 0.715f * sn,
            0.072f + 0.928f * cs + 0.072f * sn,
        )
        if (abs(sat) < 0.01f) return h
        val s = (1f + sat / 100f).coerceIn(0f, 2f)
        val st = floatArrayOf(
            0.2126f + 0.7874f * s, 0.7152f - 0.7152f * s, 0.0722f - 0.0722f * s,
            0.2126f - 0.2126f * s, 0.7152f + 0.2848f * s, 0.0722f - 0.0722f * s,
            0.2126f - 0.2126f * s, 0.7152f - 0.7152f * s, 0.0722f + 0.9278f * s,
        )
        // 先旋转再饱和：h 先作用，所以矩阵乘法顺序是 st × h
        val out = FloatArray(9)
        for (row in 0..2) for (col in 0..2) {
            var v = 0f
            for (k in 0..2) v += st[row * 3 + k] * h[k * 3 + col]
            out[row * 3 + col] = v
        }
        return out
    }

    // ================================================================ 直方图

    /** RGB + 亮度直方图，每个 256 桶。 */
    class Hist(val r: IntArray, val g: IntArray, val b: IntArray, val l: IntArray) {
        /**
         * 归一化到 0–1，并去掉最亮那一桶。
         *
         * 纯白常占一大块（天空、纸白、片边），留着它会把整条曲线压成贴地平线，
         * 什么都看不出。扔掉 255 之后剩下的形状才是调色要看的东西。
         */
        fun norm(): FloatArray {
            val all = FloatArray(256)
            for (i in 0..255) all[i] = l[i].toFloat()
            all[255] = 0f
            val max = all.maxOrNull() ?: 0f
            if (max <= 0f) return all
            for (i in 0..255) all[i] /= max
            return all
        }
    }

    /**
     * 算直方图。抽样步长默认 3 —— 1280×960 上抽掉八成像素，形状不变，耗时从
     * 十几毫秒降到两三毫秒。拖动滑杆时直方图每帧都要重算，这个开销必须省。
     */
    fun histogram(bmp: Bitmap, step: Int = 3): Hist {
        val r = IntArray(256); val g = IntArray(256)
        val b = IntArray(256); val l = IntArray(256)
        val w = bmp.width; val h = bmp.height
        val st = step.coerceAtLeast(1)
        // 按行抽样，**不整块取像素** —— 整块取在 1280×960 上要 4.9 MB，而拖动滑杆时
        // 每帧都要算一次直方图，那笔分配会直接把 GC 拖进循环。逐行取只要 5 KB 的缓冲。
        val rowBuf = IntArray(w)
        var y = 0
        while (y < h) {
            bmp.getPixels(rowBuf, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = rowBuf[x]
                val rr = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val bb = p and 0xFF
                r[rr]++; g[gg]++; b[bb]++
                l[(0.2126f * rr + 0.7152f * gg + 0.0722f * bb).toInt().coerceIn(0, 255)]++
                x += st
            }
            y += st
        }
        return Hist(r, g, b, l)
    }

    // ================================================================ 小工具

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e0 == e1) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 在 0–1 的定义域上对一张等距表做线性插值。
     *
     * 表长 ≥ 1024 时这项误差在 8 bit 输出上不到 0.1 个码值，可以忽略；用它换来的是
     * 建表成本只有 65536 项表的 1/64（拖动时每帧都要重建）。
     */
    private fun sampleAt(lut: FloatArray, v: Float): Float {
        val t = v * (lut.size - 1)
        val i = t.toInt().coerceIn(0, lut.size - 2)
        val f = t - i
        val a = lut[i]
        return a + (lut[i + 1] - a) * f
    }

    /**
     * 8×8 Bayer，归一化到 ±0.5。
     *
     * [r37] 从 4×4 换成 8×8：4×4 只有 16 个相位，在天空这类大面积平滑渐变上会显成
     * 一层细网格（尤其缩放系数不是整数时还会和像素栅格打架）。8×8 是 64 个相位，
     * 肉眼把它读成噪点而不是图案，代价只是多一次数组索引。
     */
    private val BAYER8 = FloatArray(64) {
        intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21,
        )[it] / 64f - 0.5f
    }
}
