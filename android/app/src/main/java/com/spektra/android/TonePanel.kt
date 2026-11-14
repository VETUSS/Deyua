package com.spektra.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 「影调」页（r33 / 曲线 r43.1）—— Layer 2 扫描侧调色的界面。
 *
 * 为什么单独开一页而不是并进「调校」：
 * 调校页里那十几个控件改变的是**冲洗本身**（重冲负片、重印相纸，一次几百毫秒到几秒），
 * 而这一页改的是**扫描之后的照片**，与冲洗无关，拖动是即时的。两者手感完全不同，
 * 混在一页里用户没法判断"我这一下到底动了什么、为什么要等"。
 *
 * 版面分两块：
 *   · 上半是**曲线** —— 这一页的视觉核心，也是唯一需要"看着调"的东西；
 *   · 下半是四组滑杆，可滚：白平衡 / 影调 / 色彩 / 暗角。
 *
 * r43.1 曲线区不再是钉死的：往下翻（去看白平衡、色彩）时它自动收起，只留标题行和
 * 一条缩略的曲线形状；滑回顶部自动展开。点标题行也能手动收起/展开。
 * 理由是实测的：曲线占 150 dp 时，半开档的控制台里滑杆区只剩约 120 dp —— 一屏一根半
 * 滑杆，翻的时候还得先把曲线滚过去。"看着调"和"往下翻"本来就是两个动作，不该同时占地方。
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

    // ---- 曲线的收起（r48）----
    //
    // 这一版把"收起"当成**滚动**本身来做，不再有"滚到某个阈值就把整块抽走"这回事。
    //
    // 做法只有一个式子。设 range = 曲线块全展开的高度、s = 滑杆区滚了多少：
    //   曲线块**可见**高度 h = range - s      （被 s 从上面顶出去多少，就矮多少）
    //   滑杆区顶部的垫片     = s             （正好把内容顶回去 s）
    // 于是内容的位置 = 界线 + range + y - s —— 干净的一比一，跟着手指走；
    // 而曲线块被"顶"上去的部分由标题行下面的裁剪切掉，看上去就是曲线顺着滚了出去。
    // 旧的写法是阈值 + 高度动画：内容位置和滚动各走各的，白平衡那几个字在抽走的那
    // 一瞬被切掉半边，怎么调弹簧都别扭 —— 因为根上就不是一个连续的动作。
    //
    // 手动收起 = 把 scroll 滚到 range（曲线刚好完全离开），手动展开 = 滚回 0。
    // 也就是说"手动"和"自动"是同一个状态（滚动位置），不存在"手动收起后回顶也不
    // 展开"这种需要额外标记去解的死锁。
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val d = LocalDensity.current

    // 曲线高度按可用高度自适应（上限 150dp，约占面板 42%）。
    // 控制台是能拖的：用户把它拖到半开以下时，固定 170dp 的曲线会把下面的滑杆区
    // 挤成 0 高 —— 曲线还在、什么都调不了，这种"看得见摸不着"比裁掉更糟。
    BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
        val curveH = if (maxHeight * 0.42f < 150.dp) maxHeight * 0.42f else 150.dp
        // 曲线块"全展开"的高度：编辑器 + 一行 22dp 的提示（提示单行、定高，
        // 所以不用去测 —— 测出来的值会随着折叠变，反过来把折叠算乱）。
        val rangeDp = curveH + 22.dp
        val rangePx = with(d) { rangeDp.toPx() }
        // 折叠到一半就算收起：箭头的方向只看这个，粒度粗、不会每帧重组。
        val collapsed by remember(rangePx) { derivedStateOf { scroll.value > rangePx * 0.5f } }

        // 手动收起 / 展开：滚到 range 或 0。曲线是滚出去的，所以这两个动作和手指
        // 滚动走的是同一条路径 —— 不可能出现"收起来了但位置算错"的错位。
        fun setCurveOpen(open: Boolean) {
            val target = if (open) 0 else rangePx.roundToInt()
            scope.launch {
                if (reduceMotion) scroll.scrollTo(target) else scroll.animateScrollTo(target)
            }
        }

        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 12.dp)) {

            // ================= 曲线标题行 =================
            // 一行标题带通道切换：RGB / R / G / B。通道色直接用在曲线和控制点上，
            // 切到红通道时整条线变红 —— 这是"你在改哪条曲线"最省字的说法。
            // 整行左半（标题 + 收起箭头）是收起开关，右半的通道 chip 与复位各管各的。
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp).height(38.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .iosTap(pressDim = 0.55f, haptic = true) { setCurveOpen(collapsed) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("曲线", color = Dv.paper, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp)
                    Spacer(Modifier.width(6.dp))
                    // 收起时把曲线"缩"成一个小图形，标题行也还能看出形状 ——
                    // 只写"曲线 ⌄"的话，收起来以后这一页就完全没有曲线了。
                    // 位置**常驻**、只改透明度：收放的瞬间标题行不会因为多出一个图形
                    // 而把右半的通道 chip 挤着跳一下（那也是"生硬"的一部分）。
                    val miniA by animateFloatAsState(
                        targetValue = if (collapsed) 1f else 0f,
                        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else spring(dampingRatio = 1f, stiffness = 500f),
                        label = "mini",
                    )
                    Box(Modifier.alpha(miniA.coerceIn(0f, 1f))) {
                        MiniCurve(points, chanTint)
                    }
                    Spacer(Modifier.width(2.dp))
                    Chevron(down = !collapsed, reduceMotion = reduceMotion)
                }
                Spacer(Modifier.width(6.dp))
                listOf("RGB" to 0, "R" to 1, "G" to 2, "B" to 3).forEach { (label, i) ->
                    DvChip(label, chan == i, { chan = i }, size = 10)
                }
                Spacer(Modifier.weight(1f))
                // 复位按钮常驻（未改动时压暗、不可点）。只在 dirty 时出现的话，
                // 标题行会随着"有没有动过"忽宽忽窄，右边的通道 chip 跟着跳。
                val dirty = !Layer2.isLinear(points)
                Box(Modifier.clip(RoundedCornerShape(Dv.rChip))
                    .background(if (dirty) Dv.raisedHigh else Color.Transparent)
                    .iosTap(enabled = dirty, pressScale = 0.94f, pressDim = 0.8f, haptic = true) {
                        if (dirty) { setPoints(Layer2.LINEAR); onCommit() }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("复位", color = if (dirty) Dv.label2 else Dv.label4, fontSize = 10.5f.sp)
                }
            }

            // 曲线区：可见高度 = range - scroll，内容整体上移 scroll 被裁掉 ——
            // 等价于"曲线是滑杆区里的第一块，正常地滚上去"，但它必须留在滚动区外面：
            // 曲线里自己有一套拖点手势，塞进 verticalScroll 会变成"拖一下就翻页"。
            //
            // 高度和位移都在 layout 阶段读 scroll.value（不进组合），滚动时这一页
            // 一次重组都没有 —— 底下二十来根滑杆跟着每帧重组的话必掉帧。
            val hNow = { (rangePx - scroll.value).coerceIn(0f, rangePx) }
            Box(
                Modifier.fillMaxWidth()
                    .layout { m, c ->
                        val h = hNow().roundToInt()
                        val p = m.measure(Constraints.fixed(c.maxWidth, h))
                        layout(c.maxWidth, h) { p.place(0, 0) }
                    }
                    .clipToBounds()
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        // requiredHeight：不管外面给多高都按整块高度量，量完自己溢出
                        // 出去被上面的裁剪切掉。用 height() 会被父约束压扁，挤成一团。
                        .requiredHeight(rangeDp)
                        .offset { IntOffset(0, -(rangePx - hNow()).roundToInt()) }
                ) {
                    CurveEditor(
                        points = points,
                        hist = hist,
                        chan = chan,
                        cfg = cfg,
                        tint = chanTint,
                        height = curveH,
                        onChange = setPoints,
                        onCommit = onCommit,
                    )
                    // 一行操作提示：曲线是"看着调"的东西，但光是能拖不代表知道能拖 ——
                    // 加点 / 删点 / 复位这三件事没有可见按钮，只能靠这行字说清楚。
                    // 拖动时的输入/输出码值显示在曲线框左上角（那里不占版面）。
                    // 单行 + 定高 22dp：折起来的时候这一行的位置要算得准，不能靠测量。
                    Text(
                        curveHint(points),
                        color = if (Layer2.isLinear(points)) Dv.label4 else chanTint.copy(alpha = 0.75f),
                        fontSize = 9.5f.sp, lineHeight = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, softWrap = false,
                        modifier = Modifier.fillMaxWidth().height(22.dp)
                            .padding(start = 2.dp, top = 2.dp),
                    )
                }
            }
            // 收起态不再另起一行写"已调整"：那一行会让收起/展开的高度差忽大忽小，
            // 下面的白平衡跟着跳。改过的话标题行的缩略曲线能看出来，够了。
            Hairline(alpha = 0.07f)

            // ================= 滑杆区 =================
            Column(
                Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(scroll)
                    .padding(top = 2.dp)
            ) {
                // 顶上这块垫片就是"曲线占掉的那段"：滚多少填多少，内容始终待在曲线
                // 下面原来的位置上，一像素都不会被上面的界线切到。
                // 用 layout 而不是 height(dp)：每滚一帧都要改高度，走组合会让下面
                // 二十来根滑杆跟着重组一遍。
                Spacer(Modifier.fillMaxWidth().layout { m, c ->
                    val sp = scroll.value.toFloat().coerceIn(0f, rangePx).roundToInt()
                    val p = m.measure(Constraints.fixed(c.maxWidth, sp))
                    layout(c.maxWidth, sp) { p.place(0, 0) }
                })
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

/** 曲线区下面那行字：拖动时报输入/输出码值，平时报操作方式。单行，不再换行。 */
private fun curveHint(points: List<Float>): String =
    if (Layer2.isLinear(points))
        "点按加点 · 拖动改形 · 拖出删除 · 双击复位"
    else
        "已调整 ${Layer2.pointCount(points)} 点 · 拖动改形 · 拖出删除 · 双击复位"

/** 收起态的缩略曲线：26×14 dp，只画形状。 */
@Composable
private fun MiniCurve(points: List<Float>, tint: Color) {
    Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
        val lut = Layer2.curveLut(points, 40)
        val p = Path()
        for (i in lut.indices) {
            val x = size.width * i / (lut.size - 1f)
            val y = size.height * (1f - lut[i])
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, tint.copy(alpha = 0.9f), style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
}

/** 小三角：指向下 = 可收起，指向上 = 可展开。 */
@Composable
private fun Chevron(down: Boolean, reduceMotion: Boolean) {
    val rot by animateFloatAsState(
        targetValue = if (down) 0f else 180f,
        // 弹簧而不是 180ms 的 tween：箭头跟曲线一起"落"，不是啪一下翻过去
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap()
        else spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "chev",
    )
    Canvas(Modifier.size(11.dp)) {
        rotate(rot) {
            val w = size.width
            val h = size.height
            drawLine(Dv.label3, Offset(w * 0.18f, h * 0.36f), Offset(w * 0.5f, h * 0.64f),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Dv.label3, Offset(w * 0.5f, h * 0.64f), Offset(w * 0.82f, h * 0.36f),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- 曲线编辑器

/**
 * 曲线编辑器（r43.1 重做，对齐 SpektralLab 的 CurveEditor）。
 *
 * 旧版只有 5 个固定 x 的控制点、只能上下拖。三处不够用：
 *   · 两端钉死在 (0,0)/(1,1)，所以抬黑场、压白场这类最基本的动作做不了；
 *   · 点太少，S 形只能在 1/6、2/6… 这几个位置折，想要一条"长趾部"的曲线没处下手；
 *   · 看不到读数，调完只能说"感觉亮了"。
 *
 * 现在是自由点：
 *   · **点按空白加点**，按住即拖（Capture One 的手感：按下的地方就是要的点）；
 *   · **拖点自由**，两端点只上下动（黑场 / 白场），中间点不能越过邻居；
 *   · **拖出左右边界删除**（两端点除外），配合 [Haptics.warn] 给一下"删掉了"的反馈；
 *   · **双击复位**这一条通道；
 *   · 拖动时左上角显示输入 / 输出码值（0–255）。
 *
 * 手势用 `awaitEachGesture` 手写而不是叠 detectDragGestures + detectTapGestures：
 * 两个检测器叠在同一个 Canvas 上会互相吃掉 down 事件，加点和拖点只能二选一。
 */
@Composable
private fun CurveEditor(
    points: List<Float>,
    hist: Layer2.Hist?,
    chan: Int,
    cfg: Layer2.Cfg,
    tint: Color,
    height: Dp,
    onChange: (List<Float>) -> Unit,
    onCommit: () -> Unit,
) {
    val d = LocalDensity.current
    val ctx = LocalContext.current
    val pad = with(d) { 10.dp.toPx() }
    val hit = with(d) { 22.dp.toPx() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var dragIdx by remember { mutableIntStateOf(-1) }
    var readout by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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

    // 直方图数据。
    //
    // RGB 视图把红 / 绿 / 蓝三条**各用自己的颜色**叠在一起（专业曲线工具的惯例）：
    // 哪一条堆在暗部、哪一条在中间调鼓起来，一眼就看得出来 —— 单看一条亮度包络，
    // 偏色是被抹平的。三条共用一个峰值再做归一化：各自归一会让三条永远一样高，
    // 相对关系就没了。
    //
    // 单通道视图只看自己那一条，用同色填充。
    val rgbs: Array<FloatArray>? = remember(hist, chan) {
        if (hist == null || chan != 0) null else {
            val ch = arrayOf(hist.r, hist.g, hist.b).map { src ->
                FloatArray(256) { src[it].toFloat() }.also { it[255] = 0f }
            }
            val mx = ch.maxOf { a -> a.maxOrNull() ?: 0f }
            if (mx <= 0f) null else Array(3) { k -> FloatArray(256) { ch[k][it] / mx } }
        }
    }
    val bars: FloatArray? = remember(hist, chan) {
        if (hist == null || chan == 0) null else {
            val src = when (chan) { 1 -> hist.r; 2 -> hist.g; else -> hist.b }
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
                    fun w() = boxNow.width.toFloat()
                    fun h() = boxNow.height.toFloat()
                    fun sx(x: Float) = pad + x * (w() - 2 * pad)
                    fun sy(y: Float) = pad + (1f - y) * (h() - 2 * pad)
                    fun ux(px: Float) = ((px - pad) / (w() - 2 * pad)).coerceIn(0f, 1f)
                    fun uy(py: Float) = (1f - (py - pad) / (h() - 2 * pad)).coerceIn(0f, 1f)
                    fun nearest(p: Offset): Int {
                        var best = -1
                        var bestD = hit
                        var i = 0
                        while (i * 2 < pts.size) {
                            val dx = p.x - sx(pts[i * 2])
                            val dy = p.y - sy(pts[i * 2 + 1])
                            val dd = sqrt(dx * dx + dy * dy)
                            if (dd < bestD) { bestD = dd; best = i }
                            i++
                        }
                        return best
                    }

                    // 双击判定自己数：detectTapGestures 会和下面的 drag 抢 down。
                    var lastTapMs = 0L
                    var lastTapPos = Offset.Zero

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val now = android.os.SystemClock.uptimeMillis()
                        val isDouble = now - lastTapMs < 300L &&
                            (down.position - lastTapPos).getDistance() < 42f
                        lastTapMs = now
                        lastTapPos = down.position
                        down.consume()
                        if (isDouble) {
                            // 双击：这一条通道回到线性。等手指松开再走，避免顺手又加一个点
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.all { !it.pressed }) break
                            }
                            readout = null
                            emit(Layer2.LINEAR)
                            commit()
                            Haptics.detent(ctx)
                            return@awaitEachGesture
                        }

                        var i = nearest(down.position)
                        val ins = if (i < 0)
                            Layer2.insertPoint(pts, ux(down.position.x), uy(down.position.y)) else null
                        if (i < 0 && ins == null) {
                            // 点数到顶：什么都不加，给一下"到顶了"的实感，然后吃掉这次手势
                            Haptics.warn(ctx)
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.changes.all { !it.pressed }) break
                            }
                            return@awaitEachGesture
                        }
                        var work: List<Float> = pts
                        if (ins != null) {
                            i = ins.second
                            work = ins.first
                            emit(work)
                        }
                        Haptics.tick(ctx)
                        dragIdx = i
                        var removed = false
                        drag(down.id) { change ->
                            if (removed) { change.consume(); return@drag }
                            val outLeft = change.position.x < pad - 30f
                            val outRight = change.position.x > w() - pad + 30f
                            if ((outLeft || outRight) && i > 0 && i < work.size / 2 - 1) {
                                // 拖出左右边界 = 删除。只对中间点成立：两端点是定义域
                                Layer2.removePoint(work, i)?.let { work = it; emit(work) }
                                removed = true
                                dragIdx = -1
                                readout = null
                                Haptics.warn(ctx)
                                change.consume()
                                return@drag
                            }
                            work = Layer2.movePoint(work, i, ux(change.position.x), uy(change.position.y))
                            emit(work)
                            readout = Pair(
                                (work.getOrElse(i * 2) { 0f } * 255f).toInt(),
                                (work.getOrElse(i * 2 + 1) { 0f } * 255f).toInt(),
                            )
                            change.consume()
                        }
                        dragIdx = -1
                        readout = null
                        commit()
                    }
                },
        ) {
            val l = pad
            val t = pad
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            if (w <= 0 || h <= 0) return@Canvas

            // ---- 直方图 ----
            // 单通道：同色填充。RGB：三条各自颜色叠上去，重叠处发白。
            if (bars != null) drawHist(bars, tint.copy(alpha = 0.16f), l, t, w, h)
            rgbs?.let { a ->
                // 加法混合（Plus）而不是普通叠加：红 + 绿 = 黄、三色齐 = 白，
                // 这正是把一张彩色照片的直方图"看穿"的方式。单条 alpha 压低一点，
                // 三条一起铺满时不会糊成一块。
                drawHist(a[0], Color(0xFFFF5C4D).copy(alpha = 0.42f), l, t, w, h, BlendMode.Plus)
                drawHist(a[1], Color(0xFF4FD97B).copy(alpha = 0.42f), l, t, w, h, BlendMode.Plus)
                drawHist(a[2], Color(0xFF5C8DFF).copy(alpha = 0.42f), l, t, w, h, BlendMode.Plus)
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

            // ---- 另外三条通道的曲线（只在 RGB 视图里，极淡）----
            // 专业曲线工具都会这样给个上下文：调 RGB 时能看见红在哪、蓝在哪，
            // 不用来回切通道去记形状。
            if (chan == 0) {
                listOf(
                    cfg.curveR to Color(0xFFFF7A6E),
                    cfg.curveG to Color(0xFF7ED08A),
                    cfg.curveB to Color(0xFF7EA8FF),
                ).forEach { (cp, c) ->
                    if (!Layer2.isLinear(cp)) drawCurve(cp, c.copy(alpha = 0.30f), l, t, w, h, 1.2f)
                }
            }

            // ---- 曲线本体 ----
            // 一层很淡的同色光晕，让曲线在暗背景上有"发光"的层次（发丝线的反面：这里是主角）
            drawCurve(points, tint.copy(alpha = 0.16f), l, t, w, h, 6f)
            drawCurve(points, tint, l, t, w, h, 2.2f)

            // ---- 活动点的十字准线 ----
            if (dragIdx >= 0 && dragIdx * 2 + 1 < points.size) {
                val cx = l + w * points[dragIdx * 2]
                val cy = t + h * (1f - points[dragIdx * 2 + 1])
                drawLine(Color.White.copy(alpha = 0.16f), Offset(cx, t), Offset(cx, t + h), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f), 0f))
                drawLine(Color.White.copy(alpha = 0.16f), Offset(l, cy), Offset(l + w, cy), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f), 0f))
            }

            // ---- 控制点 ----
            var i = 0
            while (i * 2 + 1 < points.size) {
                val cx = l + w * points[i * 2]
                val cy = t + h * (1f - points[i * 2 + 1])
                val active = i == dragIdx
                val end = i == 0 || i * 2 == points.size - 2
                if (active) drawCircle(tint.copy(alpha = 0.22f), radius = 12f, center = Offset(cx, cy))
                drawCircle(Dv.root, radius = if (active) 7f else 5f, center = Offset(cx, cy))
                // 端点是黑场 / 白场：实心方块，和中间点（圆）区分开
                if (end) {
                    drawRect(tint, topLeft = Offset(cx - 3.4f, cy - 3.4f),
                        size = androidx.compose.ui.geometry.Size(6.8f, 6.8f))
                } else {
                    drawCircle(tint, radius = if (active) 4.6f else 3.4f, center = Offset(cx, cy))
                }
                i++
            }
        }

        // 拖动读数：输入 x 的码值 + 这一点的输出，压在左上角。
        // 平时不显示（挡住直方图），手指按下才出现 —— 和相机取景框里的数值一样。
        readout?.let { (inV, outV) ->
            Box(Modifier.align(Alignment.TopStart).padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 7.dp, vertical = 3.dp)) {
                Text("输入 $inV · 输出 $outV", color = tint, fontSize = 10.sp,
                    fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 把一条直方图填进矩形 (l,t,w,h)。[blend] 用 Plus 时三条重叠会自然发白（RGB 视图）。 */
private fun DrawScope.drawHist(
    b: FloatArray, color: Color,
    l: Float, t: Float, w: Float, h: Float,
    blend: BlendMode = BlendMode.SrcOver,
) {
    val p = Path().apply {
        moveTo(l, t + h)
        for (i in 0..255) {
            val x = l + w * i / 255f
            lineTo(x, t + h * (1f - b[i].coerceIn(0f, 1f)))
        }
        lineTo(l + w, t + h)
        close()
    }
    drawPath(p, color, blendMode = blend)
}

/** 把一条曲线画进矩形 (l,t,w,h)。曲线与 LUT 同源，所以屏幕上看到的形状就是像素的形状。 */
private fun DrawScope.drawCurve(
    points: List<Float>, color: Color,
    l: Float, t: Float, w: Float, h: Float, width: Float,
) {
    val lut = Layer2.curveLut(points, 192)
    val p = Path()
    for (i in lut.indices) {
        val x = l + w * i / (lut.size - 1f)
        val y = t + h * (1f - lut[i])
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
