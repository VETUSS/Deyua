package com.spektra.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 苹果式「山丘刻度」滑块 —— apple-interface-design 规范 · 形态级重做。
 *
 * 视觉（醒目 + 苹果质感）：
 *  - 一条圆角胶囊「轨道槽」承载刻度（玻璃基座），刻度从槽上向上生长——不再悬空细碎；
 *  - 刻度整体加粗：主刻度粗而清晰，当前刻度最粗、用垂直渐变 + 顶部柔光辉光作高光焦点；
 *  - 主刻度距当前越近越亮（距离渐隐），形成「中间最高、两边渐低」的平滑山丘（高斯）；
 *  - 次刻度压到极淡，减少噪音，焦点服从优先级。
 *
 * 交互/动效（Apple 流体）：
 *  - 吸附：值只落在刻度档位（snapStep 粒度）；
 *  - 拖动时山丘「纯跟手」——高度由 value 直接推导、无动画重置，绝不闪烁卡顿；
 *  - 按下瞬间一次柔和淡入（可中断弹簧），松手后保持凸起表达当前位置；
 *  - 跨刻度触觉随速度分级：慢=TICK 清脆 / 中=CLICK / 快=DOUBLE_CLICK；
 *  - 「减少动态」时淡入降级为瞬时。
 *
 * 触觉安全：vibrate() 需 VIBRATE 权限，try-catch 兜底，权限被拒也不闪退。
 */
@Composable
fun TickerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    snapStep: Float,    // 吸附粒度 = 每个刻度的间隔（值档位 + 震动 + 次刻度）
    majorStep: Float,   // 主刻度间隔（视觉较亮，层级更强）
    reduceMotion: Boolean,
    // [r12] 该滑杆的「默认值」（引擎默认 / 出厂值）。给了就在右侧画一个小小的
    // 回位按钮；点一下 = 立刻回到这个值并提交。它必须是**滑杆自己的值域**里的数
    // （比如预闪那根杆传 0，亮度的杆传 0 档，不是 print_exposure 的 1.0）。
    default: Float,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val tickBaseH = with(d) { 9.dp.toPx() }      // 刻度平时高度（粗而明显）
    val peakH = with(d) { 18.dp.toPx() }         // 当前刻度上浮峰值（山丘最高点）
    val sigma = 1.15f                            // 山丘宽度（越大越缓、越宽）
    val glowR = with(d) { 7.dp.toPx() }          // 当前刻度顶部柔光半径
    val edgePad = with(d) { 10.dp.toPx() }

    val start = valueRange.start
    val span = valueRange.endInclusive - start

    // [r36] 手势块只在 valueRange / snapStep 变化时重启，所以闭包里那份
    // onValueChange 是**第一次组合**时的那一个 —— 而调用点的 lambda 又往往
    // 捕获了一份参数快照（「暗房」整组共用一个 DarkroomCfg）。后果：拖 A 杆
    // 送出去的是 `cfg.copy(A = …)`，里面的 B、C、D 还是打开面板那一刻的值，
    // 于是 B 自己跳回去。用户看到的就是「调阴影侧，高光侧跳回 2.0」。
    // 和 r33 的曲线手势同一个坑：手势块必须读最新回调，不能靠闭包。
    // 名称避开手势块里的 `change`（那是 PointerInputChange）
    val emit = rememberUpdatedState(onValueChange)
    val finish = rememberUpdatedState(onValueChangeFinished)

    // 当前吸附档位索引；山丘高度直接由 value 推导（纯跟手，无动画重置）
    val currentIdx = ((value - start) / snapStep).roundToInt()

    // 山丘「淡入」：仅按下瞬间从平缓弹起一次，拖动中保持（解决闪烁卡顿）
    val bump = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val bounceSpec: AnimationSpec<Float>? = if (reduceMotion) null else
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
    LaunchedEffect(dragging, reduceMotion) {
        if (dragging && bump.value < 0.9f) {
            if (bounceSpec == null) bump.snapTo(1f) else { bump.snapTo(0f); bump.animateTo(1f, bounceSpec) }
        } else if (!dragging && bump.value <= 0f) {
            if (bounceSpec == null) bump.snapTo(1f) else bump.animateTo(1f, bounceSpec)
        }
    }

    // iOS 式跨刻度触觉，随拖动速度分级；权限缺失/异常时静默降级，绝不闪退
    fun hapticTick(speedPxPerMs: Float) {
        runCatching {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 29) {
                val eff = when {
                    speedPxPerMs >= 1.2f -> VibrationEffect.EFFECT_DOUBLE_CLICK  // 快速横扫：急促双连
                    speedPxPerMs >= 0.4f -> VibrationEffect.EFFECT_CLICK          // 中速：中等
                    else -> VibrationEffect.EFFECT_TICK                            // 慢速逐格：清脆单次
                }
                v.vibrate(VibrationEffect.createPredefined(eff))
            } else if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else @Suppress("DEPRECATION") v.vibrate(8)
        }
    }

    var lastTick by remember { mutableIntStateOf(Int.MIN_VALUE) }

    // 是否已经不在默认值上（按钮的亮/暗就按这个来）
    val dirty = abs(value - default) > (snapStep * 0.5f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Canvas(
        Modifier
            .weight(1f)
            .height(48.dp)
            .pointerInput(valueRange, snapStep) {
                val trackW = size.width.toFloat()                 // 手势回调无 PointerInputScope，提前捕获
                val fracOf = { px: Float -> ((px - edgePad) / (trackW - 2 * edgePad)).coerceIn(0f, 1f) }
                val idxOf = { v: Float -> kotlin.math.round((v - start) / snapStep).toInt() }
                var lastX = -1f
                var lastT = 0L
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        dragging = true
                        lastTick = idxOf(start + span * fracOf(pos.x))
                        lastX = pos.x; lastT = SystemClock.uptimeMillis()
                    },
                    onDragEnd = { dragging = false; finish.value() },
                    onDragCancel = { dragging = false; finish.value() },
                ) { change, _ ->
                    change.consume()
                    val x = change.position.x
                    val t = change.uptimeMillis
                    val speed = if (lastT > 0 && t > lastT) abs(x - lastX) / (t - lastT) else 0f
                    lastX = x; lastT = t
                    val idx = idxOf(start + span * fracOf(x))
                    if (idx != lastTick) { lastTick = idx; hapticTick(speed) }
                    emit.value((start + idx * snapStep).coerceIn(start, valueRange.endInclusive))
                }
            }
    ) {
        val w = size.width; val h = size.height
        val baseY = h * 0.70f                        // 轨道中线 / 刻度基座
        val usableW = (w - 2 * edgePad).coerceAtLeast(1f)
        val xOf = { v: Float -> edgePad + (v - start) / span * usableW }
        val nTicks = (span / snapStep).roundToInt()

        // 山丘高度轮廓：以当前刻度为中心的高斯曲线；乘以 bump 做按下淡入
        val liftOf = { dist: Int -> peakH * exp(-(dist * dist) / (2f * sigma * sigma)) * bump.value }

        for (i in 0..nTicks) {
            val v = start + i * snapStep
            val x = xOf(v.coerceIn(start, valueRange.endInclusive))
            val isMajor = abs(((v - start) / majorStep) - ((v - start) / majorStep).roundToInt()) < 1e-4f
            val dist = abs(i - currentIdx)
            val topH = tickBaseH + liftOf(dist)
            val top = Offset(x, baseY - topH)
            val bottom = Offset(x, baseY)
            val cur = isMajor && dist == 0
            if (isMajor) {
                val fade = (1f - dist / 3f).coerceIn(0f, 1f)
                val alpha = 0.35f + 0.60f * fade     // 当前≈0.95，随距离衰减
                if (cur) {
                    // 当前刻度：最粗 + 垂直渐变 + 顶部柔光辉光（苹果高光焦点）
                    if (topH > tickBaseH + 0.5f)
                        drawCircle(Dv.accent.copy(alpha = 0.30f), radius = glowR * 1.25f, center = top)
                    drawLine(
                        brush = Brush.verticalGradient(
                            colors = listOf(Dv.paper, Dv.accent.copy(alpha = 0.75f)),
                            startY = top.y, endY = baseY,
                        ),
                        start = top, end = bottom, strokeWidth = 3.0f, cap = StrokeCap.Round,
                    )
                } else {
                    drawLine(Dv.paper.copy(alpha = alpha), top, bottom, strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
            } else {
                drawLine(Dv.paper.copy(alpha = 0.13f), top, bottom, strokeWidth = 1.4f, cap = StrokeCap.Round)
            }
        }
    }
        ResetDot(
            enabled = dirty,
            onClick = {
                // 与手动拖到该档位走同一条路径：先改值，再提交（提交才会触发渲染）
                onValueChange(default)
                onValueChangeFinished()
            },
        )
    }
}

/**
 * 滑杆右侧那颗小小的「回位」按钮（r12 / r13 打磨）。
 *
 * 一个圆底 + 一圈带箭头的开口圆弧（↺）。**始终占位**，只是不在默认值上时才亮起来 ——
 * 这样外观不会因为值变化而左右跳动，滑杆宽度也不会变。
 * 已经等于默认值时按钮变暗且不可点，避免"点了没反应"的手感。
 *
 * [r13] 两处手感修正：
 *  - 触控区：视觉还是 26dp，但外面套一层 34×44dp 的可点区域（滑杆本体高 48dp，
 *    所以这层是"免费"的纵向空间）。原来 26dp 的小圆点是要瞄准才点得中的。
 *  - 无障碍：Canvas 画的图标读屏拿不到名字，补 contentDescription = "回到默认值"。
 */
@Composable
private fun ResetDot(enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.28f
    Box(
        Modifier
            .size(width = 34.dp, height = 44.dp)
            .semantics { contentDescription = "回到默认值" }
            .then(
                if (enabled) Modifier.iosTap(pressScale = 0.9f, pressDim = 0.7f, haptic = true) { onClick() }
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.10f else 0.045f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val stroke = 1.5.dp.toPx()
            // 半径要给箭头留出位置：箭头是横跨圆弧的三角形，一半会露在弧外面，
            // 半径按「画布半径 - 1.5dp」取，三角外缘正好落在画布内，不会被裁掉。
            val r = size.minDimension / 2f - 1.5.dp.toPx()
            val a0 = -40.0
            // 开口圆弧：从右上角 40° 前开始，顺时针画 285°，缺口留在右上
            drawArc(
                color = Color.White.copy(alpha = 0.92f * alpha),
                startAngle = a0.toFloat(), sweepAngle = 285f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2f, r * 2f),
            )
            // 箭头画在弧的起点，沿「回转方向」（逆着画弧的方向）指出去：
            // t = 反向切线，n = 径向。这样无论半径多大，箭头都贴着弧走，不会歪。
            val th = Math.toRadians(a0)
            val cosT = kotlin.math.cos(th).toFloat()
            val sinT = kotlin.math.sin(th).toFloat()
            val p = Offset(center.x + r * cosT, center.y + r * sinT)
            val t = Offset(sinT, -cosT)     // 反向切线（箭头指向）
            val n = Offset(cosT, sinT)      // 径向
            val apex = 2.2.dp.toPx()
            val half = 1.3.dp.toPx()
            val path = Path().apply {
                moveTo(p.x + t.x * apex, p.y + t.y * apex)
                lineTo(p.x + n.x * half, p.y + n.y * half)
                lineTo(p.x - n.x * half, p.y - n.y * half)
                close()
            }
            drawPath(path, Color.White.copy(alpha = 0.92f * alpha))
        }
    }
    }
}
