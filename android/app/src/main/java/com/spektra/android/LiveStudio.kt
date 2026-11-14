package com.spektra.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 取景器 / 观片台（r50）。
 *
 * 一页两级：上面是 live 画面，下面是成片控制条。切换只看一眼就懂 —— 顶部两枚胶囊，
 * 画面立刻换（同一个底片、两套时间轴），不用退回列表再进另一页。
 *
 * 出片两条路：[LiveMovie] 的 MP4 与"JPEG + 尾部 MP4"的动态照片。
 */
@Composable
fun LiveStudioPage(rec: ExportRecord, initialStyle: Int, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var style by remember { mutableIntStateOf(initialStyle) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var quality by remember { mutableStateOf(LiveMovie.Quality.LIST[0]) }
    var showQuality by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(-1f) }
    val stopFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var note by remember { mutableStateOf<LiveMovie.Result?>(null) }
    val scope = rememberCoroutineScope()
    val seed = remember(rec.id) { rec.id.hashCode() }
    // 这张成片带片边时，门洞只占整张图中间的一块。取景器里看进去的**只有门洞**，
    // 片基（齿孔、边码）留给最后一帧。没开片边 / 不认识的片幅 → null，整张就是画面。
    val gate = remember(rec.id, rec.edge, rec.width, rec.height) {
        LiveFx.gateFracFor(rec.edge, rec.width, rec.height)
    }

    LaunchedEffect(rec.id) {
        bmp = withContext(Dispatchers.IO) {
            ExportStore.viewOf(ctx, rec, 2560)
        }
    }

    // 导出中不让返回：一退页面，软/硬件编码器就会挂在半路，下次进来还要重新初始化的坑
    DvBackHandler {
        if (progress < 0f) {
            if (showQuality) showQuality = false else onBack()
        }
    }

    fun startLive(livePhoto: Boolean) {
        val src = bmp ?: return
        if (progress >= 0f) return
        progress = 0f
        stopFlag.set(false)
        note = null
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                LiveMovie.export(
                    ctx = ctx, photo = src, style = style, seed = seed,
                    quality = quality, seconds = LiveFx.LOOP, livePhoto = livePhoto,
                    gate = gate,
                    metaLine = "胶片: ${rec.filmLabel} · ${rec.mode}: ${rec.printLabel}",
                    onProgress = { p ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post { progress = p }
                    },
                    shouldStop = { stopFlag.get() },
                )
            }
            progress = -1f
            note = r
            if (r.ok) Haptics.detent(ctx)
        }
    }

    Box(Modifier.fillMaxSize().background(Dv.root)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ---------- 顶栏 ----------
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 10.dp)
                .height(52.dp).clip(RoundedCornerShape(18.dp)).background(Dv.glassBar)
                .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
                    .iosTap(pressDim = 0.45f) { if (progress < 0f) onBack() }
                    .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    Text("‹ 返回", color = Dv.blue, fontSize = 16.sp)
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(if (style == LiveFx.VIEWFINDER) "取景器" else "观片台",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(rec.filmLabel, color = Dv.label3, fontSize = 9.5f.sp, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Text("LIVE · %.0f 秒".format(LiveFx.LOOP), color = Dv.orangeLight,
                    fontSize = 9.5f.sp, letterSpacing = 1.sp, modifier = Modifier.padding(end = 10.dp))
            }

            // ---------- 效果切换 ----------
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("取景器", "腰平 · 快门 · 出片", style == LiveFx.VIEWFINDER, Modifier.weight(1f)) {
                    style = LiveFx.VIEWFINDER; tick++
                }
                ModeChip("观片台", "灯亮 · 过曝 · 落定", style == LiveFx.PRINT, Modifier.weight(1f)) {
                    style = LiveFx.PRINT; tick++
                }
            }

            // ---------- 画面 ----------
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) {
                val b = bmp
                val ratio = if (rec.height > 0) rec.width.toFloat() / rec.height else 0.8f
                Box(
                    // 只给比例、不 fillMaxSize：fillMaxSize 会把宽高都钉死，
                    // aspectRatio 就失效了，画面会被拉成容器比例，与导出的画幅对不上。
                    Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                ) {
                    if (b != null) {
                        AndroidView(
                            factory = { c -> LiveStageView(c) },
                            update = { v ->
                                v.seed = seed
                                v.style = style
                                v.gate = gate
                                // 编码时冻住预览：软件画布那一路要吃满一个核，GPU 那一路
                                // 还在 60 fps 重绘同一张画面，纯粹是抢资源
                                v.setPaused(progress >= 0f)
                                v.setPhoto(b, tick)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Dv.accent, strokeWidth = 2.dp)
                        }
                    }
                    // 角标：告诉用户"这是会动的"，并标出画面比例
                    Row(Modifier.align(Alignment.TopStart).padding(10.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.42f))
                        .padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(Dv.accent))
                        Spacer(Modifier.width(4.dp))
                        Text("LIVE", color = Color.White, fontSize = 7.5f.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }

            // ---------- 结果 / 说明 ----------
            Box(Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center) {
                val n = note
                when {
                    progress >= 0f -> Text("正在逐帧渲染并编码… 画面与预览完全一致", color = Dv.label3,
                        fontSize = 10.sp, textAlign = TextAlign.Center)
                    n != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(n.message, color = if (n.ok) Dv.label1 else Dv.red, fontSize = 11.5f.sp,
                            textAlign = TextAlign.Center)
                        if (n.detail.isNotBlank()) Text(n.detail, color = Dv.label3, fontSize = 9.5f.sp,
                            textAlign = TextAlign.Center, lineHeight = 13.sp)
                    }
                    else -> Text(
                        if (style == LiveFx.VIEWFINDER)
                            "哈苏 503 的腰平取景：低头看进去是一块 6×6 的方形对焦屏，左右是反的 —— " +
                                "屏上只有画面本身，片基和齿孔不在对焦屏上。" +
                                "对焦拉实、反光镜拍上去、屏黑一拍再亮起来 —— 那一下有真的快门声。" +
                                "最后定格，再切到这张成片：带不带片边，和导出的那张完全一样。"
                        else "按当前成片模拟观片：先听见开关的「咔」，灯才亮；亮的瞬间先过曝，" +
                            "再像眼睛适应那样落回常曝光，之后灯管的嗡鸣一直垫在底下。",
                        color = Dv.label4, fontSize = 9.5f.sp, textAlign = TextAlign.Center, lineHeight = 13.sp,
                    )
                }
            }

            // ---------- 清晰度 ----------
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(40.dp)
                .clip(RoundedCornerShape(12.dp)).background(Dv.panel)
                .iosTap(pressDim = 0.7f) { showQuality = true }
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Video, Dv.label3, 15.dp)
                Spacer(Modifier.width(8.dp))
                Text("清晰度：${quality.label}", color = Dv.label2, fontSize = 11.5f.sp)
                Spacer(Modifier.weight(1f))
                Text("默认按原片比例，视频长边上限 1440", color = Dv.label4, fontSize = 8.5f.sp)
                Spacer(Modifier.width(4.dp))
                GlyphIcon(Glyph.Chevron, Dv.label3, 12.dp, rotation = 90f)
            }

            // ---------- 三个动作 ----------
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundAction(Modifier.weight(1f), Glyph.Play, "重播", enabled = progress < 0f) {
                    note = null; tick++
                }
                RoundAction(Modifier.weight(1f), Glyph.Video, "导出视频", enabled = progress < 0f && bmp != null) {
                    startLive(false)
                }
                PrimaryAction(
                    Modifier.weight(1.45f), Glyph.Live, "导出 Live Photo",
                    enabled = progress < 0f && bmp != null,
                ) { startLive(true) }
            }
        }

        // ---------- 编码中 ----------
        if (progress >= 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                contentAlignment = Alignment.Center) {
                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Dv.panelRaised)
                    .padding(horizontal = 26.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = if (progress <= 0f) 0.02f else progress,
                            color = Dv.accent, strokeWidth = 3.dp,
                            trackColor = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier.size(58.dp),
                        )
                        Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                            color = Dv.label1, fontSize = 13.sp, fontFamily = Dv.mono)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (quality.edge == 0) "按原片尺寸逐帧渲染" else "按 ${quality.label} 逐帧渲染",
                        color = Dv.label2, fontSize = 11.5.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("%.0f 秒 · 30 fps · 与预览同一套渲染".format(LiveFx.LOOP),
                        color = Dv.label4, fontSize = 9.5.sp)
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.height(32.dp).clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .iosTap(pressDim = 0.6f) { stopFlag.set(true) }
                        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                        Text("取消", color = Dv.label2, fontSize = 11.5.sp)
                    }
                }
            }
        }

        // ---------- 清晰度选择 ----------
        AnimatedVisibility(
            visible = showQuality, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .iosTap(pressDim = 1f) { showQuality = false }) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Dv.rSheet, topEnd = Dv.rSheet))
                    .background(Dv.panel).padding(bottom = 12.dp)
                    .iosTap(pressDim = 1f) { }) {
                    Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                        SheetGrabber(active = false)
                    }
                    Text("导出清晰度", color = Dv.label1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp, modifier = Modifier.padding(start = 18.dp, bottom = 4.dp))
                    Text("静帧按所选尺寸；视频长边最大 1440（再高只会变慢，看不出差别）。",
                        color = Dv.label3, fontSize = 10.sp, lineHeight = 15.sp,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp))
                    LiveMovie.Quality.LIST.forEach { q ->
                        Row(Modifier.fillMaxWidth().height(48.dp)
                            .iosTap(pressDim = 0.6f) { quality = q; showQuality = false }
                            .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(q.label, color = if (q == quality) Dv.accent else Dv.label1, fontSize = 14.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                when (q.id) {
                                    "orig" -> "按记录里的成片尺寸（上限 2560）"
                                    "2048" -> "2048 px 长边"
                                    "1440" -> "1440 px 长边"
                                    else -> "1080 px 长边 · 最省时间"
                                },
                                color = Dv.label3, fontSize = 10.sp,
                            )
                            Spacer(Modifier.weight(1f))
                            if (q == quality) GlyphIcon(Glyph.Live, Dv.accent, 14.dp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, sub: String, active: Boolean, modifier: Modifier, onTap: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (active) Dv.accentSoft else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (active) Dv.accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f),
                RoundedCornerShape(12.dp))
            .iosTap(pressScale = 0.97f, pressDim = 0.85f, haptic = true) { onTap() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (active) Dv.accent else Dv.label2,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = if (active) Dv.orangeLight.copy(alpha = 0.8f) else Dv.label4,
            fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun RoundAction(
    modifier: Modifier, glyph: Glyph, label: String, enabled: Boolean, onClick: () -> Unit,
) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(13.dp))
            .background(Color(0x1FFFFFFF))
            .iosTap(enabled = enabled, pressScale = 0.96f, pressDim = 0.7f, haptic = true, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (enabled) Dv.label1 else Dv.label4, 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) Dv.label1 else Dv.label4, fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryAction(
    modifier: Modifier, glyph: Glyph, label: String, enabled: Boolean, onClick: () -> Unit,
) {
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(13.dp))
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Dv.accent, Color(0xFFFFC061)))
                else Brush.horizontalGradient(listOf(Dv.accent.copy(alpha = 0.25f), Dv.accent.copy(alpha = 0.25f)))
            )
            .iosTap(enabled = enabled, pressScale = 0.96f, pressDim = 0.9f, haptic = true, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, if (enabled) Dv.root else Dv.root.copy(alpha = 0.5f), 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) Dv.root else Dv.root.copy(alpha = 0.5f),
            fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * live 画面的自绘 View。
 *
 * 为什么不用 Compose 的 Canvas：这里要的是 60 fps、每帧只画一张位图 + 几层渐变，
 * 走 Compose 会把整页卷进重组；更重要的是**导出**要复用同一个绘制函数，
 * 自绘 View 的 onDraw 与软件 Canvas 完全同构，不存在"预览一套、成片一套"。
 */
private class LiveStageView(ctx: Context) : View(ctx) {
    var style: Int = LiveFx.VIEWFINDER
    var seed: Int = 0
    /** 成片里门洞的位置；带片边时取景器只显示门洞里的像。 */
    var gate: RectF? = null
    private var paused = false
    private var photo: Bitmap? = null
    private var lastPhoto: Bitmap? = null
    private var shownTick: Int = -1
    private var startNs = System.nanoTime()
    private var lastT = 0f

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (!p) invalidate()
    }

    fun setPhoto(b: Bitmap, tick: Int) {
        photo = b
        if (b !== lastPhoto || tick != shownTick) {
            lastPhoto = b
            shownTick = tick
            startNs = System.nanoTime()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = photo ?: return
        val t = ((System.nanoTime() - startNs) / 1_000_000_000.0).toFloat()
        val tl = t % LiveFx.LOOP
        // 两条时间轴两种声音，各自落在自己的事件上：
        // 取景器是快门（CUT），观片台是灯箱开灯（LIGHT_ON 前 0.14 s）。
        // 导出的视频里是同一条音轨 —— 预览听到什么，成片就是什么。
        if (!paused) {
            if (tl < lastT) lastT = -1f
            if (style == LiveFx.VIEWFINDER) {
                if (lastT < ShutterSound.AT && tl >= ShutterSound.AT) ShutterSound.play(context)
            } else {
                if (lastT < LightboxSound.AT && tl >= LightboxSound.AT) LightboxSound.play(context)
            }
        }
        lastT = tl
        LiveFx.draw(canvas, width, height, style, t, p, seed, gate)
        if (isShown && !paused) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        photo = null
    }
}
