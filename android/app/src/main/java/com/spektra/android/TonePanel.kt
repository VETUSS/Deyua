package com.spektra.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 「影调」页（r33）—— Layer 2 扫描侧调色的界面。
 *
 * 为什么单独开一页而不是并进「调校」：
 * 调校页里那十几个控件改变的是**冲洗本身**（重冲负片、重印相纸，一次几百毫秒到几秒），
 * 而这一页改的是**扫描之后的照片**，与冲洗无关，拖动是即时的。两者手感完全不同，
 * 混在一页里用户没法判断"我这一下到底动了什么、为什么要等"。
 *
 * 版面分两块：
 *   · 上半是**曲线**，固定不滚 —— 它是这一页的视觉核心，也是唯一需要"看着调"的东西，
 *     滚走之后就没有参照了；
 *   · 下半是四组滑杆，可滚：白平衡 / 影调 / 色彩 / 暗角。
 *
 * 节标题右侧的小圆点在**该组有改动时点亮**。十八个参数平铺在一个可滚列表里，
 * 没有这个指示，用户根本不知道自己在哪几组动过手。
 */
@Composable
fun ToneTab(
    cfg: Layer2.Cfg,
    hist: Layer2.Hist?,
    onChange: (Layer2.Cfg) -> Unit,
    onCommit: () -> Unit,
    reduceMotion: Boolean,
) {
    var open by remember { mutableStateOf(setOf(0)) }
    fun toggle(i: Int) { open = if (i in open) open - i else open + i }

    // 曲线当前编辑哪条通道：0=RGB 1=R 2=G 3=B
    var chan by remember { mutableIntStateOf(0) }
    val points = when (chan) {
        1 -> cfg.curveR
        2 -> cfg.curveG
        3 -> cfg.curveB
        else -> cfg.curveRgb
    }
    val setPoints: (List<Float>) -> Unit = { np ->
        onChange(
            when (chan) {
                1 -> cfg.copy(curveR = np)
                2 -> cfg.copy(curveG = np)
                3 -> cfg.copy(curveB = np)
                else -> cfg.copy(curveRgb = np)
            }
        )
    }
    val chanTint = when (chan) {
        1 -> Color(0xFFFF7A6E)
        2 -> Color(0xFF7ED08A)
        3 -> Color(0xFF7EA8FF)
        else -> Dv.accent
    }

    // 曲线高度按可用高度自适应（上限 150dp，约占面板 42%）。
    // 控制台是能拖的：用户把它拖到半开以下时，固定 170dp 的曲线会把下面的滑杆区
    // 挤成 0 高 —— 曲线还在、什么都调不了，这种"看得见摸不着"比裁掉更糟。
    BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
    val curveH = if (maxHeight * 0.42f < 150.dp) maxHeight * 0.42f else 150.dp
    Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 12.dp)) {

        // ================= 曲线 =================
        // 一行标题带通道切换：RGB / R / G / B。通道色直接用在曲线和控制点上，
        // 切到红通道时整条线变红 —— 这是"你在改哪条曲线"最省字的说法。
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("曲线", color = Dv.paper, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp)
            Spacer(Modifier.width(10.dp))
            listOf("RGB" to 0, "R" to 1, "G" to 2, "B" to 3).forEach { (label, i) ->
                DvChip(label, chan == i, { chan = i }, size = 10)
            }
            Spacer(Modifier.weight(1f))
            // 复位按钮常驻（未改动时压暗、不可点）。只在 dirty 时出现的话，
            // 标题行会随着"有没有动过"忽宽忽窄，右边的通道 chip 跟着跳。
            val dirty = points != Layer2.LINEAR
            Box(Modifier.clip(RoundedCornerShape(Dv.rChip))
                .background(if (dirty) Dv.raisedHigh else Color.Transparent)
                .iosTap(enabled = dirty, pressScale = 0.94f, pressDim = 0.8f, haptic = true) {
                    if (dirty) { setPoints(Layer2.LINEAR); onCommit() }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("复位", color = if (dirty) Dv.label2 else Dv.label4, fontSize = 10.5f.sp)
            }
        }

        CurveEditor(
            points = points,
            hist = hist,
            chan = chan,
            tint = chanTint,
            height = curveH,
            onChange = setPoints,
            onCommit = onCommit,
        )

        Text(
            if (points == Layer2.LINEAR)
                "五个控制点沿对角线 = 不改动。往上拖提亮、往下拖压暗；曲线永远单调，不会打结"
            else
                "已调整 · 单调三次插值：拉得再陡也不会出反相的亮边",
            color = if (points == Layer2.LINEAR) Dv.label4 else chanTint.copy(alpha = 0.75f),
            fontSize = 9.5f.sp, lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp, bottom = 4.dp),
        )
        Hairline(alpha = 0.07f)

        // ================= 滑杆区 =================
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 2.dp)
        ) {
            // ---------- 0 · 白平衡 ----------
            Section("白平衡", "white balance", 0, 0 in open, ::toggle,
                trailing = { Dot(cfg.touched(0)) }) {
                Text("与冲洗出来的色温叠乘，不重新冲洗 —— 拖动是实时的",
                    color = Dv.label3, fontSize = 10f.sp, lineHeight = 14.sp,
                    modifier = Modifier.padding(bottom = 2.dp))
                ParamBlock("色温", "%+.0f".format(cfg.temp), note = "← 冷 / 暖 →") {
                    TickerSlider(value = cfg.temp, onValueChange = { onChange(cfg.copy(temp = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("色调", "%+.0f".format(cfg.tint), note = "← 绿 / 品红 →") {
                    TickerSlider(value = cfg.tint, onValueChange = { onChange(cfg.copy(tint = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
            }

            // ---------- 1 · 影调 ----------
            Section("影调", "tonal", 1, 1 in open, ::toggle,
                trailing = { Dot(cfg.touched(1)) }) {
                ParamBlock("曝光", "%+.2f EV".format(cfg.exposure), note = "−2…+2") {
                    TickerSlider(value = cfg.exposure, onValueChange = { onChange(cfg.copy(exposure = it)) },
                        onValueChangeFinished = onCommit, valueRange = -2f..2f, snapStep = 0.05f,
                        majorStep = 1f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("对比", "%+.0f".format(cfg.contrast)) {
                    TickerSlider(value = cfg.contrast, onValueChange = { onChange(cfg.copy(contrast = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("亮度", "%+.0f".format(cfg.brightness)) {
                    TickerSlider(value = cfg.brightness, onValueChange = { onChange(cfg.copy(brightness = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("高光", "%+.0f".format(cfg.highlights), note = "只作用于亮部") {
                    TickerSlider(value = cfg.highlights, onValueChange = { onChange(cfg.copy(highlights = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("阴影", "%+.0f".format(cfg.shadows), note = "只作用于暗部") {
                    TickerSlider(value = cfg.shadows, onValueChange = { onChange(cfg.copy(shadows = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("黑场", "%+.0f".format(cfg.blacks), note = "＋压黑 / −褪色") {
                    TickerSlider(value = cfg.blacks, onValueChange = { onChange(cfg.copy(blacks = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("白场", "%+.0f".format(cfg.whites), note = "＋提白 / −压白") {
                    TickerSlider(value = cfg.whites, onValueChange = { onChange(cfg.copy(whites = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
            }

            // ---------- 2 · 色彩 ----------
            Section("色彩", "colour", 2, 2 in open, ::toggle,
                trailing = { Dot(cfg.touched(2)) }) {
                ParamBlock("饱和度", "%+.0f".format(cfg.saturation), note = "−100 = 黑白") {
                    TickerSlider(value = cfg.saturation, onValueChange = { onChange(cfg.copy(saturation = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                Spacer(Modifier.height(6.dp))
                // 三区色彩平衡：阴影 / 中间调 / 高光各一套「色相 · 饱和 · 明度」。
                // 用三档 chip 切换而不是九根滑杆并排，否则这一组会占掉两屏。
                ColorBalance(cfg, onChange, onCommit, reduceMotion)
            }

            // ---------- 3 · 暗角 ----------
            Section("暗角", "vignette", 3, 3 in open, ::toggle,
                trailing = { Dot(cfg.touched(4)) }) {
                ParamBlock("强度", "%+.0f".format(cfg.vignette), note = "＋压暗四角 / −提亮") {
                    TickerSlider(value = cfg.vignette, onValueChange = { onChange(cfg.copy(vignette = it)) },
                        onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
                        majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("中点", "%.0f".format(cfg.vignetteMid), note = "越大，暗角越靠边") {
                    TickerSlider(value = cfg.vignetteMid,
                        onValueChange = { onChange(cfg.copy(vignetteMid = it)) },
                        onValueChangeFinished = onCommit, valueRange = 5f..95f, snapStep = 1f,
                        majorStep = 20f, reduceMotion = reduceMotion, default = 50f)
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }
    }
}

// ---------------------------------------------------------------- 曲线编辑器

/**
 * 曲线编辑器。
 *
 * 交互设计的三条决定：
 *
 *  1. **只有 y 可动**。五个控制点的 x 固定在 0.167 / 0.333 / 0.5 / 0.667 / 0.833。
 *     允许自由加点、拖 x 的编辑器（Lightroom 那种）在手机上一次误触就会把点拖得
 *     前后颠倒，然后整条曲线打结 —— 这不是这个 App 的主要功能，不值得为它冒这个险。
 *
 *  2. **按下即选、松手提交**。命中半径 26dp（比视觉上的圆点大得多），因为手指按在
 *     一个 8dp 的圆上几乎不可能精确。拖动过程中曲线实时跟着走，不弹窗、不确认。
 *
 *  3. **直方图当前景而不是背景**。它是这张照片自己的亮度分布，用户拖控制点时看的
 *     就是"我把这一段推到了哪里"，所以要能看清形状；用 12% 的白填充，压在网格之上、
 *     曲线之下。
 */
@Composable
private fun CurveEditor(
    points: List<Float>,
    hist: Layer2.Hist?,
    chan: Int,
    tint: Color,
    height: androidx.compose.ui.unit.Dp,
    onChange: (List<Float>) -> Unit,
    onCommit: () -> Unit,
) {
    val d = LocalDensity.current
    val ctx = LocalContext.current
    val pad = with(d) { 9.dp.toPx() }
    val hit = with(d) { 26.dp.toPx() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var dragIdx by remember { mutableIntStateOf(-1) }

    // ⚠ 手势的 key 必须是 Unit，不能是 points。
    //
    // 拖动的每一帧 onChange 都会生成一个新的 points 列表；如果把它写进 pointerInput 的
    // key，Compose 会认为"检测器该重建了"，于是把正在进行的那次 drag 取消掉 ——
    // 表现就是"按住一个点只能拖动一小段，然后手势断掉，得重新按"。
    // 这里改成 key=Unit（装一次，永不重启），points / 尺寸 / 回调都通过 State 读取最新值。
    val pts by rememberUpdatedState(points)
    val boxNow by rememberUpdatedState(box)
    val emit by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)

    // 直方图数据：RGB 通道看亮度，单通道看自己。归一化后只留形状。
    val bars: FloatArray? = remember(hist, chan) {
        if (hist == null) null else {
            val src = when (chan) { 1 -> hist.r; 2 -> hist.g; 3 -> hist.b; else -> hist.l }
            val a = FloatArray(256) { src[it].toFloat() }
            a[255] = 0f
            val mx = a.maxOrNull() ?: 0f
            if (mx <= 0f) null else FloatArray(256) { a[it] / mx }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Dv.root)
            .onSizeChanged { box = it },
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(Unit) {
                    if (boxNow.width == 0) return@pointerInput
                    fun xOf(i: Int) = pad + (boxNow.width - 2 * pad) * (i + 1f) / (Layer2.CP + 1)
                    fun yOf(v: Float) = pad + (boxNow.height - 2 * pad) * (1f - v)
                    fun nearest(p: Offset): Int {
                        var best = -1
                        var bestD = hit
                        for (i in 0 until Layer2.CP) {
                            val dx = p.x - xOf(i)
                            val dy = p.y - yOf(pts.getOrElse(i) { Layer2.LINEAR[i] })
                            val dd = sqrt(dx * dx + dy * dy)
                            if (dd < bestD) { bestD = dd; best = i }
                        }
                        return best
                    }
                    fun valueAt(y: Float): Float {
                        val h = (boxNow.height - 2 * pad).toFloat()
                        if (h <= 0f) return 0.5f
                        return (1f - (y - pad) / h).coerceIn(0f, 1f)
                    }
                    detectDragGestures(
                        onDragStart = { pos ->
                            val i = nearest(pos)
                            dragIdx = i
                            if (i >= 0) {
                                Haptics.tick(ctx)
                                val np = pts.toMutableList()
                                np[i] = valueAt(pos.y)
                                emit(np)
                            }
                        },
                        onDrag = { change, _ ->
                            val i = dragIdx
                            if (i >= 0) {
                                change.consume()
                                val np = pts.toMutableList()
                                np[i] = valueAt(change.position.y)
                                emit(np)
                            }
                        },
                        onDragEnd = { if (dragIdx >= 0) commit(); dragIdx = -1 },
                        onDragCancel = { dragIdx = -1 },
                    )
                },
        ) {
            val l = pad
            val t = pad
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            if (w <= 0 || h <= 0) return@Canvas

            // ---- 直方图 ----
            if (bars != null) {
                val p = Path().apply {
                    moveTo(l, t + h)
                    for (i in 0..255) {
                        val x = l + w * i / 255f
                        val v = bars[i].coerceIn(0f, 1f)
                        lineTo(x, t + h * (1f - v))
                    }
                    lineTo(l + w, t + h)
                    close()
                }
                drawPath(p, Color.White.copy(alpha = 0.13f))
            }

            // ---- 网格 ----
            for (i in 1..3) {
                val gx = l + w * i / 4f
                drawLine(Color.White.copy(alpha = 0.055f), Offset(gx, t), Offset(gx, t + h), 1f)
                val gy = t + h * i / 4f
                drawLine(Color.White.copy(alpha = 0.055f), Offset(l, gy), Offset(l + w, gy), 1f)
            }
            // ---- 参考对角线（未调时的样子）----
            drawLine(Color.White.copy(alpha = 0.12f), Offset(l, t + h), Offset(l + w, t),
                1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f), 0f))

            // ---- 曲线本体 ----
            val lut = Layer2.curveLut(points)
            val path = Path().apply {
                moveTo(l, t + h * (1f - lut[0]))
                for (i in 1..255) lineTo(l + w * i / 255f, t + h * (1f - lut[i]))
            }
            // 一层很淡的同色光晕，让曲线在暗背景上有"发光"的层次（发丝线的反面：这里是主角）
            drawPath(path, tint.copy(alpha = 0.16f),
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, tint,
                style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // ---- 控制点 ----
            for (i in 0 until Layer2.CP) {
                val cx = l + w * (i + 1f) / (Layer2.CP + 1)
                val cy = t + h * (1f - points.getOrElse(i) { Layer2.LINEAR[i] })
                val active = i == dragIdx
                if (active) drawCircle(tint.copy(alpha = 0.22f), radius = 13f, center = Offset(cx, cy))
                drawCircle(Dv.root, radius = if (active) 7.5f else 5.5f, center = Offset(cx, cy))
                drawCircle(tint, radius = if (active) 5f else 3.4f, center = Offset(cx, cy))
            }
        }
    }
}

// ---------------------------------------------------------------- 色彩平衡

/**
 * 三区色彩平衡：阴影 / 中间调 / 高光，各一套「色相 · 饱和 · 明度」。
 *
 * 用一张三档 chip 切换，而不是九根滑杆并排。九个滑杆在这个宽度上每根只有
 * 两三厘米行程，读数全是 ±0，等于没做。切档之后每组三根，行程够、也看得懂。
 */
@Composable
private fun ColorBalance(
    cfg: Layer2.Cfg,
    onChange: (Layer2.Cfg) -> Unit,
    onCommit: () -> Unit,
    reduceMotion: Boolean,
) {
    var zone by remember { mutableIntStateOf(1) }   // 0 阴影 1 中间调 2 高光
    val hue = when (zone) { 0 -> cfg.shHue; 2 -> cfg.hiHue; else -> cfg.mdHue }
    val sat = when (zone) { 0 -> cfg.shSat; 2 -> cfg.hiSat; else -> cfg.mdSat }
    val lum = when (zone) { 0 -> cfg.shLum; 2 -> cfg.hiLum; else -> cfg.mdLum }
    fun put(h: Float = hue, s: Float = sat, lu: Float = lum): Layer2.Cfg = when (zone) {
        0 -> cfg.copy(shHue = h, shSat = s, shLum = lu)
        2 -> cfg.copy(hiHue = h, hiSat = s, hiLum = lu)
        else -> cfg.copy(mdHue = h, mdSat = s, mdLum = lu)
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("色彩平衡", color = Dv.label2, fontSize = 12.5f.sp, letterSpacing = 0.4.sp)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            DvChip("阴影", zone == 0, { zone = 0 }, size = 10)
            DvChip("中间调", zone == 1, { zone = 1 }, size = 10)
            DvChip("高光", zone == 2, { zone = 2 }, size = 10)
        }
    }
    // 当前档的三项是否动过 —— 用一行极小的字说明，比给每个 chip 加角标干净
    val zoneDirty = abs(hue) + abs(sat) + abs(lum) > 0.01f
    if (zoneDirty) {
        Text("这一档已调整", color = Dv.accent.copy(alpha = 0.7f), fontSize = 9f.sp,
            modifier = Modifier.padding(start = 2.dp, top = 1.dp))
    }
    Spacer(Modifier.height(2.dp))
    ParamBlock("色相", "%+.0f".format(hue), note = "绕灰轴旋转") {
        TickerSlider(value = hue, onValueChange = { onChange(put(h = it)) },
            onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
            majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
    }
    ParamBlock("饱和", "%+.0f".format(sat)) {
        TickerSlider(value = sat, onValueChange = { onChange(put(s = it)) },
            onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
            majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
    }
    ParamBlock("明度", "%+.0f".format(lum)) {
        TickerSlider(value = lum, onValueChange = { onChange(put(lu = it)) },
            onValueChangeFinished = onCommit, valueRange = -100f..100f, snapStep = 1f,
            majorStep = 25f, reduceMotion = reduceMotion, default = 0f)
    }
}

/** 节标题右侧的"这一组动过"指示：6dp 实心琥珀点，未动时留同宽空白以保持对齐。 */
@Composable
private fun Dot(active: Boolean) {
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size(5.dp).clip(CircleShape).background(Dv.accent))
    }
}
