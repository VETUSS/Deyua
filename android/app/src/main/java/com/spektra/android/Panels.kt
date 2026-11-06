package com.spektra.android

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 胶片 / 相纸选择（r14 重做）。
 *
 * 旧版：一条白胶囊分段控件 + 一排品牌 chip + 4 列 52dp 缩略图 + 一条曝光滑杆，全挤在
 * 320dp 高的固定卡片里 —— 26 个胶片只能看到 8 个，名字 9~10sp 基本靠猜。
 *
 * 新版按"看片台"（light table）的顺序组织：
 *   ① 一级切换「胶片 / 相纸」用文字 + 滑动下划线（UnderlineTabs）；
 *   ② 品牌系列用 chip，横向滚动，不再和一级切换抢视觉；
 *   ③ 主体是 3 列**接触印相**网格：缩略图更大（约 100dp 见方），名字允许两行，
 *      选中的那一格描琥珀细边 + 名字变琥珀 —— "从印相样张里挑一张"的感觉；
 *   ④ 底部固定一条曝光补偿，等宽读数 + 刻度滑杆（带回位钮）。
 *
 * 交互逻辑（回调、section/漫游规则、直扫底片开关）与旧版逐字一致，只换了呈现。
 */
@Composable
fun FilmPaperTab(
    thumbCache: Map<String, Bitmap>,
    section: Int, onSection: (Int) -> Unit,
    filmVariant: String, onFilmVariant: (String) -> Unit,
    printVariant: String, onPrintVariant: (String) -> Unit,
    scanFilm: Boolean, onScanFilm: (Boolean) -> Unit,
    exposureEv: Float, onEvChange: (Float) -> Unit, onEvCommit: () -> Unit,
    filmTrueColor: Boolean, onFilmTrueColor: (Boolean) -> Unit,
    reduceMotion: Boolean,
) {
    // section = 0 胶片, 1 相纸；series 为当前选中的品牌系列（"全部"显示该 tab 下所有型号）
    var series by remember(section) { mutableStateOf("全部") }
    val groups = if (section == 0) FILM_GROUPS else PRINT_GROUPS
    val activeVariant = if (section == 0) filmVariant else printVariant
    val variants = if (series == "全部") groups.flatMap { it.variants }
                   else groups.firstOrNull { it.name == series }?.variants ?: emptyList()

    Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 12.dp)) {
        UnderlineTabs(
            labels = listOf("胶片", "相纸"), index = section,
            onSelect = { onSection(it); series = "全部" },
            reduceMotion = reduceMotion,
        )
        // 品牌系列（第二层）：横向 chip，选中琥珀
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                DvChip("全部", series == "全部", { series = "全部" })
            }
            items(groups, key = { it.name }) { g ->
                DvChip(g.name, series == g.name, { series = g.name })
            }
        }

        // [r18] 胶片色彩：校色 / 原色。之前这里是一条"点一下看本色"的提示，一点就消失，
        // 关回去就再也找不到入口了（用户反馈原话）。现在做成常驻的两档：
        // 两个状态都能点，下面一行随时说清当前这一档在做什么、为什么 26 支会/不会一样。
        if (section == 0) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("胶片色彩", color = Dv.label2, fontSize = 11.sp, letterSpacing = 0.3.sp)
                Spacer(Modifier.width(10.dp))
                DvChip("校色", !filmTrueColor, { if (filmTrueColor) onFilmTrueColor(false) }, size = 10)
                DvChip("原色", filmTrueColor, { if (!filmTrueColor) onFilmTrueColor(true) }, size = 10)
            }
            Text(
                if (filmTrueColor)
                    "已关掉自动校色：每支片按自己的滤片洗，色偏偏暖偏冷一眼分得开（成片同步变化）"
                else
                    "彩扩机会把每支胶片修成同一个中性色，所以 26 支看着接近 —— 想看差别就点「原色」",
                color = if (filmTrueColor) Dv.accent.copy(alpha = 0.85f) else Dv.label4,
                fontSize = 9.5f.sp, lineHeight = 13.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
            )
        }

        if (variants.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("该系列暂无型号", color = Dv.label3, fontSize = 12.sp)
            }
        } else {
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(variants, key = { it.id }) { v ->
                        val sel = v.id == activeVariant
                        val onClick = { if (section == 0) onFilmVariant(v.id) else onPrintVariant(v.id) }
                        val borderC by animateColorAsState(
                            if (sel) Dv.accent else Color.White.copy(alpha = 0.06f), fluidSpec(reduceMotion))
                        val borderW by animateDpAsState(if (sel) 1.5.dp else 1.dp, fluidSpec(reduceMotion))
                        // 只有描边和文字变色，缩略图本身不缩放：一格格图在滚动，缩放会显得很吵
                        Column(Modifier.fillMaxWidth()
                            .iosTap(pressScale = 0.96f, pressDim = 0.9f, haptic = true, onClick = onClick)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (sel) Dv.accent.copy(alpha = 0.06f) else Color.Transparent)
                            .padding(3.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1.15f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(borderW, borderC, RoundedCornerShape(8.dp))) {
                                FilmThumb(
                                    cache = thumbCache,
                                    id = if (section == 0) "${v.id}@$printVariant" else "$filmVariant@${v.id}",
                                    byFilm = section == 0,
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(
                                v.label,
                                color = if (sel) Dv.accent else Dv.label2,
                                fontSize = 10.sp, lineHeight = 12.5.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                // 底部渐隐：被裁掉的残行柔和隐去（与底色同色，不再硬切一刀）
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Dv.panel))))
            }
        }

        // 直扫底片（No Print Profile）：只在相纸页出现，点任一相纸即关闭（上游同规则）
        if (section == 1) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 2.dp)
                .height(40.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("直扫底片", color = Dv.label1, fontSize = 12.sp, letterSpacing = 0.4.sp)
                    Text(if (scanFilm) "正在看底片本身 · 点任一相纸即关闭" else "跳过相纸，直接看冲好的底片",
                        color = if (scanFilm) Dv.accent else Dv.label3, fontSize = 9.5f.sp)
                }
                DvToggle(checked = scanFilm, onChange = onScanFilm, desc = "直扫底片")
            }
        }

        // 曝光补偿：等宽读数 + 刻度滑杆（滑杆自带回位钮）
        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            ParamRow("胶片曝光", "%+.2f EV".format(exposureEv), note = "—3…+3")
            TickerSlider(
                value = exposureEv, onValueChange = onEvChange, onValueChangeFinished = onEvCommit,
                valueRange = -3f..3f, snapStep = 0.25f, majorStep = 1f, reduceMotion = reduceMotion,
                default = 0f,
            )
        }
    }
}

/**
 * 网格里的一格缩略图。
 *
 * r17 之前，缓存没命中时这里会退回**未经冲洗的原片**。于是每次切「胶片/相纸」，
 * 新 key 全都未命中，整片格子先闪一遍原片、再逐张跳成成片 —— 就是"点一下颜色
 * 变一下"的来源。现在三条规则：
 *  1. 绝不拿原片顶替成片。宁可先空着，也不要在成片的位置放一张没冲洗的图；
 *  2. 同一支胶片（或同一张相纸）在另一半组合下的旧图可以先用 —— 换相纸时色差
 *     只有一点点，比原片接近得多；新图到了再淡入；
 *  3. 到位时 180ms 淡入，不再硬切。
 */
@Composable
private fun FilmThumb(cache: Map<String, Bitmap>, id: String, byFilm: Boolean) {
    // key 是 "胶片@相纸"。胶片页每格换的是胶片（比前缀），相纸页每格换的是相纸（比后缀）——
    // 弄反了的话，相纸页 8 格的前缀都是同一支胶片，会全都命中同一张图。
    val me = if (byFilm) id.substringBefore('@') else id.substringAfter('@')
    val exact = cache[id]
    val near = if (exact == null)
        cache.entries.firstOrNull {
            (if (byFilm) it.key.substringBefore('@') else it.key.substringAfter('@')) == me
        }?.value else null
    val target = exact ?: near

    // 记住上一张真渲出来的图：新组合还没好时先用它顶着，别闪成空白
    val last = remember(id) { mutableStateOf<Bitmap?>(null) }
    SideEffect { if (target != null && target !== last.value) last.value = target }
    val shown = target ?: last.value

    val m = Modifier.fillMaxSize()
    if (shown != null) {
        Crossfade(shown, animationSpec = tween(180), label = "thumb") { b ->
            Image(b.asImageBitmap(), id, m, contentScale = ContentScale.Crop)
        }
    } else {
        // 真的还没有任何图（刚导入 / 引擎失败）：一块暗房色的空片位
        Box(m.background(Brush.verticalGradient(listOf(Dv.panelRaised, Dv.panel))))
    }
}

// ---------------------------------------------------------------------------
// 调校页（格式 / 影调 / 片边 / 日期 / 暗房 / 细节 / 相机）
// ---------------------------------------------------------------------------

/**
 * "调校"页（r14 重做）。
 *
 * 旧版是**一条四十多个控件的长滚动**：格式、画幅、颗粒、光晕、片边、日期后背、暗房、
 * 胶片细节、相机全平铺，找"预闪"要滑三屏，而且永远不知道下面还有没有。
 *
 * 新版折成 7 个分组，一屏就能看见全部标题，点开哪组看哪组（可多组同时展开）：
 *   画幅 · 影调 · 片边 · 日期 · 暗房 · 细节 · 相机
 *
 * 每个分组标题右侧直接放**该组的开关/状态**（片边开没开、日期开没开、当前片幅），
 * 不用点进去才知道。控件本身只换了外观，所有回调与取值路径一字未改。
 */
@Composable
fun FormatTab(
    formatIdx: Int, onFormat: (Int) -> Unit,
    antihalationRemoved: Boolean, onAntihalation: (Boolean) -> Unit,
    halation: Float, onHalationChange: (Float) -> Unit, onHalationCommit: () -> Unit,
    grain: Float, onGrainChange: (Float) -> Unit, onGrainCommit: () -> Unit,
    overscanActive: Boolean, onOverscanActive: (Boolean) -> Unit,
    overscanFormat: String, onOverscanFormat: (String) -> Unit,
    overscanFog: Float, onOverscanFog: (Float) -> Unit, onOverscanFogCommit: () -> Unit,
    overscanLeaks: Float, onOverscanLeaks: (Float) -> Unit, onOverscanLeaksCommit: () -> Unit,
    overscanGate: String, onOverscanGate: (String) -> Unit,
    overscanHoles: String, onOverscanHoles: (String) -> Unit,
    overscanCarrier: String, onOverscanCarrier: (String) -> Unit,
    overscanEdgeText: Boolean, onOverscanEdgeText: (Boolean) -> Unit,
    dateBack: DateBackCfg, onDateBack: (DateBackCfg) -> Unit, onDateBackCommit: () -> Unit,
    edgeExtra: EdgeExtraCfg, onEdgeExtra: (EdgeExtraCfg) -> Unit, onEdgeExtraCommit: () -> Unit,
    cropRatioIdx: Int, onCropRatio: (Int) -> Unit,
    rotationDeg: Float, onRotation: (Float) -> Unit, onRotationCommit: () -> Unit,
    quarterTurns: Int, onQuarterTurns: (Int) -> Unit,
    flipH: Boolean, onFlipH: (Boolean) -> Unit,
    flipV: Boolean, onFlipV: (Boolean) -> Unit,
    darkroom: DarkroomCfg, onDarkroom: (DarkroomCfg) -> Unit, onDarkroomCommit: () -> Unit,
    camera: CameraCfg, onCamera: (CameraCfg) -> Unit, onCameraCommit: () -> Unit,
    onReseed: () -> Unit,
    // [r19] 「校色 / 原色」的常驻第二入口（胶片页上是一排两档，这里是明细 + 解释）。
    filmTrueColor: Boolean, onFilmTrueColor: (Boolean) -> Unit,
    // 原色模式下这支胶片自带的两支滤片偏移（CC）。滤片读数要把这一份算进去，
    // 否则开着原色读数是 0 CC、画面却是暖的 —— 又一处"读数与画面不符"。
    trueColorCastM: Float, trueColorCastY: Float,
    // 这张照片有没有可印的拍摄数据（EXIF 快门/光圈/模式…）。没有时「DATA 字面」
    // 印出来是一片空白 —— 与其留一个点了没反应的空档，不如当场标成不可用。
    hasShootingData: Boolean,
    reduceMotion: Boolean,
) {
    // 默认展开第一组（画幅）。折叠状态只是"看哪一组"，与参数无关，不参与配方。
    var open by remember { mutableStateOf(setOf(0)) }
    fun toggle(i: Int) { open = if (i in open) open - i else open + i }

    val applyDate: (DateBackCfg) -> Unit = { c -> onDateBack(c); onDateBackCommit() }

    Column(
        Modifier.fillMaxWidth().fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        // ================= 0 · 画幅 =================
        Section("画幅", "geometry", 0, 0 in open, ::toggle,
            trailing = { Label(FORMATS.getOrElse(formatIdx) { "" }, color = Dv.label3, size = 9) }) {
            // [r19] 片边开启时这一组其实**不生效**：引擎取的是片门的毫米长度
            // （pipeline.cpp:102 `overscan_wanted() ? gate_long_mm : film_format_mm`），
            // 颗粒尺度、光晕半径全按片门算。旧版照样给一排可点的胶囊，点了画面毫无变化 ——
            // 又一个"摆设"。现在只显示当前值并说明它被谁接管。
            if (!overscanActive) {
                ChipScroller(FORMATS.map { it to it }, formatIdx, onFormat)
                Spacer(Modifier.height(6.dp))
            }
            if (overscanActive) {
                // 片边开启时画框由片门比例决定，裁剪/旋转会被引擎忽略（上游同规则）
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Dv.accent.copy(alpha = 0.07f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Undo, Dv.accent, 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("片边开启中 · 画幅由「片边 · 片幅」接管，裁剪与拉直暂时锁定",
                        color = Dv.orangeLight, fontSize = 10.5f.sp, lineHeight = 14.sp)
                }
            } else {
                ParamRow("裁剪比例", CROP_RATIOS.getOrNull(cropRatioIdx)?.first ?: "原始")
                ChipScroller(CROP_RATIOS.mapIndexed { i, p -> i to p.first }, cropRatioIdx, onCropRatio)
                Spacer(Modifier.height(8.dp))
                ParamBlock("拉直", "%+.1f°".format(rotationDeg), note = "±15°") {
                    TickerSlider(
                        value = rotationDeg, onValueChange = onRotation, onValueChangeFinished = onRotationCommit,
                        valueRange = -15f..15f, snapStep = 0.5f, majorStep = 5f, reduceMotion = reduceMotion,
                        default = 0f)
                }
                ParamRow("转向", "${quarterTurns * 90}°")
                ChipScroller(
                    listOf(0 to "0°", 1 to "90°", 2 to "180°", 3 to "270°"),
                    quarterTurns, onQuarterTurns)
                Spacer(Modifier.height(6.dp))
                SwitchRow("水平翻转", flipH, onFlipH)
                SwitchRow("垂直翻转", flipV, onFlipV)
            }
        }

        // ================= 1 · 影调 =================
        Section("影调", "tone", 1, 1 in open, ::toggle,
            trailing = { Label("%.2f×".format(grain), color = Dv.label3, size = 9) }) {
            SwitchRow("移除防光晕层", antihalationRemoved, onAntihalation)
            ParamBlock("颗粒", "%.2f×".format(grain), note = "0–2") {
                TickerSlider(value = grain, onValueChange = onGrainChange, onValueChangeFinished = onGrainCommit,
                    valueRange = 0f..2f, snapStep = 0.1f, majorStep = 0.5f, reduceMotion = reduceMotion,
                    default = 1f)
            }
            ParamBlock("光晕", "%.2f×".format(halation), note = "0–4") {
                TickerSlider(value = halation, onValueChange = onHalationChange, onValueChangeFinished = onHalationCommit,
                    valueRange = 0f..4f, snapStep = 0.2f, majorStep = 1f, reduceMotion = reduceMotion,
                    default = 1f)
            }
        }

        // ================= 2 · 片边 =================
        Section("片边", "overscan", 2, 2 in open, ::toggle,
            trailing = { DvToggle(overscanActive, onOverscanActive, desc = "片边") }) {
            if (!overscanActive) {
                Text("开启后按所选片幅重排画面：片门、齿孔、片基上的厂商边码都会出现",
                    color = Dv.label3, fontSize = 10.5f.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(vertical = 4.dp))
            } else {
                val osFormats = listOf(
                    "135", "135 Half", "135 宽幅",
                    "120 6×4.5", "120 6×6", "120 6×7", "120 6×8", "120 6×9", "120 6×12", "120 6×17",
                )
                ParamRow("片幅", osFormats.firstOrNull { osFormatKey(it) == overscanFormat } ?: overscanFormat)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    osFormats.forEach { f ->
                        DvChip(f, overscanFormat == osFormatKey(f), { onOverscanFormat(osFormatKey(f)) }, size = 11)
                    }
                }
                Spacer(Modifier.height(8.dp))
                ParamBlock("片边雾", "%.1f".format(overscanFog), note = "edge fog") {
                    TickerSlider(value = overscanFog, onValueChange = onOverscanFog,
                        onValueChangeFinished = onOverscanFogCommit, valueRange = 0f..4f, snapStep = 0.1f,
                        majorStep = 1f, reduceMotion = reduceMotion, default = 1f)
                }
                ParamBlock("漏光", "%.1f".format(overscanLeaks), note = "light leak") {
                    TickerSlider(value = overscanLeaks, onValueChange = onOverscanLeaks,
                        onValueChangeFinished = onOverscanLeaksCommit, valueRange = 0f..4f, snapStep = 0.1f,
                        majorStep = 1f, reduceMotion = reduceMotion, default = 0f)
                }
                EnumRow("片门", listOf("auto", "square", "rounded", "eared", "shouldered", "kicked"),
                    overscanGate, onOverscanGate)
                EnumRow("齿孔", listOf("white", "black"), overscanHoles, onOverscanHoles)
                EnumRow("载体", listOf("none", "black", "open"), overscanCarrier, onOverscanCarrier)
                Spacer(Modifier.height(4.dp))
                SwitchRow("边码文字", overscanEdgeText, onOverscanEdgeText, note = "厂商印在片基上的型号与帧号")
                // [r15] 这一项原来叫「光圈」并显示成 f/2.8，让人以为片边上会印出光圈数字。
                // 查引擎：overscan.cpp 里 f_number 只参与 `penumbra = gate_gap / (2N)`，
                // 也就是片门边缘阴影的宽度（params.hpp 注释写的是 "EXIF; sets the gate penumbra"），
                // 边缘上并不会多出任何字形。这里把真实作用、以及它有多小，直接写在控件旁边。
                val fnEff = if (edgeExtra.fNumber <= 0f) 5.6f else edgeExtra.fNumber
                val penUm = 0.29f / (2f * fnEff) * 1000f       // 片门缝隙 0.18–0.40 mm，取中值 0.29
                ParamRow(
                    "片门光圈",
                    if (edgeExtra.fNumber <= 0f) "自动 f/5.6"
                    else "f/${if (edgeExtra.fNumber % 1f == 0f) edgeExtra.fNumber.toInt().toString() else edgeExtra.fNumber}",
                    note = "半影 ${"%.0f".format(penUm)} μm",
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    F_STOPS.forEach { f ->
                        DvChip(
                            if (f == 0f) "自动" else "f/${if (f % 1f == 0f) f.toInt().toString() else f}",
                            kotlin.math.abs(edgeExtra.fNumber - f) < 0.05f,
                            { onEdgeExtra(edgeExtra.copy(fNumber = f)); onEdgeExtraCommit() }, size = 11)
                    }
                }
                Text("它不印字，只决定片门边缘的柔和度：光圈越大越锐。f/2.8 约 52 μm、f/22 约 7 μm，在 135 画幅上不到 0.2%，全尺寸导出才勉强分辨得出",
                    color = Dv.label3, fontSize = 9.5f.sp, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                Spacer(Modifier.height(8.dp))
                ParamBlock("起始帧号", if (edgeExtra.frameNumber <= 0) "随机" else "${edgeExtra.frameNumber}",
                    note = "0 = 由种子决定") {
                    TickerSlider(
                        value = edgeExtra.frameNumber.toFloat(),
                        onValueChange = { onEdgeExtra(edgeExtra.copy(frameNumber = it.toInt())) },
                        onValueChangeFinished = onEdgeExtraCommit,
                        valueRange = 0f..40f, snapStep = 1f, majorStep = 5f, reduceMotion = reduceMotion,
                        default = 0f)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("随机种子", color = Dv.label2, fontSize = 12.5f.sp)
                        Text("换一卷新的片基：片边纹理与帧号都会重排", color = Dv.label3, fontSize = 9.5f.sp)
                    }
                    Box(Modifier.clip(RoundedCornerShape(Dv.rChip))
                        .background(Dv.accentSoft)
                        .iosTap(pressScale = 0.95f, pressDim = 0.8f, haptic = true) { onReseed() }
                        .padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text("换卷", color = Dv.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ================= 3 · 日期后背 =================
        Section("日期后背", "date back", 3, 3 in open, ::toggle,
            trailing = { DvToggle(dateBack.active, { applyDate(dateBack.copy(active = it)) }, desc = "日期后背") }) {
            if (!dateBack.active) {
                Text("相机在底片上烧的日期。可印在画面角落，也可印在齿孔之间（需片边）",
                    color = Dv.label3, fontSize = 10.5f.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(vertical = 4.dp))
            } else {
                // [r19] 「DATA」面印的是**这张照片自己的**快门/光圈/模式/焦距（来自 EXIF）。
                // 截图、导出的 PNG、没有 EXIF 的图 —— 这一档以前看着可选、点了却印出一片空白。
                // 现在没有数据就把它标暗并说明，而不是留个空档。
                EnumRow("字面", listOf("lcd", "dots", "data"), dateBack.style,
                    { applyDate(dateBack.copy(style = it)) },
                    disabled = if (hasShootingData) emptySet() else setOf("data")) { it.uppercase() }
                if (!hasShootingData) {
                    Text("这张照片没有拍摄数据（EXIF），所以「DATA」面印不出内容 —— 已置灰",
                        color = Dv.label4, fontSize = 9.5f.sp, lineHeight = 13.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                }
                // 「齿孔间」是把字印在片基/齿孔之间，只有开了片边才有那块位置。
                // 没开片边时引擎直接跳过整个日期节点（那就等于开关失效），所以置灰并说清。
                EnumRow("位置", listOf("frame", "rebate"), dateBack.placement,
                    { applyDate(dateBack.copy(placement = it)) },
                    disabled = if (overscanActive) emptySet() else setOf("rebate")) {
                    if (it == "frame") "画面内" else "齿孔间"
                }
                if (!overscanActive && dateBack.placement == "frame") {
                    Text("「齿孔间」需要片边提供位置；开启「片边」后才能选",
                        color = Dv.label4, fontSize = 9.5f.sp, lineHeight = 13.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                } else if (!overscanActive && dateBack.placement == "rebate") {
                    Text("现在是「齿孔间」但片边没开 —— 这行字不会印出来，请开启片边或切回「画面内」",
                        color = Dv.red.copy(alpha = 0.85f), fontSize = 9.5f.sp, lineHeight = 13.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                }
                EnumRow("角落", listOf("br", "bl", "tr", "tl"), dateBack.corner,
                    { applyDate(dateBack.copy(corner = it)) }) {
                    when (it) { "br" -> "右下"; "bl" -> "左下"; "tr" -> "右上"; else -> "左上" }
                }
                ParamBlock("字号", "%.2f×".format(dateBack.size)) {
                    TickerSlider(value = dateBack.size, onValueChange = { onDateBack(dateBack.copy(size = it)) },
                        onValueChangeFinished = onDateBackCommit, valueRange = 0.4f..3f, snapStep = 0.1f,
                        majorStep = 1f, reduceMotion = reduceMotion, default = 1f)
                }
                ParamBlock("浓度", "%+.1f EV".format(dateBack.ev)) {
                    TickerSlider(value = dateBack.ev, onValueChange = { onDateBack(dateBack.copy(ev = it)) },
                        onValueChangeFinished = onDateBackCommit, valueRange = -2f..8f, snapStep = 0.5f,
                        majorStep = 2f, reduceMotion = reduceMotion, default = 3.5f)
                }
                if (dateBack.placement == "frame") {
                    ParamBlock("距边·横", "%.1f mm".format(edgeExtra.insetX)) {
                        TickerSlider(value = edgeExtra.insetX,
                            onValueChange = { onEdgeExtra(edgeExtra.copy(insetX = it)) },
                            onValueChangeFinished = onEdgeExtraCommit, valueRange = 0f..15f, snapStep = 0.1f,
                            majorStep = 1f, reduceMotion = reduceMotion, default = 3f)
                    }
                    ParamBlock("距边·纵", "%.1f mm".format(edgeExtra.insetY)) {
                        TickerSlider(value = edgeExtra.insetY,
                            onValueChange = { onEdgeExtra(edgeExtra.copy(insetY = it)) },
                            onValueChangeFinished = onEdgeExtraCommit, valueRange = 0f..15f, snapStep = 0.1f,
                            majorStep = 1f, reduceMotion = reduceMotion, default = 2.4f)
                    }
                }
            }
        }

        // ================= 4 · 暗房 =================
        // 引擎的 print_exposure 是「少曝光更亮」，这里换算成摄影习惯的「档」，往右更亮。
        val brightStops = (-Math.log(darkroom.exposure.toDouble()) / Math.log(2.0)).toFloat()
        Section("暗房", "enlarger", 4, 4 in open, ::toggle,
            trailing = { Label("%+.1f 档".format(brightStops), color = Dv.label3, size = 9) }) {
            Text("同一张底片换相纸、拨滤色片 —— 真实暗房里颜色就是这样变的",
                color = Dv.label3, fontSize = 10.5f.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            // [r19] 「校色 / 原色」的常驻说明位。胶片页上那一排只是两个词，这里
            // 负责回答"它到底在做什么"：彩扩机会按每支胶片各自的中性滤片把它配平到
            // 同一个中性点（所以 26 支印出来几乎一样）；关掉配平、共用一套滤片，
            // 每支片自身的色偏才重新显现。这就是同一台放大机、同一盒滤片的真实做法。
            SwitchRow(
                "保留各片本色", filmTrueColor, onFilmTrueColor,
                note = "关：各片按自己的滤片配平（彩扩机做法，互相接近）· 开：共用一套滤片，色偏各自显现",
            )
            Spacer(Modifier.height(4.dp))
            ParamBlock("亮度", "%+.1f 档".format(brightStops)) {
                TickerSlider(value = brightStops.coerceIn(-2f, 2f),
                    onValueChange = { onDarkroom(darkroom.copy(exposure = Math.pow(2.0, -it.toDouble()).toFloat())) },
                    onValueChangeFinished = onDarkroomCommit, valueRange = -2f..2f, snapStep = 0.1f,
                    majorStep = 1f, reduceMotion = reduceMotion, default = 0f)
            }
            // [r18] 读数换成 CC：一个 shift 单位 = 60 CC（MainActivity.FILTER_CC_SCALE）。
            // 以前显示原始 shift，而引擎默认 1 CC/单位 —— 滑杆推到头只有 1 CC，画面上
            // 什么都不会变，等于一根没接线的滑杆。
            ParamBlock("黄滤片", "%+.0f CC".format(darkroom.yShift * 60f + trueColorCastY),
                note = if (trueColorCastY != 0f) "含原色 %+.0f".format(trueColorCastY) else "→ 更蓝") {
                TickerSlider(value = darkroom.yShift, onValueChange = { onDarkroom(darkroom.copy(yShift = it)) },
                    onValueChangeFinished = onDarkroomCommit, valueRange = -1f..1f, snapStep = 0.05f,
                    majorStep = 0.5f, reduceMotion = reduceMotion, default = 0f)
            }
            ParamBlock("品红滤片", "%+.0f CC".format(darkroom.mShift * 60f + trueColorCastM),
                note = if (trueColorCastM != 0f) "含原色 %+.0f".format(trueColorCastM) else "→ 更绿") {
                TickerSlider(value = darkroom.mShift, onValueChange = { onDarkroom(darkroom.copy(mShift = it)) },
                    onValueChangeFinished = onDarkroomCommit, valueRange = -1f..1f, snapStep = 0.05f,
                    majorStep = 0.5f, reduceMotion = reduceMotion, default = 0f)
            }
            // 有用区间只有 0…0.03，显示成 ×100（0…3），否则滑杆前 3% 就走完了全部范围
            ParamBlock("预闪", "%.2f".format(darkroom.preflash * 100f)) {
                TickerSlider(value = (darkroom.preflash * 100f).coerceIn(0f, 3f),
                    onValueChange = { onDarkroom(darkroom.copy(preflash = it / 100f)) },
                    onValueChangeFinished = onDarkroomCommit, valueRange = 0f..3f, snapStep = 0.1f,
                    majorStep = 1f, reduceMotion = reduceMotion, default = 0f)
            }

            // ===== [r33] 对比度蒙版（RFC-024）=====
            //
            // 这是暗房里**遮挡与加光**的自动化版本：引擎按画面结构分区改变印相曝光，
            // 高光侧多曝、阴影侧少曝。改的是印相曝光而不是像素，所以拉高光不会连带
            // 把云压平 —— 这是像素曲线做不到的，也是它值得从 Layer 2 里分出来的原因。
            //
            // 全部参数都在 PRINT 层：只重印相纸、不重冲负片。但蒙版分析本身要跑一次
            // 高斯（上游实测 45MP 上 260–600ms，手机上只会更久），所以 scale 这类
            // 改动只在**松手之后**才出图 —— 拖动时画面不动是预期行为，不是卡死。
            Spacer(Modifier.height(10.dp))
            Hairline(alpha = 0.06f)
            Spacer(Modifier.height(4.dp))
            // [r34] 打开开关时如果两个档数都是 0，先把高光侧放到 1.5 档。
            //
            // 引擎的取法是 `contrast_mask_wanted() = active && (highlights>0 || shadows>0)`，
            // 也就是说开关本身**不改画面** —— 0 档就是原样。与其让用户先怀疑功能
            // 是坏的，不如让开灯就有画面。
            //
            // [r35] 这个 2 档现在真的看得见：这张相纸的中灰到纸白只有 1.8 档、到纸黑
            // 只有 1.9 档，而旧标定要"膝点外 4 档"才达到满值（等于纸白再往外两档，
            // 那儿的密度早就压平了），所以 1.5 档在纸白位只挪了 0.03 档 ≈ 0.002 密度，
            // 等于没动。标定改成"膝点外 1 档"后，同一个 1.5 档在纸白是 +0.96 档
            // （密度 0.05 → 0.28），2 档在天空这一类中高亮区也到了 +0.9 档，
            // 是肉眼明确的"压住高光"。
            SwitchRow(
                "分区印相", darkroom.cmActive,
                { on ->
                    val seed = on && darkroom.cmHighlights == 0f && darkroom.cmShadows == 0f
                    onDarkroom(darkroom.copy(cmActive = on,
                        cmHighlights = if (seed) 2.0f else darkroom.cmHighlights))
                    onDarkroomCommit()
                },
                note = "暗房遮挡与加光 · 改印相曝光，不改像素",
            )
            if (darkroom.cmActive) {
                Text("开关只是许可：两个档数都为 0 时，画面与关掉完全一样",
                    color = Dv.label3, fontSize = 9.5f.sp, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp))
                ParamBlock("高光侧", "%.1f 档".format(darkroom.cmHighlights), note = "多曝 · 压住高光") {
                    TickerSlider(value = darkroom.cmHighlights,
                        onValueChange = { onDarkroom(darkroom.copy(cmHighlights = it)) },
                        onValueChangeFinished = onDarkroomCommit, valueRange = 0f..3f, snapStep = 0.1f,
                        majorStep = 1f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("阴影侧", "%.1f 档".format(darkroom.cmShadows), note = "少曝 · 提亮暗部") {
                    TickerSlider(value = darkroom.cmShadows,
                        onValueChange = { onDarkroom(darkroom.copy(cmShadows = it)) },
                        onValueChangeFinished = onDarkroomCommit, valueRange = 0f..3f, snapStep = 0.1f,
                        majorStep = 1f, reduceMotion = reduceMotion, default = 0f)
                }
                ParamBlock("中间调保护", "%.1f 档".format(darkroom.cmCore), note = "0–3 · 越大越不碰中灰") {
                    TickerSlider(value = darkroom.cmCore,
                        onValueChange = { onDarkroom(darkroom.copy(cmCore = it)) },
                        onValueChangeFinished = onDarkroomCommit, valueRange = 0f..3f, snapStep = 0.1f,
                        majorStep = 1f, reduceMotion = reduceMotion, default = 1f)
                }
                ParamBlock("蒙版尺度", "%.3f".format(darkroom.cmScale), note = "0.002–0.12 · 长边比例") {
                    TickerSlider(value = darkroom.cmScale.coerceIn(0.002f, 0.12f),
                        onValueChange = { onDarkroom(darkroom.copy(cmScale = it)) },
                        onValueChangeFinished = onDarkroomCommit, valueRange = 0.002f..0.12f,
                        snapStep = 0.002f, majorStep = 0.02f, reduceMotion = reduceMotion, default = 0.03f)
                }
                Text("尺度决定分区有多粗：0.02 只压暗小片天空，0.1 会把整片天当成一个区。" +
                    "松手后才出图 —— 每次都要重算一遍蒙版，这是全流程里最贵的一步",
                    color = Dv.label3, fontSize = 9.5f.sp, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ================= 5 · 胶片细节 =================
        Section("胶片细节", "couplers", 5, 5 in open, ::toggle,
            trailing = { Label("%.2f×".format(darkroom.glare), color = Dv.label3, size = 9) }) {
            // [r25] 量程从 0…3 放到 0…10。
            //
            // 引擎里 glare 的真实量是「叠加光占白点的比例」：percent = 0.03 × amount，
            // 再在 spk_glare_add 里除以 100（params.hpp:150 与 util.metal:47）。
            // 也就是说 amount=1 只有 0.03% 的光 —— 8bit 上不到 1/255，几乎看不见；
            // 原来的 0…3 一直待在这个"看不见"的区间里，滑杆推到头也像没接线。
            // 上游自己的标定扫描（glare_ramp）用的是 0.02%…0.4%，对应 amount 0.7…13，
            // 所以 0…10 才是这个参数真正有意义的行程：1 = 相纸原样，10 = 肉眼可辨的雾。
            ParamBlock("相纸光泽", "%.1f×".format(darkroom.glare), note = "0–10 · 1 = 原样") {
                TickerSlider(value = darkroom.glare, onValueChange = { onDarkroom(darkroom.copy(glare = it)) },
                    onValueChangeFinished = onDarkroomCommit, valueRange = 0f..10f, snapStep = 0.1f,
                    majorStep = 2f, reduceMotion = reduceMotion, default = 1f)
            }
            // 互层抑制：关掉（0）时颜色分离度上升、反差变硬；加满（4）时更闷。
            // 它是真的在算 —— 乘在整个 couplers 抑制矩阵上（curves.cpp:125），
            // 再经 density_curves_before_dir_couplers 改写密度曲线。
            ParamBlock("定向成色耦合", "%.2f×".format(darkroom.dirCouplers), note = "dir couplers") {
                TickerSlider(value = darkroom.dirCouplers,
                    onValueChange = { onDarkroom(darkroom.copy(dirCouplers = it)) },
                    onValueChangeFinished = onDarkroomCommit, valueRange = 0f..4f, snapStep = 0.1f,
                    majorStep = 1f, reduceMotion = reduceMotion, default = 1f)
            }
            // [r25] 这一项只能减弱，不能加强：引擎里 scatter_amount 的上限就是 1.0
            // （params.cpp:135），而 1.0 已经是胶片乳剂自身的散射。散射半径只有
            // 2.2 µm 核心 + 9.3 µm 拖尾 —— 在 1280 长边的预览里不足 1 个像素，
            // 所以拖动时画面几乎不动，**导出全尺寸才看得出**边缘那层柔光。把这句话
            // 写在控件下面，免得又被当成摆设。
            ParamBlock("光晕散射", "%.2f×".format(darkroom.halationScatter), note = "0–1 · 只能减弱") {
                TickerSlider(value = darkroom.halationScatter,
                    onValueChange = { onDarkroom(darkroom.copy(halationScatter = it)) },
                    onValueChangeFinished = onDarkroomCommit, valueRange = 0f..1f, snapStep = 0.05f,
                    majorStep = 0.5f, reduceMotion = reduceMotion, default = 1f)
            }
            Text("1.00× 是胶片乳剂本身的散射。它只有 2–9 µm，预览尺寸下不到一个像素，导出全尺寸才看得出",
                color = Dv.label3, fontSize = 9.5f.sp, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 2.dp))
        }

        // ================= 6 · 相机 =================
        Section("相机", "camera", 6, 6 in open, ::toggle,
            trailing = { Label(CAMERA_FILTERS.firstOrNull { it.first == camera.filter }?.second ?: "无",
                color = Dv.label3, size = 9) }) {
            ParamRow("镜头滤色片", "")
            ChipScroller(CAMERA_FILTERS.map { it.first to it.second }, CAMERA_FILTERS.indexOfFirst { it.first == camera.filter },
                { i -> onCamera(camera.copy(filter = CAMERA_FILTERS[i].first)); onCameraCommit() }, size = 11)
            Spacer(Modifier.height(8.dp))
            ParamRow("测光方式", "")
            ChipScroller(AE_METHODS.map { it.first to it.second }, AE_METHODS.indexOfFirst { it.first == camera.aeMethod },
                { i -> onCamera(camera.copy(aeMethod = AE_METHODS[i].first)); onCameraCommit() }, size = 11)
            Spacer(Modifier.height(8.dp))
            ParamBlock("镜头虚化", "%.0f µm".format(camera.lensBlur), note = "老镜头像差") {
                TickerSlider(value = camera.lensBlur, onValueChange = { onCamera(camera.copy(lensBlur = it)) },
                    onValueChangeFinished = onCameraCommit, valueRange = 0f..200f, snapStep = 5f,
                    majorStep = 50f, reduceMotion = reduceMotion, default = 0f)
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

// ---------------------------------------------------------------- 小组件

/** 胶囊 chip 横滚（格式 / 比例 / 转向 / 光圈 / 滤片 / 测光共用）。 */
@Composable
private fun <T> ChipScroller(items: List<Pair<T, String>>, selectedIndex: Int, onSelect: (Int) -> Unit, size: Int = 11) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        items.forEachIndexed { i, (_, label) ->
            DvChip(label, i == selectedIndex, { onSelect(i) }, size = size)
        }
    }
}

/** 左标签 + 右侧胶囊枚举（片门/齿孔/载体/字面/位置/角落）。选项多时横向滚动。
 *  [disabled] 里的选项画成"暗、不可点"的胶囊：它在当前条件下**什么都不会发生**
 *  （比如没有 EXIF 时的 DATA 面），与其让用户点了没反应，不如当场说明。 */
@Composable
private fun EnumRow(
    title: String, options: List<String>, current: String, onSelect: (String) -> Unit,
    disabled: Set<String> = emptySet(),
    display: (String) -> String = { o -> if (o == "auto") "Auto" else o.replaceFirstChar { it.uppercase() } },
) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Dv.label2, fontSize = 12.5f.sp)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            options.forEach { o ->
                if (o in disabled) {
                    Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(Dv.rChip))
                        .border(1.dp, Dv.separator.copy(alpha = 0.6f), RoundedCornerShape(Dv.rChip))
                        .padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(display(o), color = Dv.label4, fontSize = 11.sp)
                    }
                } else {
                    DvChip(display(o), current == o, { onSelect(o) }, size = 11)
                }
            }
        }
    }
}

/** 左标签 + 右侧说明 + 自绘开关（开关行统一 44dp 高，整行可点）。 */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .iosTap(pressDim = 0.65f) { onChange(!checked) }
        .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dv.label1, fontSize = 12.5f.sp, letterSpacing = 0.3.sp)
            if (note != null) Text(note, color = Dv.label3, fontSize = 9.5f.sp)
        }
        DvToggle(checked, onChange, desc = title)
    }
}
