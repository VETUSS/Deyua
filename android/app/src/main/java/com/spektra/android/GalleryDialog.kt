package com.spektra.android

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ===== 成片相册：只展示本软件冲洗后导出到系统相册(Pictures/SpektraLab)的成片，
//        全屏大页面，按冲洗时间（日期）分组，符合 iOS 图库风格 =====

data class GalleryPhoto(val uri: Uri, val dateAddedMillis: Long)

// 「仅从 Deyva 移除」的成片：持久化记录其 uri，相册查询时过滤掉（系统相册文件仍保留）
private const val GALLERY_PREFS = "deyva_gallery"
private const val GALLERY_HIDDEN_KEY = "hidden_uris"

private fun loadHidden(ctx: Context): Set<String> =
    ctx.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE)
        .getStringSet(GALLERY_HIDDEN_KEY, emptySet()) ?: emptySet()

private fun saveHidden(ctx: Context, hidden: Set<String>) {
    ctx.getSharedPreferences(GALLERY_PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(GALLERY_HIDDEN_KEY, hidden).apply()
}

private fun querySpektraPhotos(ctx: Context, hidden: Set<String> = emptySet()): List<GalleryPhoto> {
    val list = mutableListOf<GalleryPhoto>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_ADDED
    )
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    ctx.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
        arrayOf("Pictures/SpektraLab/%"), "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
            if (uri.toString() in hidden) continue
            list.add(GalleryPhoto(uri, c.getLong(dateCol) * 1000L))
        }
    }
    return list
}

private suspend fun loadScaled(ctx: Context, uri: Uri, edge: Int): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, android.util.Size(edge, edge), null) }
                .getOrElse { decodeFallback(ctx, uri, edge) }
        } else decodeFallback(ctx, uri, edge)
    }.getOrNull()
}

private fun decodeFallback(ctx: Context, uri: Uri, edge: Int): Bitmap {
    ctx.contentResolver.openInputStream(uri)?.use { ins ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ins, null, opts)
        var s = 1
        while (opts.outWidth / (s * 2) >= edge && opts.outHeight / (s * 2) >= edge) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        ctx.contentResolver.openInputStream(uri)?.use { i2 -> return BitmapFactory.decodeStream(i2, null, o) ?: throw IllegalStateException() }
    }
    throw IllegalStateException("decode failed")
}

private fun sharePhoto(ctx: Context, uri: Uri) {
    val mime = ctx.contentResolver.getType(uri) ?: "image/*"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(intent, "分享成片")) }
        .onFailure { Diagnostics.log("gallery", "分享失败: ${it.message}") }
}

// 日期归一化到当天 0 点（毫秒），用于按天分组
private fun dayKey(ts: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return c.timeInMillis
}

// iOS 图库风格的日期头文案：今天 / 昨天 / yyyy年M月d日
private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val today = dayKey(now.timeInMillis)
    val yesterday = dayKey(now.timeInMillis) - 24 * 3600 * 1000L
    return when (dayKey(ts)) {
        today -> "今天"
        yesterday -> "昨天"
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ts))
    }
}

// 全屏玻璃导航栏（圆角毛玻璃卡片，与主界面顶栏/按钮统一圆角语言）
@Composable
private fun GalleryTopBar(title: String, subtitle: String?, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(18.dp)).background(Dv.glassBar).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // [r13] 返回键 44dp 触控区 + 按下变暗（原来 16sp 裸文字，可点高度约 20dp）
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
                .iosTap(pressDim = 0.45f) { onBack() }
                .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("‹ 返回", color = Dv.blue, fontSize = 16.sp)
            }
            Spacer(Modifier.width(4.dp))
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (subtitle != null) Text(subtitle, color = Dv.label2, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
fun GalleryPage(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var photos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<GalleryPhoto?>(null) }
    var selectedBmp by remember { mutableStateOf<Bitmap?>(null) }
    var hidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<GalleryPhoto?>(null) }
    // 相册 HDR 模拟（可开可关）：开启时把已解码的 SDR 位图按导出 gain map 的
    // 同一条高光曲线提亮。它**不解码文件里的增益图**，也不切换屏幕到 HDR 模式，
    // 所以只是"如果 HDR 屏看到会是什么样"的近似预览，别把它当成 HDR 播放。
    var hdrOn by remember { mutableStateOf(false) }
    var hdrBmp by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        hidden = loadHidden(ctx)
        photos = querySpektraPhotos(ctx, hidden); loaded = true
    }

    LaunchedEffect(selectedBmp, hdrOn) {
        hdrBmp = if (hdrOn && selectedBmp != null)
            withContext(Dispatchers.Default) { UltraHdrJpeg.hdrLook(selectedBmp!!) } else null
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        Box(Modifier.fillMaxSize().background(Dv.root)) {
            Column(Modifier.fillMaxSize()) {
                // 全屏查看单张成片
                if (selected != null) {
                    val ph = selected!!
                    LaunchedEffect(ph.uri) { selectedBmp = loadScaled(ctx, ph.uri, 1600) }
                    GalleryTopBar("成片", null) { selected = null }
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                        val b = if (hdrOn && hdrBmp != null) hdrBmp else selectedBmp
                        if (b != null) Image(b.asImageBitmap(), "成片预览", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else CircularProgressIndicator(color = Orange)
                    }
                    Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(Dv.glassBar).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            // [r13] 三个动作各占 1/3 宽、留出 44dp 高的触控区；删除给略重的触觉（warn）
                            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                                .iosTap(pressDim = 0.5f, haptic = true) { hdrOn = !hdrOn },
                                contentAlignment = Alignment.Center) {
                                Text(if (hdrOn) "● HDR 模拟" else "○ HDR 模拟", color = if (hdrOn) Orange else Dv.label2, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                                .iosTap(pressDim = 0.5f) { sharePhoto(ctx, ph.uri) },
                                contentAlignment = Alignment.Center) {
                                Text("分享", color = Orange, fontSize = 15.sp)
                            }
                            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                                .iosTap(pressDim = 0.5f) { confirmDelete = ph },
                                contentAlignment = Alignment.Center) {
                                Text("删除", color = Dv.red, fontSize = 15.sp)
                            }
                        }
                    }
                } else {
                    // 相册网格页
                    GalleryTopBar("相册", if (loaded) "共 ${photos.size} 张成片" else null) { onClose() }
                    when {
                        !loaded -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                        photos.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有用 Deyva 冲洗的成片", color = Dv.label1, fontSize = 15.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("先导入照片→选胶片/相纸→导出（存储到“照片”），成片会出现在这里。", color = Dv.label3, fontSize = 12.sp)
                        }
                        else -> {
                            // 按冲洗时间（日期）分组：分组头 + 3 列网格
                            val groups = photos.groupBy { dayKey(it.dateAddedMillis) }
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                groups.forEach { (key, list) ->
                                    item(key = "h$key", span = { GridItemSpan(maxLineSpan) }) {
                                        Text(dayLabel(key), color = Dv.label1, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp))
                                    }
                                    items(list, key = { it.uri.toString() }) { p ->
                                        val bmp by produceState<Bitmap?>(null, p.uri) { value = loadScaled(ctx, p.uri, 256) }
                                        Box(
                                            Modifier.padding(3.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Dv.panel)
                                                .iosTap(pressScale = 0.95f, pressDim = 0.85f, haptic = true) { selected = p }
                                        ) {
                                            val b = bmp
                                            if (b != null) Image(b.asImageBitmap(), "成片缩略图", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange, modifier = Modifier.size(20.dp)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认：仅从 Deyva 移除（保留系统相册文件） / 同时删除系统相册（永久删文件）
    val del = confirmDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Dv.panelRaised,
            title = { Text("删除成片", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("这张照片也保存在系统相册里，请选择删除方式。", color = Dv.label2, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    // 仅从软件内移除
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dv.panel).clickable {
                        val h = hidden + del.uri.toString()
                        hidden = h; saveHidden(ctx, h)
                        confirmDelete = null; selected = null
                        photos = querySpektraPhotos(ctx, h)
                    }.padding(14.dp)) {
                        Column {
                            Text("仅从 Deyva 移除", color = Dv.label1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("系统相册中的文件保留", color = Dv.label3, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 同时删除系统相册
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dv.panel).clickable {
                        runCatching { ctx.contentResolver.delete(del.uri, null, null) }
                        val h = hidden - del.uri.toString()
                        hidden = h; saveHidden(ctx, h)
                        confirmDelete = null; selected = null
                        photos = querySpektraPhotos(ctx, h)
                    }.padding(14.dp)) {
                        Column {
                            Text("同时删除系统相册", color = Dv.red, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("永久删除图片文件", color = Dv.label3, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消", color = Dv.label2, fontSize = 14.sp) } }
        )
    }
}
