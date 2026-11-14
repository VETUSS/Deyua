package com.spektra.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 电子观片台。
 *
 * 一块贴着屏幕的圆角灯箱：把冲洗好的成片一张张摆上去，随手拖、随手放大，
 * 需要抠细节时掀开放大镜。灯箱永远是亮的，画面靠自己的光从灯箱里透出来——
 * 这是观片台和"看图软件"在观感上的根本差别。
 *
 * 片子就是**成片本身**，不另画一圈片基：开了片边的成片自带片边、片码和齿孔，
 * 没开片边的照片就该是一张干净的相纸。所以这里只做一件额外的事——把成片上
 * 真实存在的齿孔找出来（[LtPerf]），绘制时按同一位置挖穿，孔里透出下面的
 * 灯箱；两片叠放时，孔里透出下面那张片。135 / 135 半格 / 135 宽幅才有齿孔，
 * 其余格式不会被套上假孔。
 *
 * 位置与大小按 **dp 绝对值**存：转横屏时灯箱变大，片子不跟着放大、也不跳动，
 * 只是周围多出可用的地方。全屏模式下上下栏收起，灯箱铺满整屏。
 *
 * 放大镜照 Apple「预览」的取景镜做：干净的圆、清晰的像、跟着手指的取样点，
 * 镜心抬到手指上方避开遮挡，靠近上沿时翻到下方。
 */
@Immutable
private data class LtPlate(
    val id: Long,
    /** 一张片 = 1 个 Uri；片条 = 顺序排列的多个 Uri */
    val frames: List<Uri>,
    /** 每格的画幅比（宽/高），解码后回填 */
    val ars: List<Float>,
    /** 片心相对灯箱中心的偏移（dp）——转屏时位置不变 */
    val cxDp: Float,
    val cyDp: Float,
    /** 整张片的宽度（dp）——转屏时大小不变 */
    val wDp: Float,
    val scale: Float = 1f,
    val rot: Float = 0f,
)

private const val LT_BASE_W = 0.64f      // 单张片基准宽度 = 加入时灯箱宽 × 0.64
private const val LT_MAX = 30            // 灯箱最多 30 张
private const val LT_STRIP_MAX = 12      // 一条片条最多 12 格
private const val LT_WORLD_LIMIT = 20_000f   // 世界坐标的安全边界（dp），不是可视边界

private val LtPad = 6.dp                 // 灯箱面到画布边的距离（留给光晕）
private val LtRadius = 28.dp
private val LtCorner = 1.dp              // 片角：片子是硬质薄片，圆角只用来去锯齿
private val LtLoupeR = 72.dp
private val LtBg = Color(0xFF070708)

private fun Context.ltActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** 整张片的长宽比：n 格同一高度并排，总宽 / 片高 */
private fun LtPlate.aspectRatio(): Float =
    ars.sum().coerceAtLeast(0.05f)

private fun plateWpx(p: LtPlate, dens: Float): Float = max(1f, p.wDp * dens * p.scale)

private fun plateHpx(p: LtPlate, dens: Float): Float = plateWpx(p, dens) / p.aspectRatio()

@Composable
fun LightTablePage(
    photos: List<GalleryPhoto>,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val act = remember(ctx) { ctx.ltActivity() }
    val density = LocalDensity.current
    val dens = density.density

    var plates by remember { mutableStateOf(listOf<LtPlate>()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var stripMode by remember { mutableStateOf(false) }
    var stripSel by remember { mutableStateOf(listOf<String>()) }
    var loupeOn by remember { mutableStateOf(false) }
    var loupeZoom by remember { mutableStateOf(2f) }
    var loupeFocus by remember { mutableStateOf(Offset(-1f, -1f)) }
    var boardSize by remember { mutableStateOf(Size.Zero) }
    // ---- 无限画布（r46）----
    // 灯箱不再只有一屏大：整个台面可以平移，片子摆到屏幕外就"在更远的地方"，
    // 想要多大的版式就拼多大。平移量存在**视口**这一侧（panX / panY，单位 dp），
    // 片子自己的 cxDp / cyDp 是世界坐标，谁也不改谁。
    // 亚克力面板本身不跟着动 —— 它现在是"无限大的一块灯板"，动的是板上的东西。
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    val panNow by rememberUpdatedState(Offset(panX, panY))
    // 平移过就显示"归位"：不然拖远了会不知道怎么找回中间
    fun resetView() { panX = 0f; panY = 0f }
    var fullscreen by remember { mutableStateOf(false) }
    // 全屏工具条：进来先露一下，几秒后自己收走，空白处轻点再叫出来。
    // 全屏就是为了把灯箱铺满，工具不该长期占着画面底部。
    var barOn by remember { mutableStateOf(true) }
    // 灯箱的灯：真实观片台是可以关灯的——关掉以后亚克力面板只剩室内光，
    // 负片立刻变成一片暗，只剩形状和一点反光。0 = 全暗，1 = 全亮。
    // 灯丝的亮起/熄灭不是开关量：给 1.2 秒，中间还会经过一段偏暖的低亮度。
    var lightOn by remember { mutableStateOf(true) }
    val light by animateFloatAsState(
        targetValue = if (lightOn) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "light"
    )
    // 灯箱边上的刻度尺（上边横向、左边纵向），拟真用的
    var rulerOn by remember { mutableStateOf(false) }
    val textMeasurer = rememberTextMeasurer()
    val bitmaps = remember { mutableStateMapOf<String, ImageBitmap>() }
    // 每张图的真实画幅比。格子必须按它放图：AR 一旦取错，绘制时就会把片边、
    // 片码裁掉——裁掉之后再挖齿孔，就成了"裁掉真片边、自己画假孔"。
    val arOf = remember { mutableStateMapOf<String, Float>() }
    // 成片上真实存在的齿孔（归一化到该图）；检测不出来就没有条目 = 不挖孔
    val perfs = remember { mutableStateMapOf<String, List<LtHole>>() }
    // 放大镜停稳后单独取一张高清图，镜内才够锐；只保留一份
    var hiUri by remember { mutableStateOf<String?>(null) }
    var hiBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var nextId by remember { mutableStateOf(1L) }
    var hint by remember { mutableStateOf<String?>(null) }

    // HDR：窗口色彩模式 + 灯箱发光贴图，任一失败就退回 SDR
    var hdrOn by remember { mutableStateOf(false) }
    var glow by remember { mutableStateOf<ImageBitmap?>(null) }

    // 侧边手势 / 返回键：一层一层退（选片抽屉 → 全屏 → 取消选中 → 回相册）。
    // 不接住的话会落到 Activity 的默认行为上直接 finish —— 从观片台一滑就回桌面。
    DvBackHandler {
        when {
            pickerOpen -> pickerOpen = false
            fullscreen -> fullscreen = false
            selectedId != null -> selectedId = null
            else -> onClose()
        }
    }

    DisposableEffect(act) {
        val w = act?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prevMode = LtHdr.prevMode(act)
        hdrOn = LtHdr.supported(ctx) && LtHdr.enter(act)
        onDispose {
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            LtHdr.exit(act, prevMode)
        }
    }

    // r47.1：**不再碰窗口的布局模式**。
    //
    // 这里原本在进入时切 edge-to-edge、退出时切回 fit。切回来那一下，系统不会立刻
    // 重发 insets，主界面的 Scaffold 仍按"状态栏被系统占着"来让位，等于让了两遍 ——
    // 顶栏就往下掉一截，要转屏或重启才回得去。现在 edge-to-edge 和挖孔模式都在
    // Activity 启动时定死，观片台只负责显隐系统栏，退出后主界面一个像素都不会动。

    // 全屏：收起系统栏，灯箱铺满整屏；退出时还给系统
    DisposableEffect(fullscreen) {
        val w = act?.window
        val ctl = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen && ctl != null) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) ctl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(hdrOn) {
        val t = withContext(Dispatchers.Default) { if (hdrOn) LtHdr.glowTile() else null }
        glow = t
        if (hdrOn && t == null) {
            // 能进 HDR 窗口，却做不出 F16 贴图：退回 SDR，否则 HDR 下反而发灰
            LtHdr.exit(act, android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT)
            hdrOn = false
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1700)
            hint = null
        }
    }

    // 工具条露一会儿就收：全屏是来看片的，不是来看按钮的。
    // 放大镜开着也照收——轻点一下就能再叫出来，不用让工具条长期占着画面
    LaunchedEffect(fullscreen, barOn, loupeOn) {
        if (fullscreen && barOn) {
            delay(3400)
            barOn = false
        }
    }

    fun addPlate(frames: List<Uri>, wide: Float = 0f) {
        if (plates.size >= LT_MAX) {
            hint = "灯箱最多放 $LT_MAX 张"
            return
        }
        val n = plates.size
        // 位置按 dp 错开：新片子落下来不会正好压住上一张。
        // 基准是**当前视口的中心**（-pan），所以平移到别处再添加，片子就落在眼前，
        // 不会掉回原点那个已经看不见的老中心。
        val ox = -panX + ((n % 3) - 1) * 20f
        val oy = -panY + ((n % 4) - 1.5f) * 24f
        val id = nextId
        plates = plates + LtPlate(
            id = id,
            frames = frames,
            ars = frames.map { arOf[it.toString()] ?: 1.5f },
            cxDp = ox,
            cyDp = oy,
            wDp = (if (boardSize.width > 0f) boardSize.width / dens else 320f) *
                (if (wide > 0f) wide else LT_BASE_W),
            scale = 1f,
            rot = if (frames.size > 1) 0f else ((n % 5) - 2) * 0.8f,
        )
        nextId = id + 1
        selectedId = id
        // 第一张落下来时给一句操作提示（以前这行字常驻底栏，占地方）
        if (n == 0) hint = "点住片子可拖动 · 双指缩放旋转 · 双指拖空白移画布"
    }

    fun updatePlate(id: Long, f: (LtPlate) -> LtPlate) {
        plates = plates.map { if (it.id == id) f(it) else it }
    }

    // 点哪张，哪张就到最上面。列表顺序就是叠放次序（后画的在上面），所以
    // "提到最前"= 把这个元素挪到列表末尾。
    fun raisePlate(id: Long) {
        val i = plates.indexOfFirst { it.id == id }
        if (i < 0 || i == plates.lastIndex) return
        val p = plates[i]
        plates = plates.filterNot { it.id == id } + p
    }

    // 灯箱上出现的所有画格（片条展开），去重后按需解码。
    // 片条里的一格在屏幕上很小，900 边就够；单张给 1200，放大后仍有余量。
    val wanted = plates.flatMap { p ->
        val edge = if (p.frames.size > 1) 900 else 1200
        p.frames.map { it.toString() to edge }
    }.distinctBy { it.first }
    LaunchedEffect(wanted) {
        for ((k, edge) in wanted) {
            if (bitmaps.containsKey(k)) continue
            val b = withContext(Dispatchers.IO) { loadScaled(ctx, Uri.parse(k), edge) } ?: continue
            val ar = b.width.toFloat() / b.height.toFloat()
            // 齿孔检测放在解码线程之外：它只在导入时跑一次，之后随片子一起画
            val hs = withContext(Dispatchers.Default) { runCatching { LtPerf.detect(b) }.getOrNull() }
            bitmaps[k] = b.asImageBitmap()
            arOf[k] = ar
            if (!hs.isNullOrEmpty()) perfs[k] = hs
            // 画幅比回填到**所有**引用这张图的格子：同一张成片放第二次时不会
            // 再走解码，如果只在首次解码时回填，第二次上片的格子就永远停在
            // 缺省的 1.5，绘制时按 1.5 去"铺满"一个 1.4 的画面 = 上下裁掉片边
            plates = plates.map { p ->
                var np = p
                for (i in p.frames.indices) {
                    if (p.frames[i].toString() != k) continue
                    if (abs(np.ars.getOrElse(i) { 1.5f } - ar) < 0.01f) continue
                    val na = np.ars.toMutableList().also { it[i] = ar }
                    // 竖幅片（半格、竖拍 135）落下来可能比灯箱还高：按画幅第一次算出
                    // 真实长宽比时就收一次宽度，之后不再动它，转屏大小才稳
                    val total = na.sum().coerceAtLeast(0.05f)
                    val maxHdp =
                        if (boardSize.height > 0f) boardSize.height / dens * 0.82f else Float.MAX_VALUE
                    val fit = if (np.wDp / total > maxHdp) maxHdp * total else np.wDp
                    np = np.copy(ars = na, wDp = fit)
                }
                np
            }
        }
    }

    // 灯箱尺寸变了（转屏 / 进出全屏 / 全屏收起系统栏）：片子本身不动，只把
    // 会被切在灯箱外的片收回来——这是"转屏不跳"与"不丢片"之间唯一的取舍。
    //
    // r46 起有无限画布了：灯箱之外不再等于"看不见"，所以这里的边界放到
    // ±[LT_WORLD_LIMIT] dp 这个纯粹的数值安全区，正常的摆法一根手指都碰不到它。
    LaunchedEffect(boardSize, fullscreen) {
        if (boardSize.width <= 0f) return@LaunchedEffect
        plates = plates.map { p ->
            val nx = p.cxDp.coerceIn(-LT_WORLD_LIMIT, LT_WORLD_LIMIT)
            val ny = p.cyDp.coerceIn(-LT_WORLD_LIMIT, LT_WORLD_LIMIT)
            if (nx == p.cxDp && ny == p.cyDp) p else p.copy(cxDp = nx, cyDp = ny)
        }
    }

    LaunchedEffect(loupeOn, boardSize, fullscreen) {
        if (loupeOn && boardSize.width > 0f && loupeFocus.x < 0f) {
            val pad = if (fullscreen) 0f else with(density) { LtPad.toPx() }
            val inner = innerOf(boardSize, pad)
            loupeFocus = Offset(pad + inner.width / 2f, pad + inner.height / 2f)
        }
    }

    // 转屏后取样点可能已经落在灯箱外，收回来
    LaunchedEffect(boardSize, fullscreen) {
        if (boardSize.width > 0f && loupeFocus.x >= 0f) {
            val pad = if (fullscreen) 0f else with(density) { LtPad.toPx() }
            loupeFocus = clampToBoard(loupeFocus, boardSize, pad, 0f)
        }
    }

    // 手指停稳再取高清：拖动过程中不去抢解码带宽
    LaunchedEffect(loupeOn, loupeFocus, boardSize, plates.size, panX, panY) {
        if (!loupeOn || boardSize.width <= 0f) {
            hiBmp = null
            hiUri = null
            return@LaunchedEffect
        }
        delay(150)
        val idx = hitPlate(loupeFocus, plates, boardSize, dens, panNow)
        val p = idx?.let { plates[it] }
        val u = if (p != null && p.frames.size == 1) p.frames[0].toString() else null
        if (u == null) {
            hiBmp = null
            hiUri = null
            return@LaunchedEffect
        }
        if (u == hiUri && hiBmp != null) return@LaunchedEffect
        val b = withContext(Dispatchers.IO) { loadScaled(ctx, Uri.parse(u), 2400) }
        if (b != null) {
            hiUri = u
            hiBmp = b.asImageBitmap()
        }
    }

    Box(
        Modifier.fillMaxSize().background(LtBg)
            // 非全屏时避开状态栏/导航栏（窗口已经 edge-to-edge 了）；全屏时
            // 一点内边距都不留，灯箱直接顶到屏幕四边
            .then(if (fullscreen) Modifier else Modifier.systemBarsPadding())
    ) {
        // 吃掉落在空白处的点击：观片台是叠在主界面之上的，不挡住的话点偏一点
        // 就会穿到下面主界面的按钮上
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
        )

        Column(Modifier.fillMaxSize()) {

            // ---------- 顶栏（全屏时收起） ----------
            if (!fullscreen) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.padding(horizontal = 4.dp).height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .iosTap(pressDim = 0.45f) { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("‹ 返回", color = Dv.blue, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    }

                    Text("观片台", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

                    Spacer(Modifier.weight(1f))

                    if (plates.isNotEmpty()) {
                        val frames = plates.sumOf { it.frames.size }
                        Text(
                            if (frames > plates.size) "${plates.size} 张 · $frames 格" else "${plates.size}/$LT_MAX",
                            color = Dv.label3,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }

                    // 灯箱本体的两个开关：刻度尺、灯。放在顶栏是因为它们是"台面"的
                    // 设置，和底栏那几个"看片工具"不是一类东西
                    LtTopChip(if (rulerOn) "刻度 · 开" else "刻度", rulerOn) { rulerOn = !rulerOn }
                    LtTopChip(if (lightOn) "关灯" else "开灯", !lightOn) { lightOn = !lightOn }

                    // 视觉 32dp、触控 44dp：顶栏的按钮要够大，不然容易点偏
                    Box(
                        Modifier.padding(horizontal = 3.dp).height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .iosTap(pressDim = 0.6f) { pickerOpen = !pickerOpen },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
                                .background(Orange.copy(alpha = if (pickerOpen) 0.30f else 0.18f))
                                .padding(horizontal = 13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (pickerOpen) "收起" else "+ 添加",
                                color = Orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // ---------- 灯箱 ----------
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    // 全屏时连这 2dp 的黑缝也不留：那两缕黑边在整屏灯箱边上很扎眼
                    .then(
                        if (fullscreen) Modifier
                        else Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                    )
            ) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { boardSize = it.toSize() }
                        .pointerInput(loupeOn, boardSize, plates.size, fullscreen) {
                            val pad = if (fullscreen) 0f else LtPad.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)

                                // 放大镜模式：单指拖动取样点，不移动片子
                                if (loupeOn) {
                                    down.consume()
                                    var last = down.position
                                    var travel = 0f
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id }
                                        if (ch == null || !ch.pressed) break
                                        travel = max(travel, (ch.position - down.position).getDistance())
                                        // 拖起来才推取样点：单纯点一下是"叫工具条"，
                                        // 别把镜心甩到手指点的地方去
                                        if (travel > 3.dp.toPx()) {
                                            // 用相对位移推进，取样点被钳在边内时不会"粘住"
                                            loupeFocus = clampToBoard(
                                                loupeFocus + (ch.position - last),
                                                boardSize, pad, 0f
                                            )
                                        }
                                        last = ch.position
                                        ch.consume()
                                    }
                                    // 轻点一下先让工具条出来：取样手势会吃掉所有触摸，
                                    // 否则开着放大镜就再也点不回"放大镜"这个开关
                                    if (travel <= 6.dp.toPx() && fullscreen) barOn = true
                                    return@awaitEachGesture
                                }

                                val hitIdx = hitPlate(down.position, plates, boardSize, dens, panNow, innerRect(boardSize, pad))
                                val dragId = hitIdx?.let { plates[it].id }
                                // 先提到最前再拖：手指按下的那张就该压在上面
                                dragId?.let { raisePlate(it) }
                                selectedId = dragId

                                var last = down.position
                                var lastDist = 0f
                                var lastAngle = 0f
                                var multi = false
                                var travel = 0f
                                // 空白处拖的是**画布**，而且只认双指（r47）。
                                //
                                // 单指在空白上按着走什么都不做：这一版之前单指也能推画布，
                                // 结果"点一下想把工具条叫出来"经常被判成一次小幅平移，
                                // 抬手时台面已经偏了 —— 那是最让人火的一种误操作。
                                // 现在单指在空白处只可能是"轻点"，双指才是"我确实要移台面"，
                                // 跟片子上的双指（缩放/旋转）也不会打架：那必须压在片子上。
                                var px = panNow.x
                                var py = panNow.y
                                var multiC = Offset.Zero
                                // 画布真的被推动过没有：双指平移时 travel 不增长，
                                // 不加这个标记，一次双指轻推会被当成"轻点空白"
                                var panned = false
                                down.consume()

                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val pressed = ev.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (dragId == null) {
                                        // ---- 移画布（只认双指）----
                                        if (pressed.size >= 2) {
                                            // 用多指的中心推：两指同向拖 = 平移，
                                            // 两指各走各的也不会把画布抖成两倍速度
                                            val c = pressed
                                                .fold(Offset.Zero) { acc, ch -> acc + ch.position } /
                                                pressed.size.toFloat()
                                            if (multi && multiC != Offset.Zero) {
                                                val dlt = c - multiC
                                                if (dlt.getDistance() > 0f) panned = true
                                                px += dlt.x / dens
                                                py += dlt.y / dens
                                                panX = px
                                                panY = py
                                            }
                                            multiC = c
                                            multi = true
                                        } else {
                                            // 单指：只记位移，不动画布。抬手时位移小的算轻点
                                            val pos = pressed[0].position
                                            travel = max(travel, (pos - down.position).getDistance())
                                            if (multi) {
                                                // 从双指收回到单指：重新立足，剩下那根手指
                                                // 要是继续走，就当成新的一次单指（不动画布）
                                                multi = false
                                                multiC = Offset.Zero
                                                last = pos
                                            }
                                        }
                                        ev.changes.forEach { it.consume() }
                                        continue
                                    }

                                    if (pressed.size >= 2) {
                                        val a = pressed[0].position
                                        val b = pressed[1].position
                                        val dist = (b - a).getDistance()
                                        val ang = atan2(b.y - a.y, b.x - a.x) * 180f / PI.toFloat()
                                        if (lastDist > 0f) {
                                            val z = (dist / lastDist).coerceIn(0.88f, 1.14f)
                                            val dr = ang - lastAngle
                                            updatePlate(dragId) {
                                                it.copy(
                                                    scale = (it.scale * z).coerceIn(0.12f, 3.4f),
                                                    rot = it.rot + dr
                                                )
                                            }
                                        }
                                        lastDist = dist
                                        lastAngle = ang
                                        multi = true
                                    } else {
                                        val pos = pressed[0].position
                                        travel = max(travel, (pos - down.position).getDistance())
                                        if (multi) {
                                            multi = false
                                            lastDist = 0f
                                            last = pos
                                        } else {
                                            val dx = (pos.x - last.x) / dens
                                            val dy = (pos.y - last.y) / dens
                                            // 只挡数值溢出，不挡"摆到屏幕外" —— 无限画布上
                                            // 片子本来就该能放到视口之外，稍后平移过去看
                                            updatePlate(dragId) {
                                                it.copy(
                                                    cxDp = (it.cxDp + dx)
                                                        .coerceIn(-LT_WORLD_LIMIT, LT_WORLD_LIMIT),
                                                    cyDp = (it.cyDp + dy)
                                                        .coerceIn(-LT_WORLD_LIMIT, LT_WORLD_LIMIT)
                                                )
                                            }
                                            last = pos
                                        }
                                    }
                                    ev.changes.forEach { it.consume() }
                                }

                                // 空白处轻点（不是拖）：全屏时把工具条叫出来或收回去
                                if (hitIdx == null && !panned && travel <= 6.dp.toPx() && fullscreen) {
                                    barOn = !barOn
                                }
                            }
                        }
                ) {
                    // 全屏：灯箱一直铺到屏幕边缘（连圆角都不留，交给手机的圆屏角去切），
                    // 非全屏才留出那圈黑框给光晕
                    val pad = if (fullscreen) 0f else LtPad.toPx()
                    val rad = if (fullscreen) 0f else LtRadius.toPx()

                    // 片子只画在灯箱面之内。
                    //
                    // 这是"无限画布"最要紧的一笔：台面是一块很大的亚克力，片子是摆在
                    // 台面上的实物，所以片子的可见范围就是灯箱面本身。把片子往边上
                    // 平移，它是**从边上滑出去**（被灯箱的边框切掉），不是浮在黑底上 ——
                    // 反过来拖回来，切掉的那一半就跟着回来。画布照旧可以一直往外走。
                    fun DrawScope.paintScene(cullC: Offset?, cullR: Float, clipHere: Boolean = true) {
                        // 面板不跟着平移：它现在是"无限大的一块灯板"，移的是板上的东西。
                        // 只有刻度尺跟着走 —— 刻度是印在亚克力上的，动了才看得出画布在动
                        drawLightBox(pad, rad, glow, light, rulerOn, textMeasurer, panNow)
                        // 放大镜那一趟是在已经变换过的坐标系里重画整场，裁剪得由调用方
                        // 在屏幕坐标里做（否则会被 scale 一起放大）；这里可以跳过
                        val board = innerRect(size, pad)
                        val body: DrawScope.() -> Unit = {
                            plates.forEach { p ->
                                val c = plateCenter(p, size, dens, panNow)
                                val w = plateWpx(p, dens)
                                val h = plateHpx(p, dens)
                                if (cullC != null) {
                                    // 只画镜内能看见的片：抬镜时灯箱上可能有 30 张
                                    val diag = hypot(w, h) / 2f
                                    if ((c - cullC).getDistance() > cullR + diag) return@forEach
                                }
                                // 整张都在灯箱外就整块跳过：30 张片摆满一张无限画布时，
                                // 这一句省下的不只是绘制，还有每帧的位图采样
                                val rad0 = Math.toRadians(p.rot.toDouble())
                                val ca = abs(cos(rad0)).toFloat()
                                val sa = abs(sin(rad0)).toFloat()
                                val hx = (ca * w + sa * h) / 2f
                                val hy = (sa * w + ca * h) / 2f
                                if (c.x + hx < board.left || c.x - hx > board.right ||
                                    c.y + hy < board.top || c.y - hy > board.bottom
                                ) return@forEach
                                val imgs = p.frames.map { u ->
                                    val k = u.toString()
                                    if (hiBmp != null && k == hiUri) hiBmp else bitmaps[k]
                                }
                                val holes = p.frames.map { perfs[it.toString()] }
                                drawPlate(
                                    imgs = imgs,
                                    holes = holes,
                                    glow = glow,
                                    c = c, w = w, ars = p.ars, rot = p.rot,
                                    selected = p.id == selectedId,
                                    dim = 1f - light
                                )
                            }
                        }
                        // 用**圆角**裁剪，不是矩形：灯箱四个角是弧的，矩形裁剪会让片子
                        // 在角落伸出一个尖角（正是那圈弧没盖住的地方）。全屏时 rad = 0，
                        // 退化成矩形，不用分支。
                        val boardPath = Path().apply {
                            addRoundRect(RoundRect(board, CornerRadius(rad)))
                        }
                        if (clipHere) clipPath(boardPath) { body() } else body()
                    }

                    paintScene(null, 0f)

                    if (loupeOn && loupeFocus.x >= 0f) {
                        val r = LtLoupeR.toPx()
                        val k = loupeZoom
                        val inner = innerRect(size, pad)
                        // 镜心抬到取样点上方 1.3r，手指不挡镜片；上沿放不下就翻到下方
                        val lens = lensCenter(loupeFocus, r, inner)

                        val oval = Path().apply {
                            addOval(
                                Rect(
                                    lens.x - r, lens.y - r,
                                    lens.x + r, lens.y + r
                                )
                            )
                        }

                        // 落地阴影：一圈从镜缘向外淡出的柔影，镜片才像浮在灯箱上
                        val shC = Offset(lens.x, lens.y + 5.dp.toPx())
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to Color.Transparent,
                                0.76f to Color.Transparent,
                                0.87f to Color(0x3D000000),
                                1f to Color(0x00000000),
                                center = shC,
                                radius = r * 1.26f
                            ),
                            radius = r * 1.26f,
                            center = shC
                        )

                        clipPath(oval) {
                            // 镜片里也只照灯箱面上有的东西：片子滑出去了，放大镜里也该空着。
                            // 同样按圆角裁 —— 放大镜照到角落时，弧外的部分不该出现
                            val bd = innerRect(size, pad)
                            val bdPath = Path().apply {
                                addRoundRect(RoundRect(bd, CornerRadius(rad)))
                            }
                            clipPath(bdPath) {
                                // 取样点 → 镜心：把手指下方的像素搬到镜片正中再放大
                                translate(lens.x - k * loupeFocus.x, lens.y - k * loupeFocus.y) {
                                    scale(k, k, pivot = Offset.Zero) {
                                        paintScene(loupeFocus, r / k * 1.6f + 4f, clipHere = false)
                                    }
                                }
                            }
                            // 镜内边缘极轻的压暗，圆边才有"玻璃厚度"
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0.80f to Color.Transparent,
                                    0.97f to Color(0x14000000),
                                    1f to Color(0x33000000),
                                    center = lens,
                                    radius = r
                                ),
                                radius = r,
                                center = lens
                            )
                            // 一道柔和的斜向反光，压在镜面上方
                            val sheen = Offset(lens.x - r * 0.30f, lens.y - r * 0.52f)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0f to Color(0x38FFFFFF),
                                    0.55f to Color(0x0FFFFFFF),
                                    1f to Color(0x00FFFFFF),
                                    center = sheen,
                                    radius = r * 0.86f
                                ),
                                radius = r * 0.86f,
                                center = sheen
                            )
                        }

                        // 镜圈：一道实白环 + 一道外暗线，边界干净利落（预览的观感）
                        drawCircle(
                            color = Color(0xE6FFFFFF),
                            radius = r - 1.dp.toPx(),
                            center = lens,
                            style = Stroke(2.dp.toPx())
                        )
                        drawCircle(
                            color = Color(0x38000000),
                            radius = r + 0.6.dp.toPx(),
                            center = lens,
                            style = Stroke(1.dp.toPx())
                        )

                        // 取样点标记：一个细环，不遮住被取样的像素
                        drawCircle(
                            color = Color(0x66FFFFFF),
                            radius = 9.dp.toPx(),
                            center = loupeFocus,
                            style = Stroke(1.3.dp.toPx())
                        )
                    }
                }

                if (plates.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 灯关了，这行字也不能还是暖棕色的——那会像贴上去的标签。
                        // 跟着灯光退成冷灰，才像真的印在亚克力上
                        Text(
                            "灯箱是空的",
                            color = lerpColor(Color(0xFF6A5E4C), Color(0xFFC6C0B6), 1f - light),
                            fontSize = 15.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点右上角「+ 添加」，把冲洗好的成片放上来",
                            color = lerpColor(Color(0xFF8C7F6B), Color(0xFF9A948A), 1f - light),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ---------- 底部操作条 / 添加面板 ----------
            if (!fullscreen && !pickerOpen) {
                Row(
                    Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LtChip("放大镜", loupeOn) { loupeOn = !loupeOn }
                    if (loupeOn) {
                        LtChip("${loupeZoom.toInt()}×") {
                            loupeZoom = when {
                                loupeZoom < 3f -> 3f
                                loupeZoom < 4f -> 4f
                                else -> 2f
                            }
                        }
                    }
                    LtChip("全屏") {
                        pickerOpen = false
                        fullscreen = true
                        barOn = true
                        hint = "轻点空白处可唤出工具条"
                    }
                    // 画布平移过才出现：没移过的时候留一个永远不亮的按钮只会占地方
                    if (panX != 0f || panY != 0f) {
                        LtChip("归位") {
                            resetView()
                            hint = "画布已回到中间"
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 选中片子时才出现的两个动作。其余时间底栏只留按钮，
                    // 拖放提示改为进页面时的一句浮层，不再长期占着一行字
                    if (hint == null && selectedId != null) {
                        LtChip("居中") {
                            selectedId?.let { id ->
                                // 落到**当前**视口中心，不是世界原点：平移过之后原点
                                // 早就不知道飘到哪儿去了
                                updatePlate(id) {
                                    it.copy(scale = 1f, rot = 0f, cxDp = -panX, cyDp = -panY)
                                }
                            }
                        }
                        LtChip("移除", danger = true) {
                            selectedId?.let { id -> plates = plates.filterNot { it.id == id } }
                            selectedId = null
                        }
                    }
                    hint?.let { Text(it, color = Orange, fontSize = 11.sp) }
                }
            } else if (!fullscreen) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF141416))
                        .padding(top = 8.dp, bottom = 10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("选择成片", color = Dv.label1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(10.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(Color(0x14FFFFFF)).padding(2.dp)
                        ) {
                            LtSeg("单张摆放", !stripMode) {
                                stripMode = false
                                stripSel = emptyList()
                            }
                            LtSeg("拼片条", stripMode) { stripMode = true }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (stripMode) "已选 ${stripSel.size}/$LT_STRIP_MAX"
                            else "${plates.size}/$LT_MAX",
                            color = Dv.label3, fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (photos.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(96.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("相册里还没有成片", color = Dv.label3, fontSize = 12.sp) }
                    } else {
                        LazyRow(
                            Modifier.fillMaxWidth().height(92.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(photos, key = { it.uri.toString() }) { ph ->
                                val key = ph.uri.toString()
                                val order = stripSel.indexOf(key)
                                val picked = order >= 0
                                val thumb by produceState<ImageBitmap?>(null, ph.uri) {
                                    value = withContext(Dispatchers.IO) { loadScaled(ctx, ph.uri, 240) }
                                        ?.asImageBitmap()
                                }
                                Box(
                                    Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF232326))
                                        .then(
                                            if (picked) Modifier.background(Orange.copy(alpha = 0.16f))
                                            else Modifier
                                        )
                                        .iosTap(pressDim = 0.7f) {
                                            if (!stripMode) {
                                                addPlate(listOf(ph.uri))
                                            } else {
                                                if (picked) {
                                                    stripSel = stripSel.filterNot { it == key }
                                                } else if (stripSel.size >= LT_STRIP_MAX) {
                                                    hint = "一条最多 $LT_STRIP_MAX 格"
                                                } else {
                                                    stripSel = stripSel + key
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val t = thumb
                                    if (t != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = t,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text("…", color = Dv.label3, fontSize = 12.sp)
                                    }
                                    if (stripMode) {
                                        Box(
                                            Modifier.align(Alignment.TopStart).padding(5.dp)
                                                .size(20.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (picked) Orange else Color(0x8C000000)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                if (picked) "${order + 1}" else "",
                                                color = if (picked) Color.Black else Color.White,
                                                fontSize = 11.sp, fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (stripMode) {
                        val can = stripSel.size >= 2
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                                .height(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (can) Orange.copy(alpha = 0.22f) else Color(0x12FFFFFF))
                                .iosTap(pressDim = 0.6f) {
                                    if (can) {
                                        val n = stripSel.size
                                        addPlate(
                                            stripSel.map { Uri.parse(it) },
                                            wide = minOf(0.96f, 0.30f + 0.16f * n)
                                        )
                                        stripSel = emptyList()
                                        pickerOpen = false
                                    } else {
                                        hint = "至少选 2 格才能拼成一条"
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (can) "拼成 1 条 · ${stripSel.size} 格"
                                else "按顺序点选 2 格以上，拼成一条长片",
                                color = if (can) Orange else Dv.label3,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    val pickHint = hint
                    if (pickHint != null) {
                        Text(
                            pickHint,
                            color = Orange,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 14.dp, bottom = 2.dp)
                        )
                    }
                }
            }
        }

        // ---------- 全屏：一条会自己躲起来的细工具条 ----------
        // 只在需要时出现（进来时露 3 秒，空白处轻点唤出），其余时间画面里
        // 一个控件都没有——全屏本来就是为了让灯箱占满整块屏幕。
        if (fullscreen) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val h = hint
                if (h != null) {
                    Text(
                        h,
                        color = Color(0xFFD9CBB2),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (barOn) {
                    Row(
                        Modifier.clip(RoundedCornerShape(15.dp))
                            .background(Color(0x99101214))
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        LtMini("放大镜", loupeOn) {
                            loupeOn = !loupeOn
                            barOn = true
                            // 开了放大镜，触摸就全交给取样点了：提醒一句怎么把工具条叫回来
                            if (fullscreen && loupeOn) hint = "轻点空白处可唤出工具条"
                        }
                        LtMini(if (lightOn) "关灯" else "开灯", !lightOn) {
                            lightOn = !lightOn
                            barOn = true
                        }
                        LtMini(if (rulerOn) "刻度 · 开" else "刻度", rulerOn) {
                            rulerOn = !rulerOn
                            barOn = true
                        }
                        if (panX != 0f || panY != 0f) {
                            LtMini("归位", false) {
                                resetView()
                                barOn = true
                            }
                        }
                        if (loupeOn) {
                            LtMini("${loupeZoom.toInt()}×", true) {
                                loupeZoom = when {
                                    loupeZoom < 3f -> 3f
                                    loupeZoom < 4f -> 4f
                                    else -> 2f
                                }
                                barOn = true
                            }
                        }
                        Box(
                            Modifier.width(1.dp).height(14.dp)
                                .background(Color(0x26FFFFFF))
                        )
                        LtMini("退出全屏", false) { fullscreen = false }
                    }
                }
            }
        }
    }
}

// ---------------- 几何 ----------------

private fun innerOf(size: Size, pad: Float): Size =
    Size(max(1f, size.width - pad * 2f), max(1f, size.height - pad * 2f))

private fun innerRect(size: Size, pad: Float): Rect =
    Rect(pad, pad, size.width - pad, size.height - pad)

/**
 * 片心在屏幕上的位置 = 灯箱中心 + 片的世界坐标 + 画布平移。
 *
 * 引入无限画布（r46）之后，片子的 cxDp / cyDp 是**世界坐标**：转屏、平移都不改它，
 * 改的只是 [pan]。这样"把片子拖出屏幕外再拖回来"能原样回来，转一圈屏也不会
 * 把摆好的版面揉掉。
 */
private fun plateCenter(p: LtPlate, size: Size, dens: Float, pan: Offset = Offset.Zero): Offset =
    Offset(
        size.width / 2f + (p.cxDp + pan.x) * dens,
        size.height / 2f + (p.cyDp + pan.y) * dens,
    )

private fun clampToBoard(pos: Offset, size: Size, pad: Float, r: Float): Offset {
    if (size.width <= 0f) return pos
    val inner = innerOf(size, pad)
    val loX = pad + minOf(r, inner.width / 2f)
    val hiX = pad + inner.width - minOf(r, inner.width / 2f)
    val loY = pad + minOf(r, inner.height / 2f)
    val hiY = pad + inner.height - minOf(r, inner.height / 2f)
    return Offset(
        if (hiX > loX) pos.x.coerceIn(loX, hiX) else pos.x,
        if (hiY > loY) pos.y.coerceIn(loY, hiY) else pos.y
    )
}

/** 镜心：取样点上方 1.3r；上沿放不下就翻到下方；再整体的把镜片收在灯箱内 */
private fun lensCenter(focus: Offset, r: Float, inner: Rect): Offset {
    var cx = focus.x
    var cy = focus.y - r * 1.30f
    if (cy - r < inner.top) cy = focus.y + r * 1.30f
    val loX = inner.left + r
    val hiX = inner.right - r
    val loY = inner.top + r
    val hiY = inner.bottom - r
    if (hiX > loX) cx = cx.coerceIn(loX, hiX) else cx = inner.center.x
    if (hiY > loY) cy = cy.coerceIn(loY, hiY) else cy = inner.center.y
    return Offset(cx, cy)
}

/** 命中测试：从最上层往下找，把点逆旋转到片子本地坐标再判矩形 */
private fun hitPlate(
    pos: Offset, plates: List<LtPlate>, size: Size, dens: Float,
    pan: Offset = Offset.Zero, board: Rect? = null,
): Int? {
    if (size.width <= 0f) return null
    // 点在灯箱面之外（那圈黑框里）什么也没点中：片子是被灯箱裁过的，
    // 看不见的那一半就不该还能点到 —— 否则会把一张"已经滑出去的片"拖回来
    if (board != null && !board.contains(pos)) return null
    for (i in plates.indices.reversed()) {
        val p = plates[i]
        val c = plateCenter(p, size, dens, pan)
        val w = plateWpx(p, dens)
        val h = plateHpx(p, dens)
        val a = Math.toRadians(-p.rot.toDouble())
        val dx = (pos.x - c.x).toDouble()
        val dy = (pos.y - c.y).toDouble()
        val rx = dx * cos(a) - dy * sin(a)
        val ry = dx * sin(a) + dy * cos(a)
        if (abs(rx) <= w / 2f && abs(ry) <= h / 2f) return i
    }
    return null
}

// ---------------- 绘制 ----------------

/**
 * 灯箱面。
 *
 * `tile` 非空时用 F16 发光贴图铺底——值超过 1.0，在 HDR 窗口下就是一块
 * 真比界面白更亮的灯箱；否则退回 SDR 的多层渐变（暖白面 + 中心光斑 + 边缘衰减）。
 *
 * `level` 是灯光的强度：1 = 灯全开；0 = 关灯，这时面板退回一块被室内光打到的
 * 乳白亚克力——不是黑屏。真实灯箱断电就是这个样子：亚克力还是灰白的，负片却
 * 暗下去。所以点亮的部分整体乘 level，底下的亚克力始终在。
 */
private fun DrawScope.drawLightBox(
    pad: Float,
    radius: Float,
    tile: ImageBitmap?,
    level: Float,
    ruler: Boolean,
    tm: TextMeasurer?,
    pan: Offset = Offset.Zero,
) {
    val r = Rect(pad, pad, size.width - pad, size.height - pad)
    if (r.width <= 0f || r.height <= 0f) return

    // 灯箱漏到黑底上的光：三层收得紧一点，给灯箱面让出宽度。灯一关，漏光也没了
    if (level > 0.004f) {
        for (i in 1..3) {
            val g = i * 1.9.dp.toPx()
            drawRoundRect(
                color = Color(0xFFFFF6E6).copy(alpha = 0.055f * (4 - i) * level),
                topLeft = Offset(r.left - g, r.top - g),
                size = Size(r.width + g * 2f, r.height + g * 2f),
                cornerRadius = CornerRadius(radius + g)
            )
        }
    }

    val path = Path().apply { addRoundRect(RoundRect(r, CornerRadius(radius))) }
    clipPath(path) {
        // 底：没点亮的亚克力。室内光从上方来，上沿稍亮，下沿沉一点
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF6E6961), Color(0xFF605B53), Color(0xFF4E4942)),
                start = Offset(r.left, r.top),
                end = Offset(r.right, r.bottom)
            ),
            topLeft = r.topLeft,
            size = r.size
        )
        // 亚克力自己的大面积反光：一整块均匀的灰会假得像色卡
        val sheenC = Offset(r.center.x, r.center.y - r.height * 0.10f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x12FFFFFF), Color(0x00FFFFFF)),
                center = sheenC,
                radius = r.width * 0.86f
            ),
            radius = r.width * 0.86f,
            center = sheenC
        )

        if (level > 0.004f) {
            if (tile != null) {
                drawImage(
                    image = tile,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(tile.width, tile.height),
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    alpha = level,
                    filterQuality = FilterQuality.High
                )
            } else {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFFEFA), Color(0xFFFBF3E4), Color(0xFFF2E6D1)),
                        start = Offset(r.left, r.top),
                        end = Offset(r.right, r.bottom)
                    ),
                    topLeft = r.topLeft,
                    size = r.size,
                    alpha = level
                )
                val glowC = Offset(r.center.x, r.center.y - r.height * 0.06f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x4DFFFFFF), Color(0x00FFFFFF)),
                        center = glowC,
                        radius = r.width * 0.72f
                    ),
                    radius = r.width * 0.72f,
                    center = glowC,
                    alpha = level
                )
            }
            // 灯箱四角压一点点暗，避免整块像贴了张纯色纸
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to Color(0x1A000000),
                    center = r.center,
                    radius = max(r.width, r.height) * 0.66f
                ),
                topLeft = r.topLeft,
                size = r.size,
                alpha = level
            )
        }

        // 灯丝的余温：亮到一半时偏暖，全亮或全灭时都不暖。真实灯箱的灯管
        // 在起亮和熄灭的中间状态就是这种橘色，不是简单的线性淡入淡出。
        if (level in 0.01f..0.985f) {
            val warm = sin(PI.toFloat() * level) * (1f - level)
            if (warm > 0.002f) {
                drawRect(
                    color = Color(0xFFFF9A3C).copy(alpha = 0.30f * warm),
                    topLeft = r.topLeft,
                    size = r.size
                )
            }
        }

        if (ruler) drawBoardRuler(r, tm, pan)
    }
}

/**
 * 灯箱边上的刻度尺：上边一条横向、左边一条纵向，就像真实观片台贴的那把钢尺。
 *
 * 整块灯箱按 300 mm 宽的工作台算，所以 5 mm 一小格、10 mm 中格、50 mm 长格带数字；
 * 两个方向共用同一套 mm 尺度，格子才是方的。刻度画在灯箱面之上、片子之下——
 * 片子摆上去会盖住刻度，本来就该这样。
 */
private fun DrawScope.drawBoardRuler(r: Rect, tm: TextMeasurer?, pan: Offset = Offset.Zero) {
    val mm = r.width / 300f
    if (mm <= 0.4f) return
    val band = 20.dp.toPx()
    // 亚克力上印的墨：偏暖的黑，不能是纯黑（纯黑在灯箱上像贴图）
    val ink = Color(0xFF2B2620)
    val major = max(6f, 1.dp.toPx() * 0.9f)
    val minor = max(4f, 1.dp.toPx() * 0.5f)

    // 横向：贴着上边，刻度向下伸。
    //
    // 刻度是**印在亚克力上**的，所以它跟着画布一起走（r46 的无限画布）：平移时
    // 刻度和数字整体滑动，这正是"台面在动、我在上面挪"的体感；不跟着走的话，
    // 画布移动就完全没有参照物了。原点 0 在进场时的左上角，往负方向也照样标。
    val x0 = floor((-pan.x / mm) / 5f).toInt() * 5
    val x1 = ceil((r.width - pan.x) / mm / 5f).toInt() * 5
    for (i in x0..x1 step 5) {
        val x = r.left + i * mm + pan.x
        if (x < r.left - 0.5f || x > r.right - 0.5f) continue
        val long = i % 50 == 0
        val len = when {
            long -> major * 2.2f
            i % 10 == 0 -> major * 1.4f
            else -> minor
        }
        drawLine(
            color = ink.copy(alpha = if (long) 0.62f else 0.40f),
            start = Offset(x, r.top),
            end = Offset(x, r.top + len),
            strokeWidth = if (long) 1.1.dp.toPx() else 0.8.dp.toPx()
        )
        if (long && tm != null && i != 0) {
            val label = i.toString()
            val t = tm.measure(
                label,
                TextStyle(color = ink.copy(alpha = 0.55f), fontSize = 7.5.sp, fontWeight = FontWeight.Medium)
            )
            drawText(t, topLeft = Offset(x + 2.dp.toPx(), r.top + len + 1.dp.toPx()))
        }
    }
    // 半透明压边，让刻度贴住灯箱边而不飘在画面上
    drawRect(
        brush = Brush.verticalGradient(
            listOf(Color(0x14000000), Color(0x00000000)),
            startY = r.top, endY = r.top + band
        ),
        topLeft = r.topLeft,
        size = Size(r.width, band)
    )

    // 纵向：贴着左边，刻度向右伸；数字横排（转 90° 反而不好读）
    val y0 = floor((-pan.y / mm) / 5f).toInt() * 5
    val y1 = ceil((r.height - pan.y) / mm / 5f).toInt() * 5
    for (i in y0..y1 step 5) {
        val y = r.top + i * mm + pan.y
        if (y < r.top - 0.5f || y > r.bottom - 0.5f) continue
        val long = i % 50 == 0
        val len = when {
            long -> major * 2.2f
            i % 10 == 0 -> major * 1.4f
            else -> minor
        }
        drawLine(
            color = ink.copy(alpha = if (long) 0.62f else 0.40f),
            start = Offset(r.left, y),
            end = Offset(r.left + len, y),
            strokeWidth = if (long) 1.1.dp.toPx() else 0.8.dp.toPx()
        )
        if (long && tm != null && i != 0) {
            val label = i.toString()
            val t = tm.measure(
                label,
                TextStyle(color = ink.copy(alpha = 0.55f), fontSize = 7.5.sp, fontWeight = FontWeight.Medium)
            )
            drawText(t, topLeft = Offset(r.left + len + 2.dp.toPx(), y - t.size.height / 2f))
        }
    }
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(Color(0x14000000), Color(0x00000000)),
            startX = r.left, endX = r.left + band
        ),
        topLeft = r.topLeft,
        size = Size(band, r.height)
    )
}

/**
 * 一张片（或一条片条）——就是成片本身，不再另画片基。
 *
 * 成片里真实存在的齿孔（[holes]，归一化到该格）在绘制时被从裁剪路径里减掉，
 * 孔里一个像素都不画：透出来的是灯箱，或者叠在下面的另一张片。没检测到孔
 * 的照片（没开片边，或 120 这类无孔格式）就是一张干干净净的相纸，不会被
 * 套上一圈假齿孔。
 */
private fun DrawScope.drawPlate(
    imgs: List<ImageBitmap?>,
    holes: List<List<LtHole>?>,
    glow: ImageBitmap?,
    c: Offset,
    w: Float,
    ars: List<Float>,
    rot: Float,
    selected: Boolean,
    dim: Float = 0f,
) {
    if (imgs.isEmpty()) return
    val n = imgs.size
    val sumAr = ars.sum().coerceAtLeast(0.05f)
    val h = w / sumAr

    rotate(rot, pivot = c) {
        val tl = Offset(c.x - w / 2f, c.y - h / 2f)
        val rect = Rect(tl.x, tl.y, tl.x + w, tl.y + h)
        val corner = CornerRadius(LtCorner.toPx())

        // 每格的横向区间（同一高度并排）
        val edges = FloatArray(n + 1)
        var acc = 0f
        for (i in 0 until n) {
            edges[i] = tl.x + w * acc / sumAr
            acc += ars.getOrElse(i) { 1.5f }
        }
        edges[n] = tl.x + w

        val outer = Path().apply { addRoundRect(RoundRect(rect, corner)) }

        // 漏光只铺在片外：灯箱的光从片边透出来，片像浮在光里。关灯就没有这层光
        if (glow != null && dim < 0.995f) {
            val g = min(w, h) * 0.07f + 4f
            clipPath(outer, ClipOp.Difference) {
                drawImage(
                    image = glow,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(glow.width, glow.height),
                    dstOffset = IntOffset((tl.x - g).roundToInt(), (tl.y - g).roundToInt()),
                    dstSize = IntSize((w + g * 2f).roundToInt(), (h + g * 2f).roundToInt()),
                    alpha = 0.8f * (1f - dim),
                    blendMode = BlendMode.Screen,
                    filterQuality = FilterQuality.Low
                )
            }
        }

        // 每格里的图：按图片自己的画幅比装进格子，**绝不裁切**。片边、片码、
        // 齿孔都长在画面上，裁掉任何一个都是在改用户的成片——观片台只负责把
        // 片子照亮，没有资格替用户重新构图。
        val imgRects = ArrayList<Rect>(n)
        for (i in 0 until n) {
            val cellW = edges[i + 1] - edges[i]
            val img = imgs.getOrNull(i)
            val ar = if (img != null && img.height > 0) img.width.toFloat() / img.height
            else ars.getOrElse(i) { 1.5f }
            val cw: Float
            val chh: Float
            if (cellW / h > ar) { chh = h; cw = h * ar } else { cw = cellW; chh = cellW / ar }
            val l = edges[i] + (cellW - cw) / 2f
            val t = tl.y + (h - chh) / 2f
            imgRects.add(Rect(l, t, l + cw, t + chh))
        }

        // 所有格上真实存在的孔，按**图**的坐标换算成绝对坐标，一次性减掉
        val holePath = Path()
        var anyHole = false
        for (i in 0 until n) {
            val hs = holes.getOrNull(i) ?: continue
            if (hs.isEmpty()) continue
            val r = imgRects[i]
            for (hole in hs) {
                val x0 = r.left + hole.u0 * r.width
                val x1 = r.left + hole.u1 * r.width
                val y0 = r.top + hole.v0 * r.height
                val y1 = r.top + hole.v1 * r.height
                if (x1 - x0 < 0.5f || y1 - y0 < 0.5f) continue
                // 齿孔是冲出来的，四角带一点圆：纯方块看着像贴纸。圆角别太大，
                // 圆角切掉的那点面积必须小于检测时外扩的余量，否则真孔的直角
                // 会在圆角外露出来（白点）
                val hr = min(x1 - x0, y1 - y0) * 0.13f
                holePath.addRoundRect(RoundRect(Rect(x0, y0, x1, y1), CornerRadius(hr)))
                anyHole = true
            }
        }
        val clip = if (!anyHole) outer else runCatching {
            Path().apply { op(outer, holePath, PathOperation.Difference) }
        }.getOrElse { outer }

        clipPath(clip) {
            for (i in 0 until n) {
                val img = imgs.getOrNull(i)
                val r = imgRects[i]
                if (img != null) {
                    drawImage(
                        image = img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(
                            r.width.roundToInt().coerceAtLeast(1),
                            r.height.roundToInt().coerceAtLeast(1)
                        ),
                        filterQuality = FilterQuality.High
                    )
                } else {
                    drawRect(Color(0xFF24201C), r.topLeft, r.size)
                }
            }

            // 关灯：负片没有背光就只剩一片暗，形状还在，颜色没了。压暗只压在
            // 片子本身（clip 里已经挖掉了齿孔）——孔照旧透出下面的亚克力，
            // 那一点点亮边正是"关灯后还看得见片边"的真实感觉。
            if (dim > 0.002f) {
                drawRect(Color(0xFF0B0A09), rect.topLeft, rect.size, alpha = 0.93f * dim)
                // 室内光落在片子表面的那层弱反光：灯亮的时候根本看不见，关灯才显出来
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.075f * dim),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.025f * dim)
                        ),
                        start = Offset(tl.x, tl.y),
                        end = Offset(tl.x + w, tl.y + h * 0.9f)
                    ),
                    topLeft = rect.topLeft,
                    size = rect.size
                )
            }
        }

        // 一道极细的边：片子才像一片实体，而不是墙上贴了张图
        drawPath(
            path = outer,
            color = if (selected) Orange else Color(0x33FFFFFF),
            style = Stroke(if (selected) 1.6.dp.toPx() else max(0.7f, 1.dp.toPx() * 0.7f))
        )

        if (selected) {
            val o = 3.dp.toPx()
            drawRect(
                color = Orange.copy(alpha = 0.55f),
                topLeft = Offset(tl.x - o, tl.y - o),
                size = Size(w + o * 2f, h + o * 2f),
                style = Stroke(1.dp.toPx())
            )
        }
    }
}

@Composable
private fun LtSeg(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(28.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0x30FFFFFF) else Color.Transparent)
            .iosTap(pressDim = 0.7f) { onClick() }
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.White else Dv.label3,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 顶栏上的小开关：32dp 高、无重底色，和「+ 添加」并排不抢戏 */
@Composable
private fun LtTopChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = 3.dp).height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .iosTap(pressDim = 0.6f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.height(32.dp).clip(RoundedCornerShape(10.dp))
                .background(if (active) Orange.copy(alpha = 0.24f) else Color(0x1FFFFFFF))
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (active) Orange else Dv.label1,
                fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LtChip(label: String, active: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    danger -> Dv.red.copy(alpha = 0.16f)
                    active -> Orange.copy(alpha = 0.22f)
                    else -> Color(0x1FFFFFFF)
                }
            )
            .padding(horizontal = 13.dp)
            .iosTap(pressDim = 0.6f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                danger -> Dv.red
                active -> Orange
                else -> Dv.label1
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 全屏工具条上的小条目：26dp 高、无底色的细按钮，尽量不挡片 */
@Composable
private fun LtMini(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(26.dp).clip(RoundedCornerShape(12.dp))
            .background(if (active) Orange.copy(alpha = 0.22f) else Color.Transparent)
            .padding(horizontal = 11.dp)
            .iosTap(pressDim = 0.55f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Orange else Color(0xE6FFFFFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
