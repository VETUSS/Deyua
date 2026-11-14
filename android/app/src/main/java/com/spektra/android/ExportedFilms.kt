package com.spektra.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已导出胶片（r50）。
 *
 * 一行一卷，竖向排。点一行展开它的**冲洗数据** —— 胶片、相纸、曝光、颗粒、
 * 光晕、片边、色彩、尺寸、存放位置、时间。这些值在导出那一刻同时写进了
 * EXIF 的 ImageDescription，但那是给别的软件看的；在自家列表里应该一眼能读到。
 *
 * 展开后最下面两枚是这版的重点：取景器 live / 观片台 live。
 */
@Composable
fun ExportedFilmsPage(
    onClose: () -> Unit,
    onOpenLive: (ExportRecord, Int) -> Unit,
    refreshKey: Int = 0,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var records by remember { mutableStateOf<List<ExportRecord>?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ExportRecord?>(null) }

    LaunchedEffect(refreshKey) {
        records = withContext(Dispatchers.IO) { ExportStore.load(ctx) }
    }

    DvBackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Dv.root)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // 顶栏
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 4.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp))
                    .background(Dv.glassBar).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
                        .iosTap(pressDim = 0.45f) { onClose() }
                        .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text("‹ 返回", color = Dv.blue, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("已导出胶片", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    val n = records?.size
                    Text(if (n == null) "" else "共 $n 卷", color = Dv.label2, fontSize = 12.sp,
                        modifier = Modifier.padding(end = 10.dp))
                }
            }

            val list = records
            when {
                list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Dv.accent, strokeWidth = 2.dp)
                }
                list.isEmpty() -> EmptyExports()
                else -> LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                ) {
                    item(key = "head") {
                        Column(Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, bottom = 10.dp)) {
                            Text("冲洗记录", color = Dv.paper, fontSize = 22.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Spacer(Modifier.height(3.dp))
                            Text("点一行展开冲洗数据；展开后可以把这张成片放进取景器或观片台，导出成视频 / 动态照片。",
                                color = Dv.label3, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                    items(list, key = { it.id }) { rec ->
                        ExportRow(
                            ctx = ctx, rec = rec,
                            expanded = expanded == rec.id,
                            onToggle = { expanded = if (expanded == rec.id) null else rec.id },
                            onOpenLive = { style -> onOpenLive(rec, style) },
                            onDelete = { confirmDelete = rec },
                        )
                    }
                }
            }
        }
    }

    // 删除：只删记录与本地副本，相册里的原片不动 —— 文案必须说清楚
    val del = confirmDelete
    if (del != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Dv.panelRaised,
            title = { Text("移除这条记录？", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text("只会删除 Deyva 里的这条冲洗记录和它的预览副本。系统相册里的成片 ${del.name} 不会被删除。",
                    color = Dv.label2, fontSize = 13.sp, lineHeight = 19.sp)
            },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    ExportStore.delete(ctx, del.id)
                    records = records?.filterNot { it.id == del.id }
                    if (expanded == del.id) expanded = null
                    confirmDelete = null
                }) { Text("移除", color = Dv.red, fontSize = 14.sp) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton({ confirmDelete = null }) {
                    Text("取消", color = Dv.label2, fontSize = 14.sp)
                }
            },
        )
    }
}

@Composable
private fun EmptyExports() {
    Column(Modifier.fillMaxSize().padding(horizontal = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(74.dp).clip(RoundedCornerShape(22.dp))
            .background(Dv.panelRaised), contentAlignment = Alignment.Center) {
            GlyphIcon(Glyph.Roll, Dv.label3, 34.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text("还没有导出过胶片", color = Dv.label1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("在暗房里冲洗一张，点「导出」之后，它会带着完整的冲洗数据出现在这里。",
            color = Dv.label3, fontSize = 12.sp, lineHeight = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("（这份档案从现在起才建立：此前已经导出到相册的成片没有留下冲洗数据，不会自动出现在这里）",
            color = Dv.label4, fontSize = 9.5.sp, lineHeight = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ExportRow(
    ctx: Context,
    rec: ExportRecord,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLive: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<Bitmap?>(null, rec.id) {
        value = withContext(Dispatchers.IO) { ExportStore.thumbOf(rec) }
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (expanded) Dv.panelRaised else Dv.panel)
            .border(1.dp, if (expanded) Dv.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(16.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().iosTap(pressDim = 0.62f) { onToggle() }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 缩略图：4:5 的相纸比例，边上一圈极细的暖白，像一张小样
            Box(Modifier.width(66.dp).height(82.dp).clip(RoundedCornerShape(9.dp))
                .background(Dv.root).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(9.dp))) {
                val b = thumb
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Dv.accent, strokeWidth = 1.5.dp, modifier = Modifier.size(16.dp))
                }
                // 右下角一枚小小的 live 标记：这两张图随时可以"动起来"
                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)) {
                    Text("LIVE", color = Dv.orangeLight, fontSize = 6.5f.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rec.filmLabel, color = Dv.label1, fontSize = 14.5f.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (rec.mode == "相纸") rec.printLabel else "${rec.printLabel} · ${rec.mode}",
                    color = Dv.label2, fontSize = 11.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stampOf(rec.ts), color = Dv.label3, fontSize = 9.5f.sp)
                    Text(" · ", color = Dv.label4, fontSize = 9.5f.sp)
                    Text("${rec.width}×${rec.height}", color = Dv.label3, fontSize = 9.5f.sp, fontFamily = Dv.mono)
                    Text(" · ", color = Dv.label4, fontSize = 9.5f.sp)
                    Text(rec.formatLabel, color = Dv.label3, fontSize = 9.5f.sp)
                }
            }
            Spacer(Modifier.width(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "收起" else "详情", color = if (expanded) Dv.accent else Dv.label3,
                    fontSize = 10.5f.sp)
                Spacer(Modifier.width(4.dp))
                GlyphIcon(Glyph.Chevron, if (expanded) Dv.accent else Dv.label3, 14.dp,
                    rotation = if (expanded) 180f else 0f)
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
                Hairline(alpha = 0.07f)
                Spacer(Modifier.height(10.dp))
                Text("冲洗数据", color = Dv.label3, fontSize = 9.5f.sp, letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                KeyVal("胶片", rec.filmLabel)
                KeyVal("相纸", if (rec.mode == "相纸") rec.printLabel else rec.mode)
                KeyVal("曝光", "%+.2f EV".format(rec.exposureEv))
                KeyVal("颗粒", "%.2f×".format(rec.grain))
                KeyVal("光晕", "%.2f×".format(rec.halation))
                KeyVal("片边", rec.edge)
                KeyVal("色彩", rec.colorSpace)
                KeyVal("尺寸", "${rec.width} × ${rec.height}（%.1f MP）".format(rec.megapixels))
                KeyVal("格式", "${rec.formatLabel} · ${rec.destination}")
                if (rec.shotInfo.isNotBlank()) KeyVal("拍摄", rec.shotInfo)
                KeyVal("文件", rec.name, mono = true, small = true)
                KeyVal("冲洗于", fullStamp(rec.ts))

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveAction(
                        Modifier.weight(1f), Glyph.Frame, "取景器 live",
                        "手持呼吸 · 颗粒 · 曝光泵动", Dv.accent,
                    ) { onOpenLive(LiveFx.VIEWFINDER) }
                    LiveAction(
                        Modifier.weight(1f), Glyph.Lamp, "观片台 live",
                        "灯箱启辉 · 相纸浮出", Dv.blue,
                    ) { onOpenLive(LiveFx.PRINT) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallAction(Modifier.weight(1f), "分享成片") {
                        shareRecord(ctx, rec)
                    }
                    SmallAction(Modifier.weight(1f), "打开原图") {
                        openRecord(ctx, rec)
                    }
                    SmallAction(Modifier.weight(1f), "移除记录", danger = true) { onDelete() }
                }
            }
        }
    }
}

@Composable
private fun KeyVal(label: String, value: String, mono: Boolean = false, small: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.5.dp)) {
        Text(label, color = Dv.label3, fontSize = 11.sp, modifier = Modifier.width(52.dp))
        Text(
            value,
            color = if (mono) Dv.label2 else Dv.label1,
            fontSize = if (small) 10.sp else 12.sp,
            fontFamily = if (mono) Dv.mono else null,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LiveAction(
    modifier: Modifier, glyph: Glyph, title: String, sub: String, tint: Color, onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(13.dp))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.20f), tint.copy(alpha = 0.08f))))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
            .iosTap(pressScale = 0.97f, pressDim = 0.8f, haptic = true) { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(glyph, tint, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(title, color = tint, fontSize = 12.5f.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(3.dp))
        Text(sub, color = Dv.label3, fontSize = 8.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SmallAction(modifier: Modifier, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
        .background(if (danger) Dv.red.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.055f))
        .iosTap(pressDim = 0.6f) { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = if (danger) Dv.red else Dv.label2, fontSize = 11.5f.sp)
    }
}

private fun stampOf(ts: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ts))

private fun fullStamp(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))

private fun shareRecord(ctx: Context, rec: ExportRecord) {
    runCatching {
        val uri = if (rec.mediaUri.isNotBlank()) android.net.Uri.parse(rec.mediaUri)
                  else androidx.core.content.FileProvider.getUriForFile(
                      ctx, "${ctx.packageName}.fileprovider", File(rec.viewPath))
        val itn = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(itn, "分享成片"))
    }.onFailure { Diagnostics.log("exports", "分享失败：${it.message}") }
}

private fun openRecord(ctx: Context, rec: ExportRecord) {
    runCatching {
        val uri = if (rec.mediaUri.isNotBlank()) android.net.Uri.parse(rec.mediaUri)
                  else androidx.core.content.FileProvider.getUriForFile(
                      ctx, "${ctx.packageName}.fileprovider", File(rec.viewPath))
        val itn = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(itn)
    }.onFailure { Diagnostics.log("exports", "打开失败：${it.message}") }
}
