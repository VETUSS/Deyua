package com.spektra.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater

val Orange = Dv.accent

// ===== 导出选项 =====
enum class ExportFormat(val label: String, val ext: String) {
    JPEG("JPEG", "jpg"), PNG("PNG", "png"), TIFF("TIFF", "tiff"), UHDR_JPEG("HDR JPEG", "jpg"),
    // [r20] HEIF/HEIC（HEVC 编码）。设备没有 HEVC 编码器时整个选项不出现，
    // 见 HeifExport.available —— 免得点了之后导不出来。
    HEIF("HEIF", "heic"),
}
val EXPORT_COLOR_SPACES = listOf("sRGB", "Display P3")  // Display P3 导出会嵌入 ICC，相册按 P3 正确显示
val EXPORT_SIZES = listOf("全尺寸", "2048", "1600", "1280")

// 配方 = 格式+色彩 预设组合
data class Recipe(val name: String, val format: ExportFormat, val color: String, val cctf: Boolean)

val RECIPES = listOf(
    Recipe("JPEG sRGB", ExportFormat.JPEG, "sRGB", true),
    Recipe("JPEG P3", ExportFormat.JPEG, "Display P3", true),
    Recipe("PNG 无损", ExportFormat.PNG, "sRGB", true),
    Recipe("TIFF 16 位", ExportFormat.TIFF, "sRGB", true),
    Recipe("HEIF sRGB", ExportFormat.HEIF, "sRGB", true),
)

// ===== 导出文件名规则（r11 第 4 批）=====
// 与上游 ExportRecipe.swift::NameToken 完全同一组四项：原片名 / 胶片 / 相纸 / 日期。
// 上游 notes.md 的原话是「Four Naming options … user can only choose between these
// four」，并且刻意**不用** printf 式格式串（格式串错了是静默出错）；这里照做：
// 四项开关 + 固定 "_" 连接，至少留一项。
enum class NameToken(val label: String, val hint: String) {
    ORIGINAL("原片名", "源文件名，不含扩展名"),
    FILM("胶片", "当前胶片型号"),
    PRINT("相纸", "当前相纸；直扫底片时为 film-scan"),
    DATE("日期", "冲洗日期 yyyyMMdd"),
}

/**
 * 拼文件名主干（不含扩展名、不含目录）。
 *
 * 某个 token 取值为空就跳过；四项全空则回落原片名 —— 空 stem 会导致反复覆盖
 * 同一个文件，上游同样以原片名为兜底。`/` 与 `:` 一律换成 `-`：文件名不能带
 * 路径分隔符，不管型号名里恰好出现了什么。
 */
fun buildNameStem(tokens: Set<NameToken>, srcName: String, film: String, print: String, date: String): String {
    val parts = listOf(
        NameToken.ORIGINAL to srcName,
        NameToken.FILM to film,
        NameToken.PRINT to print,
        NameToken.DATE to date,
    ).filter { it.first in tokens }.map { it.second.trim() }.filter { it.isNotEmpty() }
    val joined = if (parts.isEmpty()) srcName else parts.joinToString("_")
    return joined.replace("/", "-").replace(":", "-").trim().ifEmpty { "Deyva" }
}

// ===== 冲洗/导出进度 =====
data class ProgressStep(val label: String, var done: Boolean = false, var active: Boolean = false)

// ===== 导出面板 =====
// iOS 分段胶囊：父级用 panel 分组背景，选中项橙色填充（选中态不单靠颜色，仍保留文字与字重）
@Composable
fun SegChip(label: String, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.iosTap(pressScale = 0.95f, pressDim = 0.85f, haptic = true) { onClick() }
        .clip(RoundedCornerShape(10.dp)).background(if (sel) Orange else Color.Transparent)
        .padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = if (sel) Color.White else Dv.label2, fontSize = 11.5f.sp, maxLines = 1,
             overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
             fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
    }
}

/** 横向滚动区右端渐隐（r22）：划到边缘的内容柔和淡出，而不是被切成半截。 */
private fun Modifier.edgeFade(width: Dp = 22.dp): Modifier = drawWithContent {
    drawContent()
    val fade = width.toPx().coerceAtMost(size.width * 0.4f)
    if (fade <= 0f) return@drawWithContent
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Dv.panelRaised),
            startX = size.width - fade, endX = size.width),
        topLeft = Offset(size.width - fade, 0f),
        size = Size(fade, size.height),
    )
}

// ===== 导出面板（r21 重排）=====
// 上一版把 5 个格式塞进一条**横向滚动**的胶囊：JPEG / PNG / TIFF / HDR JPEG / HEIF。
// 窄屏上第 5 个正好被右边缘切掉 —— 用户反馈"导出界面里没看到 HEIF"。藏在滚动条外的
// 选项等于不存在，所以这一版换成 3 列**瓦片网格**：五档一眼全在，每档下面一句实话
// （有损/无损/16 位/增益图/HEVC），选中 = 琥珀细边 + 琥珀字。
//
// 配方原来占"3 行 × 2 列"（约 130dp），压成一行横滑小 chip；省下的高度正好让瓦片长出来。
// 下面的每一行都走 [ExportRow]：左标签固定 44dp，控件从同一条竖线起 —— 五行左边缘对齐。

/** 段落小标题：左中文名（字距拉开），右侧一句等宽读数（可为空）。 */
@Composable
private fun ExportSection(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Dv.paper, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            letterSpacing = 1.4.sp)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Mono(trailing, color = Dv.label3, size = 10f)
    }
}

/** 选项行：左标签固定 44dp，右边控件从同一条竖线开始。 */
@Composable
private fun ExportRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Dv.label3, fontSize = 11.sp, letterSpacing = 0.4.sp,
            modifier = Modifier.width(44.dp))
        content()
    }
}

/** 分段轨：选中项在轨里填琥珀（尺寸 / 色彩 / 存储到 / 格式共用）。 */
@Composable
private fun SegTrack(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(modifier.clip(RoundedCornerShape(Dv.rBtn)).background(Dv.panel).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/**
 * 分段格（r22）：比 [SegChip] 多一行 8sp 的副标，用来在格式那一排里说明"怎么编"。
 *
 * 为什么把 5 个格式压成一排：上一版是 3 列瓦片 × 2 行，5 档只能填满 5 格、右下角
 * 空一个洞；而且 81dp 宽的瓦片要放下「增益图 · 高动态」这种 7 字副标，必被裁掉一半
 * （截图里"只显示一部分"就是这个）。现在每格 46dp 宽，只放 2~3 个字的副标，
 * 说明统一收到下面一行 —— 既不再有空洞，也不会截字。
 */
@Composable
private fun SegCell(
    label: String, sub: String, sel: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(9.dp))
            .background(if (sel) Orange else Color.Transparent)
            .iosTap(enabled = enabled, pressScale = 0.95f, pressDim = 0.85f, haptic = true, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label, maxLines = 1,
            color = when {
                sel -> Color.White
                !enabled -> Dv.label4
                else -> Dv.label2
            },
            fontSize = 10.5f.sp,
            fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold
                         else androidx.compose.ui.text.font.FontWeight.Medium,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            sub, maxLines = 1,
            color = when {
                sel -> Color.White.copy(alpha = 0.72f)
                !enabled -> Dv.label4
                else -> Dv.label4
            },
            fontSize = 8.sp,
        )
    }
}

/** 格式的短名：分段轨里一格只有 46dp，写不下 "HDR JPEG"。 */
private fun formatShort(f: ExportFormat): String = when (f) {
    ExportFormat.UHDR_JPEG -> "HDR"
    else -> f.label
}

/** 格式的二字副标（写"怎么编"，不写形容词）。 */
private fun formatSub(f: ExportFormat, heifOk: Boolean): String = when (f) {
    ExportFormat.JPEG -> "通用"
    ExportFormat.PNG -> "无损"
    ExportFormat.TIFF -> "16 位"
    ExportFormat.UHDR_JPEG -> "增益图"
    ExportFormat.HEIF -> if (heifOk) "半体积" else "不支持"
}

/** 选中格式的一句说明 —— 原来那两段条件提示合并成这一行，位置固定、不再跳。 */
private fun formatNote(f: ExportFormat, heifOk: Boolean): String = when (f) {
    ExportFormat.JPEG -> "有损压缩，任何设备都能打开；日常分享用这个。"
    ExportFormat.PNG -> "无损、支持透明，文件较大；需要再修图时用。"
    ExportFormat.TIFF -> "16 位无损，给后期留足余量；单张动辄几十 MB。"
    ExportFormat.UHDR_JPEG -> "8 位主图 + 增益图。RAW 输入会多印一帧 3 档，把压在纸白里的高光读回来；JPEG 输入没有这份余量。"
    ExportFormat.HEIF -> if (heifOk)
        "HEVC 编码，体积约为 JPEG 的一半；容器不带 ICC，色彩固定 sRGB。"
    else "系统低于 Android 9，或本机没有 HEVC 编码器 —— 这一档不可选。"
}

/** 位深：格式区右上角那句读数（不像"有损/无损"那样和瓦片上的说明重复）。 */
private fun formatDepth(f: ExportFormat): String = when (f) {
    ExportFormat.TIFF -> "16 位"
    ExportFormat.UHDR_JPEG -> "8 位 + 增益图"
    else -> "8 位"
}

@Composable
fun ExportPanel(
    imgName: String, sourceW: Int, sourceH: Int,
    // [r11 第 4 批] 命名 token 的三份取值：原片名 / 当前胶片 / 当前相纸。
    // 日期在面板里取"此刻"（= 冲洗日），所以不放参数。
    srcName: String, filmName: String, printName: String,
    // saveDest: 0=照片 1=文件 2=共享；stem = 命名 token 拼出来的文件名主干
    onExport: (ExportFormat, String, String, Int, Int, String, () -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var format by remember { mutableStateOf(ExportFormat.JPEG) }
    var size by remember { mutableIntStateOf(0) }      // 全尺寸
    var color by remember { mutableStateOf("sRGB") }
    var quality by remember { mutableIntStateOf(100) }
    var saveDest by remember { mutableIntStateOf(0) }  // 0=照片 1=文件 2=共享
    var showing by remember { mutableStateOf(false) }
    val heifOk = remember { HeifExport.available }
    // 诊断：HEIF 这一项到底为什么可用/不可用。r24 起走 androidx.heifwriter，
    // 不再受 hidden-API 影响；这一行留着是为了区分"系统太老"和"真没有 HEVC 编码器"。
    LaunchedEffect(Unit) {
        Diagnostics.log("heif", "available=$heifOk sdk=${Build.VERSION.SDK_INT} " +
            "provider=androidx quality=${HeifExport.supportsQuality} formats=${ExportFormat.values().size}")
    }
    // 配方是"格式 + 色彩"的快捷组合；HEIF 不可用时那条配方也不出现（点了会导出失败）
    val recipes = remember { RECIPES.filter { it.format != ExportFormat.HEIF || heifOk } }
    // 默认开三项（原片名 + 胶片 + 相纸），日期默认关 —— 与上游 [.originalName,
    // .filmStock, .printStock] 的默认完全一致，不跟着日期把文件名拉长。
    var tokens by remember { mutableStateOf(setOf(NameToken.ORIGINAL, NameToken.FILM, NameToken.PRINT)) }
    val dateStr = remember { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }
    val stem = buildNameStem(tokens, srcName, filmName, printName, dateStr)

    // [r19] PNG / TIFF 是**无损**编码，quality 参数根本没人读 —— 旧版这里照样给一根
    // 滑杆，拖到 30 与拖到 100 导出的字节一模一样，是标准的"摆设控件"。
    val lossless = format == ExportFormat.PNG || format == ExportFormat.TIFF
    // [r20] API 28 的 HeifWriter 没有质量参数（只有 API 29+ 的 Builder 有）。
    val heifFixedQuality = format == ExportFormat.HEIF && !HeifExport.supportsQuality
    val targetW = if (size == 0) sourceW else minOf(sourceW, size)
    val targetH = (sourceH.toLong() * targetW / maxOf(1, sourceW)).toInt()
    val effSize = if (format == ExportFormat.UHDR_JPEG) "全尺寸" else EXPORT_SIZES[size]

    // 结构（r22）：外层定高，**内容滚动、底部按钮钉住**。
    // 上一版把摘要和「导出」一起放进滚动区 —— 面板在矮屏上要滚到底才看得见主按钮，
    // 没滚到就只剩半截，看起来像"没显示完全"。主操作永远不该需要滚动才能找到。
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).background(Dv.panelRaised)) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {

        // ---------- 顶栏 ----------
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp).height(50.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("导出", color = Dv.paper, fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            // 源片真实像素尺寸：比"IMG_xxxxx · 1张"那种当场编的编号有信息量
            Mono("源片 $imgName", color = Dv.label3, size = 10f)
            Box(Modifier.size(44.dp).clip(CircleShape)
                .iosTap(pressDim = 0.5f) { onClose() }
                .semantics { contentDescription = "关闭" },
                contentAlignment = Alignment.Center) {
                Text("×", color = Dv.label2, fontSize = 18.sp)
            }
        }
        Hairline(alpha = 0.07f)

        // ---------- 配方（一行横滑）----------
        // 五个配方在 289dp 宽里放不下，横滑是必然的 —— 但右端必须**淡出**而不是切一刀，
        // 否则最后一个 chip 看着像"显示不全"（上一版就是这样）。
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("配方", color = Dv.label3, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).edgeFade(20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recipes.forEach { r ->
                    val sel = format == r.format && color == r.color
                    Box(Modifier.clip(RoundedCornerShape(9.dp))
                        .background(if (sel) Dv.accentSoft else Color.Transparent)
                        .border(1.dp, if (sel) Dv.accent.copy(alpha = 0.55f) else Dv.separator,
                            RoundedCornerShape(9.dp))
                        .iosTap(pressScale = 0.95f, pressDim = 0.85f, haptic = true) {
                            format = r.format; color = r.color
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(r.name, color = if (sel) Dv.accent else Dv.label2, fontSize = 10.5f.sp,
                            fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.SemiBold
                                         else androidx.compose.ui.text.font.FontWeight.Normal,
                            maxLines = 1)
                    }
                }
            }
        }

        // ---------- 格式：一条 5 档分段轨 ----------
        ExportSection("格式", formatDepth(format))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegTrack(Modifier.fillMaxWidth()) {
                ExportFormat.values().forEach { f ->
                    SegCell(
                        label = formatShort(f), sub = formatSub(f, heifOk),
                        sel = f == format,
                        enabled = f != ExportFormat.HEIF || heifOk,
                        modifier = Modifier.weight(1f),
                    ) {
                        format = f
                        // HEIF 只按 sRGB 编码，切过去时把色彩一起归位，
                        // 面板上不留一个"看着选了 P3、其实没生效"的状态
                        if (f == ExportFormat.HEIF) color = "sRGB"
                    }
                }
            }
        }
        // 选中格式的一句说明（固定两行高，切格式时下面不会上下跳）
        Text(formatNote(format, heifOk), color = Dv.label3, fontSize = 9.5f.sp, lineHeight = 13.sp,
            minLines = 2, maxLines = 2,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        Spacer(Modifier.height(6.dp))
        Hairline(alpha = 0.06f)

        // ---------- 尺寸（HDR JPEG 固定全尺寸，隐藏）----------
        if (format != ExportFormat.UHDR_JPEG) {
            Spacer(Modifier.height(4.dp))
            ExportRow("尺寸") {
                SegTrack { EXPORT_SIZES.forEachIndexed { i, s -> SegChip(s, i == size) { size = i } } }
            }
            Text("$targetW × $targetH · ${"%.1f".format(targetW * targetH / 1e6f)} MP",
                color = Dv.label3, fontSize = 10.sp, fontFamily = Dv.mono,
                modifier = Modifier.padding(start = 60.dp, top = 2.dp))
        }
        // ---------- 色彩（HDR JPEG 主图固定 sRGB、HEIF 不带 ICC —— 都只按 sRGB 出字节）----------
        if (format != ExportFormat.UHDR_JPEG && format != ExportFormat.HEIF) {
            ExportRow("色彩") {
                SegTrack { EXPORT_COLOR_SPACES.forEach { c -> SegChip(c, c == color) { color = c } } }
            }
        }
        // ---------- 质量 ----------
        if (heifFixedQuality) {
            ExportRow("质量") {
                Spacer(Modifier.weight(1f))
                Text("最高（系统编码器决定）", color = Dv.label2, fontSize = 11.5f.sp)
            }
        } else if (lossless) {
            ExportRow("质量") {
                Spacer(Modifier.weight(1f))
                Text(if (format == ExportFormat.TIFF) "16 位无损" else "无损", color = Dv.label2, fontSize = 11.5f.sp)
            }
        } else {
            ExportRow("质量") {
                Spacer(Modifier.weight(1f))
                Mono("$quality", color = Dv.paper, size = 13f)
            }
            Slider(
                value = quality.toFloat(), onValueChange = { quality = it.toInt() },
                valueRange = 1f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Dv.accent, activeTrackColor = Dv.accent,
                    inactiveTrackColor = Dv.panel,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                // 起点和 ExportRow 的标签列（16 + 44）对齐，收尾回到 16 ——
                // 上一版是 54/22，滑杆比上面所有控件都短一截、还偏左，看着像没对齐
                modifier = Modifier.padding(start = 60.dp, end = 16.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        Hairline(alpha = 0.06f)

        // ---------- 文件名（命名 token）：四项开关，至少留一项 ----------
        ExportRow("文件名") {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NameToken.values().forEach { t ->
                    val on = t in tokens
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                            .background(if (on) Dv.accentSoft else Dv.panel)
                            .border(1.dp, if (on) Dv.accent.copy(alpha = 0.50f) else Dv.separator,
                                RoundedCornerShape(9.dp))
                            .clickable {
                                // 至少留一项：关到最后一个时不动（与上游 NamingRule.toggle 同规则）
                                tokens = if (on) { if (tokens.size > 1) tokens - t else tokens }
                                         else tokens + t
                            }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.label, color = if (on) Dv.accent else Dv.label2, fontSize = 10.sp,
                            maxLines = 1,
                            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold
                                         else androidx.compose.ui.text.font.FontWeight.Normal)
                    }
                }
            }
        }
        Text("$stem.${format.ext}", color = Dv.label3, fontSize = 10.sp,
            fontFamily = Dv.mono, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 60.dp, end = 16.dp, top = 4.dp))
        // ---------- 存储到 ----------
        ExportRow("存储到") {
            SegTrack {
                SegChip("照片", saveDest == 0) { saveDest = 0 }
                SegChip("文件", saveDest == 1) { saveDest = 1 }
                SegChip("共享", saveDest == 2) { saveDest = 2 }
            }
        }

        }
        // ↑ 滚动区到此为止
        // ---------- 摘要 + 导出（钉在底部，不随内容滚走）----------
        // 一行实况 + 一整条主按钮：一根手指按得到，不用瞄、也不用先滚到底。
        Hairline(alpha = 0.07f)
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${format.label} · $color · $effSize",
                    color = Dv.label2, fontSize = 10.5f.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(
                    when (saveDest) {
                        0 -> "存入「照片」"
                        1 -> "存入「文件」"
                        else -> "打开分享面板"
                    },
                    color = Dv.label3, fontSize = 9.5f.sp, maxLines = 1)
            }
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(46.dp)
                .iosTap(pressScale = 0.985f, pressDim = 0.9f, haptic = true) {
                    if (showing) return@iosTap
                    showing = true
                    onExport(format, color, EXPORT_SIZES[size], quality, saveDest, stem) { showing = false }
                }
                .clip(RoundedCornerShape(14.dp))
                .background(if (showing) Dv.accent.copy(alpha = 0.55f) else Orange),
                contentAlignment = Alignment.Center) {
                Text(if (showing) "冲洗中…" else if (saveDest == 2) "导出并共享" else "导出",
                    color = Color.White, fontSize = 15.5.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 0.6.sp)
            }
        }
    }
}

// ===== 源片 EXIF 继承（r11 第 4 批）=====
/**
 * 从导入的源照片读一份拍摄信息，导出时贴到成片上。
 *
 * **白名单，不整段搬 APP1**：源图的 MakerNote / 内嵌缩略图存的是相对原文件的
 * 偏移，搬进重新编码过的 JPEG 会失效（相册读缩略图花屏）。这里只取那些
 * "相机写了什么"的标签，它们都是自包含值。
 *
 * 读不到就返回空表：`applyExif` 会退回原来的行为（只写成片配方 + 当前时间），
 * 不会因为源文件没有 EXIF（截图、导出的 PNG）而丢导出。
 */
object ExifInherit {
    private val TAGS = listOf(
        // 机身与镜头
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        // 镜头型号与 ISO 类型在 android.media.ExifInterface 里没有常量（那是
        // androidx.exifinterface 才有的），用标准 EXIF 标签名直接写：能识别就写进
        // 文件，识别不了就被它自己跳过 —— 都是旁路信息，不写也不会让导出失败。
        "LensMake", "LensModel",
        // 曝光三元组与光学
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        "PhotographicSensitivity", "SensitivityType",
        // 老文件的 ISO 存在 ISO tag（37）里；新标叫 PhotographicSensitivity（34855）。
        // 两条都读，写出去哪条能被识别就写哪条 —— 不丢 ISO 就够了。
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        // 拍摄时刻（含时区偏移）
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        // GPS
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )

    fun read(ctx: Context, uri: Uri?): Map<String, String> {
        if (uri == null) return emptyMap()
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val e = ExifInterface(ins)
                TAGS.mapNotNull { t ->
                    e.getAttribute(t)?.takeIf { it.isNotBlank() }?.let { t to it }
                }.toMap()
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }
}

// ===== 真实编码 =====
object Exporter {
    // ---- 色彩配置嵌入（复刻 host/output.cpp make_icc + icc_for）----
    // 标准 Display P3 primaries → XYZ(D50)，行主序，与 host display_p3_space() 一致。
    // 引擎 Android 输出 P3 编码即基于此色域；嵌入 ICC 后相册/系统按 P3 正确解释，预览(sRGB)与导出(P3)各自正确。
    private val displayP3ToXyzD50 = doubleArrayOf(
        0.5151215, 0.2919769, 0.1571045,
        0.2411957, 0.6922455, 0.0665588,
        -0.0010529, 0.0418854, 0.7840729)

    // 返回需嵌入的 ICC；sRGB 省略（系统默认按 sRGB 解释）
    private fun iccFor(colorSpace: String): ByteArray? = when (colorSpace) {
        "Display P3" -> buildIccV2("Display P3", displayP3ToXyzD50)
        else -> null
    }

    private fun buildIccV2(description: String, toXyzD50: DoubleArray): ByteArray {
        fun put32(b: ByteArrayOutputStream, v: Int) {
            b.write((v ushr 24) and 0xFF); b.write((v ushr 16) and 0xFF); b.write((v ushr 8) and 0xFF); b.write(v and 0xFF)
        }
        fun put16(b: ByteArrayOutputStream, v: Int) { b.write((v ushr 8) and 0xFF); b.write(v and 0xFF) }
        fun put15(b: ByteArrayOutputStream, v: Double) { put32(b, Math.round(v * 65536.0).toInt()) }
        data class Tag(val sig: String, val data: ByteArray)
        val tags = ArrayList<Tag>()
        run {   // desc（textDescription，仿 host：ASCII + 空 unicode 段）
            val d = ByteArrayOutputStream()
            d.write("desc".toByteArray()); put32(d, 0)
            put32(d, description.length + 1); d.write(description.toByteArray()); d.write(0)
            put32(d, 0); put32(d, 0)
            put16(d, 0); d.write(0); d.write(ByteArray(67))
            tags.add(Tag("desc", d.toByteArray()))
        }
        run {   // cprt
            val text = "No copyright, use freely"
            val d = ByteArrayOutputStream()
            d.write("text".toByteArray()); put32(d, 0)
            put32(d, text.length + 1); d.write(text.toByteArray()); d.write(0)
            tags.add(Tag("cprt", d.toByteArray()))
        }
        fun xyzTag(x: Double, y: Double, z: Double): ByteArray {
            val d = ByteArrayOutputStream()
            d.write("XYZ ".toByteArray()); put32(d, 0)
            put15(d, x); put15(d, y); put15(d, z)
            return d.toByteArray()
        }
        tags.add(Tag("wtpt", xyzTag(0.9642, 1.0, 0.8249)))
        tags.add(Tag("rXYZ", xyzTag(toXyzD50[0], toXyzD50[3], toXyzD50[6])))
        tags.add(Tag("gXYZ", xyzTag(toXyzD50[1], toXyzD50[4], toXyzD50[7])))
        tags.add(Tag("bXYZ", xyzTag(toXyzD50[2], toXyzD50[5], toXyzD50[8])))
        run {   // rTRC：sRGB transfer 的 encoded→linear 曲线（1024 点），gTRC/bTRC 共享
            val n = 1024
            val d = ByteArrayOutputStream()
            d.write("curv".toByteArray()); put32(d, 0)
            put32(d, n)
            for (i in 0 until n) {
                val lin = srgbDecode(i.toDouble() / (n - 1))
                val v = Math.round(Math.max(0.0, Math.min(1.0, lin)) * 65535.0).toInt()
                put16(d, v)
            }
            tags.add(Tag("rTRC", d.toByteArray()))
        }
        tags.add(Tag("gTRC", ByteArray(0)))
        tags.add(Tag("bTRC", ByteArray(0)))

        val body = ByteArrayOutputStream()
        val places = ArrayList<IntArray>()
        val table = 128 + 4 + tags.size * 12
        var sharedCurve = 0
        for (t in tags) {
            if (t.sig == "gTRC" || t.sig == "bTRC") { places.add(intArrayOf(sharedCurve, t.data.size)); continue }
            val at = table + body.size()
            if (t.sig == "rTRC") sharedCurve = at
            body.write(t.data)
            while (body.size() % 4 != 0) body.write(0)
            places.add(intArrayOf(at, t.data.size))
        }
        val out = ByteArrayOutputStream()
        put32(out, table + body.size())   // size
        put32(out, 0)                      // CMM
        put32(out, 0x02100000)             // v2.1
        out.write("mntrRGB XYZ ".toByteArray())
        put16(out, 2000); put16(out, 1); put16(out, 1); put16(out, 0); put16(out, 0); put16(out, 0)
        out.write("acsp".toByteArray())
        put32(out, 0); put32(out, 0); put32(out, 0); put32(out, 0)
        put32(out, 0); put32(out, 0)
        put32(out, 0)                       // perceptual intent
        put15(out, 0.9642); put15(out, 1.0); put15(out, 0.8249)  // PCS illuminant
        put32(out, 0)                       // creator
        while (out.size() < 128) out.write(0)
        put32(out, tags.size)
        for (i in tags.indices) {
            out.write(tags[i].sig.toByteArray())
            put32(out, places[i][0]); put32(out, places[i][1])
        }
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun srgbDecode(v: Double): Double {
        val max = 0.0031308 * 12.92
        return if (max >= v) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    // JPEG 嵌 ICC：APP2 "ICC_PROFILE" 段，插在 SOI(FFD8) 之后
    private fun embedIccJpeg(jpeg: ByteArray, icc: ByteArray): ByteArray {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) return jpeg
        val payloadLen = 12 + 2 + icc.size   // "ICC_PROFILE\0" + seq + count
        val seg = ByteArrayOutputStream()
        seg.write(0xFF); seg.write(0xE2)
        seg.write((payloadLen + 2) ushr 8); seg.write(payloadLen + 2)  // APP 段长含自身
        seg.write("ICC_PROFILE".toByteArray()); seg.write(0)
        seg.write(1); seg.write(1)            // seq, count
        seg.write(icc)
        return ByteArrayOutputStream().apply {
            write(jpeg, 0, 2)
            seg.writeTo(this)
            write(jpeg, 2, jpeg.size - 2)
        }.toByteArray()
    }

    // PNG 嵌 iCCP：profileName\0 + method(0) + zlib-deflate ICC，插在 IEND 前
    private fun embedIccPng(png: ByteArray, icc: ByteArray): ByteArray {
        val iend = indexOfIEND(png)
        if (iend < 0) return png
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        val comp = ByteArrayOutputStream()
        deflater.setInput(icc); deflater.finish()
        val buf = ByteArray(256)
        while (!deflater.finished()) { val n = deflater.deflate(buf); comp.write(buf, 0, n) }
        deflater.end()
        val chunk = ByteArrayOutputStream().apply {
            write("ICC".toByteArray()); write(0); write(0)
            comp.writeTo(this)
        }.toByteArray()
        val crc = CRC32().apply { update("iCCP".toByteArray(Charsets.US_ASCII)); update(chunk) }.value
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(chunk.size); put("iCCP".toByteArray(Charsets.US_ASCII))
        }.array()
        val tail = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.toInt()).array()
        return ByteArrayOutputStream().apply {
            write(png, 0, iend)
            write(header); write(chunk); write(tail)
            write(png, iend, png.size - iend)
        }.toByteArray()
    }

    // 渲染全尺寸结果 -> 编码(带配方元数据) -> 保存。quality 仅 JPEG 有效；PNG/TIFF 无损。
    // metadata：胶片/相纸/曝光/颗粒/光晕/防光晕/冲洗日期 等成片配方详情
    // sourceExif：源照片继承下来的拍摄信息（相机/镜头/曝光/GPS），见 ExifInherit
    // 返回 Pair(是否成功, 实际扩展名)。
    fun encodeAndSave(
        ctx: Context, bmp: Bitmap, format: ExportFormat, colorSpace: String, quality: Int,
        saveToPhotos: Boolean, imgName: String, metadata: String,
        sourceExif: Map<String, String> = emptyMap(),
    ): Pair<Boolean, String> {
        var ext = format.ext
        try {
            var bytes: ByteArray
            when (format) {
                ExportFormat.JPEG -> {
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                    var j = bos.toByteArray()
                    iccFor(colorSpace)?.let { j = embedIccJpeg(j, it) }
                    bytes = applyExif(j, metadata, sourceExif, bmp.width, bmp.height)
                }
                ExportFormat.PNG -> {
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    var p = bos.toByteArray()
                    iccFor(colorSpace)?.let { p = embedIccPng(p, it) }
                    bytes = applyPngText(p, "Deyva", metadata)
                }
                ExportFormat.TIFF -> bytes = tiffEncode(bmp, 16, iccFor(colorSpace))
                ExportFormat.UHDR_JPEG -> bytes = ByteArray(0) // 不应走到：HDR JPEG 走独立渲染+封装分支
                ExportFormat.HEIF -> bytes = ByteArray(0)      // 不应走到：HEIF 由 HeifExport 编码
            }
            val name = "$imgName.$ext"
            if (saveToPhotos) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/$ext")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpektraLab")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val f = File(dir, "SpektraLab/$name"); f.parentFile?.mkdirs()
                        put(MediaStore.Images.Media.DATA, f.absolutePath)
                    }
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false to ext
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false to ext
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            } else {
                val dir = ctx.getExternalFilesDir(null)?.let { File(it, "SpektraLab") } ?: return false to ext
                dir.mkdirs()
                FileOutputStream(uniqueFile(dir, name, ext)).use { it.write(bytes) }
            }
            return true to ext
        } catch (e: Exception) {
            Diagnostics.log("export", "编码/保存失败: ${e.message}")
            return false to ext
        }
    }

    /**
     * 「文件」目录里不静默覆盖同名文件。
     *
     * 命名 token 上线后，同一张照片用同一套设置导出两次会得到同一个名字 ——
     * 直接写就等于把上一张吃掉。有重名就加 _2、_3（上限 999，之后交给系统报错）。
     * 相册那条路由 MediaStore 自己去重，这里不管。
     */
    private fun uniqueFile(dir: File, name: String, ext: String): File {
        var f = File(dir, "$name.$ext")
        var i = 2
        while (f.exists() && i <= 999) { f = File(dir, "${name}_$i.$ext"); i++ }
        return f
    }

    // 供共享导出调用的 TIFF 字节编码（公开包装）
    fun tiffBytes(bmp: Bitmap, bits: Int): ByteArray = tiffEncode(bmp, bits)

    /**
     * 编码成字节、不落盘 —— 「共享」那条路用它。
     *
     * 原来共享分支自己 `bmp.compress` 一遍，于是分享出去的成片既没有 ICC 也没有
     * 任何配方信息：同一个文件走「文件」有、走「共享」没有，纯属两套代码各写一遍。
     * 现在两条路都从这个函数拿字节（HDR JPEG 除外，它的字节由 libultrahdr 生成）。
     */
    fun encodeBytes(
        bmp: Bitmap, format: ExportFormat, colorSpace: String, quality: Int,
        metadata: String, sourceExif: Map<String, String>,
    ): ByteArray? = runCatching {
        when (format) {
            ExportFormat.JPEG -> {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                var j = bos.toByteArray()
                iccFor(colorSpace)?.let { j = embedIccJpeg(j, it) }
                applyExif(j, metadata, sourceExif, bmp.width, bmp.height)
            }
            ExportFormat.PNG -> {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                var p = bos.toByteArray()
                iccFor(colorSpace)?.let { p = embedIccPng(p, it) }
                applyPngText(p, "Deyva", metadata)
            }
            ExportFormat.TIFF -> tiffEncode(bmp, 16, iccFor(colorSpace))
            ExportFormat.UHDR_JPEG -> null      // HDR 的字节不是这里产生的
            ExportFormat.HEIF -> null           // HEIF 的字节由 HeifExport 产生
        }
    }.getOrNull()

    /** 保存任意字节流（如 Ultra HDR JPEG）到照片或文件。 */
    fun saveBytes(ctx: Context, bytes: ByteArray, ext: String, saveToPhotos: Boolean, name: String): Boolean {
        return try {
            val fname = "$name.$ext"
            if (saveToPhotos) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fname)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/$ext")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpektraLab")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val f = File(dir, "SpektraLab/$fname"); f.parentFile?.mkdirs()
                        put(MediaStore.Images.Media.DATA, f.absolutePath)
                    }
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            } else {
                val dir = ctx.getExternalFilesDir(null)?.let { File(it, "SpektraLab") } ?: return false
                dir.mkdirs()
                FileOutputStream(uniqueFile(dir, name, ext)).use { it.write(bytes) }
                true
            }
        } catch (e: Exception) {
            Diagnostics.log("export", "保存(HDR)失败: ${e.message}")
            false
        }
    }

    // 给 JPEG 写入成片元数据（配方详情）+ 继承源片的拍摄信息。
    //
    // 上游的做法是整段 sourceEXIF 交给 ImageIO（`props.exif = sourceEXIF`），
    // EXIF 只是旁路信息，永远不进引擎。Android 这边不能整段搬 APP1：源图的
    // MakerNote 与内嵌缩略图里存的是**相对原文件**的偏移，搬到一个重新编码过的
    // JPEG 里全部失效（相册读缩略图会花屏）。所以按白名单逐条搬标签。
    //
    // 三件事必须按成片改写，不能照抄源片：
    //   Orientation —— 旋转已经在解码时烤进像素（applyExifRotation），再带原值会被转第二次；
    //   PixelX/YDimension —— 成片尺寸可能已缩放；
    //   DateTime —— 文件的写入时间 = 冲洗时间（拍摄时间仍沿用 DateTimeOriginal）。
    private fun applyExif(
        jpeg: ByteArray, metadata: String, source: Map<String, String>, w: Int, h: Int,
    ): ByteArray {
        return runCatching {
            val now = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.CHINA).format(Date())
            val tmp = File.createTempFile("spektra_exif", ".jpg")
            try {
                tmp.writeBytes(jpeg)
                val exif = ExifInterface(tmp)
                // 1) 先继承源片的拍摄信息（相机 / 镜头 / 快门 / 光圈 / ISO / GPS …）
                for ((k, v) in source) runCatching { exif.setAttribute(k, v) }
                // 2) 再写"这张成片是什么"——同名标签以成片为准
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Deyva")
                exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, metadata)
                // 源片没有相机信息时才把宿主填进去；有就保留拍摄它的那台相机
                if (source[ExifInterface.TAG_MAKE].isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MAKE, "Deyva")
                if (source[ExifInterface.TAG_MODEL].isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MODEL, "胶片模拟冲洗")
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
                exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
                if (source[ExifInterface.TAG_DATETIME_ORIGINAL].isNullOrBlank()) {
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, now)
                }
                exif.setAttribute(ExifInterface.TAG_DATETIME, now)
                exif.saveAttributes()
                tmp.readBytes()
            } finally { tmp.delete() }
        }.getOrElse { jpeg }
    }

    /**
     * 切一段可直接交给 `HeifWriter.addExifData` 的 EXIF 块（`Exif\0\0` + TIFF 头）。
     *
     * 不另写一套 IFD 写入器：把源图缩到 16×16 走一遍普通 JPEG 导出，再把生成文件里
     * 的 APP1 段整段切出来。好处是"成片覆盖源片"（方向/尺寸/时间改写成成片的）、
     * 白名单、GPS 继承 —— 全与 JPEG 那条路共用同一份代码，不会两处各说各话。
     * 传进来的 w/h 是**成片**尺寸，所以 EXIF 里报的是真实像素，不是 16×16。
     */
    fun exifApp1(
        ctx: Context, src: Bitmap, metadata: String,
        sourceExif: Map<String, String>, w: Int, h: Int,
    ): ByteArray? = runCatching {
        val tiny = Bitmap.createScaledBitmap(src, 16, 16, false)
        val bos = ByteArrayOutputStream()
        tiny.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        extractApp1(applyExif(bos.toByteArray(), metadata, sourceExif, w, h))
    }.getOrNull()

    /** 从 JPEG 里取出第一段 APP1（不含 0xFFE1 标记与长度字段）。 */
    private fun extractApp1(j: ByteArray): ByteArray? {
        var i = 2
        while (i + 4 <= j.size) {
            if ((j[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            val m = j[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0x01 || (m in 0xD0..0xD7)) { i += 2; continue }
            if (m == 0xDA) return null            // 到扫描数据了，说明没有 EXIF
            val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
            if (m == 0xE1 && len > 8) return j.copyOfRange(i + 4, i + 2 + len)
            i += 2 + len
        }
        return null
    }

    // 给 PNG 追加 tEXt 文本块（keyword=SpektraLab，含成片配方）
    private fun applyPngText(png: ByteArray, keyword: String, text: String): ByteArray {
        return runCatching {
            // 找 IEND chunk（结尾 12 字节：00 00 00 00 49 45 4E 44 AE 42 60 82）
            if (png.size < 8) return png
            val iend = indexOfIEND(png)
            if (iend < 0) return png
            val kw = keyword.toByteArray(Charsets.UTF_8)
            val txt = text.toByteArray(Charsets.UTF_8)
            val chunk = ByteArrayOutputStream().apply {
                write(kw); write(0); write(txt)
            }.toByteArray()
            val crc = CRC32().apply { update("tEXt".toByteArray(Charsets.US_ASCII)); update(chunk) }.value
            val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(chunk.size); put("tEXt".toByteArray(Charsets.US_ASCII))
            }.array()
            val tail = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.toInt()).array()
            // 在 IEND 前插入 tEXt
            ByteArrayOutputStream().apply {
                write(png, 0, iend)
                write(header); write(chunk); write(tail)
                write(png, iend, png.size - iend)
            }.toByteArray()
        }.getOrElse { png }
    }

    private fun indexOfIEND(png: ByteArray): Int {
        // PNG 由 8 字节签名 + 若干 chunk；IEND 是最后一个 chunk(12 字节)。从尾往前找 49 45 4E 44
        for (i in png.size - 4 downTo 8) {
            if (png[i] == 0x49.toByte() && png[i + 1] == 0x45.toByte() && png[i + 2] == 0x4E.toByte() && png[i + 3] == 0x44.toByte()) {
                return i - 4 // 返回 IEND 数据区前的字节偏移（IEND 的 length 字段起始）
            }
        }
        return -1
    }

    // 手写 TIFF 编码器（uncompressed RGB，16 位深，II little-endian）。icc 非空时写入 InterColorProfile(34675) 标签。
    private fun tiffEncode(bmp: Bitmap, bits: Int, icc: ByteArray? = null): ByteArray {
        val w = bmp.width; val h = bmp.height
        val spp = 3; val bps = bits; val bytesPerSample = bps / 8
        val stripBytes = w * h * spp * bytesPerSample
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val data = ByteBuffer.allocate(stripBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (p in px) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            if (bps == 16) {
                data.putShort((r * 257).toShort()); data.putShort((g * 257).toShort()); data.putShort((b * 257).toShort())
            } else {
                data.put(r.toByte()); data.put(g.toByte()); data.put(b.toByte())
            }
        }
        val nEntries = 13 + (if (icc != null) 1 else 0)
        val ifdOffset = 8
        val bitsOffset = ifdOffset + 2 + 12 * nEntries + 4
        val resOffset = bitsOffset + 6
        val dataOffset = resOffset + 16
        val iccOffset = if (icc != null) dataOffset + stripBytes else 0
        val total = if (icc != null) iccOffset + icc!!.size else dataOffset + stripBytes
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("II".toByteArray()); buf.putShort(42); buf.putInt(ifdOffset)
        buf.putShort(nEntries.toShort())
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            buf.putShort(tag.toShort()); buf.putShort(type.toShort()); buf.putInt(count)
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            if (type == 3 && count == 1) { bytes.putShort(value.toShort()); }
            else bytes.putInt(value)
            buf.put(bytes.array())
        }
        entry(256, 4, 1, w)                       // ImageWidth LONG
        entry(257, 4, 1, h)                       // ImageLength LONG
        entry(258, 3, 3, bitsOffset)              // BitsPerSample SHORT[3]
        entry(259, 3, 1, 1)                       // Compression = none
        entry(262, 3, 1, 2)                       // Photometric = RGB
        entry(273, 4, 1, dataOffset)              // StripOffsets
        entry(277, 3, 1, spp)                     // SamplesPerPixel
        entry(278, 4, 1, h)                       // RowsPerStrip
        entry(279, 4, 1, stripBytes)              // StripByteCounts
        entry(282, 5, 1, resOffset)               // XResolution RATIONAL
        entry(283, 5, 1, resOffset + 8)           // YResolution RATIONAL
        entry(296, 3, 1, 2)                       // ResolutionUnit = inch
        entry(284, 3, 1, 1)                       // PlanarConfig = chunky
        if (icc != null) entry(34675, 7, icc.size, iccOffset)  // InterColorProfile UNDEFINED
        buf.putInt(0)                              // next IFD
        buf.putShort(bps.toShort()); buf.putShort(bps.toShort()); buf.putShort(bps.toShort())  // bits per sample
        // resolution rationals (72/1)
        buf.putInt(72); buf.putInt(1); buf.putInt(72); buf.putInt(1)
        buf.put(data.array())
        if (icc != null) buf.put(icc)
        return buf.array()
    }
}

// ===== 写文件 Uri 辅助（诊断用）=====
fun buildUri(): Uri? = null
