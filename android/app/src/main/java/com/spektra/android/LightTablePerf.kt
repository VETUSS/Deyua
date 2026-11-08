package com.spektra.android

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 成片上真实存在的一个齿孔，坐标归一化到**这张图**（0..1，左上原点）。
 *
 * 只记录位置，不记录颜色：绘制时按同一块矩形把片"挖穿"，孔里透出来的是
 * 灯箱（或者叠在下面的另一张片），而不是一个画上去的深色方块。
 *
 * 归一化基准是图片而不是格子/片子：绘制时会按图片自身的画幅比把图放进格子
 * （绝不裁切），孔位必须跟着图片走，否则一裁切孔就漂到画面上去了。
 */
internal class LtHole(
    val u0: Float, val v0: Float,
    val u1: Float, val v1: Float,
)

/**
 * 齿孔检测：只在成片**本身带齿孔**时返回孔位。
 *
 * 观片台以前给每张成片凭空画一圈 135 齿孔，结果连"没开片边"的照片也被套上
 * 一条假底片。真正带齿孔的只有 135 / 135 半格 / 135 宽幅（引擎的
 * `Format.perforated`），而成片里这些孔本来就在像素里；这里把它们找出来，
 * 绘制时按同样位置挖穿，透出灯箱。
 *
 * 判据（全部满足才算，宁可不挖也不乱挖）：
 *  · 孔落在片边 2%–22% 的窄带内（135 实测：孔距片边 2.0 mm、孔 1.98×2.80 mm、
 *    片宽 35 mm → 孔带在 5.7%–13.7%）
 *  · 同一带里至少 3 个孔，**且在同一排**（片边的印字常常和孔挤在一条带里，
 *    按跨轴中心聚簇，只认同一排的那几个）
 *  · 同一排的孔等大、等距（变异 < 30% / 24%）、占空比 0.22–0.58
 *    （1.98/4.75 = 0.42）
 *  · 孔是规整的长方形（饱满度 > 0.72；横片高宽比 0.95–2.2，竖片转 90° 后 0.45–1.05）
 *  · 孔与周围反差极大：亮孔均值 ≥ 185，或被暗孔均值 ≤ 70
 *  · 孔所在的那几行里，孔以外必须是均匀片基（稳健标准差 ≤ 24）——否则那是
 *    画面里的窗格、栏杆，不是片边
 *  · 首尾被画布切掉的孔按周期补回来，免得只挖半条边
 *
 * 一排孔的尺寸取中位数、跨轴位置按最小二乘拟合一条直线再回填：同一排的孔在
 * 真实胶片上完全一样大、完全等高，把单孔检测的 ±1px 抖动平均掉，挖出来的孔
 * 才不会有的偏左、有的偏上，也不会在真孔边上留一道黑缝。
 */
internal object LtPerf {

    private class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w: Int get() = x1 - x0 + 1
        val h: Int get() = y1 - y0 + 1
    }

    private class Comp(val box: Box) {
        val cx: Float get() = (box.x0 + box.x1) / 2f
        val cy: Float get() = (box.y0 + box.y1) / 2f
    }

    private enum class Band { TOP, BOTTOM, LEFT, RIGHT }

    fun detect(src: Bitmap?): List<LtHole>? {
        if (src == null || src.width < 160 || src.height < 120) return null
        val w0 = src.width
        val h0 = src.height
        // 检测在 ~640 边上做：孔有 11×16px，形状和边缘位置都够判；再小一点
        // 单个孔的 ±1px 就会变成片子上的几像素错位
        val k = min(1f, 640f / max(w0, h0).toFloat())
        val w = max(64, (w0 * k).toInt())
        val h = max(48, (h0 * k).toInt())
        val small = runCatching {
            if (w != w0 || h != h0) Bitmap.createScaledBitmap(src, w, h, true) else src
        }.getOrNull() ?: return null
        if (small.width != w || small.height != h) return null

        val px = IntArray(w * h)
        runCatching { small.getPixels(px, 0, w, 0, 0, w, h) }.getOrElse { return null }
        if (small !== src) runCatching { small.recycle() }

        val lum = ByteArray(w * h)
        for (i in 0 until w * h) {
            val p = px[i]
            val y = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
            lum[i] = y.coerceIn(0, 255).toByte()
        }

        // 横向画布先看上下两条带（135 常规/半格/宽幅都是这样），
        // 竖着扫的片（相机竖持时引擎把片转了 90°）再看左右
        val wide = w.toFloat() / h.toFloat()
        val bands = if (wide >= 0.95f) listOf(Band.TOP, Band.BOTTOM, Band.LEFT, Band.RIGHT)
        else listOf(Band.LEFT, Band.RIGHT, Band.TOP, Band.BOTTOM)

        for (b in bands) {
            val hs = scan(lum, w, h, b) ?: continue
            if (hs.size < 3) continue
            val found = ArrayList<LtHole>(hs)
            // 真 135 两侧都有孔带：对边能找出来就一起挖，找不出就只挖这一边
            val partner = when (b) {
                Band.TOP -> Band.BOTTOM
                Band.BOTTOM -> Band.TOP
                Band.LEFT -> Band.RIGHT
                Band.RIGHT -> Band.LEFT
            }
            scan(lum, w, h, partner)?.let { found.addAll(it) }
            return found
        }
        return null
    }

    private fun scan(lum: ByteArray, w: Int, h: Int, band: Band): List<LtHole>? {
        val horiz = band == Band.TOP || band == Band.BOTTOM
        val dim = if (horiz) h else w          // across the film
        val d0 = (dim * 0.02f).toInt()
        val d1 = max(d0 + 8, (dim * 0.22f).toInt())
        val lo = if (band == Band.TOP || band == Band.LEFT) d0 else dim - d1
        val hi = if (band == Band.TOP || band == Band.LEFT) d1 else dim - d0
        if (hi - lo < 6) return null

        val lw = if (horiz) w else hi - lo     // 带长
        val lh = if (horiz) hi - lo else h
        if (lw < 24 || lh < 6) return null

        val n = lw * lh
        val vals = IntArray(n)
        val hist = IntArray(256)
        for (y in 0 until lh) {
            for (x in 0 until lw) {
                val gx = if (horiz) x else lo + x
                val gy = if (horiz) lo + y else y
                val v = lum[gy * w + gx].toInt() and 0xFF
                vals[y * lw + x] = v
                hist[v]++
            }
        }

        // Otsu：带里通常只有两类（片基 / 孔）
        var sumAll = 0.0
        for (t in 0..255) sumAll += t.toDouble() * hist[t]
        var sumB = 0.0
        var wB = 0
        var best = -1.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += t.toDouble() * hist[t]
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = t }
        }
        // 对比度太低：整条带是同一块东西，没有孔
        var loV = 255
        var hiV = 0
        var acc = 0
        var cnt = 0
        var darkAcc = 0
        var darkCnt = 0
        for (i in 0 until n) {
            val v = vals[i]
            if (v < loV) loV = v
            if (v > hiV) hiV = v
            if (v > thr) { acc += v; cnt++ } else { darkAcc += v; darkCnt++ }
        }
        if (hiV - loV < 40) return null
        val brightShare = cnt.toFloat() / n
        if (brightShare < 0.04f || brightShare > 0.72f) return null
        val brightMean = if (cnt > 0) acc.toFloat() / cnt else 0f
        val darkMean = if (darkCnt > 0) darkAcc.toFloat() / darkCnt else 0f

        // 两种极性都试：白孔（扫描透光）与黑孔（暗背景/暗房印相）
        val bright = components(lw, lh, horiz) { vals[it] > thr }
        val dark = components(lw, lh, horiz) { vals[it] <= thr }
        val comps: List<Comp>
        val isBright: Boolean
        if (bright != null) { comps = bright; isBright = true }
        else if (dark != null) { comps = dark; isBright = false }
        else return null
        // 孔本身必须够亮/够黑，灰色块不是孔
        if (isBright && brightMean < 185f) return null
        if (!isBright && darkMean > 70f) return null

        // 孔是一排。片边的印字（KODAK PORTRA 400 之类）就在孔旁边，亮字还会被
        // Otsu 判成"孔"，于是按跨轴中心聚簇：同一排的孔中心相差不到半个孔高，
        // 印字那一排落在别的行上。逐簇试，先把人数最多的一簇交给 fromRow 验证。
        val cross: (Comp) -> Float = { if (horiz) it.cy else it.cx }
        val tol = max(1.6f, median(comps.map { it.box.h.toFloat() }) * 0.60f)
        val byCross = comps.sortedBy(cross)
        val groups = ArrayList<List<Comp>>(4)
        var i0 = 0
        while (i0 < byCross.size) {
            val seed = cross(byCross[i0])
            var j = i0
            val g = ArrayList<Comp>(8)
            while (j < byCross.size && cross(byCross[j]) - seed <= tol) {
                g.add(byCross[j])
                j++
            }
            if (g.size >= 3) groups.add(g)
            i0 = if (j > i0) j else i0 + 1
        }
        for (g in groups.sortedByDescending { it.size }) {
            fromRow(g, vals, lw, lh, lo, w, h, horiz)?.let { return it }
        }
        return null
    }

    /**
     * 一排孔 → 孔位。
     *
     * 沿排布方向的相位只信实测（等距排布是胶片机的机械精度），尺寸取中位数、
     * 跨轴位置按最小二乘拟合直线——扫描时胶片轻微倾斜（< 8°）能跟上，单孔检测
     * 的抖动被平均掉。最后每边外扩 1px：Otsu 阈值切在孔的抗锯齿过渡带上，实测
     * 框会略小于真孔，不外扩就会在孔边留一道黑缝。
     */
    private fun fromRow(
        g0: List<Comp>,
        vals: IntArray,
        lw: Int,
        lh: Int,
        lo: Int,
        w: Int,
        h: Int,
        horiz: Boolean,
    ): List<LtHole>? {
        // 同一排的孔等大：偏离中位数太多的不是孔（印字、划痕）
        val mw0 = median(g0.map { it.box.w.toFloat() })
        val mh0 = median(g0.map { it.box.h.toFloat() })
        if (mw0 < 2.5f || mh0 < 2.5f) return null
        val g = g0.filter {
            abs(it.box.w - mw0) <= mw0 * 0.45f && abs(it.box.h - mh0) <= mh0 * 0.45f
        }
        if (g.size < 3) return null
        val medW = median(g.map { it.box.w.toFloat() })
        val medH = median(g.map { it.box.h.toFloat() })
        val mainSize = if (horiz) medW else medH        // 沿孔排布方向的尺寸
        val crossSize = if (horiz) medH else medW

        // 片基只在"孔所在的那几行"里量：带的内侧可能已经是画面，那是内容不是片边
        val ry0 = g.minOf { it.box.y0 }
        val ry1 = g.maxOf { it.box.y1 }
        val inBox = BooleanArray(lw * lh)
        for (c in g) {
            for (y in c.box.y0..c.box.y1) {
                val row = y * lw
                for (x in c.box.x0..c.box.x1) inBox[row + x] = true
            }
        }
        val base = ArrayList<Int>(lw * (ry1 - ry0 + 1))
        for (y in ry0..ry1) {
            val row = y * lw
            for (x in 0 until lw) if (!inBox[row + x]) base.add(vals[row + x])
        }
        if (base.size < 24) return null
        val med = median(base.map { it.toFloat() })
        val devs = base.map { abs(it - med) }.sorted()
        // 稳健标准差（MAD）：片边上的边码印字不该把判据顶爆
        if (devs[devs.size / 2] * 1.4826f > 24f) return null

        // 排布：等距、占空比像 135
        val seq = g.sortedBy { if (horiz) it.cx else it.cy }
        val centers = seq.map { if (horiz) it.cx else it.cy }
        val gaps = ArrayList<Float>(centers.size - 1)
        for (i in 1 until centers.size) gaps.add(centers[i] - centers[i - 1])
        if (gaps.isEmpty()) return null
        val gm = gaps.average().toFloat()
        if (gm <= 0f) return null
        if (gaps.any { it < gm * 0.55f }) return null   // 粘连成一块
        if (cv(gaps) > 0.24f) return null
        val duty = mainSize / gm
        if (duty < 0.22f || duty > 0.58f) return null

        // 跨轴位置：最小二乘拟合一条直线，扫描时胶片可能轻微旋转
        val n = seq.size
        val mx = centers.sumOf { it.toDouble() } / n
        val my = seq.sumOf { (if (horiz) it.cy else it.cx).toDouble() } / n
        var sxx = 0.0
        var sxy = 0.0
        for (c in seq) {
            val x = (if (horiz) c.cx else c.cy).toDouble()
            val y = (if (horiz) c.cy else c.cx).toDouble()
            sxx += (x - mx) * (x - mx)
            sxy += (x - mx) * (y - my)
        }
        var slope = if (sxx > 1e-6) (sxy / sxx).toFloat() else 0f
        if (abs(slope) > 0.14f) slope = 0f    // 歪得不像片边，按水平处理
        val inter = (my - slope * mx).toFloat()
        fun crossAt(main: Float): Float = slope * main + inter

        // 首尾的孔常常被画布切掉（检测时因"碰边"被丢），按周期补回来
        val cs = ArrayList<Pair<Float, Float>>(20)   // 主向中心 to 跨轴中心
        for (c in seq) {
            val m = if (horiz) c.cx else c.cy
            cs.add(m to crossAt(m))
        }
        if (cs.size <= 16) {
            val first = cs.first()
            var k2 = 1
            while (cs.size < 20) {
                val m = first.first - k2 * gm
                if (m + mainSize / 2f < 0f) break
                cs.add(0, m to crossAt(m))
                k2++
            }
            val last = cs.last()
            k2 = 1
            while (cs.size < 20) {
                val m = last.first + k2 * gm
                if (m - mainSize / 2f > (if (horiz) lw else lh)) break
                cs.add(m to crossAt(m))
                k2++
            }
        }

        val ex = 1.3f
        val out = ArrayList<LtHole>(cs.size)
        for ((m, x) in cs) {
            val a0 = m - mainSize / 2f - ex
            val a1 = m + mainSize / 2f + ex
            val b0 = x - crossSize / 2f - ex
            val b1 = x + crossSize / 2f + ex
            out.add(
                if (horiz) LtHole(
                    u0 = a0 / lw,
                    u1 = a1 / lw,
                    v0 = (lo + b0) / h,
                    v1 = (lo + b1) / h,
                ) else LtHole(
                    u0 = (lo + a0) / w,
                    u1 = (lo + a1) / w,
                    v0 = b0 / lh,
                    v1 = b1 / lh,
                )
            )
        }
        return out
    }

    private fun median(xs: List<Float>): Float {
        if (xs.isEmpty()) return 0f
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    private fun cv(xs: List<Float>): Float {
        val m = xs.average().toFloat()
        if (m <= 0f) return 9f
        return sqrt(xs.sumOf { ((it - m) * (it - m)).toDouble() }.toFloat() / xs.size) / m
    }

    /** 连通域；返回合格的"孔"候选（≥3 个、规整、等大），否则 null。 */
    private fun components(lw: Int, lh: Int, horiz: Boolean, test: (Int) -> Boolean): List<Comp>? {
        val n = lw * lh
        val mask = BooleanArray(n)
        for (i in 0 until n) mask[i] = test(i)
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        val list = ArrayList<Comp>(12)
        for (i in 0 until n) {
            if (!mask[i] || seen[i]) continue
            var sp = 0
            stack[sp++] = i
            seen[i] = true
            var x0 = lw; var x1 = -1; var y0 = lh; var y1 = -1
            var area = 0
            var touch = false
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % lw
                val y = p / lw
                area++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x == 0 || y == 0 || x == lw - 1 || y == lh - 1) touch = true
                // 4 邻域
                if (x > 0) { val q = p - 1; if (mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (x < lw - 1) { val q = p + 1; if (mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - lw; if (mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
                if (y < lh - 1) { val q = p + lw; if (mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q } }
            }
            if (touch) continue                       // 被带边切开的，不算一个孔
            if (area < 24) continue
            val bx = Box(x0, y0, x1, y1)
            if (bx.w < 3 || bx.h < 3) continue
            val fill = area.toFloat() / (bx.w * bx.h)
            if (fill < 0.72f) continue
            val ratio = bx.h.toFloat() / bx.w.toFloat()
            // 横片：孔 1.98 沿 × 2.80 跨 → 1.41；竖片整体转 90° → 0.71
            if (horiz) {
                if (ratio < 0.95f || ratio > 2.20f) continue
            } else {
                if (ratio < 0.45f || ratio > 1.05f) continue
            }
            list.add(Comp(bx))
        }
        return list.takeIf { it.size >= 3 }
    }
}
