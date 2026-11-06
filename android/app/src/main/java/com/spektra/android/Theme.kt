package com.spektra.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Deyva 设计系统（r14 大改版）。
 *
 * 旧版是"iOS 设置页的深色模式"：纯黑 + 中性灰卡片 + 系统橙，功能都在，但没有自己的性格，
 * 摆在一堆相机 App 里认不出来。这一版换了叙事——**暗房**：
 *
 *  · 底色不是纯黑，是微微偏暖的炭黑（0x0B0A08）。真实暗房没有纯黑的墙，安全灯把一切
 *    都染上一层暖褐；纯黑 + 中性灰是"屏幕"的灰，暖炭黑才是"房间"的灰。
 *  · 强调色从 iOS systemOrange(#FF9500) 换成**安全灯琥珀(#FFA53C)**：更黄一点、更旧一点，
 *    是暗房里那盏灯的颜色，而不是系统控件的高亮色。
 *  · 文字不用纯白，用**相纸白(#F3EDE3)**；次级文字是暖灰而不是蓝灰。整块界面因此有
 *    "纸与化学药水"的温度，而不是"金属与玻璃"的冷。
 *  · 数字一律等宽字体（FontFamily.Monospace）。光圈、EV、mm、µm 这些量值对齐后像仪表
 *    刻度盘上的读数 —— 这是胶片机的语言，也是区分"专业工具"和"设置项"最快的手段。
 *  · 层级用**发丝线**（1px, 8% 纸白）而不是大圆角卡片堆叠。卡片堆多了像表单，
 *    发丝线像印刷品的栏线。
 *
 * 组件层面的三个新形态：
 *  1. [UnderlineTabs] —— 一级导航用"文字 + 滑动下划线"，不再用白胶囊分段控件
 *     （白胶囊是 iOS 设置页的标志，也是旧版最"老土"的一处）。
 *  2. [DvChip] —— 二级选项用胶囊 chip，选中=琥珀字 + 琥珀细边 + 抬升底色。
 *  3. [DvToggle] —— 自绘开关（46×28 琥珀轨道），替掉 Material Switch 的大块绿色滑块。
 */
object Dv {
    // ---- 表面（暖炭黑，逐级抬升）----
    val root = Color(0xFF0B0A08)          // 画布 / 暗房墙面
    val panel = Color(0xFF12100D)         // 底板（sheet 主体）
    val panelRaised = Color(0xFF1C1915)   // 卡片 / chip / 输入框
    val raisedHigh = Color(0xFF262119)    // 再抬一级（按下态、选中底）
    val separator = Color(0xFF2B2620)     // 发丝线
    val label4 = Color(0xFF332D26)        // 弱分隔 / 占位块

    // ---- 玻璃层（顶栏、浮层）----
    val glassThin = Color(0x6612100D)
    val glassRegular = Color(0xCC12100D)
    val glassBar = Color(0xE612100D)
    val glassHighlight = Color(0x1AFFFFFF)

    // ---- 文字（相纸白 → 暖灰）----
    val label1 = Color(0xFFF3EDE3)
    val label2 = Color(0xFFB6ADA0)
    val label3 = Color(0xFF93897B)        // 三级灰：在 #1C1915 上约 4.9:1，正文门槛够
    val paper = Color(0xFFF3EDE3)

    // ---- 强调 ----
    val accent = Color(0xFFFFA53C)        // 安全灯琥珀
    val accentSoft = Color(0x33FFA53C)
    val accentDim = Color(0x22FFA53C)   // 选中底/进度槽
    val orangeLight = Color(0xFFFFC77A)
    val blue = Color(0xFF9CB0B8)          // 冷灰（仪表刻度 / 日志正文）
    val green = Color(0xFF6FCF97)
    val red = Color(0xFFFF6B5E)

    // ---- 圆角 ----
    val rSheet = 20.dp   // r15: 26 → 20，配合台沿渐隐，弧线不再切出月牙
    val rCard = 14.dp
    val rChip = 10.dp
    val rBtn = 12.dp

    // ---- 排版 ----
    val mono = FontFamily.Monospace
}

/**
 * 次级说明文字的通用样式（旧版到处是 10~11sp 的裸 Text，行距与字距各不相同）。
 * 中文没有大写，所以"小标题"的仪式感靠**字距**和**颜色**做，而不是靠全大写。
 */
@Composable
fun Label(text: String, color: Color = Dv.label3, size: Int = 10, spacing: Float = 1.6f,
          modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = size.sp, letterSpacing = spacing.sp,
        fontWeight = FontWeight.Medium, modifier = modifier)
}

/** 等宽数字读数（EV / mm / µm / 倍数）。右对齐由调用方用 Row 控制。 */
@Composable
fun Mono(text: String, color: Color = Dv.label1, size: Float = 13f, weight: FontWeight = FontWeight.Medium,
         modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = size.sp, fontFamily = Dv.mono, fontWeight = weight,
        modifier = modifier)
}

/** 发丝线：印刷品栏线的手感，比 Material Divider 细一半、短一截。 */
@Composable
fun Hairline(modifier: Modifier = Modifier, alpha: Float = 0.10f) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = alpha)))
}

// ---------------------------------------------------------------- 一级导航

/**
 * 文字 + 滑动下划线（r14）。
 *
 * 为什么换掉白胶囊分段控件：胶囊来自 iOS 设置页，是"系统偏好"的语汇；这一版要的是
 * 杂志/仪表的语汇 —— 两个词并排、下面一条琥珀短线跟着走。它更轻（不占一整块底色），
 * 也让预览区显得更安静。
 *
 * 下划线用容器 drawBehind 统一画、位置用弹簧追索引，所以是**滑动**过去的，
 * 连点几下会从当前位置继续（可中断），不是排队动画。
 */
@Composable
fun UnderlineTabs(
    labels: List<String>,
    index: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val pos by animateFloatAsState(index.toFloat(), livelySpec(reduceMotion), label = "tabUnderline")
    Row(modifier.fillMaxWidth().height(46.dp)
        .drawBehind {
            if (labels.isEmpty()) return@drawBehind
            val itemW = size.width / labels.size
            val w = itemW * 0.34f
            val x = itemW * pos.coerceIn(0f, labels.lastIndex.toFloat()) + (itemW - w) / 2f
            drawRoundRect(
                color = Dv.accent,
                topLeft = Offset(x, size.height - 2.dp.toPx() - 1.dp.toPx()),
                size = Size(w, 2.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }) {
        labels.forEachIndexed { i, label ->
            val sel = i == index
            Box(Modifier.weight(1f).height(46.dp)
                .iosTap(pressDim = 0.5f, haptic = true) { onSelect(i) },
                contentAlignment = Alignment.Center) {
                Text(label, color = if (sel) Dv.paper else Dv.label3,
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    letterSpacing = 1.2.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 二级选项

/** 选项胶囊。选中：琥珀字 + 琥珀 1dp 细边 + 抬升底；未选中：暖灰字 + 透明底。 */
@Composable
fun DvChip(
    label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, size: Int = 12,
) {
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) Dv.accentSoft else Color.Transparent, fluidSpec(false), label = "chipBg")
    val fg by androidx.compose.animation.animateColorAsState(
        if (selected) Dv.accent else Dv.label2, fluidSpec(false), label = "chipFg")
    Box(modifier.padding(end = 6.dp).clip(RoundedCornerShape(Dv.rChip))
        .background(bg)
        .border(1.dp, if (selected) Dv.accent.copy(alpha = 0.55f) else Dv.separator,
            RoundedCornerShape(Dv.rChip))
        .iosTap(pressScale = 0.95f, pressDim = 0.82f, haptic = true, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 9.dp)) {
        Text(label, color = fg, fontSize = size.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ---------------------------------------------------------------- 开关

/**
 * 自绘开关（r14）。Material3 的 Switch 是 52×32 的绿色大滑块，在暖色暗房里很跳；
 * 这个 46×28 的琥珀轨道更小、更"机器"。
 *
 * 无障碍：用 semantics 显式给出 role/state（画出来的控件读屏默认拿不到这些）。
 */
@Composable
fun DvToggle(checked: Boolean, onChange: (Boolean) -> Unit, desc: String? = null,
             enabled: Boolean = true) {
    val knob by animateFloatAsState(if (checked) 1f else 0f, fluidSpec(false), label = "knob")
    val track by androidx.compose.animation.animateColorAsState(
        if (checked) Dv.accent else Dv.raisedHigh, fluidSpec(false), label = "track")
    Box(
        Modifier.width(46.dp).height(28.dp)
            .semantics {
                if (desc != null) contentDescription = desc
                stateDescription = if (checked) "已开启" else "已关闭"
            }
            .iosTap(enabled = enabled, pressScale = 0.94f, pressDim = 0.85f, haptic = true) { onChange(!checked) }
            .clip(RoundedCornerShape(14.dp)).background(track)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = (18.dp * knob)).size(22.dp).clip(CircleShape)
            .background(if (checked) Dv.root else Dv.label2))
    }
}

// ---------------------------------------------------------------- 图标按钮

/** 顶栏 / 面板里的圆形图标按钮：40dp 视觉、44dp 热区、按下微缩。 */
@Composable
fun DvIconButton(
    glyph: Glyph, desc: String, onClick: () -> Unit,
    tint: Color = Dv.label1, box: Dp = 40.dp, glyphSize: Dp = 21.dp,
    bg: Color = Color.Transparent,
) {
    Box(Modifier.size(box).clip(CircleShape).background(bg)
        .semantics { contentDescription = desc }
        .iosTap(pressScale = 0.9f, pressDim = 0.5f, haptic = false, onClick = onClick),
        contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, tint, glyphSize)
    }
}

// ---------------------------------------------------------------- 折叠分组

/**
 * 可折叠分组（r14）。旧的「格式」页是一整条 40 多个控件的长滚动：颗粒、光晕、片边、
 * 日期、暗房、相机全平铺，找一个参数要滑三屏，而且永远不知道下面还有什么。
 *
 * 折叠后一屏能看见全部七个分组标题，点开哪组看哪组 —— 这是"工具"而不是"表单"。
 * 折叠头是整行 52dp 可点，右侧小箭头随展开旋转（位移类动画 → 用带一点回弹的 livelySpec）。
 */
@Composable
fun Section(
    title: String, en: String, index: Int, open: Boolean, onToggle: (Int) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val arrow by animateFloatAsState(if (open) 180f else 0f, livelySpec(false), label = "arrow")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(50.dp)
            .iosTap(pressDim = 0.55f) { onToggle(index) }
            .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Dv.paper, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp)
            Spacer(Modifier.width(8.dp))
            Label(en.uppercase(), color = Dv.label3, size = 9, spacing = 1.8f)
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
            Spacer(Modifier.width(6.dp))
            androidx.compose.foundation.Canvas(Modifier.size(14.dp)) {
                val w = size.width; val h = size.height
                rotate(arrow) {
                    drawLine(Dv.label3, Offset(w * 0.22f, h * 0.38f), Offset(w * 0.5f, h * 0.64f),
                        strokeWidth = 1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Dv.label3, Offset(w * 0.5f, h * 0.64f), Offset(w * 0.78f, h * 0.38f),
                        strokeWidth = 1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { content() }
        }
        Hairline(alpha = 0.07f)
    }
}

/** 一行参数标题：左中文名、右等宽读数。滑杆之前统一用这个，读数不会左右跳。 */
@Composable
fun ParamRow(title: String, value: String, modifier: Modifier = Modifier, note: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Dv.label2, fontSize = 12.5f.sp, letterSpacing = 0.4.sp)
        if (note != null) {
            Spacer(Modifier.width(6.dp))
            Label(note, color = Dv.label3, size = 9)
        }
        Spacer(Modifier.weight(1f))
        Mono(value, color = Dv.paper, size = 12.5f)
    }
}

/** 滑杆+读数的组合块（滑杆自带 48dp 高度与 4dp 内边距，这里只补间距）。 */
@Composable
fun ParamBlock(title: String, value: String, note: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        ParamRow(title, value, note = note)
        Spacer(Modifier.height(2.dp))
        content()
    }
}

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))
