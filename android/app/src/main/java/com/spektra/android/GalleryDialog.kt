@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.spektra.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ===== 成片相册：只展示本软件冲洗后导出到系统相册(Pictures/Deyva)的成片，
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
    // 静态成片在 `Pictures/Deyva`；动态照片改到了 `DCIM/Camera`（相册的动态照片
    // 索引只挂在这个桶上，见 LiveMovie.saveImage 的注释）。两条都要收，否则导出的
    // Live 图在本软件相册里会凭空消失。
    val selection = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
        "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
        "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?))"
    ctx.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection,
        arrayOf("Pictures/Deyva/%", "DCIM/Camera/%", "Deyva_Live_%"),
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
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

/**
 * 把系统相册里挑出来的图片**复制**进本软件自己的相册：`Pictures/Deyva/导入/`。
 *
 * 为什么拍一份副本而不是直接引用原图：
 *  · 观片台和导出都按 Uri 走，原始 Uri 的读权限只在选图这一次会话内有效，
 *    下次进来就成死链；
 *  · 副本都落在 Pictures/Deyva 下，相册查询（LIKE 'Pictures/Deyva/%'）、
 *    删除、"从 Deyva 移除"、观片台全部沿用同一条路径，不需要第二套逻辑。
 *
 * 只复制字节，不做转码 —— 导入是"拿来看"，不该悄悄改一次画质。
 */
private suspend fun importToAlbum(ctx: Context, uris: List<Uri>): Pair<Int, Int> =
    withContext(Dispatchers.IO) {
        var ok = 0
        var bad = 0
        val resolver = ctx.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        uris.forEachIndexed { i, u ->
            runCatching {
                // mime 有可能拿不到（某些 provider 什么都不报）：再从原文件名猜扩展名，
                // 都猜不到才按 jpg 处理，避免把 HEIC 的字节存成 .jpg
                val mime = resolver.getType(u) ?: "image/jpeg"
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    mime.contains("heic") -> "heic"
                    mime.contains("heif") -> "heif"
                    else -> {
                        val n = rawDisplayName(ctx, u).substringAfterLast('.', "").lowercase()
                        if (n in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng")) n else "jpg"
                    }
                }
                val name = "导入_${stamp}_${i + 1}"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Deyva/导入")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val f = File(dir, "Deyva/导入/$name.$ext")
                        f.parentFile?.mkdirs()
                        @Suppress("DEPRECATION")
                        put(MediaStore.Images.Media.DATA, f.absolutePath)
                    }
                }
                val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("insert 返回空")
                resolver.openInputStream(u)?.use { ins ->
                    resolver.openOutputStream(out)?.use { os -> ins.copyTo(os, 64 * 1024) }
                        ?: error("打不开输出流")
                } ?: error("打不开源文件")
                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(out, values, null, null)
                }
                ok++
            }.onFailure {
                bad++
                Diagnostics.log("gallery", "导入失败 ${u}: ${it.message}")
            }
        }
        ok to bad
    }

internal suspend fun loadScaled(ctx: Context, uri: Uri, edge: Int): Bitmap? = withContext(Dispatchers.IO) {    runCatching {
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
private fun GalleryTopBar(title: String, subtitle: String?, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
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
            if (trailing != null) trailing()
            if (subtitle != null) Text(subtitle, color = Dv.label2, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
fun GalleryPage(onClose: () -> Unit, onOpenLightTable: (List<GalleryPhoto>) -> Unit = {}) {
    val ctx = LocalContext.current
    var photos by remember { mutableStateOf<List<GalleryPhoto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // 全屏浏览：记的是**序号**而不是那一张的 Uri。
    // [r43.1] 改成序号是因为要支持左右滑动翻页 —— 翻页翻的就是这个序号，
    // 而"当前是哪一张"必须只有一个来源，否则滑到第 3 张时删除键删的还是第 1 张。
    var viewer by remember { mutableStateOf<Int?>(null) }
    var hidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf<GalleryPhoto?>(null) }
    // 相册 HDR 模拟（可开可关）：开启时把已解码的 SDR 位图按导出 gain map 的
    // 同一条高光曲线提亮。它**不解码文件里的增益图**，也不切换屏幕到 HDR 模式，
    // 所以只是"如果 HDR 屏看到会是什么样"的近似预览，别把它当成 HDR 播放。
    var hdrOn by remember { mutableStateOf(false) }

    // ---------- 导入：从系统相册挑图，复制进 Pictures/Deyva/导入 ----------
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val (ok, bad) = importToAlbum(ctx, uris)
            photos = querySpektraPhotos(ctx, hidden)
            importing = false
            if (ok + bad > 0) {
                android.widget.Toast.makeText(
                    ctx,
                    if (bad == 0) "已导入 $ok 张" else "导入 $ok 张，失败 $bad 张",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        hidden = loadHidden(ctx)
        photos = querySpektraPhotos(ctx, hidden); loaded = true
    }

    // 返回：大图 → 网格 → 退出相册。三级都要自己接住，不能落到 Activity 的默认
    // 行为上（那会直接 finish，桌面见）。
    DvBackHandler {
        if (viewer != null) viewer = null else onClose()
    }

    // [r48] 不再用 Dialog 装相册。
    //
    // Dialog 是**另一个窗口**：侧边手势返回时窗口先被 dismiss，手势还没结束，
    // 剩下那一截就交给了 Activity —— 于是相册关了、app 也跟着退回桌面。改成一个
    // 铺满的普通 Composable，全机只有一个窗口、一个返回派发者，退一层是一层。
    // insets 也从"对话框自己让位"改成自己 systemBarsPadding（主界面是 edge-to-edge）。
    // 铺满的相册不再是独立窗口（Dialog），得自己"占住"触摸：命中测试一旦落在这一层，
    // 下面的编辑器就不在事件路径里了。否则网格空白处的拖动会穿到画面上，
    // 背后的照片跟着缩放/平移，用户在相册里拖半天，回到编辑页发现画面歪了。
    Box(Modifier.fillMaxSize().background(Dv.root)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // 全屏查看单张成片（左右滑动翻页）
            if (viewer != null) {
                val startPage = (viewer ?: 0).coerceIn(0, (photos.size - 1).coerceAtLeast(0))
                val pager = rememberPagerState(initialPage = startPage) { photos.size }
                val cur = photos.getOrNull(pager.currentPage)
                GalleryTopBar(
                    "成片",
                    if (photos.isEmpty()) null else "${pager.currentPage + 1} / ${photos.size}",
                    onBack = { viewer = null },
                )
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                ) { page ->
                    val p = photos.getOrNull(page)
                    // 每页自己解码：翻回来时不用重算，翻页时相邻页已经备好。
                    // HDR 模拟也在这里做，开关一拨整页重算一遍。
                    val bmp by produceState<Bitmap?>(null, p?.uri, hdrOn) {
                        value = p?.let { ph ->
                            withContext(Dispatchers.Default) {
                                loadScaled(ctx, ph.uri, 1600)?.let { b ->
                                    if (hdrOn) runCatching { UltraHdrJpeg.hdrLook(b) }.getOrDefault(b) else b
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val b = bmp
                        if (b != null) Image(b.asImageBitmap(), "成片预览",
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else CircularProgressIndicator(color = Orange)
                    }
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
                            .iosTap(pressDim = 0.5f) { cur?.let { sharePhoto(ctx, it.uri) } },
                            contentAlignment = Alignment.Center) {
                            Text("分享", color = Orange, fontSize = 15.sp)
                        }
                        Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                            .iosTap(pressDim = 0.5f) { confirmDelete = cur },
                            contentAlignment = Alignment.Center) {
                            Text("删除", color = Dv.red, fontSize = 15.sp)
                        }
                    }
                }
            } else {
                // 相册网格页
                GalleryTopBar("相册", if (loaded) "共 ${photos.size} 张" else null, { onClose() }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 导入系统相册里的图片：给没有导出成片的人也留一条进观片台的路
                        Box(
                            Modifier.height(34.dp).clip(RoundedCornerShape(11.dp))
                                .background(Color(0x1FFFFFFF))
                                .padding(horizontal = 12.dp)
                                .iosTap(pressDim = 0.6f, haptic = true) {
                                    if (!importing) importPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (importing) "导入中…" else "导入",
                                color = if (importing) Dv.label3 else Dv.label1,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (photos.isNotEmpty()) {
                            Box(
                                Modifier.height(34.dp).clip(RoundedCornerShape(11.dp))
                                    .background(Orange.copy(alpha = 0.18f))
                                    .padding(horizontal = 12.dp)
                                    .iosTap(pressDim = 0.6f, haptic = true) { onOpenLightTable(photos) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("观片台", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                when {
                    !loaded -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                    photos.isEmpty() -> Column(
                        Modifier.weight(1f).fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("相册还是空的", color = Dv.label1, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("冲洗导出后的成片会出现在这里；也可以直接导入相册里的照片。",
                            color = Dv.label3, fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        Box(
                            Modifier.height(38.dp).clip(RoundedCornerShape(11.dp))
                                .background(Orange.copy(alpha = 0.18f))
                                .padding(horizontal = 18.dp)
                                .iosTap(pressDim = 0.6f, haptic = true) {
                                    importPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("导入图片", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
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
                                            .iosTap(pressScale = 0.95f, pressDim = 0.85f, haptic = true) {
                                                // 从网格进大图：记序号，之后左右滑就是在这个列表里前后走
                                                val at = photos.indexOfFirst { it.uri == p.uri }
                                                if (at >= 0) viewer = at
                                            }
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
                        confirmDelete = null; viewer = null
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
                        confirmDelete = null; viewer = null
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
