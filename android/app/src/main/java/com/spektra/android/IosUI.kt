package com.spektra.android

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 触觉反馈（r13）。
 *
 * 之前只有 TickerSlider 会震，开关 / 分段 / 缩略图卡片点下去全都没反应 —— 手感和
 * 滑杆是两套语言。这里抽成一处，规则和滑杆保持一致：
 *  - [tick] 选中类（分段、胶囊、缩略图卡片、开关）：最轻的一下，只表示"收到了"；
 *  - [warn] 危险操作（删除）：稍重，给一点阻抗。
 *
 * 权限缺失 / 设备无马达时静默降级，绝不抛异常（老机器上 VIBRATOR_SERVICE 可能为 null）。
 */
object Haptics {
    fun tick(ctx: Context) = play(ctx, if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_TICK else -1)

    fun warn(ctx: Context) = play(ctx, if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_CLICK else -1)

    private fun play(ctx: Context, effect: Int) {
        runCatching {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (effect >= 0) {
                v.vibrate(VibrationEffect.createPredefined(effect))
            } else if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION") v.vibrate(6)
            }
        }
    }
}

/**
 * Apple 式按压反馈（r13）——「按下即反馈」。
 *
 * 现状问题：界面上所有可点区域都是 `Modifier.clickable`，在 iOS 风格表面上会盖出一层
 * Material 水波纹，语言是错的（涟漪是 Android 的隐喻，iOS 用「变暗 / 微缩」）。
 *
 * 这个修饰符做了三件事：
 *  1. 去掉水波纹（indication = null）；
 *  2. 按下立刻变暗 / 微缩，松手弹回 —— 用高刚度临界阻尼弹簧，按下约 1 帧到位，
 *     所以是"跟手"的，不是"等动画"；
 *  3. 可选触觉。
 *
 * 用法：把原来 `.clickable { ... }` 换成 `.iosTap { ... }`（参数默认值即"变暗 + 触觉关"）。
 * 文字按钮用 `pressDim = 0.45f, pressScale = 1f`（不缩，只暗，和 iOS 导航栏一致）；
 * 卡片 / 胶囊用 `pressScale = 0.96f, pressDim = 0.85f`（缩一点，像被按进去）。
 */
@Composable
fun Modifier.iosTap(
    enabled: Boolean = true,
    haptic: Boolean = false,
    pressScale: Float = 1f,
    pressDim: Float = 0.45f,
    onClick: () -> Unit,
): Modifier {
    val ctx = LocalContext.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val down = pressed && enabled
    // 刚度取 High：按下 ~70ms 内完成，肉眼就是"即时"；阻尼 1.0 不过冲，
    // 松手不会有一点回弹（回弹只留给手势驱动的位移动画）。
    val spec = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)
    val scale by animateFloatAsState(if (down) pressScale else 1f, spec, label = "iosPressScale")
    val dim by animateFloatAsState(if (down) pressDim else 1f, spec, label = "iosPressDim")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = dim
        }
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            if (haptic) Haptics.tick(ctx)
            onClick()
        }
}

/**
 * Apple 弹簧两条轨（r13）。
 *
 * 参考 WWDC「流体界面」给出的两档：
 *  - [fluidSpec]  默认 UI 变化（颜色、尺寸、透明度）→ 阻尼比 1.0，**不过冲**。
 *                 之前全界面用的是 DampingRatioMediumBouncy(0.5)，描边宽度、卡片缩放
 *                 都会来回抖一下，观感偏"果冻"，反而离苹果更远。
 *  - [livelySpec] 由手势/位移动画的场合（分段控制的胶囊滑动、选中卡片的弹起）→ 0.78，
 *                 轻微过冲，符合"带着动量"的直觉。
 *
 * 系统开启「减少动态」时两者都退化成 snap()。
 */
fun <T> fluidSpec(reduce: Boolean): AnimationSpec<T> =
    if (reduce) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow)

fun <T> livelySpec(reduce: Boolean): AnimationSpec<T> =
    if (reduce) snap() else spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

/**
 * 工具栏 / 按钮上的矢量小图标（r13）。
 *
 * 原来底栏三个按钮是文字字形「↺」「…」「⧉」——字形来自字体，粗细、基线、留白都不可控，
 * 混在真图标里一眼就"不是系统画的"。这里用 Canvas 按 24dp 网格画三枚，线宽统一 1.7dp、
 * 圆角端点，和系统 SF Symbols 的观感一致（也方便以后换色/换尺寸）。
 */
enum class Glyph { Undo, More, Copy, Plus, Grid, Pulse, Close }

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color = Color.White, box: androidx.compose.ui.unit.Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(box)) {
        val s = size.minDimension
        val stroke = s * 0.077f
        val c = center
        when (glyph) {
            Glyph.Undo -> {
                val r = s * 0.31f
                // 开口圆弧（顺时针 290°，缺口留右上），与 TickerSlider 的回位按钮同一几何
                drawArc(
                    color = tint, startAngle = -40f, sweepAngle = 290f, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2f, r * 2f),
                )
                // 箭头：贴着弧的起点、沿逆时针切线指出。底边沿切线回退 12%，
                // 让三角和弧的圆头稍微咬合，看起来是"一个符号"而不是"弧 + 一块碎片"。
                val th = Math.toRadians(-40.0)
                val cosT = kotlin.math.cos(th).toFloat(); val sinT = kotlin.math.sin(th).toFloat()
                val p = Offset(c.x + r * cosT, c.y + r * sinT)
                val t = Offset(sinT, -cosT)
                val n = Offset(cosT, sinT)
                val apex = s * 0.135f; val half = s * 0.072f
                val base = Offset(p.x + t.x * apex * 0.10f, p.y + t.y * apex * 0.10f)
                drawPath(Path().apply {
                    moveTo(base.x + t.x * apex, base.y + t.y * apex)
                    lineTo(base.x + n.x * half, base.y + n.y * half)
                    lineTo(base.x - n.x * half, base.y - n.y * half)
                    close()
                }, tint)
            }
            Glyph.More -> {
                val r = s * 0.068f
                listOf(-1f, 0f, 1f).forEach { k ->
                    drawCircle(tint, radius = r, center = Offset(c.x + k * s * 0.235f, c.y))
                }
            }
            Glyph.Copy -> {
                // 两张同尺寸纸片沿对角线错开 d，整体按 d 的一半居中（否则图标会偏右下）。
                // 后面那张只画「左 + 上」两条边（L 形）—— 两个完整矩形叠在一起，重叠处的
                // 轮廓会互相穿过，看起来像画错了；这也是系统 content_copy 的做法。
                val w = s * 0.48f
                val d = w * 0.36f
                val l = -w * 0.5f + d * 0.5f     // 前面那张的左边
                val tp = -w * 0.5f + d * 0.5f    // 前面那张的上边
                drawPath(Path().apply {
                    moveTo(l - d, tp + w - d * 0.55f)
                    lineTo(l - d, tp - d)
                    lineTo(l + w - d * 0.55f, tp - d)
                }, tint, style = Stroke(width = stroke * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawRoundRect(
                    color = tint, topLeft = Offset(l, tp),
                    size = Size(w, w), cornerRadius = CornerRadius(s * 0.085f),
                    style = Stroke(width = stroke * 0.95f),
                )
            }
            // [r14] 导入照片：一枚十字。线宽与其它图标统一，交点圆头不重叠（两段各让出半线宽）
            Glyph.Plus -> {
                val r = s * 0.30f
                drawLine(tint, Offset(c.x - r, c.y), Offset(c.x + r, c.y),
                    strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(c.x, c.y - r), Offset(c.x, c.y + r),
                    strokeWidth = stroke, cap = StrokeCap.Round)
            }
            // [r14] 相册：2×2 四格（与系统"照片"图标的语汇一致，但角更圆、格间距更大）
            Glyph.Grid -> {
                val w = s * 0.30f
                val gap = s * 0.075f
                val rad = s * 0.055f
                val o = w / 2f + gap / 2f
                listOf(-o to -o, o to -o, -o to o, o to o).forEach { (dx, dy) ->
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(c.x + dx - w / 2f, c.y + dy - w / 2f),
                        size = Size(w, w), cornerRadius = CornerRadius(rad),
                        style = Stroke(width = stroke * 0.9f),
                    )
                }
            }
            // [r14] 关闭：两条对角交叉线（交叉点留 1px 空隙，避免圆头叠出一个小疙瘩）
            Glyph.Close -> {
                val r = s * 0.24f
                drawLine(tint, Offset(c.x - r, c.y - r), Offset(c.x - r * 0.12f, c.y - r * 0.12f),
                    strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(c.x + r * 0.12f, c.y + r * 0.12f), Offset(c.x + r, c.y + r),
                    strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(c.x + r, c.y - r), Offset(c.x + r * 0.12f, c.y - r * 0.12f),
                    strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(c.x - r * 0.12f, c.y + r * 0.12f), Offset(c.x - r, c.y + r),
                    strokeWidth = stroke, cap = StrokeCap.Round)
            }
            // [r14] 诊断：一条心电图（表示"读数/信号"，而不是虫子或问号）
            Glyph.Pulse -> {
                val y = c.y
                val p = Path().apply {
                    moveTo(c.x - s * 0.33f, y)
                    lineTo(c.x - s * 0.16f, y)
                    lineTo(c.x - s * 0.07f, y - s * 0.18f)
                    lineTo(c.x + s * 0.03f, y + s * 0.20f)
                    lineTo(c.x + s * 0.13f, y - s * 0.06f)
                    lineTo(c.x + s * 0.20f, y)
                    lineTo(c.x + s * 0.33f, y)
                }
                drawPath(p, tint, style = Stroke(width = stroke * 0.95f,
                    cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
