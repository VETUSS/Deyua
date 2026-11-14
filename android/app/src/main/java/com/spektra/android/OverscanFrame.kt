package com.spektra.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 片边取景。
 *
 * 模型和观片台那套一样：「画面在下、片边在上」—— 整张成片先画出来，再压一块开了
 * 门洞的片边模板上去。门外被片基挡住，门里才是成片会留下来的部分。
 *
 * 所以手势改的是**画面本身**的自由变换（位置 + 倍率），而不是一个固定在门里的
 * 窗口：手指按在哪里就以哪里为中心缩放，拖动跟着指尖走，不设起步阈值，也不把
 * 手势限制在门洞那点面积里 —— 整块预览区都能操作。
 *
 * r42.2 两件事重做：
 *
 *  1. **模板必须是"最后会出成什么样"的那个模板。** 以前这里画的是自造的深色片基
 *     和几颗假孔，尺寸也和引擎对不上（门两边留了 22 mm，而引擎 strip 模式只留
 *     0.9 mm）。现在几何全部照 `upstream/engine/src/pipeline/overscan.cpp`：
 *     整条 35 mm（120 为 61 mm）片宽、顺片方向每侧 0.45–1.7 mm、齿孔 1.98×2.80 mm
 *     / 孔距 4.75 mm / 距片边 2.00 mm、边码印在最外侧 2 mm 那条带里；片子是横着走
 *     还是竖着走（片边在上下还是左右）也按引擎的判据算。选 6×17 看到的就是一条
 *     宽幅，选半格看到的就是竖着走的半格 —— 门占多大、留多少边，和成片一致。
 *
 *  2. **调整时模板统一是灰白色。** 深色片基盖住画面，看不见框外还有什么，没法调。
 *     现在片基是半透明的灰白（0.10），画面透过它继续可见；门洞用白虚线 + 四角标
 *     + 三分线标出来。这层灰白只属于取景页 —— 退出后主预览照旧渲染引擎出的真片边，
 *     不受影响。
 *
 * zoom = 1 时画面恰好铺满片门，也就是引擎的"居中裁到门幅"，所以历史参数能原样恢复。
 */
@Composable
fun OverscanFrameEditor(
    src: ImageBitmap,
    format: String,
    cx0: Float,
    cy0: Float,
    zoom0: Float,
    stockLabel: String,
    onFormat: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Float, Float, Float) -> Unit,
) {
    val srcW = src.width.toFloat().coerceAtLeast(1f)
    val srcH = src.height.toFloat().coerceAtLeast(1f)

    // 引擎要求"先裁到片门"：画面比例必须等于门幅，所以取景窗的底窗就是
    // osBaseCrop 给的那一块，和 MainActivity 下发的 geometry_crop 同一套算法。
    val base = remember(src, format) {
        osBaseCrop(src.width.toDouble(), src.height.toDouble(), format)
    }
    // 画面是横构图还是竖构图 —— 决定片子横着走还是竖着走（片边在上下还是左右）
    val picLandscape = remember(base) { base.first * srcW >= base.second * srcH }

    // 画面自身的摆放：zoom = 1 恰好铺满片门；fx/fy 是画面中心相对片门中心的
    // 偏移，单位取画面自身的长宽 —— 与视图像素无关，转屏、换门幅都不必换算。
    var zoom by remember(src) { mutableFloatStateOf(zoom0.coerceIn(1f, OS_MAX_ZOOM)) }
    var fx by remember(src) { mutableFloatStateOf((0.5f - cx0).coerceIn(-0.5f, 0.5f)) }
    var fy by remember(src) { mutableFloatStateOf((0.5f - cy0).coerceIn(-0.5f, 0.5f)) }

    var dragging by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }

    // 手停下来再把格线收走：调的时候需要它，调完那一眼不需要。
    // 用 collectLatest 而不是拿 state 当 key 重启协程 —— 后者每一帧都会
    // 取消并新建一个协程，拖动时等于自己给自己加抖动
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(zoom, fx, fy) }
            .collectLatest {
                if (!moved) return@collectLatest
                grid = true
                delay(700)
                grid = false
            }
    }

    val tm = rememberTextMeasurer()
    val ink = remember {
        TextStyle(color = Color(0xBFE9E7E1), fontSize = 7.sp, fontWeight = FontWeight.Medium)
    }
    val head = if (stockLabel.isBlank()) "DEYVA 400" else stockLabel.uppercase()
    val textA = remember(format, head) {
        tm.measure(if (osIs120(format)) "120   $head" else "$head   24", ink)
    }
    val textB = remember(format) {
        tm.measure(if (osIs120(format)) "▶ 6 ◀" else "▶ 12 ◀", ink)
    }

    /** 画面摆到位以后，门洞中心落在源图的哪一点（归一化坐标） */
    fun cropCenter(): Pair<Float, Float> = (0.5f - fx) to (0.5f - fy)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF07070A))) {
            Column(Modifier.fillMaxSize()) {

                // ---------- 顶栏 ----------
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
                            .iosTap(pressDim = 0.45f) { onCancel() }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("取消", color = Dv.blue, fontSize = 16.sp) }

                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("取景", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${osFormatLabel(format)} · 整屏拖动 · 双指缩放",
                            color = Dv.label3, fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))

                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
                            .iosTap(pressDim = 0.45f, haptic = true) {
                                val c = cropCenter()
                                onConfirm(c.first, c.second, zoom)
                            }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("完成", color = Orange, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }

                // ---------- 取景台 ----------
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(format, picLandscape) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    dragging = true
                                    var prevC: Offset? = null
                                    var prevD = 0f
                                    var prevN = 0
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val pressed = ev.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                        val n = pressed.size
                                        var sum = Offset.Zero
                                        for (c in pressed) sum += c.position
                                        val cen = sum / n.toFloat()
                                        val dist = if (n >= 2) {
                                            (pressed[0].position - pressed[1].position).getDistance()
                                        } else 0f

                                        // 手指数量变化的那一帧只更新基准：否则落第二指时
                                        // 重心从单指位置跳到两指中点，画面会猛拽一下
                                        if (prevC != null && n == prevN) {
                                            val pan = cen - prevC!!
                                            val gz = if (prevD > 2f && dist > 2f) dist / prevD else 1f
                                            if (pan.getDistanceSquared() > 0.01f || abs(gz - 1f) > 0.0005f) {
                                                moved = true
                                                val avail = Size(size.width.toFloat(), size.height.toFloat())
                                                val g = layoutOf(avail, format, picLandscape, padOf(avail)).gate
                                                val k0 = max(g.width / srcW, g.height / srcH)
                                                val w0 = srcW * k0 * zoom
                                                val h0 = srcH * k0 * zoom
                                                val nz = (zoom * gz).coerceIn(1f, OS_MAX_ZOOM)
                                                val kk = nz / zoom
                                                // 画面中心先按手指中心缩放，再叠加平移
                                                val ccx = g.center.x + fx * w0
                                                val ccy = g.center.y + fy * h0
                                                val ncx = cen.x + (ccx - cen.x) * kk + pan.x
                                                val ncy = cen.y + (ccy - cen.y) * kk + pan.y
                                                val w1 = srcW * k0 * nz
                                                val h1 = srcH * k0 * nz
                                                // 门洞必须留在画面里，否则那里根本没有像素
                                                val cw = (g.width / w1).coerceIn(0.02f, 1f)
                                                val ch = (g.height / h1).coerceIn(0.02f, 1f)
                                                val hx = (0.5f - cw / 2f).coerceAtLeast(0f)
                                                val hy = (0.5f - ch / 2f).coerceAtLeast(0f)
                                                fx = ((ncx - g.center.x) / w1).coerceIn(-hx, hx)
                                                fy = ((ncy - g.center.y) / h1).coerceIn(-hy, hy)
                                                zoom = nz
                                            }
                                        }
                                        prevC = cen
                                        prevD = dist
                                        prevN = n
                                        for (c in ev.changes) c.consume()
                                    }
                                    dragging = false
                                }
                            }
                    ) {
                        val lay = layoutOf(size, format, picLandscape, padOf(size))
                        drawFrameScene(
                            src = src,
                            lay = lay,
                            format = format,
                            zoom = zoom,
                            fx = fx,
                            fy = fy,
                            grid = grid,
                            dragging = dragging,
                            textA = textA,
                            textB = textB,
                        )
                    }

                    // 右下角：重置
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x66101114))
                            .iosTap(pressDim = 0.6f) {
                                zoom = 1f; fx = 0f; fy = 0f
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) { Text("重置", color = Color(0xE6FFFFFF), fontSize = 11.sp) }
                }

                // ---------- 片幅 ----------
                Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
                    Text(
                        "片幅",
                        color = Dv.label3, fontSize = 10.5.sp,
                        modifier = Modifier.padding(start = 16.dp, bottom = 5.dp)
                    )
                    // 每张卡片直接画出该片幅的片子：门多大、留多少边、有没有齿孔，
                    // 选之前就看得见 —— 以前只有一串文字，选完才知道是什么形状
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        osFormatList().forEach { f ->
                            FormatCard(
                                format = f,
                                selected = f == format,
                                picLandscape = picLandscape,
                                onClick = {
                                    if (f != format) {
                                        onFormat(f)
                                        // 换门幅，上一门摆的位置对不上新门
                                        zoom = 1f; fx = 0f; fy = 0f
                                    }
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "画面可拖到片门之外 · 双指任意位置缩放 · 缩到最小即铺满片门",
                        color = Dv.label3, fontSize = 10.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private const val OS_MAX_ZOOM = 8f

/** 取景台四周至少留这么多，好让门框的四角标不贴边 */
internal fun padOf(avail: Size): Float = min(avail.width, avail.height) * 0.045f

// ---------------- 几何 ----------------

/** 片门物理尺寸（mm）：长 × 短 */
internal fun osGateMm(format: String): Pair<Float, Float> = when (format) {
    "135_half" -> 24f to 18f
    "110" -> 17f to 13f
    "aps" -> 30.2f to 16.7f
    "135_xpan" -> 65f to 24f
    "120_645" -> 56f to 41.5f
    "120_6x6" -> 56f to 56f
    "120_6x7" -> 69.5f to 56f
    "120_6x8" -> 76f to 56f
    "120_6x9" -> 84f to 56f
    "120_6x12" -> 112f to 56f
    "120_6x17" -> 168f to 56f
    else -> 36.25f to 24.3f
}

/** 片门"横跨片基"的那一边（mm）。135 = 24.3，半格 / 宽幅 = 24，120 全系 = 56 */
internal fun osGateAcrossMm(format: String): Float = when (format) {
    "135_half", "135_xpan" -> 24f
    "110" -> 13f
    "aps" -> 16.7f
    else -> if (osIs120(format)) 56f else 24.3f
}

/** 片门"顺片"的那一边：长边短边里剩下的那个 */
internal fun osGateAlongMm(format: String): Float {
    val (l, s) = osGateMm(format)
    return if (abs(osGateAcrossMm(format) - l) < 0.01f) s else l
}

/** 片宽（横跨方向）：135 = 35 mm，120 = 61 mm —— 与 overscan.cpp 的 kFormats 一致 */
internal fun osFilmMm(format: String): Float = when {
    osIs120(format) -> 61f
    format == "110" -> 16f
    format == "aps" -> 24f
    else -> 35f
}

/**
 * 顺片方向每侧留多少片基（mm）。引擎 strip 模式抽的是 0.75–0.95（135）/
 * 0.40–0.50（半格）/ 1.3–2.1（120），这里取中值：预览要和成片一个比例，
 * 留宽了门就显小，留窄了齿孔会被切。
 */
internal fun osAlongMm(format: String): Float = when {
    osIs120(format) -> 1.7f
    format == "135_half" -> 0.45f
    format == "110" -> 0.45f
    format == "aps" -> 3.6f
    else -> 0.9f
}

/**
 * 片子横着走还是竖着走。
 *
 * 引擎的判据（overscan.cpp: L.vertical）是拿**画面宽度**去比该格式横跨片基的
 * 尺寸，谁近算谁：竖构图的 135 宽 24.3 ≈ 跨片 24.3，于是片子顺着画面的高走 ——
 * 片边在左右；横构图的 135 宽 36.25 不接近 24.3，片子就横过来，片边在上下。
 * 半格、645 的跨片尺寸正好是它们的长边，所以横构图也是竖着走。
 */
internal fun osVertical(format: String, picLandscape: Boolean): Boolean {
    val (l, s) = osGateMm(format)
    val across = osGateAcrossMm(format)
    val picW = if (picLandscape) l else s
    val picH = if (picLandscape) s else l
    return abs(picW - across) < abs(picH - across)
}

internal fun osFormatList(): List<String> = listOf(
    "135", "135_half", "135_xpan", "110", "aps",
    "120_645", "120_6x6", "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"
)

internal fun osFormatShort(format: String): String = when (format) {
    "135_half" -> "半格"
    "135_xpan" -> "宽幅"
    "110" -> "110"
    "aps" -> "APS"
    "120_645" -> "645"
    "120_6x6" -> "6×6"
    "120_6x7" -> "6×7"
    "120_6x8" -> "6×8"
    "120_6x9" -> "6×9"
    "120_6x12" -> "6×12"
    "120_6x17" -> "6×17"
    else -> "135"
}

/**
 * 反查：[osFormatShort] 的那个短名 → 格式 id。
 *
 * 相册记录里存的片边是**短名**（导出那一刻写进 ExportRecord.edge 的就是它），
 * 而几何表用的是格式 id。取景器要把门洞从成片里抠出来，就得先把短名翻回去。
 * 不认识的（包括"没开片边"的 "—"）返回 null —— 那时整张图就是画面。
 */
internal fun osFormatOfShort(short: String): String? = when (short) {
    "135" -> "135"
    "半格" -> "135_half"
    "宽幅" -> "135_xpan"
    "110" -> "110"
    "APS" -> "aps"
    "645" -> "120_645"
    "6×6" -> "120_6x6"
    "6×7" -> "120_6x7"
    "6×8" -> "120_6x8"
    "6×9" -> "120_6x9"
    "6×12" -> "120_6x12"
    "6×17" -> "120_6x17"
    else -> null
}

/** 顶栏那句：一眼看清现在选的是哪个模板 */
internal fun osFormatLabel(format: String): String = when (format) {
    "110" -> "110 13×17"
    "aps" -> "APS 16.7×30"
    else -> when {
        osIs120(format) -> "120 ${osFormatShort(format)}"
        format == "135" -> "135"
        else -> "135 ${osFormatShort(format)}"
    }
}

internal fun osIs120(format: String): Boolean = format.startsWith("120")

/**
 * 门幅裁切窗（归一化到源图）。和 MainActivity 里下发 `geometry_crop_*` 用的是
 * 同一套算法，必须一致 —— 预览里门洞框住的地方就是引擎真正会裁到的地方。
 *
 * 返回 (cropW, cropH)：窗口占源图宽/高的比例，长边尽量铺满。
 */
internal fun osBaseCrop(w: Double, h: Double, format: String): Pair<Double, Double> {
    if (w <= 0.0 || h <= 0.0) return 1.0 to 1.0
    val gate = osGateRatio(format)
    val cur = max(w, h) / min(w, h)
    if (abs(cur / gate - 1.0) <= 0.05) return 1.0 to 1.0
    return if (cur > gate) {
        // 画面比门幅更扁：裁长边
        val tl = min(w, h) * gate
        if (w >= h) (tl / w) to 1.0 else 1.0 to (tl / h)
    } else {
        // 门幅比画面更扁：裁短边
        val ts = max(w, h) / gate
        if (w >= h) 1.0 to (ts / h) else (ts / w) to 1.0
    }
}

/**
 * 一块"成片画布"的摆位。
 *
 * [canvas] 就是最后那张图的构图（片基 + 门洞），[gate] 是门洞。两者都是
 * 真实毫米乘同一个 k，所以预览里的比例关系和成片一模一样。
 */
internal class FrameLayout(
    val canvas: Rect,
    val gate: Rect,
    val pxPerMm: Float,
    val vertical: Boolean,
)

internal fun layoutOf(avail: Size, format: String, picLandscape: Boolean, pad: Float): FrameLayout {
    val across = osGateAcrossMm(format)
    val along = osGateAlongMm(format)
    val m = osAlongMm(format)
    val film = osFilmMm(format)
    val vertical = osVertical(format, picLandscape)

    // 画布：顺片方向 = 门 + 两侧留白；横跨方向 = 整条片宽
    val cwMm = if (vertical) film else along + m * 2f
    val chMm = if (vertical) along + m * 2f else film
    val k = min(
        (avail.width - pad * 2f) / cwMm,
        (avail.height - pad * 2f) / chMm
    ).coerceAtLeast(0.01f)

    val left = (avail.width - cwMm * k) / 2f
    val top = (avail.height - chMm * k) / 2f
    val canvas = Rect(left, top, left + cwMm * k, top + chMm * k)

    val gw = (if (vertical) across else along) * k
    val gh = (if (vertical) along else across) * k
    val gate = Rect(
        canvas.center.x - gw / 2f, canvas.center.y - gh / 2f,
        canvas.center.x + gw / 2f, canvas.center.y + gh / 2f
    )
    return FrameLayout(canvas, gate, if (vertical) gh / along else gw / along, vertical)
}

// ---------------- 片幅卡片 ----------------

/**
 * 一个片幅 = 一张小样片。
 *
 * 卡片里画的就是这个片幅的片子：整条片宽、门、两边的留白、135 的齿孔。
 * 尺寸全部走 layoutOf，所以卡片和取景台是同一套几何，不会各说各话。
 */
@Composable
private fun FormatCard(
    format: String,
    selected: Boolean,
    picLandscape: Boolean,
    onClick: () -> Unit,
) {
    val ink = Color(0xFFE9E7E1)
    Box(
        Modifier.width(68.dp).height(74.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Orange.copy(alpha = 0.20f) else Color(0x14FFFFFF))
            .iosTap(pressDim = 0.7f, haptic = true) { onClick() }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val padL = 7.dp.toPx()
            val padT = 8.dp.toPx()
            val avail = Size(size.width - padL * 2f, size.height - padT - 18.dp.toPx())
            val lay = layoutOf(avail, format, picLandscape, 0f)
            val dx = padL
            val dy = padT

            // 片子：灰白底 + 一圈描边
            val c = lay.canvas.translate(dx, dy)
            drawRoundRect(
                color = ink.copy(alpha = if (selected) 0.26f else 0.16f),
                topLeft = c.topLeft, size = c.size,
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            drawRoundRect(
                color = ink.copy(alpha = if (selected) 0.55f else 0.30f),
                topLeft = c.topLeft, size = c.size,
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(0.8.dp.toPx())
            )
            // 门洞：挖空的样子（卡片底色够黑，直接盖一块底色）
            val g = lay.gate.translate(dx, dy)
            drawRect(Color(0xFF121216), g.topLeft, g.size)

            // 135 的齿孔：卡片太小，画成两排短刻线
            if (osPerforated(format)) {
                val pxmm = lay.pxPerMm
                val tw = max(0.7f, osPerfW(format) * pxmm)
                val th = max(0.7f, 0.9f * pxmm)
                val pitch = osPerfPitch(format) * pxmm
                val holes = ink.copy(alpha = if (selected) 0.75f else 0.45f)
                if (lay.vertical) {
                    var y = c.top + 1.2f * pxmm
                    while (y + th < c.bottom - 1.2f * pxmm) {
                        drawRect(holes, Offset(c.left + 0.9f * pxmm, y), Size(th, tw))
                        drawRect(holes, Offset(c.right - 0.9f * pxmm - th, y), Size(th, tw))
                        y += pitch
                    }
                } else {
                    var x = c.left + 1.2f * pxmm
                    while (x + tw < c.right - 1.2f * pxmm) {
                        drawRect(holes, Offset(x, c.top + 0.9f * pxmm), Size(tw, th))
                        drawRect(holes, Offset(x, c.bottom - 0.9f * pxmm - th), Size(tw, th))
                        x += pitch
                    }
                }
            }
        }
        Text(
            osFormatShort(format),
            color = if (selected) Orange else Dv.label1,
            fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
        )
    }
}

// ---------------- 绘制 ----------------

/** 片基的灰白。取景页是"模板"，不是成片，所以整套都用这一个中性色 */
private val TemplateInk = Color(0xFFE9E7E1)

/**
 * 先画画面，再压片边模板。
 *
 * 顺序是有讲究的：门外那些像素不会进成片，所以压暗；片基是半透明的灰白，画面
 * 透过它仍旧看得见（拖到边界时才不会"画面凭空消失"）；齿孔与边码压在片基上；
 * 最后是门洞的白虚线、四角标和三分线 —— 那才是"裁到哪"的答案。
 */
private fun DrawScope.drawFrameScene(
    src: ImageBitmap,
    lay: FrameLayout,
    format: String,
    zoom: Float,
    fx: Float,
    fy: Float,
    grid: Boolean,
    dragging: Boolean,
    textA: TextLayoutResult,
    textB: TextLayoutResult,
) {
    val c = lay.canvas
    val g = lay.gate
    val is120 = osIs120(format)
    val pxmm = lay.pxPerMm

    // ---- 画面：按自由变换铺在下层 ----
    val k0 = max(g.width / src.width, g.height / src.height)
    val k = k0 * zoom
    val w = src.width * k
    val h = src.height * k
    val left = g.center.x + (fx - 0.5f) * w
    val top = g.center.y + (fy - 0.5f) * h
    drawImageRegion(src, left, top, w, h, if (dragging) FilterQuality.Medium else FilterQuality.High)

    // ---- 门外压暗：那部分像素不进成片，但要看得出画面伸到哪 ----
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(0f, 0f, size.width, size.height))
        addRoundRect(RoundRect(g, CornerRadius(1.dp.toPx())))
    }
    drawPath(outside, Color(0x60000000))

    // ---- 片基：灰白半透明，门洞挖空 ----
    val plate = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(c, CornerRadius(2.dp.toPx())))
        addRoundRect(RoundRect(g, CornerRadius(1.dp.toPx())))
    }
    drawPath(plate, TemplateInk.copy(alpha = 0.10f))
    drawRoundRect(
        color = TemplateInk.copy(alpha = 0.32f),
        topLeft = c.topLeft, size = c.size,
        cornerRadius = CornerRadius(2.dp.toPx()),
        style = Stroke(0.9.dp.toPx())
    )

    // ---- 齿孔与边码 ----
    // 2 mm 是引擎的 perf_edge，孔 1.98 × 2.80，孔距 4.75；边码印在最外侧那 2 mm 里
    val edge = 2.0f * pxmm
    if (osPerforated(format)) drawPerforations(c, lay.vertical, pxmm, format)
    // 边码占的带宽：135 是外侧 2 mm；120 的顺片留白只有 1.7 mm，取小的那个，
    // 免得字被裁进画面里
    val band = min(edge, if (lay.vertical) (c.width - g.width) / 2f else (c.height - g.height) / 2f)
    drawEdgePrint(c, lay.vertical, band, textA, textB)

    // ---- 门洞：白虚线 + 四角标 + 三分线 ----
    val dashW = 1.1.dp.toPx()
    val dash = 5.dp.toPx()
    val gap = 4.dp.toPx()
    val dark = Color(0x66000000)
    val light = Color(0xF2FFFFFF)
    // 先垫一层暗线：亮天空上纯白虚线会糊掉，垫一层才分得清
    dashRect(g, dash, gap, dashW + 1.2.dp.toPx(), dark)
    dashRect(g, dash, gap, dashW, light)

    if (grid) {
        val line = Color(0x2EFFFFFF)
        for (i in 1..2) {
            val gx = g.left + g.width * i / 3f
            val gy = g.top + g.height * i / 3f
            drawLine(line, Offset(gx, g.top), Offset(gx, g.bottom), 0.8.dp.toPx())
            drawLine(line, Offset(g.left, gy), Offset(g.right, gy), 0.8.dp.toPx())
        }
    }

    // 四角标：门洞的角压在画面里不好找，用 L 形短角提出来
    val tick = 16.dp.toPx()
    val tw = 2.2.dp.toPx()
    for ((ax, ay, sx, sy) in listOf(
        floatArrayOf(g.left, g.top, 1f, 1f),
        floatArrayOf(g.right, g.top, -1f, 1f),
        floatArrayOf(g.left, g.bottom, 1f, -1f),
        floatArrayOf(g.right, g.bottom, -1f, -1f),
    )) {
        drawLine(light, Offset(ax, ay), Offset(ax + tick * sx, ay), tw)
        drawLine(light, Offset(ax, ay), Offset(ax, ay + tick * sy), tw)
    }
}

/** 片幅有没有齿孔：120 是纸背、APS 是磁涂层，都没有 */
internal fun osPerforated(format: String): Boolean = !osIs120(format) && format != "aps"

/** 孔距（mm）：110 每格一个孔，135 是 4.75（8 孔一格） */
internal fun osPerfPitch(format: String): Float = if (format == "110") 17f else 4.75f
internal fun osPerfW(format: String): Float = if (format == "110") 2.40f else 1.98f
internal fun osPerfH(format: String): Float = if (format == "110") 2.00f else 2.80f
internal fun osPerfEdge(format: String): Float = if (format == "110") 1.60f else 2.00f
internal fun osPerfR(format: String): Float = if (format == "110") 0.60f else 0.50f

/** 齿孔：135 1.98 × 2.80 / 孔距 4.75 / 距边 2.00；110 每格一个 2.40 × 2.00 / 距边 1.60 */
private fun DrawScope.drawPerforations(c: Rect, vertical: Boolean, pxmm: Float, format: String) {
    val fill = TemplateInk.copy(alpha = 0.20f)
    val line = TemplateInk.copy(alpha = 0.55f)
    val stroke = 0.8.dp.toPx()
    val pitch = osPerfPitch(format) * pxmm
    val w = osPerfW(format) * pxmm
    val h = osPerfH(format) * pxmm
    val r = CornerRadius(osPerfR(format) * pxmm)
    val edge = osPerfEdge(format) * pxmm
    val margin = (pitch * 0.25f).coerceAtLeast(0.8f * pxmm)

    fun hole(x: Float, y: Float, ww: Float, hh: Float) {
        drawRoundRect(fill, Offset(x, y), Size(ww, hh), r)
        drawRoundRect(line, Offset(x, y), Size(ww, hh), r, style = Stroke(stroke))
    }
    if (!vertical) {
        var x = c.left + margin
        var guard = 0
        while (x + w < c.right - margin * 0.6f && guard++ < 200) {
            hole(x, c.top + edge, w, h)
            hole(x, c.bottom - edge - h, w, h)
            x += pitch
        }
    } else {
        var y = c.top + margin
        var guard = 0
        while (y + w < c.bottom - margin * 0.6f && guard++ < 200) {
            hole(c.left + edge, y, h, w)
            hole(c.right - edge - h, y, h, w)
            y += pitch
        }
    }
}

/**
 * 边码：印在片基最外侧那条窄带里（引擎里 baseline = film_w − 0.43 mm）。
 *
 * 字号以前是固定的 7 sp，位置按"带子正好是 2 mm"硬算 —— 只在 135 上碰巧
 * 成立。片幅一变就不对：6×17 的画布有 171 mm，铺在同一块屏幕上 1 mm 只剩
 * 五六个像素，7 sp 的字高已经是那条带子的两倍，字就顶到片条外面去了；
 * 竖着走的片子更糟，原来那条分支连裁剪都没有，字会一直画到屏幕上。
 *
 * 现在尺寸全部由带子自己推：字高不超过带宽的 78%，长度不超过片条可用长度，
 * 再按 90° 的那一侧换算位置。四个方向都裁在带内兜底，任何片幅都不可能出界。
 */
private fun DrawScope.drawEdgePrint(
    c: Rect,
    vertical: Boolean,
    band: Float,
    textA: TextLayoutResult,
    textB: TextLayoutResult,
) {
    if (band <= 0f || c.width <= 0f || c.height <= 0f) return
    if (!vertical) {
        // 上带：厂商与片幅；下带：帧号
        drawEdgeInBand(c, c.top, band, textA)
        drawEdgeInBand(c, c.bottom - band, band, textB)
    } else {
        // 片子竖着走：字转 90° 压在左右两条带里
        drawEdgeInColumn(c, c.left, band, textA)
        drawEdgeInColumn(c, c.right - band, band, textB)
    }
}

/** 横着走的片子：字沿长边排，落在 [bandTop, bandTop + band] 这条带里 */
private fun DrawScope.drawEdgeInBand(c: Rect, bandTop: Float, band: Float, t: TextLayoutResult) {
    if (t.size.width <= 0f || t.size.height <= 0f) return
    val pad = band * 0.16f
    val s = min(1f, min((c.width - pad * 2f) / t.size.width, band * 0.78f / t.size.height))
    if (s <= 0f) return
    val x0 = c.left + pad
    val y0 = bandTop + (band - t.size.height * s) / 2f
    clipRect(c.left, bandTop, c.right, bandTop + band) {
        withTransform({
            translate(x0, y0)
            scale(s, s, Offset.Zero)
        }) { drawText(t, topLeft = Offset.Zero) }
    }
}

/** 竖着走的片子：字顺时针转 90°，落在 [bandLeft, bandLeft + band] 这条带里 */
private fun DrawScope.drawEdgeInColumn(c: Rect, bandLeft: Float, band: Float, t: TextLayoutResult) {
    if (t.size.width <= 0f || t.size.height <= 0f) return
    val pad = band * 0.16f
    val s = min(1f, min((c.height - pad * 2f) / t.size.width, band * 0.78f / t.size.height))
    if (s <= 0f) return
    // 转 90° 之后，字的"高"变成横向厚度 th，字的"长"变成纵向长度
    val th = t.size.height * s
    clipRect(bandLeft, c.top, bandLeft + band, c.bottom) {
        withTransform({
            translate(bandLeft + (band + th) / 2f, c.top + pad)
            rotate(90f, Offset.Zero)
            scale(s, s, Offset.Zero)
        }) { drawText(t, topLeft = Offset.Zero) }
    }
}

/** 虚线矩形：两遍（先暗后亮）才在亮画面和暗画面上都读得出来 */
private fun DrawScope.dashRect(r: Rect, dash: Float, gap: Float, w: Float, color: Color) {
    fun seg(a: Offset, b: Offset) {
        val len = (b - a).getDistance()
        if (len <= 0.5f) return
        val dir = (b - a) / len
        var t = 0f
        while (t < len) {
            val t2 = min(t + dash, len)
            drawLine(color, a + dir * t, a + dir * t2, w)
            t = t2 + gap
        }
    }
    seg(Offset(r.left, r.top), Offset(r.right, r.top))
    seg(Offset(r.right, r.top), Offset(r.right, r.bottom))
    seg(Offset(r.right, r.bottom), Offset(r.left, r.bottom))
    seg(Offset(r.left, r.bottom), Offset(r.left, r.top))
}

/**
 * 画一整张图到给定矩形，但只提交屏幕上看得见的那一块。
 *
 * 放大到 8 倍时目标矩形是视口的八倍，交给 Skia 去裁是白费力气；按可见区反算
 * 源矩形，采样量直接降到一屏。
 */
private fun DrawScope.drawImageRegion(
    img: ImageBitmap,
    left: Float,
    top: Float,
    w: Float,
    h: Float,
    quality: FilterQuality,
) {
    if (w <= 0f || h <= 0f) return
    val x0 = max(0f, left)
    val y0 = max(0f, top)
    val x1 = min(size.width, left + w)
    val y1 = min(size.height, top + h)
    val dw = x1 - x0
    val dh = y1 - y0
    if (dw < 1f || dh < 1f) return

    val iw = img.width
    val ih = img.height
    if (iw <= 0 || ih <= 0) return
    val sx = ((x0 - left) / w * iw).roundToInt().coerceIn(0, iw - 1)
    val sy = ((y0 - top) / h * ih).roundToInt().coerceIn(0, ih - 1)
    val sw = (dw / w * iw).roundToInt().coerceIn(1, iw - sx)
    val sh = (dh / h * ih).roundToInt().coerceIn(1, ih - sy)

    drawImage(
        image = img,
        srcOffset = IntOffset(sx, sy),
        srcSize = IntSize(sw, sh),
        dstOffset = IntOffset(x0.roundToInt(), y0.roundToInt()),
        dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
        filterQuality = quality,
    )
}
