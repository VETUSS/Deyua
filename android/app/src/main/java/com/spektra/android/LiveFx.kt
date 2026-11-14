package com.spektra.android

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 取景器 / 观片台 的 live 图渲染（r52）。
 *
 * 关键约束：**预览与导出视频必须是同一张画面**。所以这里不碰 Compose 的 Canvas，
 * 只用 android.graphics 画到一个普通 Canvas 上 —— 预览由自绘 View 每帧调它，
 * 导出时由一个软件 Bitmap 的 Canvas 调同一个函数。
 *
 * 时间轴是**绝对秒数**，一切随机都由 (seed, 时间) 决定，不用 Math.random。
 *
 * ---------------------------------------------------------------- 一共两套时间轴
 * 两段参考片都是同一件事的两种场合：**一次曝光事件**。所以两套都按"事件"排，
 * 不按"循环动画"排 —— 全程没有一处是"为了动而在动"。
 *
 *  · 观片台（灯亮）：暗态 → 硬切过曝 → 指数回落 → 一动不动。纸不动，画不动。
 *  · 取景器（哈苏 503 的腰平）：对焦拉近 → 实焦静止 → 反光镜拍上去（屏黑、整机一跳）
 *    → 反光镜回位、屏重新亮起 → 落定、定格 → 走黑场 → 出片（就是导出那张图本身）。
 *
 * 上一版（r51）把"胶片感"理解成了"往画面里加东西"：手持漂移 + 片门走位 + 测光
 * 泵动 + 灰尘 + 划痕 + 漏光，全在同一秒里发生。实测参考片之后发现恰好相反 ——
 * 参考片每一拍都**只做一件事**，而且做完就彻底停住。于是 r52 把所有位移归零。
 */
object LiveFx {
    const val VIEWFINDER = 0
    const val PRINT = 1

    /**
     * 一轮的长度（秒）。导出的视频/动态照片也是这个长度。
     *
     * r58 起结尾多了"出片"这一拍：快门响过之后先**定格**在取景器里的最终画面，
     * 再切到成片 —— 也就是相册里那张照片本身。5.6 s = 3.0 s 讲完一次拍摄，
     * 剩下 2.6 s 让成片停在那儿被看清。动态照片的封面用的就是这段的最后一帧。
     */
    const val LOOP = 5.6f

    /**
     * 成片在动态影像里稳定可见的时刻（秒）。
     *
     * 3.26 s 起画面就是那张成片，一直到 5.6 s 结束。写进动态照片 XMP 的"主图时刻"
     * 取这一段的中点：相册若按这个时间去视频里取帧，拿到的一定是成片本身；而取
     * 5.6 s（正好是末帧边界）时，有的解码器会停在空帧上，长按播放先黑一下。
     */
    const val STILL_AT = 4.4f

    // ---------------------------------------------------------------- 观片台的时间参数
    /** 灯亮的时刻。之前是"没开灯"，之后是曝光事件。LightboxSound 直接引用它。 */
    const val LIGHT_ON = 0.95f
    /** 过曝保持时长。参考片实测 0.17 s —— 这一段是"烧"的关键，短了就不像过曝。 */
    private const val FLASH_HOLD = 0.16f
    /** 过曝峰值超出常曝光的部分（增益 − 1）。参考片实测 180.1 / 75.8 − 1 = 1.37。 */
    private const val FLASH_GAIN = 1.37f

    /**
     * 回落曲线：过曝峰值的**剩余比例**，每 0.1 s 一个点。
     *
     * 为什么不用 exp()：参考片实测的回落不是指数 —— 前段偏慢、中段加速、末段掉得干净
     * （d=0 时 1.00，0.3 s 时 0.51，0.8 s 时 0.08，1.2 s 时 0.003）。硬套 τ=0.42 的
     * 指数在 1.4 s 处还会剩 7% 的亮度，肉眼就是"一直差一口气没落定"。
     * 这张表是逐帧量出来再归一化的，落定的时刻与参考片一致。
     */
    private val SETTLE = floatArrayOf(
        1.000f, // d = 0.0
        0.810f, // 0.1
        0.650f, // 0.2
        0.510f, // 0.3
        0.394f, // 0.4
        0.292f, // 0.5
        0.204f, // 0.6
        0.139f, // 0.7
        0.080f, // 0.8
        0.027f, // 1.0（0.9 处插值）
        0.006f, // 1.1
        0.000f, // 1.2
    )
    /** 未点灯时画面的相对亮度。参考片实测 17.3 / 75.8 = 0.228。 */
    private const val DARK_PHOTO = 0.24f
    /** 未点灯时纸的相对亮度。参考片实测 101.6 / 246.7 = 0.412。 */
    private const val DARK_PAPER = 0.41f

    // ---------------------------------------------------------------- 取景器的时间参数
    // 全部来自参考片（640×790 / 30 fps / 3.05 s）的逐帧实测，单位秒。
    /** 对焦拉到位。参考片 f30（1.00 s）之后清晰度不再变化。 */
    private const val FOCUS_END = 1.05f
    /** 快门。f46→f48 之间，两帧走完。快门声也落在这一刻（ShutterSound 直接引用它）。 */
    const val CUT = 1.567f
    /** 暗场结束，光开始进。f052 之后开始变亮。 */
    private const val DARK_END = 1.733f
    /** 曝光峰值。f056 均值 212.3，是全片最亮的一帧。 */
    private const val PEAK = 1.867f
    /** 落定。f070 之后再没有变化。 */
    private const val SETTLED = 2.333f
    /**
     * 定格。曝光已经落定、屏上是最实的一帧 —— 从这里开始**一个参数都不再变**。
     *
     * 为什么要有这一段：快门之后立刻切到成片，观众来不及看完"刚才对焦对上了什么"。
     * 留 0.67 s 定格，眼睛才认得出"取景器里这一张"和"出来的那一张"是同一张。
     */
    private const val HOLD_END = 3.00f

    /**
     * 取景器沉进黑场。0.13 s。
     *
     * 不用叠化：叠化会让取景器的四壁和成片的四边同时存在，两层轮廓叠在一起就是重影。
     * 走黑场读起来是一刀（真实的转场就是一刀），短到眼睛只感到"换了"，不会被跳帧扎到。
     */
    private const val BLACK_END = 3.13f

    /** 成片从黑里升起来。同样 0.13 s。之后到片尾一动不动。 */
    private const val REVEAL_END = 3.26f

    /**
     * 对焦曲线：虚焦量的剩余比例，0 = 实焦，1 = 最虚。
     * 逐帧实测（σ 由梯度幅值反解）：0 s 最虚，0.5 s 才收掉四成，0.7–1.0 s 一口气收完。
     * 是典型的自动对焦：先慢慢找，再一下咬住。
     */
    private val FOCUS = floatArrayOf(
        1.00f, 0.96f, 0.88f, 0.74f, 0.54f, 0.36f, 0.21f, 0.11f, 0.05f, 0.02f, 0.00f,
    )

    /**
     * 曝光回落的剩余比例，每 1/30 s 一个点（f056→f070 逐帧实测，已扣掉底噪）。
     * 同样不是指数：每帧的比值从 0.91 一路走到 0.07 —— 前半段"依依不舍"，
     * 末段掉得干净，所以落定那一刻是"啪"地停住，不是"一直差一口气"。
     */
    private val VEIL = floatArrayOf(
        1.000f, 0.911f, 0.804f, 0.701f, 0.601f, 0.511f, 0.425f, 0.343f,
        0.268f, 0.201f, 0.141f, 0.092f, 0.048f, 0.019f, 0.001f,
    )

    /**
     * 最虚时的 σ，占**屏边长**的比例。
     *
     * 实拍的参考片里，屏幕宽约 500 px 时 σ≈13 px —— 也就是说虚焦的尺度是跟着屏走的，
     * 不是跟着画面走的。所以这里乘的是屏边长，不是画面短边。
     */
    private const val SIGMA_MAX = 0.0245f

    // ---------------------------------------------------------------- 腰平取景器
    /** 对焦屏边长 / 短边。正俯视下屏是正方形，这个比例就是它在画面里的实际宽度。 */
    private const val WLF_SCREEN = 0.82f
    /** 屏边黑压条 / 屏边长（实拍约 1.2%）。 */
    private const val WLF_LIP = 0.012f
    /** 镀铬屏框 / 屏边长（实拍约 3%）。 */
    private const val WLF_FRAME = 0.030f
    /** 罩壁进深（正视投影的宽度）/ 屏边长。 */
    private const val WLF_WALL = 0.090f
    /** 罩口镀铬卷边 / 屏边长。 */
    private const val WLF_RIM = 0.012f

    // ---------------------------------------------------------------- 噪声
    private val fineNoise by lazy { noise(512, 0x5EED, 190f) }
    private val coarseNoise by lazy { noise(64, 0xBEEF, 190f) }
    private val fiberNoise by lazy { noise(256, 0x0F1B, 96f) }

    private fun noise(size: Int, seed: Int, mag: Float): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        var s = seed
        fun rnd(): Float {
            s = s * 1664525 + 1013904223
            return ((s ushr 8) and 0xFFFF) / 65535f
        }
        for (i in px.indices) {
            // 两次抽样相乘 → 幅度分布偏向低值，看起来是稀疏的晶粒，而不是一层均匀灰雾
            val a = (rnd() * rnd() * mag).toInt()
            px[i] = if (rnd() < 0.5f) Color.argb(a, 255, 255, 255) else Color.argb(a, 0, 0, 0)
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    // ---------------------------------------------------------------- 工具
    private fun smooth01(x: Float): Float {
        val v = x.coerceIn(0f, 1f); return v * v * (3f - 2f * v)
    }

    /** 查表：输入 0…1 映射到整张表，档内 smoothstep，首尾钳制。 */
    private fun table(tab: FloatArray, x: Float): Float {
        val u = x.coerceIn(0f, 1f) * (tab.size - 1)
        val i = u.toInt()
        if (i >= tab.size - 1) return tab[tab.size - 1]
        return tab[i] + (tab[i + 1] - tab[i]) * smooth01(u - i)
    }

    /** 查 [SETTLE] 表：0.1 s 一档，档内 smoothstep，首尾钳制。 */
    private fun settle(d: Float): Float {
        if (d <= 0f) return SETTLE[0]
        val x = d / 0.1f
        val i = x.toInt()
        if (i >= SETTLE.size - 1) return 0f
        return SETTLE[i] + (SETTLE[i + 1] - SETTLE[i]) * smooth01(x - i)
    }

    /** 确定性抖动：同一 (seed, 序号) 永远同一结果。 */
    private fun hash(seed: Int, n: Int): Float {
        var h = seed * 374761393 + n * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7FFFFFFF) / 2147483647f
    }

    /**
     * 曝光增益。**这是整套效果的核心**：真正的亮度乘法。
     *
     * Android 的 ColorMatrix 允许系数 > 1，卷积结果在写入 8 bit 图时按通道截断 ——
     * 于是亮部自然"烧"成纯色块，暗部保持细节，这正是过曝的样子。用半透明白纱
     * 永远做不出这个：白纱只会把暗部一起提亮成灰。
     */
    private fun setGain(p: Paint, g: Float, r: Float = 1f, gg: Float = 1f, bb: Float = 1f) {
        val cm = ColorMatrix(floatArrayOf(
            g * r, 0f, 0f, 0f, 0f,
            0f, g * gg, 0f, 0f, 0f,
            0f, 0f, g * bb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        p.colorFilter = ColorMatrixColorFilter(cm)
    }

    /** 等比装进 dst（不裁切），返回实际落位。照片与相纸比例不一致时靠它居中。 */
    private fun fitRect(photo: Bitmap, dst: RectF): RectF {
        val sw = photo.width.toFloat(); val sh = photo.height.toFloat()
        if (sw <= 0f || sh <= 0f) return dst
        val s = min(dst.width() / sw, dst.height() / sh)
        val dw = sw * s; val dh = sh * s
        val l = dst.centerX() - dw / 2f; val t = dst.centerY() - dh / 2f
        return RectF(l, t, l + dw, t + dh)
    }

    // ================================================================ 真模糊
    /**
     * r53 之前的虚焦是"把位图缩小 2^k 倍，再用双线性放大画回去"。
     *
     * 那些方块就是这么来的：**放大那一步是双线性插值**，一张 1/64 的图拉回满屏，
     * 每个源像素被摊成几十像素的方块，方块之间线性过渡 —— 视觉上就是马赛克上过了一层
     * 磨砂。而真实镜头离焦是卷积，每个点摊成一个圆。
     *
     * 而且老写法在两级之间做交叉淡化：先把糊的铺底，再按 (1−t) 盖半透明的清晰的。
     * 那等于同一张画**叠了两遍**，中间态是"糊影 + 清晰鬼影"，这就是重影。
     *
     * 现在改成真卷积：
     *  · 可分离的三次 box blur（高斯的标准快速近似，σ 由核半径反解，不是拍脑袋）；
     *  · 顺序是"先降到 1/k，再卷积，最后放大" —— 模糊必须发生在降采样**之后**，
     *    这样放大时看到的已经是一张被抹平的低频图，插值只负责平滑，露不出方块；
     *  · 同一个 σ 只算一次，整帧只画一次底图，
     *    每个像素一帧只被画一次，物理上就没有重影可言。
     */
    private val stage = arrayOfNulls<Bitmap>(3)      // 1/1、1/2、1/4
    private var stageSrc: Bitmap? = null
    private var stageW = 0
    private var stageH = 0

    private val blurOut = arrayOf(arrayOfNulls<Bitmap>(3), arrayOfNulls<Bitmap>(3))
    private var pxA: IntArray? = null
    private var pxB: IntArray? = null

    /**
     * 上一次算过的 σ（每个 slot 一份）。
     *
     * 整段动画里有三秒多 σ 是不变的，而 box 卷积是逐像素的三次往返 —— 每帧重算是纯浪费，
     * 预览掉帧有一半来自这里。σ 相同就直接返回上一帧那张位图。
     */
    private val lastSigma = floatArrayOf(-1f, -1f)
    private val lastK = intArrayOf(-1, -1)

    /** 把底图按目标尺寸缓存三级；换图或换尺寸才重建。 */
    private fun stageOf(photo: Bitmap, iw: Int, ih: Int): Array<Bitmap?> {
        if (stageSrc === photo && stageW == iw && stageH == ih) return stage
        stageSrc = null
        val base = runCatching { Bitmap.createScaledBitmap(photo, iw, ih, true) }.getOrNull() ?: return stage
        stage[0] = base
        stage[1] = runCatching {
            Bitmap.createScaledBitmap(base, max(4, iw / 2), max(4, ih / 2), true)
        }.getOrNull()
        stage[2] = runCatching {
            Bitmap.createScaledBitmap(base, max(4, iw / 4), max(4, ih / 4), true)
        }.getOrNull()
        stageSrc = photo
        stageW = iw
        stageH = ih
        lastSigma[0] = -1f; lastSigma[1] = -1f
        return stage
    }

    /**
     * 一维 box（横向）。滑动窗口和，O(1)/像素，边界 clamp。
     * 三次 box 串联的等效 σ = sqrt(3·((2r+1)²−1)/12) —— 反解就是 r ≈ 0.92σ。
     */
    private fun boxH(s: IntArray, d: IntArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val b0 = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val x = if (i < 0) 0 else if (i >= w) w - 1 else i
                val c = s[b0 + x]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                d[b0 + x] = (0xFF shl 24) or
                    (((sr * inv) + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    (((sg * inv) + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    ((sb * inv) + 0.5f).toInt().coerceIn(0, 255)
                val xo = (x - r).coerceIn(0, w - 1)
                val xi = (x + r + 1).coerceIn(0, w - 1)
                val co = s[b0 + xo]; val ci = s[b0 + xi]
                sr += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sg += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }
    }

    private fun boxV(s: IntArray, d: IntArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val y = if (i < 0) 0 else if (i >= h) h - 1 else i
                val c = s[y * w + x]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                d[y * w + x] = (0xFF shl 24) or
                    (((sr * inv) + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    (((sg * inv) + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    ((sb * inv) + 0.5f).toInt().coerceIn(0, 255)
                val yo = (y - r).coerceIn(0, h - 1)
                val yi = (y + r + 1).coerceIn(0, h - 1)
                val co = s[yo * w + x]; val ci = s[yi * w + x]
                sr += ((ci shr 16) and 0xFF) - ((co shr 16) and 0xFF)
                sg += ((ci shr 8) and 0xFF) - ((co shr 8) and 0xFF)
                sb += (ci and 0xFF) - (co and 0xFF)
            }
        }
    }

    /**
     * 返回一张按 σ（画面像素）真正虚焦过的位图。尺寸是 iw/k × ih/k，画到 dest 时放大。
     *
     * [slot] 用来隔离同一帧内不同 σ 的两次计算，否则后算的会覆盖先算的。
     * 返回的位图只在下一次调用前有效 —— 每帧重算，不跨帧持有。
     */
    private fun blurredAt(photo: Bitmap, iw: Int, ih: Int, sigma: Float, slot: Int = 0): Bitmap? {
        val st = stageOf(photo, iw, ih)
        if (iw < 8 || ih < 8) return stage[0]
        val s = sigma.coerceIn(0f, 40f)
        /**
         * 分三级，但**小 σ 不再走全分辨率**。
         *
         * r58 的写法是 σ ≤ 1.5 时在 900×900 的原图上做三对 box —— 那就是一帧 800k 像素
         * 六趟往返，单帧 20 ms 上下。对焦末段和"实焦静止"那半秒正好都落在这个区间，
         * 于是最该稳的地方反而掉帧。
         *
         * 现在的界线是 1.6 px：比它更小的模糊，在这么一块屏上肉眼本来就不存在
         * （屏边长 900 px 时 1.6 px 是 0.18%），直接把原图交出去；更大的模糊放到
         * 1/2 或 1/4 上算，放大时本来就是双线性，看不出差别。
         */
        val k = when {
            s <= 1.6f -> 0
            s <= 5.5f -> 1
            else -> 2
        }
        if (k == 0) return st[0]
        val lvl = st[k] ?: return stage[0]

        val slotIdx = slot.coerceIn(0, 1)
        val hit = blurOut[slotIdx][k]
        // 容差 0.12 px：σ 差这么点在放大绘制后不到四分之一个像素，肉眼没有，
        // 但能把对焦那一段的重算次数从"每帧"压到"每三帧"——那一段正好是最卡的。
        if (hit != null && !hit.isRecycled && lastK[slotIdx] == k &&
            s - lastSigma[slotIdx] in -0.12f..0.12f) return hit

        val w = lvl.width; val h = lvl.height
        val n = w * h
        var a = pxA; if (a == null || a.size < n) { a = IntArray(n); pxA = a }
        var b = pxB; if (b == null || b.size < n) { b = IntArray(n); pxB = b }
        lvl.getPixels(a, 0, w, 0, 0, w, h)

        val r = max(1, (s / (1 shl k) * 0.92f + 0.5f).toInt())
        repeat(3) {
            boxH(a, b, w, h, r)
            boxV(b, a, w, h, r)
        }

        val slotArr = blurOut[slotIdx]
        var out = slotArr[k]
        if (out == null || out.width != w || out.height != h || out.isRecycled) {
            out = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
                ?: return lvl
            slotArr[k] = out
        }
        out.setPixels(a, 0, w, 0, 0, w, h)
        lastSigma[slotIdx] = s
        lastK[slotIdx] = k
        return out
    }

    // ---------------------------------------------------------------- 高光溢出（bloom）
    /**
     * 只保留画面里最亮的那一小部分（阈值以上按平方衰减），降采样后做真模糊，
     * 最后用 **ADD** 叠回去。
     *
     * 老写法是把整幅照片的缩略图半透明盖在照片上 —— 暗部也被复制了一份，于是画面
     * 出现第二层轮廓，看着像对焦没对准。真正的过曝溢出只发生在亮部：暗的地方
     * 不发光，所以这里先做阈值，再模糊，再相加。
     */
    private var hiSrc: Bitmap? = null
    private var hiPx: IntArray? = null
    private var hiW = 0
    private var hiH = 0

    private fun highlightOf(photo: Bitmap): IntArray? {
        if (hiSrc === photo && hiPx != null) return hiPx
        val w = (photo.width / 10).coerceIn(20, 160)
        val h = (photo.height / 10).coerceIn(20, 160)
        val s = runCatching { Bitmap.createScaledBitmap(photo, w, h, true) }.getOrNull() ?: return null
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val v = max(r, max(g, b))
            val f = ((v - 158) / 97f).coerceIn(0f, 1f)
            val k = (f * f * 255f).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or ((r * k / 255) shl 16) or ((g * k / 255) shl 8) or (b * k / 255)
        }
        s.recycle()
        hiPx = px; hiW = w; hiH = h
        hiSrc = photo
        return px
    }

    private var bloomKey: Bitmap? = null
    private var bloomR = 0
    private var bloomBmp: Bitmap? = null

    private fun bloomBitmap(photo: Bitmap, radius: Int): Bitmap? {
        val cached = bloomBmp
        if (bloomKey === photo && bloomR == radius && cached != null && !cached.isRecycled) return cached
        val src = highlightOf(photo) ?: return null
        val w = hiW; val h = hiH
        val n = w * h
        val a = IntArray(n); src.copyInto(a)
        val b = IntArray(n)
        val r = radius.coerceIn(1, max(1, min(w, h) / 3))
        repeat(2) {
            boxH(a, b, w, h, r)
            boxV(b, a, w, h, r)
        }
        val out = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        out.setPixels(a, 0, w, 0, 0, w, h)
        bloomKey = photo
        bloomR = radius
        bloomBmp = out
        return out
    }

    // ================================================================ 腰平取景器（正俯视）
    /**
     * 正交俯视的几何。
     *
     * r56 用的是透视相机：屏幕被画成上窄下宽的梯形。屏幕上一变形，取景画面跟着被拉斜，
     * 一眼就假 —— 而且四壁靠透视撑起来的"盒子感"，在手机上只会读成"一张贴图加黑边"。
     *
     * 这一次视点垂直向下，屏幕就是**正方形**，像一点不变形。立体感不再交给透视，
     * 而是交给结构：黑压条 → 镀铬屏框 → 四片罩壁 → 罩口卷边 → 机身，一层压一层，
     * 每层都有自己的厚度、材质和投影。眼睛读到的是**深度**，不是梯形。
     */
    private class Vf(val w: Float, val h: Float, val m: Float) {
        // 屏边长。竖屏时由宽度定；方屏/横屏时再按高度收一下，免得罩口顶出画外
        val s = min(m * WLF_SCREEN, h * 0.62f)
        val cx = w * 0.5f
        val cy = h * 0.505f
        val lip = s * WLF_LIP                  // 黑压条
        val fw = s * WLF_FRAME                 // 镀铬屏框
        val wall = s * WLF_WALL                // 罩壁
        val rim = s * WLF_RIM                  // 罩口卷边
        val sl = cx - s * 0.5f
        val st = cy - s * 0.5f
        val sr = cx + s * 0.5f
        val sb = cy + s * 0.5f
        val fl = sl - lip                      // 压条外沿
        val ft = st - lip
        val fr = sr + lip
        val fb = sb + lip
        val gl = fl - fw                       // 屏框外沿
        val gt = ft - fw
        val gr = fr + fw
        val gb = fb + fw
        val hl = gl - wall                     // 罩壁外沿
        val ht = gt - wall
        val hr = gr + wall
        val hb = gb + wall
        val rl = hl - rim                      // 罩口
        val rt = ht - rim
        val rr = hr + rim
        val rb = hb + rim
    }

    // ---------------------------------------------------------------- 布景缓存
    /**
     * 除了"像"以外的一切，都只画一次。
     *
     * r56 每帧都在重画四片壁、四圈铬边、屏框和颗粒 —— 每帧几百个 Path / Shader 对象，
     * 这就是"卡顿"的来源。现在整块布景烘成一张位图，每帧只 blit 一次；
     * 屏上的磨砂层（暗角、热点、刻线、颗粒）另烘一张，跟屏同尺寸，1:1 贴上去。
     */
    private var vfGeo: Vf? = null
    private var vfBmp: Bitmap? = null
    private var vfGlassBmp: Bitmap? = null
    private var vfKeyW = -1
    private var vfKeyH = -1
    private var vfKeySeed = Int.MIN_VALUE
    private val vfPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private fun vfLayers(w: Int, h: Int, seed: Int): Vf? {
        val hit = vfGeo
        if (hit != null && vfKeyW == w && vfKeyH == h && vfKeySeed == seed &&
            vfBmp?.isRecycled == false && vfGlassBmp?.isRecycled == false
        ) return hit

        val geo = Vf(w.toFloat(), h.toFloat(), min(w, h).toFloat())
        val set = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        drawSet(Canvas(set), geo, seed)
        val side = geo.s.toInt().coerceAtLeast(16)
        val glass = runCatching { Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888) }.getOrNull()
        if (glass != null) drawGlass(Canvas(glass), side, geo)

        vfBmp?.let { if (!it.isRecycled) it.recycle() }
        vfGlassBmp?.let { if (!it.isRecycled) it.recycle() }
        vfGeo = geo; vfBmp = set; vfGlassBmp = glass
        vfKeyW = w; vfKeyH = h; vfKeySeed = seed
        return geo
    }

    // ---------------------------------------------------------------- 门洞
    /** 估计偏大时每条边再往里收这么多。**只准少看一线画面，不准漏一条片基进来。** */
    private const val GATE_INSET = 0.015f

    /**
     * 成片里"门洞"占整张图的位置（归一化 0..1）。没开片边返回 null。
     *
     * 开了片边的成片 = 片基围着门洞：135 一整条 35 mm 片宽里，门洞只有 24.3 mm，
     * 两边各留 5.35 mm 片基；顺片方向两头再留 0.9 mm（引擎每格在 0.75–0.95 之间抽）。
     * 那圈片基——齿孔、边码、"KODAK PORTRA 400"——只属于**最后那张成片**。
     * 取景器里低头看到的对焦屏是门洞里的像，从来没有片基，所以动画这一段必须先把它裁掉。
     *
     * 只认一个物理量：片宽。另一条方向的留白从**画幅比反解**（引擎每格的 m 都不同，
     * 猜不得）：片基横躺时 画布高 = 片宽，于是 画布宽 = 片宽 × 画幅比，门洞占的就是
     * along / 画布宽。最后每条边各内收 [GATE_INSET]：载体开外扩时片宽会多 0.4 mm，
     * 估计会偏大一点点，宁可少看一眼，也不能让一条片基漏进对焦屏。
     *
     * 半格组（一张片基上左右两格）是唯一门洞不在中间的情况：那时取**左边那一格**，
     * 否则对焦屏正中间正好是两格之间的片基。
     */
    fun gateFracFor(edgeLabel: String, w: Int, h: Int): RectF? {
        val fmt = osFormatOfShort(edgeLabel) ?: return null
        if (w <= 0 || h <= 0) return null
        val across = osGateAcrossMm(fmt)          // 横跨片基的那一边（mm）
        val along = osGateAlongMm(fmt)            // 顺片的那一边（mm）
        val film = osFilmMm(fmt)                  // 片宽（mm）
        val a = w.toFloat() / h.toFloat()         // 画布比 = 画幅比
        // 单格半格只会是 1.85（横构图）或 0.54（竖构图）；落在 1.0–1.32 的只有
        // 两格并排那一张（画布 37.9 × 35 mm）。osVertical 认的是单格，这里得自己判。
        val pair = fmt == "135_half" && a > 1.0f && a < 1.32f
        // 片基竖着走时，画布宽就是整条片宽；否则画布高是片宽
        val upright = !pair && osVertical(fmt, w >= h)
        val cw: Float
        val ch: Float
        if (upright) { cw = film; ch = film / a } else { ch = film; cw = film * a }
        val gw = if (upright) across / cw else along / cw
        val gh = if (upright) along / ch else across / ch
        val m = osAlongMm(fmt)
        val x0 = if (upright) (cw - across) * 0.5f / cw else m / cw
        val y0 = if (upright) m / ch else (ch - across) * 0.5f / ch
        val iw = (gw * (1f - 2f * GATE_INSET)).coerceIn(0.05f, 1f)
        val ih = (gh * (1f - 2f * GATE_INSET)).coerceIn(0.05f, 1f)
        val cx = x0 + gw * 0.5f
        val cy = y0 + gh * 0.5f
        return RectF(cx - iw * 0.5f, cy - ih * 0.5f, cx + iw * 0.5f, cy + ih * 0.5f)
    }

    /** 屏幕的像必须是正方形 —— 先裁方，再交给模糊。不裁就会把 4:3 的照片压扁。 */
    private var sqKey: Bitmap? = null
    private var sqBmp: Bitmap? = null
    private var sqSize = -1
    private var sqGate: RectF? = null

    /**
     * 对焦屏上的那一块像：**门洞里**的正方形。
     *
     * [gate] 不为空说明这张成片带片边，先按门洞把片基裁掉，再在门洞里取中心正方
     * —— 对焦屏上不该出现齿孔和边码。为空的（没开片边）就是整张图中心裁方。
     */
    private fun squareSrc(photo: Bitmap, size: Int, gate: RectF?): Bitmap? {
        if (sqKey === photo && sqSize == size && sqGate == gate && sqBmp?.isRecycled == false) return sqBmp
        var x: Int
        var y: Int
        var side: Int
        if (gate != null) {
            val gw = gate.width() * photo.width
            val gh = gate.height() * photo.height
            val s = min(gw, gh)
            x = (gate.left * photo.width + (gw - s) * 0.5f).toInt()
            y = (gate.top * photo.height + (gh - s) * 0.5f).toInt()
            side = s.toInt()
        } else {
            side = min(photo.width, photo.height)
            x = (photo.width - side) / 2
            y = (photo.height - side) / 2
        }
        side = side.coerceIn(16, min(photo.width, photo.height))
        x = x.coerceIn(0, photo.width - side)
        y = y.coerceIn(0, photo.height - side)
        val out = runCatching { Bitmap.createBitmap(photo, x, y, side, side) }.getOrNull() ?: return null
        sqKey = photo; sqBmp = out; sqSize = size; sqGate = gate
        return out
    }

    // ---------------------------------------------------------------- 布景
    private fun drawSet(c: Canvas, g: Vf, seed: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        c.drawColor(0xFF07070A.toInt())

        drawLens(c, g)
        drawBack(c, g, seed)

        // 罩子：先铺一块底，再用四片壁盖上去
        p.color = Color.rgb(9, 9, 10)
        c.drawRect(g.rl, g.rt, g.rr, g.rb, p)

        drawWall(c, RectF(g.hl, g.gt, g.gl, g.gb), g)      // 左
        drawWall(c, RectF(g.gr, g.gt, g.hr, g.gb), g)      // 右
        drawWall(c, RectF(g.gl, g.gb, g.gr, g.hb), g)      // 后（靠人）
        drawRearFlap(c, g)                                 // 后挡板 + 铬压条 + 两个铰链节
        drawFrontPanel(c, g)                               // 前（相机前方）
        drawRim(c, g)
        drawFrame(c, g)

        // 整体暗角：镜头是一支大光圈，四角本来就落光。顺带把视线压回屏幕
        p.shader = RadialGradient(g.cx, g.cy, g.s * 1.55f,
            intArrayOf(Color.TRANSPARENT, Color.argb(26, 0, 0, 0), Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, g.w, g.h, p)
        p.shader = null
    }

    /**
     * 一片罩壁。
     *
     * 光只有一个来源：屏。所以贴屏的一侧被照亮、往罩口一路黑下去 —— 这一层是"屏在发光"
     * 的全部证据。再叠一层荔枝纹（真实罩壁内侧的黑色皮革），不然它就是一块塑料板。
     */
    private fun drawWall(c: Canvas, r: RectF, g: Vf) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val cols = intArrayOf(Color.rgb(36, 32, 27), Color.rgb(21, 19, 17), Color.rgb(8, 8, 9))
        val pos = floatArrayOf(0f, 0.34f, 1f)
        p.shader = if (r.width() >= r.height()) {
            LinearGradient(0f, r.top, 0f, r.bottom, cols, pos, Shader.TileMode.CLAMP)
        } else {
            val near = if (r.centerX() < g.cx) r.right else r.left
            val far = if (r.centerX() < g.cx) r.left else r.right
            LinearGradient(near, 0f, far, 0f, cols, pos, Shader.TileMode.CLAMP)
        }
        c.drawRect(r, p)
        p.shader = null

        // 皮革纹：粗噪声铺一层，让壁有颗粒，不是渐变塑料
        val sh = BitmapShader(coarseNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val mm = Matrix()
        val k = g.s / 210f
        mm.setScale(k, k)
        sh.setLocalMatrix(mm)
        p.shader = sh
        p.alpha = 40
        c.drawRect(r, p)
        p.shader = null
        p.alpha = 255

        // 合缝：罩壁内侧是一层黑色皮革，靠罩口那边缝一道。虚线、极低对比 ——
        // 远看只是一条深一点的线，凑近才是一条缝。这类细节不跳，但少了就不像"做过的东西"。
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, g.s * 0.0018f)
            color = Color.argb(58, 128, 120, 106)
            pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(g.s * 0.0055f, g.s * 0.0085f), 0f)
        }
        if (r.width() >= r.height()) {
            val y = r.top + r.height() * 0.74f
            c.drawLine(r.left + r.width() * 0.02f, y, r.right - r.width() * 0.02f, y, sp)
        } else {
            val x = if (r.centerX() < g.cx) r.right - r.width() * 0.26f else r.left + r.width() * 0.26f
            c.drawLine(x, r.top + r.height() * 0.02f, x, r.bottom - r.height() * 0.02f, sp)
        }
        sp.pathEffect = null
    }

    /**
     * 后挡板 —— 腰平罩靠人那一侧翻起来的那片，加上**叠在上面放平的折叠放大镜**。
     *
     * 实拍 44 号图（罩子收起来、只留屏）里，屏下方就是这块：一块哑黑面板，
     * 一根横向镀铬压条，中间躺着一个黑框 —— 那是折叠放大镜收起来的样子。
     * 它同时解决了两件事：罩子读得出是四片板拼的；下面不再是"第四个黑边"。
     */
    private fun drawRearFlap(c: Canvas, g: Vf) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val r = RectF(g.gl, g.gb, g.gr, g.hb)

        // 哑黑板：它挡光，所以不吃屏的光，比四壁更黑更平
        p.color = Color.rgb(10, 10, 11)
        c.drawRect(r, p)

        // 上沿一道软高光：罩口外的光擦过这条边
        p.shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.55f,
            intArrayOf(Color.argb(50, 152, 148, 138), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = null

        // ---- 叠着的放大镜：一个镀铬的扁框，中间是深色玻璃 ----
        val lw = g.s * 0.300f          // 框宽
        val lh = r.height() * 0.46f    // 框高（放平之后就是一条，不是圆）
        val lx = g.cx - lw * 0.5f
        val ly = r.top + r.height() * 0.20f
        // 影：它是一块叠在板上的东西，边缘外面必有一圈暗
        p.color = Color.argb(100, 0, 0, 0)
        c.drawRect(lx - lh * 0.16f, ly - lh * 0.10f, lx + lw + lh * 0.16f, ly + lh + lh * 0.20f, p)
        chrome(c, RectF(lx, ly, lx + lw, ly + lh), p)
        // 暗边：金属框的最外沿总是先暗一线，再亮 —— 只有亮边就像贴纸
        run {
            val op = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, lh * 0.10f)
                color = Color.argb(190, 6, 6, 7)
            }
            c.drawRect(lx, ly, lx + lw, ly + lh, op)
        }
        // 玻璃：几乎全黑，只有上缘反一线天光
        p.color = Color.rgb(13, 14, 16)
        c.drawRect(lx + lh * 0.16f, ly + lh * 0.18f, lx + lw - lh * 0.16f, ly + lh * 0.84f, p)
        p.shader = LinearGradient(0f, ly + lh * 0.18f, 0f, ly + lh * 0.52f,
            intArrayOf(Color.argb(64, 176, 182, 194), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(lx + lh * 0.16f, ly + lh * 0.18f, lx + lw - lh * 0.16f, ly + lh * 0.52f, p)
        p.shader = null
        // 玻璃里一道斜反光：玻璃是斜着放的，反光永远是一条斜线
        run {
            val gl0 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, lh * 0.10f)
                color = Color.argb(40, 208, 214, 226)
            }
            c.drawLine(lx + lw * 0.20f, ly + lh * 0.78f, lx + lw * 0.44f, ly + lh * 0.26f, gl0)
        }

        // ---- 压条与铰链：放大镜下面那道横条，是后片翻板的转轴 ----
        val barY = r.top + r.height() * 0.72f
        chrome(c, RectF(r.left, barY, r.right, barY + r.height() * 0.14f), p)
        val kn = r.height() * 0.26f
        for (i in 0 until 2) {
            val kx = r.left + r.width() * (0.13f + i * 0.74f)
            chrome(c, RectF(kx - kn * 0.5f, barY - r.height() * 0.05f,
                kx + kn * 0.5f, barY + r.height() * 0.19f), p)
            p.color = Color.argb(235, 5, 5, 6)
            c.drawCircle(kx, barY + r.height() * 0.07f, max(1f, kn * 0.13f), p)
        }

        // 压条投在下面的窄影：板立着、压条压着，一定有这条
        p.shader = LinearGradient(0f, barY + r.height() * 0.14f, 0f, r.bottom,
            intArrayOf(Color.argb(190, 3, 3, 4), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r.left, barY + r.height() * 0.14f, r.right, r.bottom, p)
        p.shader = null
    }

    /**
     * 相机前方那块宽的镀铬前框。
     *
     * 实拍里它是最亮的一块：金属反射出好几条亮带，中间压着一道凹槽。
     * 画面顶部要是没有它，整台机器就只剩一块黑。
     */
    private fun drawFrontPanel(c: Canvas, g: Vf) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val r = RectF(g.rl, g.rt, g.rr, g.gt)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            intArrayOf(
                Color.rgb(9, 9, 8),
                Color.rgb(46, 43, 39),
                Color.rgb(118, 113, 103),
                Color.rgb(34, 32, 28),
                Color.rgb(88, 84, 77),
                Color.rgb(168, 162, 150),
                Color.rgb(52, 49, 44),
            ),
            floatArrayOf(0f, 0.11f, 0.27f, 0.43f, 0.68f, 0.87f, 1f),
            Shader.TileMode.CLAMP)
        c.drawRect(r, p)
        p.shader = null

        // 凹槽
        val g0 = r.top + r.height() * 0.40f
        p.color = Color.argb(150, 6, 6, 7)
        c.drawRect(r.left, g0, r.right, g0 + r.height() * 0.035f, p)
        p.color = Color.argb(90, 224, 218, 204)
        c.drawRect(r.left, g0 + r.height() * 0.035f, r.right, g0 + r.height() * 0.058f, p)

        // 下沿落在屏框上的影
        p.shader = LinearGradient(0f, r.bottom - r.height() * 0.22f, 0f, r.bottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(215, 2, 2, 3)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.bottom - r.height() * 0.22f, r.right, r.bottom, p)
        p.shader = null
    }

    /** 罩口的镀铬卷边：金属的反射是几条亮带，不是一圈渐变。 */
    private fun drawRim(c: Canvas, g: Vf) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        // 左右两片翻板
        chrome(c, RectF(g.rl, g.gt, g.hl, g.rb), p)
        chrome(c, RectF(g.hr, g.gt, g.rr, g.rb), p)
        // 后片（靠人那一侧）
        chrome(c, RectF(g.rl, g.hb, g.rr, g.rb), p)

        // 翻板之间的接缝：真机上是四片各自独立的板，接缝不画，四壁就粘成一体
        val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        seam.strokeWidth = max(1f, g.s * 0.0026f)
        seam.color = Color.argb(190, 3, 3, 4)
        c.drawLine(g.rl, g.gt, g.hl, g.gt, seam)
        c.drawLine(g.hr, g.gt, g.rr, g.gt, seam)
        c.drawLine(g.rl, g.hb, g.rr, g.hb, seam)
        c.drawLine(g.rl, g.rt, g.rl, g.rb, seam)
        c.drawLine(g.rr, g.rt, g.rr, g.rb, seam)
    }

    private fun chrome(c: Canvas, r: RectF, p: Paint) {
        val cols = intArrayOf(
            Color.rgb(26, 24, 22), Color.rgb(104, 99, 90), Color.rgb(52, 49, 45),
            Color.rgb(186, 180, 168), Color.rgb(84, 80, 74), Color.rgb(20, 19, 18),
        )
        val pos = floatArrayOf(0f, 0.13f, 0.40f, 0.63f, 0.86f, 1f)
        p.shader = if (r.width() >= r.height()) {
            LinearGradient(0f, r.top, 0f, r.bottom, cols, pos, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(r.left, 0f, r.right, 0f, cols, pos, Shader.TileMode.CLAMP)
        }
        c.drawRect(r, p)
        p.shader = null
    }

    /**
     * 屏框：黑压条 + 镀铬框。
     *
     * 真机的层次是 玻璃 → 压条（黑） → 铬框（亮） → 罩壁。少一层就变成"贴了张图"。
     */
    private fun drawFrame(c: Canvas, g: Vf) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        // 黑压条：压着玻璃的那一圈
        p.color = Color.rgb(7, 7, 8)
        c.drawRect(g.fl, g.ft, g.fr, g.fb, p)

        // 铬框：斜向渐变 —— 左上受光、右下落在影里，金属才有厚度
        val ring = Path().apply {
            addRect(g.gl, g.gt, g.gr, g.gb, Path.Direction.CW)
            addRect(g.fl, g.ft, g.fr, g.fb, Path.Direction.CCW)
            fillType = Path.FillType.EVEN_ODD
        }
        p.shader = LinearGradient(g.gl, g.gt, g.gr, g.gb,
            intArrayOf(
                Color.rgb(206, 200, 188), Color.rgb(128, 123, 114),
                Color.rgb(60, 57, 53), Color.rgb(158, 152, 141), Color.rgb(78, 75, 70),
            ),
            floatArrayOf(0f, 0.24f, 0.56f, 0.83f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(ring, p)
        p.shader = null

        // 外沿高光、内沿阴影：两条线就把"厚度"讲完
        val l = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        l.strokeWidth = max(1f, g.s * 0.0022f)
        l.color = Color.argb(120, 226, 220, 206)
        c.drawRect(g.gl + l.strokeWidth * 0.5f, g.gt + l.strokeWidth * 0.5f,
            g.gr - l.strokeWidth * 0.5f, g.gb - l.strokeWidth * 0.5f, l)
        l.strokeWidth = max(1f, g.s * 0.0030f)
        l.color = Color.argb(200, 6, 6, 7)
        c.drawRect(g.fl, g.ft, g.fr, g.fb, l)

        // 台阶：真机这圈框是铣出来的，不是一块平板 —— 外沿往里约四成有一道台阶。
        // 受光的两条边亮、背光的两条边暗，这道台阶一出现，"一圈金色"就变成了"一个零件"。
        run {
            val st = g.fw * 0.40f
            val x0 = g.gl + st
            val y0 = g.gt + st
            val x1 = g.gr - st
            val y1 = g.gb - st
            l.strokeWidth = max(1f, g.s * 0.0016f)
            l.color = Color.argb(110, 236, 230, 216)
            c.drawLine(x0, y0, x1, y0, l)
            c.drawLine(x0, y0, x0, y1, l)
            l.color = Color.argb(150, 8, 8, 9)
            c.drawLine(x0, y1, x1, y1, l)
            c.drawLine(x1, y0, x1, y1, l)
        }

        // 屏床：反光镜抬起时看到的就是这块黑
        p.shader = null
        p.color = Color.rgb(10, 10, 11)
        c.drawRect(g.sl, g.st, g.sr, g.sb, p)

    }

    /**
     * 机身前方的镜头：从正上方只看到镜筒的顶面。
     *
     * 一根圆柱的顶面是一条亮带（中间受光、两侧转暗），靠近机身的一端依次是
     * 光圈环、对焦环和镀铬卡口。镜筒一直伸出画面 —— 真实俯拍就是这样裁掉的，
     * 给它画一个"封口"反而像一根截断的管子。
     */
    private fun drawLens(c: Canvas, g: Vf) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val half = g.s * 0.240f
        val l = g.cx - half
        val r = g.cx + half
        val bottom = g.rt + g.s * 0.015f

        // 镜筒顶面：圆柱的高光带
        p.shader = LinearGradient(l, 0f, r, 0f,
            intArrayOf(
                Color.rgb(4, 4, 5), Color.rgb(13, 13, 14), Color.rgb(30, 30, 31),
                Color.rgb(18, 18, 19), Color.rgb(10, 10, 11), Color.rgb(3, 3, 4),
            ),
            floatArrayOf(0f, 0.12f, 0.38f, 0.52f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(l, -g.s * 0.35f, r, bottom, p)
        p.shader = null

        // 对焦环：宽的一圈细齿。整支镜头上唯一"抓得住"的东西，没有它就是一根黑管
        val fy = bottom - g.s * 0.360f
        val fh = g.s * 0.255f
        p.shader = LinearGradient(l, fy, r, fy,
            intArrayOf(Color.rgb(8, 8, 9), Color.rgb(30, 29, 29), Color.rgb(14, 14, 15),
                Color.rgb(26, 25, 25), Color.rgb(7, 7, 8)),
            floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(l, fy, r, fy + fh, p)
        p.shader = null
        val rib = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        rib.strokeWidth = max(1f, g.s * 0.0035f)
        rib.color = Color.argb(190, 8, 8, 9)
        var x = l
        while (x < r) {
            c.drawLine(x, fy + g.s * 0.010f, x, fy + fh - g.s * 0.010f, rib)
            x += g.s * 0.011f
        }

        // 光圈环：一圈刻度
        val ay = bottom - g.s * 0.185f
        val ah = g.s * 0.070f
        p.shader = LinearGradient(l, 0f, r, 0f,
            intArrayOf(Color.rgb(8, 8, 9), Color.rgb(56, 52, 46), Color.rgb(19, 18, 17),
                Color.rgb(42, 39, 35), Color.rgb(7, 7, 8)),
            floatArrayOf(0f, 0.14f, 0.42f, 0.74f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(l, ay, r, ay + ah, p)
        p.shader = null
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        tick.strokeWidth = max(1f, g.s * 0.0022f)
        tick.color = Color.argb(150, 146, 142, 132)
        var tx = l + g.s * 0.014f
        while (tx < r - g.s * 0.010f) {
            c.drawLine(tx, ay + ah * 0.66f, tx, ay + ah * 0.92f, tick)
            tx += g.s * 0.026f
        }

        // 卡口
        chrome(c, RectF(l - g.s * 0.010f, bottom - g.s * 0.085f, r + g.s * 0.010f, bottom - g.s * 0.030f), p)

        // 红色索引点
        p.color = Color.argb(210, 168, 36, 28)
        c.drawCircle(l + (r - l) * 0.30f, bottom - g.s * 0.115f, max(1.6f, g.s * 0.006f), p)

        // 机身前沿的镀铬线：把"镜头"和"机身"分开，上面那块黑才有解释
        p.shader = LinearGradient(0f, g.rt - g.s * 0.020f, 0f, g.rt - g.s * 0.004f,
            intArrayOf(Color.rgb(20, 19, 18), Color.rgb(78, 74, 67),
                Color.rgb(34, 32, 29), Color.rgb(14, 13, 12)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, g.rt - g.s * 0.020f, g.w, g.rt - g.s * 0.004f, p)
        p.shader = null
    }

    /**
     * 机身后方：胶片后背的顶面。
     *
     * 一片黑荔枝皮 + 顶缝的镀铬压边 + 胶片提示牌 + ISO 盘 + 暗袋插片的拉手 +
     * 卷片旋钮。这些不是装饰：少一样，这台机器就只是"一块黑方块"。
     */
    private fun drawBack(c: Canvas, g: Vf, seed: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val top = g.rb - g.s * 0.012f

        p.color = Color.rgb(15, 14, 14)
        c.drawRect(0f, top, g.w, g.h, p)

        // 荔枝皮
        val sh = BitmapShader(coarseNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val mm = Matrix()
        val k = g.s / 230f
        mm.setScale(k, k)
        sh.setLocalMatrix(mm)
        p.shader = sh
        p.alpha = 62
        c.drawRect(0f, top, g.w, g.h, p)
        p.shader = null
        p.alpha = 255

        // 一片柔光：皮面不是平的
        p.shader = RadialGradient(g.cx, top + g.s * 0.30f, g.s * 0.95f,
            intArrayOf(Color.argb(38, 92, 86, 76), Color.argb(10, 60, 56, 50), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, g.w, g.h, p)
        p.shader = null

        // 后背与机身的接缝：一条镀铬压边
        chrome(c, RectF(0f, top, g.w, top + g.s * 0.017f), p)

        // 胶片提示牌：铬框、内凹、两条刻线
        val pw = g.s * 0.245f
        val ph = g.s * 0.072f
        val px = g.cx - g.s * 0.355f
        val py = top + g.s * 0.105f
        chrome(c, RectF(px, py, px + pw, py + ph), p)
        p.color = Color.argb(190, 9, 9, 10)
        c.drawRect(px + ph * 0.20f, py + ph * 0.22f, px + pw - ph * 0.20f, py + ph * 0.78f, p)
        p.color = Color.argb(90, 150, 144, 132)
        c.drawRect(px + ph * 0.30f, py + ph * 0.40f, px + pw - ph * 0.30f, py + ph * 0.455f, p)
        c.drawRect(px + ph * 0.30f, py + ph * 0.56f, px + pw - ph * 0.30f, py + ph * 0.615f, p)

        /**
         * 计数窗。后背顶上那个"12"—— 503 上最容易一眼认出来的东西。
         * 铬底板 + 内凹的黑窗 + 一块浅色度盘 + 黑数字。这块牌子在实拍 44 号图里
         * 就在屏的正下方，所以这里必须放在中轴线上。
         */
        run {
            val cw = g.s * 0.135f
            val ch = g.s * 0.098f
            val x0 = g.cx - cw * 0.5f
            val y0 = top + g.s * 0.128f
            val plate = Path().apply {
                addRoundRect(RectF(x0, y0, x0 + cw, y0 + ch), ch * 0.30f, ch * 0.30f, Path.Direction.CW)
            }
            p.shader = LinearGradient(x0, y0, x0 + cw, y0 + ch,
                intArrayOf(Color.rgb(196, 190, 178), Color.rgb(120, 115, 106),
                    Color.rgb(58, 55, 50), Color.rgb(152, 146, 135)),
                floatArrayOf(0f, 0.34f, 0.72f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(plate, p)
            p.shader = null

            // 窗：内凹的圆角方孔
            val iw = cw * 0.52f
            val ih = ch * 0.50f
            val win = Path().apply {
                addRoundRect(RectF(g.cx - iw * 0.5f, y0 + ch * 0.26f, g.cx + iw * 0.5f, y0 + ch * 0.26f + ih),
                    ih * 0.34f, ih * 0.34f, Path.Direction.CW)
            }
            p.color = Color.rgb(9, 9, 10)
            c.drawPath(win, p)
            p.color = Color.rgb(176, 172, 162)
            c.drawCircle(g.cx, y0 + ch * 0.26f + ih * 0.5f, ih * 0.38f, p)

            // 数字
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(225, 24, 23, 22)
                textSize = g.s * 0.050f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            }
            c.drawText("12", g.cx, y0 + ch * 0.26f + ih * 0.5f + t.textSize * 0.36f, t)
        }

        // ISO 盘：铬环 + 内凹 + 上方一道高光
        run {
            val ix = g.cx + g.s * 0.185f
            val iy = top + g.s * 0.152f
            val ir = g.s * 0.048f
            p.color = Color.rgb(58, 55, 50)
            c.drawCircle(ix, iy, ir, p)
            p.color = Color.rgb(19, 18, 18)
            c.drawCircle(ix, iy, ir * 0.86f, p)
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, g.s * 0.0026f)
                color = Color.argb(150, 172, 166, 154)
            }
            c.drawArc(ix - ir, iy - ir, ix + ir, iy + ir, 200f, 140f, false, arc)
        }

        // 暗袋插片的拉手：左边一条细铬条，端头一个孔
        val dx = g.cx - g.s * 0.36f
        chrome(c, RectF(dx, top + g.s * 0.10f, dx + g.s * 0.020f, top + g.s * 0.30f), p)
        p.color = Color.argb(210, 6, 6, 7)
        c.drawCircle(dx + g.s * 0.010f, top + g.s * 0.255f, g.s * 0.009f, p)

        // 卷片旋钮：滚花圆盘 + 铬芯 + 红点
        run {
            val kx = g.w - g.s * 0.115f
            val ky = top + g.s * 0.245f
            val kr = g.s * 0.105f
            p.shader = RadialGradient(kx - kr * 0.3f, ky - kr * 0.3f, kr * 1.4f,
                intArrayOf(Color.rgb(26, 25, 25), Color.rgb(12, 12, 13), Color.rgb(6, 6, 7)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(kx, ky, kr, p)
            p.shader = null
            val kp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            kp.strokeWidth = max(1.2f, g.s * 0.0038f)
            kp.color = Color.argb(160, 54, 52, 50)
            var i = 0
            while (i < 48) {
                val a = (i / 48f) * 6.2831855f
                c.drawLine(
                    kx + (kr * 0.84f) * kotlin.math.cos(a), ky + (kr * 0.84f) * kotlin.math.sin(a),
                    kx + kr * kotlin.math.cos(a), ky + kr * kotlin.math.sin(a), kp,
                )
                i++
            }
            p.color = Color.rgb(26, 25, 25)
            c.drawCircle(kx, ky, kr * 0.42f, p)
            p.color = Color.rgb(70, 67, 62)
            c.drawCircle(kx, ky, kr * 0.20f, p)

            // 摇柄：折出来的那根铬条 + 端头的胶木握把。旋钮上什么都不长的时候，
            // 它读起来只是一个"圆"；有了这根柄，它才是"卷片的"。
            c.save()
            c.rotate(21f, kx, ky)
            chrome(c, RectF(kx - kr * 0.105f, ky - kr * 0.80f, kx + kr * 0.105f, ky + kr * 0.06f), p)
            c.restore()
            p.color = Color.rgb(40, 38, 36)
            c.drawCircle(kx + kr * 0.028f, ky - kr * 0.76f, kr * 0.175f, p)
            p.color = Color.argb(120, 120, 114, 104)
            c.drawCircle(kx + kr * 0.028f - kr * 0.03f, ky - kr * 0.76f - kr * 0.04f, kr * 0.075f, p)
        }

        // 背带环：左侧一个镀铬的小方环
        val sv = g.s * 0.072f
        chrome(c, RectF(g.s * 0.050f, top + g.s * 0.34f, g.s * 0.050f + sv * 0.42f, top + g.s * 0.34f + sv), p)
        p.color = Color.argb(210, 8, 8, 9)
        c.drawRect(g.s * 0.050f + sv * 0.12f, top + g.s * 0.34f + sv * 0.22f,
            g.s * 0.050f + sv * 0.30f, top + g.s * 0.34f + sv * 0.78f, p)

        // 底部渐暗：机身往画面外退，光就断了
        p.shader = LinearGradient(0f, top + g.s * 0.42f, 0f, g.h,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 0, 0, 0), Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, top, g.w, g.h, p)
        p.shader = null
    }

    /**
     * 屏上的磨砂层：暗角、热点、刻线、颗粒。
     *
     * 这一层和"像"是分开烘的，因为像每帧都在变，这层一秒都不变。
     * 磨砂玻璃的质感就三件事：**四边落影**（屏沉在框里）、**中心热点**（聚光镜）、
     * **一层砂**（不是一层灰）。r56 的过渡不自然，就是因为这几样每帧重画、还叠了两次。
     */
    private fun drawGlass(c: Canvas, side: Int, g: Vf) {
        val s = side.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        // 四边落影
        val e = s * 0.085f
        p.shader = LinearGradient(0f, 0f, 0f, e,
            intArrayOf(Color.argb(215, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, e, p)
        p.shader = LinearGradient(0f, s, 0f, s - e,
            intArrayOf(Color.argb(165, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, s - e, s, s, p)
        p.shader = LinearGradient(0f, 0f, e, 0f,
            intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, e, s, p)
        p.shader = LinearGradient(s, 0f, s - e, 0f,
            intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(s - e, 0f, s, s, p)
        p.shader = null

        // 四角压暗（暗角是圆的，不是盒子）
        p.shader = RadialGradient(s * 0.5f, s * 0.5f, s * 0.74f,
            intArrayOf(Color.TRANSPARENT, Color.argb(16, 0, 0, 0), Color.argb(84, 0, 0, 0)),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        p.shader = null

        // 聚光镜的热点：中心抬亮
        p.shader = RadialGradient(s * 0.5f, s * 0.42f, s * 0.62f,
            intArrayOf(Color.argb(34, 255, 246, 226), Color.argb(10, 255, 244, 224), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        p.shader = null

        // 砂：粗颗粒管"密度"，细颗粒管"颗粒感"，都是静止的
        run {
            val sh = BitmapShader(coarseNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val mm = Matrix()
            mm.setScale(s / 150f, s / 150f)
            sh.setLocalMatrix(mm)
            p.shader = sh; p.alpha = 16
            c.drawRect(0f, 0f, s, s, p)
            val sh2 = BitmapShader(fineNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            p.shader = sh2; p.alpha = 13
            c.drawRect(0f, 0f, s, s, p)
            p.shader = null; p.alpha = 255
        }

        // 刻线：十字贯穿 + 中心一个同心圆（Acute-Matte 的样子，很淡）
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        lp.strokeWidth = max(1f, s * 0.0022f)
        lp.color = Color.argb(30, 255, 253, 246)
        c.drawLine(0f, s * 0.5f, s, s * 0.5f, lp)
        c.drawLine(s * 0.5f, 0f, s * 0.5f, s, lp)
        lp.strokeWidth = max(1f, s * 0.0026f)
        lp.color = Color.argb(24, 255, 253, 246)
        c.drawCircle(s * 0.5f, s * 0.5f, s * 0.075f, lp)

        // 灰点：磨砂面上永远有几粒
        run {
            val dp = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until 5) {
                val dx = s * (0.10f + hash(0x51F, i * 7 + 1) * 0.80f)
                val dy = s * (0.10f + hash(0x51F, i * 7 + 2) * 0.80f)
                val dr = s * (0.0018f + hash(0x51F, i * 7 + 3) * 0.0026f)
                dp.color = Color.argb(70, 0, 0, 0)
                c.drawCircle(dx, dy, dr, dp)
            }
        }

        // 两根细毛。腰平是敞着的，屏上永远落着灰和毛 —— 真实感有一半来自这种"没人擦"。
        // 只用两条极淡的曲线：画重了就成划痕，画多了就成脏。
        run {
            val hp2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, s * 0.0015f)
                color = Color.argb(56, 14, 14, 14)
            }
            val a = Path().apply {
                moveTo(s * 0.615f, s * 0.275f)
                cubicTo(s * 0.700f, s * 0.330f, s * 0.655f, s * 0.420f, s * 0.780f, s * 0.462f)
            }
            c.drawPath(a, hp2)
            val b = Path().apply {
                moveTo(s * 0.205f, s * 0.655f)
                cubicTo(s * 0.262f, s * 0.700f, s * 0.240f, s * 0.760f, s * 0.312f, s * 0.792f)
            }
            c.drawPath(b, hp2)
        }

        // 内沿的一线光：玻璃贴着压条，边缘一定有一道反光
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        hp.strokeWidth = max(1.2f, s * 0.004f)
        hp.color = Color.argb(30, 255, 240, 214)
        c.drawRect(hp.strokeWidth * 0.5f, hp.strokeWidth * 0.5f,
            s - hp.strokeWidth * 0.5f, s - hp.strokeWidth * 0.5f, hp)
    }

    // ---------------------------------------------------------------- 入口
    /**
     * @param gate 成片里门洞的位置（[gateFracFor] 算出来的，归一化）。带片边的成片用它
     *   把片基从取景器里裁掉；null = 这张没开片边，整张图就是画面。
     */
    fun draw(
        c: Canvas, w: Int, h: Int, style: Int, time: Float, photo: Bitmap,
        seed: Int = 0, gate: RectF? = null,
    ) {
        if (w <= 0 || h <= 0 || photo.isRecycled) return
        val t = ((time % LOOP) + LOOP) % LOOP
        if (style == PRINT) drawPrint(c, w.toFloat(), h.toFloat(), t, photo, seed)
        else drawViewfinder(c, w.toFloat(), h.toFloat(), t, photo, seed, gate)
    }

    /**
     * 动态照片的封面：**成片本身**，不是取景器。
     *
     * 相册里第一眼看到的是那张照片（开了片边就带片边），点开才动；动完最后停在同一张上。
     * 封面若还用取景器那一帧，就成了"封面一个样子、结尾另一个样子"，谁看都觉得错。
     */
    fun drawStill(c: Canvas, w: Int, h: Int, photo: Bitmap) {
        if (w <= 0 || h <= 0 || photo.isRecycled) return
        c.drawColor(Color.BLACK)
        drawPhoto(c, w.toFloat(), h.toFloat(), photo, 1f)
    }

    // ================================================================ 取景器（腰平）
    /**
     * 哈苏 503CW 的腰平取景器，正俯视。
     *
     * 一眼看上去要成立，只有两件事：**像不变形**、**结构有厚度**。
     *  · 视点垂直向下 → 屏幕是正方形，取景画面不会被拉成梯形。
     *  · 黑压条 / 铬框 / 四片壁 / 卷边 / 机身，各自有自己的宽度、材质和投影 → 读到深度。
     *
     * 时间轴：前四拍是参考片逐帧实测的，第五拍是 r58 加的"出片"（常数在文件顶部）：
     *
     *  0.00–1.05  对焦拉近。σ 从 ≈10 px 收到 0，画面位置一动不动。
     *  1.05–1.57  实焦静止。
     *  1.57–1.73  按下快门：反光镜拍上去 —— 屏黑了，整块取景器跟着跳一下。
     *  1.73–2.33  反光镜回位：屏重新亮起、先亮过头再落回常曝光。
     *  2.33–3.00  定格。所有参数停在终值，一动不动 —— 这是"取景器里的最终画面"。
     *  3.00–3.13  沉进黑场。
     *  3.13–3.26  成片从黑里升起来。
     *  3.26–5.60  成片。与原图 1:1，一个像素不裁。
     */
    private fun drawViewfinder(
        c: Canvas, w: Float, h: Float, t: Float, photo: Bitmap, seed: Int, gate: RectF?,
    ) {
        // ---- 出片之后：整幅就是那张成片，一秒都不动 ----
        // 先判这一支：省掉整套布景，也让最后一帧与相册里那张照片**逐像素同源**。
        if (t >= REVEAL_END) {
            drawPhoto(c, w, h, photo, 1f)
            return
        }

        val m = min(w, h)
        val g = vfLayers(w.toInt(), h.toInt(), seed) ?: return
        val set = vfBmp ?: return

        // ---- 时间轴 ----
        var blur = 0f; var gain = 1f; var veil = 0f; var flat = 0f
        var lit = 1f          // 屏的通光：反光镜一抬起来就是 0

        when {
            t < FOCUS_END -> { blur = table(FOCUS, t / FOCUS_END); flat = 1f }
            t < CUT -> flat = 1f
            t < CUT + 0.033f -> {
                val u = smooth01((t - CUT) / 0.033f)
                blur = u; lit = 1f - u; gain = 1f - 0.214f * u; flat = 1f
            }
            t < DARK_END -> { blur = 1f; lit = 0f; flat = 1f }
            t < PEAK -> {
                val u = smooth01((t - DARK_END) / (PEAK - DARK_END))
                veil = u; blur = 1f; lit = u
                gain = 0.786f + 0.764f * u
                flat = 1f
            }
            t < SETTLED -> {
                val u = (t - PEAK) / (SETTLED - PEAK)
                veil = table(VEIL, u)
                blur = 0.90f * (1f - u).pow(1.5f)
                // 亮起来那一下只是"刺眼"，不是过曝：增益从 1.55 平滑落回 1.0
                gain = 1f + 0.55f * veil
                flat = veil
            }
            // 落定之后一个参数都不再变 —— 这就是"定格"。再往后只有黑场和出片。
            else -> { }
        }

        // ---- 反光镜的冲量：整块取景器跟着跳 ----
        // 503 的反光镜是这块机身的招牌，打上去手都发麻。画面不跳，就还是"动画"。
        //
        // r59：频率必须压在帧率的 Nyquist 之下。r58 用的是 17 Hz，而导出是 24 fps ——
        // 每帧只有 0.7 个周期，采样出来的不是振动，是**随机跳点**，看起来就是"卡"。
        // 现在导出 30 fps、频率降到 7 Hz 附近，每周期 4 帧多，眼睛读到的是
        // "顿一下再回弹"，这才是反光镜拍上去的样子。6 ms 的起振也不能省：
        // 从 0 慢慢摆起来是"抖"，瞬间到位再衰减才是"撞"。
        var jx = 0f; var jy = 0f
        if (t < HOLD_END) {
            val d1 = t - CUT
            if (d1 >= 0f && d1 < 0.30f) {
                val a = 0.010f * m * (1f - exp(-d1 / 0.006f)) * exp(-d1 / 0.055f)
                jy += a * sin(d1 * 6.2831855f * 7.2f)
                jx += a * 0.35f * sin(d1 * 6.2831855f * 5.3f + 1.1f)
            }
            val d2 = t - DARK_END
            if (d2 >= 0f && d2 < 0.34f) {
                val a = 0.0080f * m * (1f - exp(-d2 / 0.007f)) * exp(-d2 / 0.060f)
                jy += a * sin(d2 * 6.2831855f * 6.4f + 0.6f)
                jx -= a * 0.30f * sin(d2 * 6.2831855f * 4.8f)
            }
        }

        c.save()
        c.translate(jx, jy)
        drawFinder(c, g, set, photo, blur, gain, veil, flat, lit, gate)
        c.restore()

        // ---- 定格之后：走黑场 → 出片 ----
        if (t >= HOLD_END) {
            if (t < BLACK_END) {
                val u = smooth01((t - HOLD_END) / (BLACK_END - HOLD_END))
                c.drawColor(Color.argb((255f * u).toInt().coerceIn(0, 255), 0, 0, 0))
            } else {
                c.drawColor(Color.BLACK)
                val u = smooth01((t - BLACK_END) / (REVEAL_END - BLACK_END))
                drawPhoto(c, w, h, photo, u)
            }
        }
    }

    /**
     * 腰平取景器这一整个"看进去"的画面：布景 + 像 + 磨砂层 + 放大镜。
     *
     * 抽出来是因为它要被调两次：正常播放时一次、定格那一秒一次（参数已到终值）。
     */
    private fun drawFinder(
        c: Canvas, g: Vf, set: Bitmap, photo: Bitmap,
        blur: Float, gain: Float, veil: Float, flat: Float, lit: Float,
        gate: RectF?,
    ) {
        // ---- 布景：一整张位图，一次 blit ----
        c.drawBitmap(set, 0f, 0f, null)

        // ---- 像：正方形，1:1，不做任何几何变换（只左右镜像）----
        if (lit > 0.004f) {
            val dst = RectF(g.sl, g.st, g.sr, g.sb)
            val dim = g.s.toInt().coerceAtLeast(16)
            val sq = squareSrc(photo, dim, gate)
            val sigma = blur * SIGMA_MAX * g.s + 0.85f
            val base = if (sq != null) blurredAt(sq, dim, dim, sigma, 0) else null

            val bp = vfPaint
            bp.isFilterBitmap = true
            bp.alpha = (255f * lit).toInt().coerceIn(0, 255)
            bp.colorFilter = filterFor(gain, flat, veil)
            c.save()
            c.scale(-1f, 1f, g.cx, 0f)          // 反光镜把像左右翻过来，这是"看进去"的分界
            if (base != null) c.drawBitmap(base, null, dst, bp)
            else c.drawBitmap(photo, null, dst, bp)
            c.restore()
            bp.colorFilter = null

            // ---- 磨砂层：同一张位图 1:1 盖上去 ----
            val glass = vfGlassBmp
            if (glass != null) c.drawBitmap(glass, null, dst, bp)
            bp.alpha = 255
        }
    }

    /**
     * 出片：导出的那张图，原样铺满画布。
     *
     * 不裁、不缩放变形、不叠任何滤镜 —— 画布比例就是这张图的比例（导出时按它定的），
     * 所以这一帧与相册里那张照片是**同一个画面**。开了片边就带片边，片码、齿孔都在，
     * 一个像素都不少。动态照片的封面用的也是它。
     */
    private fun drawPhoto(c: Canvas, w: Float, h: Float, photo: Bitmap, alpha: Float) {
        if (photo.isRecycled) return
        val p = vfPaint
        p.isFilterBitmap = true
        p.colorFilter = null
        p.alpha = (255f * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        c.drawBitmap(fitted(photo, w.toInt(), h.toInt()), null, RectF(0f, 0f, w, h), p)
        p.alpha = 255
    }

    /**
     * 按画布尺寸预缩一张。
     *
     * 为什么不在 drawBitmap 里直接给目标矩形：源图 2560 px、画布 1080 px，每帧都让
     * 采样器现场缩 2.4 倍。**没有 mipmap 的双线性缩小是采样的**——相邻帧的采样点偏移
     * 一点点，细密的纹理（片边齿孔、头发、织物）就会闪，人眼读到的就是"画面在抖"，
     * 而不是"它在播"。预缩一次之后每帧都是 1:1 贴图，闪的东西就不存在了。
     *
     * 顺带把导出也救了一遍：134 帧 × 全屏重采样，在软件画布上是实打实的时间。
     */
    private var phSrc: Bitmap? = null
    private var phW = -1
    private var phH = -1
    private var phBmp: Bitmap? = null

    private fun fitted(photo: Bitmap, w: Int, h: Int): Bitmap {
        val c = phBmp
        if (c != null && phSrc === photo && phW == w && phH == h && !c.isRecycled) return c
        if (w <= 0 || h <= 0) return photo
        val n = runCatching { Bitmap.createScaledBitmap(photo, w, h, true) }.getOrNull() ?: return photo
        // 不回收上一张：这一帧可能还挂在 RenderThread 的显示列表里，recycle 会直接把渲染线程打死。
        // 交给 GC，最多也就一张。
        phSrc = photo; phW = w; phH = h; phBmp = n
        return n
    }

    /**
     * 取景器的色彩矩阵：一次算完"平片 → 成片"和"曝光"。
     *
     * flat = 1 时是**未冲洗的平片** —— 降一点对比、抬一点黑位、退一点饱和、偏冷一点。
     * 参考片里快门之前那段就是这样的一张"淡"画面（p5 33 / p95 149 / 饱和 43.6），
     * 落定之后变成 p5 4 / p95 155 / 饱和 69。也就是说那一束光不只是把画面照亮，
     * 它同时把底片"冲"出来了 —— 这才是这个动画真正的叙事。
     *
     * veil 走的是矩阵的偏移项（0…255 单位）而不是半透明白纱：
     * 加白纱会把暗部一起提亮成灰，加偏移才是"光落在底片上"。
     */
    private fun gradeMatrix(gain: Float, flat: Float, veil: Float): ColorMatrix {
        val sat = 1f - 0.07f * flat
        val con = 1f - 0.10f * flat
        val lift = 0.045f * flat
        val tintR = 1f - 0.018f * flat
        val tintB = 1f + 0.018f * flat
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        val s = con * gain

        val m = FloatArray(20)
        m[0] = s * (lr + sat * (1f - lr)) * tintR
        m[1] = s * (lg * (1f - sat))
        m[2] = s * (lb * (1f - sat)) * tintB
        m[4] = lift * 255f + veil * 112f
        m[5] = s * (lr * (1f - sat)) * tintR
        m[6] = s * (lg + sat * (1f - lg))
        m[7] = s * (lb * (1f - sat)) * tintB
        m[9] = lift * 255f + veil * 104f
        m[10] = s * (lr * (1f - sat)) * tintR
        m[11] = s * (lg * (1f - sat))
        m[12] = s * (lb + sat * (1f - lb)) * tintB
        m[14] = lift * 255f + veil * 86f
        m[15] = 0f; m[16] = 0f; m[17] = 0f; m[18] = 1f; m[19] = 0f
        return ColorMatrix(m)
    }

    /**
     * 同一个 (gain, flat, veil) 只建一次 filter。
     *
     * 对焦那一整秒、实焦静止那半秒、定格那 0.67 秒 —— 三个参数都是恒定值，
     * 但 r58 每帧都在 new ColorMatrix + new ColorMatrixColorFilter。每帧两个小对象看着不多，
     * 可它是 60 fps × 每次重绘都要走一遍的分配，正是 GC 偶尔插一脚的地方。
     */
    private var gmGain = Float.NaN
    private var gmFlat = Float.NaN
    private var gmVeil = Float.NaN
    private var gmFilter: ColorMatrixColorFilter? = null

    private fun filterFor(gain: Float, flat: Float, veil: Float): ColorMatrixColorFilter {
        val f = gmFilter
        if (f != null && gain == gmGain && flat == gmFlat && veil == gmVeil) return f
        val nf = ColorMatrixColorFilter(gradeMatrix(gain, flat, veil))
        gmGain = gain; gmFlat = flat; gmVeil = veil; gmFilter = nf
        return nf
    }

    // ================================================================ 观片台
    /**
     * 观片台：相纸冲好了，搁在灯箱上，灯刚亮。
     *
     * 全段只有一个事件：灯亮 → 过曝 → 回落 → 落定。纸不动，画不动，位置一个像素都不变。
     *
     * 亮起之后还有两样几乎看不见的东西在动，用来避免"这视频卡住了"的错觉：
     *  · 曝光在 ±0.6% 之间极慢地呼吸；
     *  · 灯箱色温在 0.35% 内往冷/暖偏 —— 老灯管的色漂。
     */
    private fun drawPrint(c: Canvas, w: Float, h: Float, t: Float, photo: Bitmap, seed: Int) {
        val m = min(w, h)
        val dt = t - LIGHT_ON
        val lit = dt >= 0f

        // 曝光：暗态 0.24 → 过曝 2.37 → 1.00
        val gain = if (!lit) DARK_PHOTO else {
            val raw = 1f + FLASH_GAIN * settle(dt - FLASH_HOLD)
            // 落定之后的呼吸：慢到看不出周期，但画面是"活的"
            raw * (1f + 0.006f * sin(t * 0.9f + seed * 0.3f) * smooth01((dt - 1.0f) / 0.6f))
        }
        // 纸：暗态 0.41 → 点亮瞬间 0.975 → 1.0
        val paperG = if (!lit) DARK_PAPER else
            0.975f + 0.025f * smooth01(dt / 0.9f) +
                0.006f * sin(t * 0.7f + 1.3f) * smooth01((dt - 1.0f) / 0.6f)
        // 灯箱色漂：点亮后极缓地暖→冷（0.5% 量级）
        val warm = if (!lit) 0f else 0.004f * sin(t * 0.33f + seed * 0.11f)

        c.drawColor(0xFF0A0908.toInt())

        // 桌面：不是一块死黑，底部略亮（灯箱把光打到桌面上）
        run {
            val p = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h,
                    Color.argb(0, 40, 34, 28),
                    Color.argb((26 * paperG).toInt(), 46, 38, 30), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w, h, p)
        }

        // 版面：相纸几乎铺满画面，留一圈桌面
        val padX = 0.042f * w
        val padT = 0.050f * h
        val padB = 0.065f * h
        val paper = RectF(padX, padT, w - padX, h - padB)
        val pRad = m * 0.020f

        // 光池：灯箱照在桌面上的那一圈暖光。过曝时更亮更大，随后收回。
        run {
            val spill = if (!lit) 0f else 1f + 0.55f * (gain / 2.37f - 0.42f).coerceIn(0f, 1f)
            if (spill > 0.01f) {
                val r = m * (0.72f + 0.10f * spill)
                val p = Paint().apply {
                    shader = RadialGradient(paper.centerX(), paper.centerY(), r,
                        intArrayOf(Color.argb((30 * spill).toInt(), 255, 250, 236),
                            Color.argb((10 * spill).toInt(), 255, 244, 222), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
                }
                c.drawRect(0f, 0f, w, h, p)
            }
        }

        // 相纸投影：三层同心冒充一次模糊。灯亮起来之后投影变紧、变深（光从上方来）。
        run {
            val tight = if (!lit) 1f else 1f - 0.35f * smooth01(dt / 0.5f)
            val base = if (!lit) 0.5f else 0.62f
            for (i in 3 downTo 1) {
                val grow = m * 0.012f * i * tight
                val p = Paint().apply {
                    color = Color.argb(((8 + 7 * (4 - i)) * base).toInt(), 0, 0, 0)
                    isAntiAlias = true
                }
                c.drawRoundRect(RectF(paper.left - grow, paper.top - grow + m * 0.004f,
                    paper.right + grow, paper.bottom + grow + m * 0.004f),
                    pRad + grow, pRad + grow, p)
            }
        }

        // 相纸本体：白，但带纸基的暖与一点点从上到下的厚薄变化
        run {
            val tintR = (250 * paperG * (1f + warm)).toInt()
            val tintG = (248 * paperG).toInt()
            val tintB = (243 * paperG * (1f - warm * 1.6f)).toInt()
            val top = Color.rgb(tintR.coerceIn(0, 255), tintG.coerceIn(0, 255), tintB.coerceIn(0, 255))
            val bot = Color.rgb((tintR * 0.975f).toInt(), (tintG * 0.975f).toInt(),
                (tintB * 0.972f).toInt())
            val p = Paint().apply {
                shader = LinearGradient(paper.left, paper.top, paper.left, paper.bottom, top, bot,
                    Shader.TileMode.CLAMP)
                isAntiAlias = true
            }
            c.drawRoundRect(paper, pRad, pRad, p)
        }

        // 纸纹：极细、静态。相纸的白边不是塑料，摸得到的纹理是它
        run {
            val p = Paint().apply { isFilterBitmap = true; alpha = 22 }
            val sh = BitmapShader(fiberNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val mm = Matrix(); mm.setScale(0.85f, 0.85f)
            sh.setLocalMatrix(mm); p.shader = sh
            c.drawRoundRect(paper, pRad, pRad, p)
        }

        // 画面：相纸留边。左右留得多、下边比上边多一截 —— 暗房装裱的惯例。
        val inner = RectF(
            paper.left + paper.width() * 0.105f,
            paper.top + paper.height() * 0.052f,
            paper.right - paper.width() * 0.105f,
            paper.bottom - paper.height() * 0.068f,
        )
        val img = fitRect(photo, inner)

        val base = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { isAntiAlias = true }
        setGain(base, gain)
        // 预缩到画面实际尺寸再贴：观片台这张"相纸"只有画面区域的 1/3 尺寸，
        // 每帧现场缩 2560 px 一样会闪（细密的影像纹理最明显）。
        c.drawBitmap(fitted(photo, img.width().toInt(), img.height().toInt()), null, img, base)

        // 光溢：只在过曝那一小段出现。
        //
        // 旧版是把整幅照片缩到 1/22 再半透明盖回去 —— 暗部也被复制了一份，于是画面
        // 出现第二层轮廓，看着就是重影。真正的过曝只发生在亮部：所以这里先抽高光
        // （阈值以上按平方衰减），再把这张只有光斑的图模糊，最后用 **ADD** 加回去。
        // 暗部不发光，就不可能有重影。
        run {
            val over = ((gain - 1f) / FLASH_GAIN).coerceIn(0f, 1f)
            if (over > 0.02f) {
                val b = bloomBitmap(photo, 5)
                if (b != null) {
                    val grow = m * 0.008f * over
                    val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                        isAntiAlias = true
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                        alpha = (142 * over).toInt().coerceIn(0, 255)
                    }
                    c.drawBitmap(b, null, RectF(img.left - grow, img.top - grow,
                        img.right + grow, img.bottom + grow), bp)
                    // 再往纸面上散一圈：同一张光斑图放大画，更淡
                    val bp2 = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                        isAntiAlias = true
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
                        alpha = (48 * over).toInt().coerceIn(0, 255)
                    }
                    val g2 = m * 0.038f * over
                    c.drawBitmap(b, null, RectF(img.left - g2, img.top - g2,
                        img.right + g2, img.bottom + g2), bp2)
                }
            }
        }
        base.colorFilter = null

        // 相纸的轻微卷曲：画面下沿一条渐深的暗带（纸在灯箱上不会完全平）
        run {
            val p = Paint().apply {
                shader = LinearGradient(0f, img.bottom - img.height() * 0.15f, 0f, img.bottom,
                    Color.TRANSPARENT, Color.argb(32, 0, 0, 0), Shader.TileMode.CLAMP)
            }
            c.drawRect(img.left, img.bottom - img.height() * 0.15f, img.right, img.bottom, p)
        }

        // 画面与白边之间：一道 1 px 的暗线。有了它，照片是"压"在纸上的
        run {
            val p = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1f }
            p.color = Color.argb((26 * paperG).toInt(), 0, 0, 0)
            c.drawRect(img, p)
        }

        // 相纸上的灰：位置固定（照片已经冲好，灰是落在纸上的，不会飞）
        run {
            val p = Paint().apply { isAntiAlias = true }
            for (i in 0 until 6) {
                val x = paper.left + paper.width() * (0.03f + hash(seed, i * 11 + 5) * 0.94f)
                val y = paper.top + paper.height() * (0.02f + hash(seed, i * 11 + 6) * 0.96f)
                val r = m * (0.0007f + hash(seed, i * 11 + 7) * 0.0016f)
                p.color = Color.argb(((32 * paperG)).toInt().coerceIn(0, 255), 40, 34, 28)
                c.drawCircle(x, y, r, p)
            }
        }

        // 纸边：内侧一道极淡的暗边 + 点亮时外侧一道暖白（纸透光，边缘会亮）
        run {
            val p = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
            p.strokeWidth = max(1f, m * 0.0011f)
            p.color = Color.argb((42 * paperG).toInt().coerceIn(0, 255), 0, 0, 0)
            c.drawRoundRect(paper, pRad, pRad, p)
            val edge = if (!lit) 0f else (1f - smooth01(dt / 1.1f))
            if (edge > 0.01f) {
                p.strokeWidth = max(1f, m * 0.0026f)
                p.color = Color.argb((70 * edge).toInt(), 255, 252, 240)
                c.drawRoundRect(paper, pRad, pRad, p)
            }
        }

        // 四周压暗：参考片四角 8–29/255，是很重的暗角，纸才站得住
        run {
            val r = max(w, h) * 0.80f
            val p = Paint().apply {
                shader = RadialGradient(w / 2f, h / 2f, r,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(160, 0, 0, 0)),
                    floatArrayOf(0f, 0.56f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w, h, p)
        }
    }

    private fun drawGrain(c: Canvas, r: RectF, t: Float, seed: Int, alphaFine: Int, alphaCoarse: Int) {
        val m = Matrix()
        // 细颗粒：每 1/24 s 换一次位置
        run {
            val p = Paint().apply { isAntiAlias = false; isFilterBitmap = true }
            val sh = BitmapShader(fineNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val ox = hash(seed, (t * 24).toInt() * 3 + 1) * 512f
            val oy = hash(seed, (t * 24).toInt() * 3 + 2) * 512f
            m.reset(); m.setScale(1.15f, 1.15f); m.postTranslate(-ox, -oy)
            sh.setLocalMatrix(m)
            p.shader = sh; p.alpha = alphaFine
            c.drawRect(r, p)
        }
        // 粗颗粒：慢一倍，负责"密度"，让细颗粒不至于像数字噪声
        run {
            val p = Paint().apply { isFilterBitmap = true }
            val sh = BitmapShader(coarseNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val ox = hash(seed + 7, (t * 12).toInt() * 3 + 1) * 64f
            val oy = hash(seed + 7, (t * 12).toInt() * 3 + 2) * 64f
            m.reset(); m.setScale(3.6f, 3.6f); m.postTranslate(-ox * 3.6f, -oy * 3.6f)
            sh.setLocalMatrix(m)
            p.shader = sh; p.alpha = alphaCoarse
            c.drawRect(r, p)
        }
    }

}
