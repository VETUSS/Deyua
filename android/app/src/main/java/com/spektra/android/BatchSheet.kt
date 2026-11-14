package com.spektra.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 批量冲洗：把**当前这套设置**（胶片、相纸、片边、配方、尺寸与质量）一次套到选中的
 * 多张照片上，逐张从原片重新冲洗再写出。
 *
 * 手机上一卷 24/36 张，一张张改一张张导是最费的环节；这里不做"批量改参数"，
 * 只做"同一套设置跑很多张" —— 和暗房里一次配好药水、连续放一批相是同一件事。
 */
@Composable
fun BatchSheet(
    uris: List<Uri>,
    states: List<String>,
    running: Boolean,
    onPick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0xF00B0A09))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("取消", color = Dv.label2, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .iosTap(pressDim = 0.6f) { if (!running) onClose() }
                        .padding(horizontal = 6.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f))
                Text("批量冲洗", color = Dv.paper, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (running) "冲洗中" else "开始",
                    color = if (uris.isEmpty() || running) Dv.label4 else Dv.accent,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .iosTap(pressDim = 0.6f) {
                            if (running) onStop() else if (uris.isNotEmpty()) onStart()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "当前这套设置会原样套到每一张：胶片、相纸、片边、影调与配方。" +
                    "每张都从原片重新冲洗，不是复制第一张的结果。",
                color = Dv.label3, fontSize = 10.5f.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(12.dp))

            if (uris.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有选照片", color = Dv.label2, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("一次最多 50 张", color = Dv.label4, fontSize = 10.5f.sp)
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(Dv.accent.copy(alpha = 0.18f))
                                .iosTap(pressDim = 0.7f, haptic = true) { onPick() }
                                .padding(horizontal = 18.dp, vertical = 11.dp)
                        ) {
                            Text("选择照片", color = Dv.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(uris) { i, _ ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${i + 1}", color = Dv.label4, fontSize = 11.sp,
                                modifier = Modifier.width(26.dp))
                            Text(
                                "照片 ${i + 1}",
                                color = Dv.label1, fontSize = 12.5.sp, modifier = Modifier.weight(1f)
                            )
                            Text(
                                states.getOrNull(i) ?: "等待",
                                color = when {
                                    states.getOrNull(i)?.startsWith("已") == true -> Dv.accent
                                    states.getOrNull(i)?.startsWith("失败") == true -> Dv.red
                                    else -> Dv.label3
                                },
                                fontSize = 10.5f.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${uris.size} 张", color = Dv.label3, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text("重新选择", color = Dv.label2, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .iosTap(pressDim = 0.6f) { if (!running) onPick() }
                            .padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}


/**
 * 写一张 JPEG 到「照片 / Pictures/Deyva」，返回是否成功。
 *
 * IS_PENDING 是必须的：先建占位行再写，写完才置 0 —— 否则相册会在写入过程中
 * 索引到一张半截文件，缩略图就是灰的。
 */
fun saveJpegToGallery(ctx: Context, bmp: Bitmap, name: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/Deyva")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ?: return false
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (t: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}
