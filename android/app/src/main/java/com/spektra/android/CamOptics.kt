package com.spektra.android

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 一颗镜头（r69）。
 *
 * ## 为什么要自己算等效焦距
 *
 * `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 给的是**物理焦距**（比如 4.7 mm）。这个数字单独看
 * 毫无意义 —— 同一个 4.7 mm，装在 1/2.55" 的传感器上是广角，装在 1/1.7" 上就是超广角。
 * 手机厂商在相机 App 上标的"0.5×/1×/3×"是**等效 35 mm 焦距的比值**（主流机 1× 约 24–26 mm）。
 *
 * 所以这里按 35 mm 画幅对角 43.27 mm 换算：
 *
 *     等效 = 物理焦距 × 43.27 / sqrt(传感器宽² + 传感器高²)      （传感器尺寸单位 mm）
 *
 * 有了等效焦距，多摄才有"哪个是哪个"：把最长的那颗除以基准（最接近 26 mm 的那颗），
 * 得到的比值就是厂商标的 0.5×/1×/3×。
 *
 * ## 物理 vs 逻辑
 *
 * 现代多摄机器对外只暴露 2 个逻辑相机（后/前），超广角与长焦是**物理子相机**：
 *  · 有的机型能 `openCamera(物理 id)` 直开（画质最好、还能吃满像素）；
 *  · 有的机型不允许，只能通过逻辑相机的 `CONTROL_ZOOM_RATIO` 让 HAL 自己去切镜头。
 *
 * [Lens.openable] 记录前者。切换镜头时先试直开，失败再退回"逻辑相机 + 变焦比"。
 */
data class Lens(
    val id: String,
    val facing: Int,
    val focalMm: Float,
    val equivMm: Float,
    val aperture: Float,
    val physical: Boolean,
    val openable: Boolean,
) {
    /** 角色名。按等效焦距分档，和厂商的叫法对齐。 */
    val role: String
        get() = when {
            facing == CameraCharacteristics.LENS_FACING_FRONT -> "前置"
            equivMm < 18f -> "超广角"
            equivMm < 32f -> "广角主摄"
            equivMm < 60f -> "中焦"
            equivMm < 120f -> "长焦"
            else -> "超长焦"
        }

    val focalText: String get() = String.format(Locale.US, "%.1f mm", focalMm)
    val equivText: String get() = "${equivMm.roundToInt()} mm 等效"
    val apertureText: String get() = if (aperture > 0f) String.format(Locale.US, "f/%.1f", aperture) else ""
}

/** 变焦档位：一颗镜头 + 相对基准的倍率 + 显示标签。 */
data class ZoomAnchor(val lens: Lens, val ratio: Float, val label: String)

object CamOptics {

    /** 35 mm 画幅对角线（36×24 → 43.2666）。 */
    private const val FULL_FRAME_DIAGONAL = 43.2666

    /**
     * 扫全部镜头。逻辑相机与物理子相机都收进来，按 (朝向, 等效焦距) 排序。
     *
     * 取不到焦距或传感器尺寸的条目直接跳过 —— 那些是深度/单色辅助相机，
     * 根本不能出图，混进镜头条里只会让人以为手机有五颗后摄。
     */
    fun scan(ctx: Context): List<Lens> {
        val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val all = ArrayList<Lens>()
        val logicalIds = runCatching { cam.cameraIdList.toList() }.getOrDefault(emptyList())

        fun read(id: String, physical: Boolean): Lens? = runCatching {
            val ch = cam.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: return null
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return null
            val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val diag = hypot(phys.width.toDouble(), phys.height.toDouble())
            if (diag <= 0.0) return null
            val equiv = (focal * FULL_FRAME_DIAGONAL / diag).toFloat()
            if (equiv < 5f || equiv > 900f) return null
            val ap = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f
            Lens(
                id = id, facing = facing, focalMm = focal, equivMm = equiv, aperture = ap,
                physical = physical, openable = id in logicalIds,
            )
        }.getOrNull()

        for (id in logicalIds) read(id, physical = false)?.let { all += it }

        // 物理子相机：一般不在 cameraIdList 里，要从逻辑相机的 physicalCameraIds 拿。
        // 它们即使不可直开也有用 —— 是变焦档位的来源（HAL 内部会切过去）。
        for (id in logicalIds) {
            val ch = runCatching { cam.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) continue
            for (pid in ch.physicalCameraIds) {
                if (all.any { it.id == pid }) continue
                read(pid, physical = true)?.let { all += it }
            }
        }
        return all.sortedWith(compareBy({ it.facing }, { it.equivMm }))
    }

    /**
     * 变焦档位。基准是"最接近 26 mm 的那颗"（主流机的 1×）。
     *
     * 去重规则：比值相差不到 8% 的两颗视为同一档（同焦段的不同传感器很常见，
     * 比如主摄与它的 2× 裁切模式），优先保留能直开的那颗 —— 画质更好。
     */
    fun anchors(lenses: List<Lens>, facing: Int): List<ZoomAnchor> {
        val mine = lenses.filter { it.facing == facing && it.equivMm > 5f }
        if (mine.isEmpty()) return emptyList()
        val base = mine.minByOrNull { abs(it.equivMm - 26f) } ?: mine.first()
        if (base.equivMm <= 0f) return emptyList()

        val raw = mine.map { ZoomAnchor(it, it.equivMm / base.equivMm, zoomLabel(it.equivMm / base.equivMm)) }
            .filter { it.ratio in 0.35f..8f }
            .sortedBy { it.ratio }

        val keep = ArrayList<ZoomAnchor>()
        for (a in raw) {
            val last = keep.lastOrNull()
            when {
                last == null -> keep += a
                a.ratio / last.ratio > 1.08f -> keep += a
                // 同档：可直开的替换掉不可直开的
                a.lens.openable && !last.lens.openable -> keep[keep.size - 1] = a
            }
        }
        // 档位太多时留离 1× 最近的五个（按对数距离，0.5× 和 2× 的距离才对称）
        val trimmed = if (keep.size <= 5) keep else keep
            .sortedBy { abs(ln(it.ratio.toDouble())) }
            .take(5)
            .sortedBy { it.ratio }
        return trimmed
    }

    /** 变焦倍率的显示文本。接近厂商惯用值时取整，否则保留一位小数。 */
    fun zoomLabel(r: Float): String {
        val nice = listOf(0.5f, 0.6f, 1f, 2f, 2.5f, 3f, 3.2f, 3.5f, 4f, 5f)
        val hit = nice.firstOrNull { abs(it - r) < 0.10f * it }
        val v = hit ?: ((r * 10f).roundToInt() / 10f)
        return if (abs(v - v.roundToInt()) < 0.05f) "${v.roundToInt()}×"
        else String.format(Locale.US, "%.1f×", v)
    }

    /** 宽高比（归一化到 <=1 的小数，4:3 与 3:4 视为同一档）。 */
    fun aspectOf(w: Int, h: Int): Float {
        if (w <= 0 || h <= 0) return 4f / 3f
        val a = w.toFloat() / h
        return if (a >= 1f) a else 1f / a
    }

    /** 宽高比的显示名：4:3 / 16:9 / 1:1 / 3:2 / 全画幅。 */
    fun aspectLabel(a: Float): String {
        val table = listOf(
            4f / 3f to "4:3",
            16f / 9f to "16:9",
            1f to "1:1",
            3f / 2f to "3:2",
            18f / 9f to "18:9",
        )
        val hit = table.minByOrNull { abs(it.first - a) }
        return if (hit != null && abs(hit.first - a) < 0.05f) hit.second
        else String.format(Locale.US, "%.2f:1", a)
    }

    /** 像素数的显示名：12MP / 48MP / 3.7MP。超过 10MP 取整，小尺寸保留一位。 */
    fun mpLabel(w: Int, h: Int): String {
        val mp = (w.toLong() * h) / 1_000_000.0
        return if (mp >= 10) "${mp.roundToInt()}MP" else String.format(Locale.US, "%.1fMP", mp)
    }

    /** 4:3 / 3:2 / 16:9 / 1:1 的候选档位（从实际出图尺寸里挑，不编造）。 */
    fun aspectsOf(sizes: List<Size>): List<Float> {
        val out = ArrayList<Float>()
        for (s in sizes) {
            val a = aspectOf(s.width, s.height)
            if (out.none { abs(it - a) < 0.02f }) out += a
        }
        return out.sortedDescending()
    }

    /** 同一比例下按分辨率从大到小。 */
    fun sizesAt(sizes: List<Size>, aspect: Float): List<Size> =
        sizes.filter { abs(aspectOf(it.width, it.height) - aspect) < 0.02f }
            .sortedByDescending { it.width.toLong() * it.height }

    /**
     * 挑预览尺寸：给定比例、长边上限，取最接近的。
     *
     * 长边上限不是为了省算力（着色器开销与分辨率几乎无关），而是**帧延迟**：
     * 太小的流会被 HAL 换成低帧率模式，太大的流在高像素机型上会拖慢 AE 收敛。
     */
    fun pickPreview(sizes: List<Size>, aspect: Float, longEdge: Int = 1600): Size {
        val same = sizesAt(sizes, aspect)
        val pool = if (same.isNotEmpty()) same else sizes
        return pool.minByOrNull { s ->
            abs(maxOf(s.width, s.height) - longEdge) + abs(aspectOf(s.width, s.height) - aspect) * 4000
        } ?: Size(1440, 1080)
    }

    /** 挑拍照尺寸：给定比例与像素上限，取不超过上限里最大的。 */
    fun pickCapture(sizes: List<Size>, aspect: Float, maxMp: Int): Size {
        val same = sizesAt(sizes, aspect)
        val pool = if (same.isNotEmpty()) same else sizes
        val under = pool.filter { it.width.toLong() * it.height <= maxMp * 1_000_000L }
        return under.maxByOrNull { it.width.toLong() * it.height }
            ?: pool.minByOrNull { it.width.toLong() * it.height }
            ?: Size(4000, 3000)
    }

    /** 镜头名（设置页里那一行）。 */
    fun lensLine(l: Lens): String = buildString {
        append(l.role)
        append(" · ")
        append(l.equivText)
        if (l.aperture > 0f) { append(" · "); append(l.apertureText) }
        if (!l.openable) append(" · 经主摄切换")
    }
}
