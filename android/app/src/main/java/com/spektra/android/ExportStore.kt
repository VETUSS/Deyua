package com.spektra.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 已导出胶片（r50）。
 *
 * 以前"导出"是一次性动作：文件进了系统相册，软件这边什么都不知道。于是
 * 「这张用了什么胶卷、什么相纸、几档曝光」这些冲洗信息只写在 EXIF 的
 * ImageDescription 里，得去相册看属性、还得逐行读中文。
 *
 * 这里给每次成功导出留一条**本地记录**：
 *   · 一条 JSON（filesDir/exports/index.json）；
 *   · 一张 2048 px 的查看副本（取景器 / 观片台 live 图要用它当底片）；
 *   · 一张 320 px 的列表缩略图。
 *
 * 为什么不直接引用系统相册里的那张：MediaStore 的 Uri 可能被用户删掉、
 * 也可能被"清理"类软件搬走；而 live 图、列表缩略图每次进页面都要读。
 * 自己留一份副本，列表就永远不会出现"一排空框"。副本只用于显示，
 * 原片仍在相册里，删除记录不会动相册文件（由调用方明确告知）。
 */
data class ExportRecord(
    val id: String,
    val ts: Long,
    val name: String,
    val filmId: String,
    val filmLabel: String,
    val printId: String,
    val printLabel: String,
    /** 相纸 / 直扫底片 / 数字中间片 —— 决定"冲洗数据"里第二行的说法 */
    val mode: String,
    val exposureEv: Float,
    val grain: Float,
    val halation: Float,
    val edge: String,
    val colorSpace: String,
    val width: Int,
    val height: Int,
    val formatLabel: String,
    val destination: String,
    val mediaUri: String,
    val viewPath: String,
    val thumbPath: String,
    /** 拍摄信息一句话（相机 / 镜头 / 快门），没有就空 */
    val shotInfo: String = "",
) {
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 1f
    val megapixels: Float get() = width.toLong() * height / 1_000_000f
}

object ExportStore {
    private const val DIR = "exports"
    private const val INDEX = "index.json"
    private const val MAX_KEEP = 120
    private const val VIEW_EDGE = 2560
    private const val THUMB_EDGE = 360
    private val lock = Any()

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun indexFile(ctx: Context) = File(dir(ctx), INDEX)

    fun load(ctx: Context): List<ExportRecord> = synchronized(lock) {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.optJSONObject(i)) }
                .sortedByDescending { it.ts }
        }.getOrElse {
            Diagnostics.log("exports", "索引损坏，按空处理：${it.message}")
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<ExportRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        runCatching { indexFile(ctx).writeText(arr.toString()) }
            .onFailure { Diagnostics.log("exports", "写索引失败：${it.message}") }
    }

    /**
     * 记一笔。位图是**成品本身**（导出线程手里那张），这里顺手做两份显示副本。
     * 失败不抛 —— 导出已经成功了，记录失败不该把"已导出"变成失败。
     */
    fun record(
        ctx: Context, bmp: Bitmap,
        filmId: String, filmLabel: String,
        printId: String, printLabel: String,
        mode: String,
        exposureEv: Float, grain: Float, halation: Float,
        edge: String, colorSpace: String,
        formatLabel: String, destination: String, mediaUri: String,
        name: String, shotInfo: String = "",
    ): ExportRecord? = runCatching {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return null
        val id = System.currentTimeMillis().toString(36) + "-" + (100..999).random()
        val d = dir(ctx)
        val view = File(d, "${id}_view.jpg")
        val thumb = File(d, "${id}_thumb.jpg")
        writeScaled(bmp, view, VIEW_EDGE, 90)
        writeScaled(bmp, thumb, THUMB_EDGE, 84)
        val rec = ExportRecord(
            id = id, ts = System.currentTimeMillis(), name = name,
            filmId = filmId, filmLabel = filmLabel,
            printId = printId, printLabel = printLabel, mode = mode,
            exposureEv = exposureEv, grain = grain, halation = halation,
            edge = edge, colorSpace = colorSpace,
            width = bmp.width, height = bmp.height,
            formatLabel = formatLabel, destination = destination,
            mediaUri = mediaUri,
            viewPath = view.absolutePath, thumbPath = thumb.absolutePath,
            shotInfo = shotInfo,
        )
        synchronized(lock) {
            val list = (listOf(rec) + load(ctx)).sortedByDescending { it.ts }
            val kept = list.take(MAX_KEEP)
            list.drop(MAX_KEEP).forEach { old ->
                runCatching { File(old.viewPath).delete() }
                runCatching { File(old.thumbPath).delete() }
            }
            save(ctx, kept)
        }
        Diagnostics.log("exports", "记录一笔 $filmLabel · $printLabel · ${bmp.width}x${bmp.height} 共 ${load(ctx).size} 条")
        rec
    }.getOrElse {
        Diagnostics.log("exports", "记录失败：${it.message}")
        null
    }

    fun delete(ctx: Context, id: String) {
        synchronized(lock) {
            val list = load(ctx)
            val hit = list.firstOrNull { it.id == id } ?: return
            runCatching { File(hit.viewPath).delete() }
            runCatching { File(hit.thumbPath).delete() }
            save(ctx, list.filterNot { it.id == id })
        }
    }

    /** 取景器 / 观片台的底片：优先读自己的副本，缺了再从相册 Uri 解一次。 */
    suspend fun viewOf(ctx: Context, rec: ExportRecord, edge: Int = VIEW_EDGE): Bitmap? {
        decodeFile(rec.viewPath, edge)?.let { return it }
        if (rec.mediaUri.isNotBlank()) {
            runCatching {
                val uri = Uri.parse(rec.mediaUri)
                return loadScaled(ctx, uri, edge)
            }.onFailure { Diagnostics.log("exports", "回读相册失败：${it.message}") }
        }
        return decodeFile(rec.thumbPath, THUMB_EDGE)
    }

    fun thumbOf(rec: ExportRecord): Bitmap? = decodeFile(rec.thumbPath, THUMB_EDGE)

    private fun decodeFile(path: String, edge: Int): Bitmap? = runCatching {
        val f = File(path)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var s = 1
        while (opts.outWidth / (s * 2) >= edge && opts.outHeight / (s * 2) >= edge) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    private fun writeScaled(src: Bitmap, dst: File, edge: Int, q: Int) {
        val maxEdge = maxOf(src.width, src.height)
        val s = if (maxEdge <= edge) 1f else edge.toFloat() / maxEdge
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        val b = if (s >= 1f) src else Bitmap.createScaledBitmap(src, w, h, true)
        FileOutputStream(dst).use { b.compress(Bitmap.CompressFormat.JPEG, q, it) }
        if (b !== src) b.recycle()
    }

    private fun toJson(r: ExportRecord) = JSONObject().apply {
        put("id", r.id); put("ts", r.ts); put("name", r.name)
        put("filmId", r.filmId); put("filmLabel", r.filmLabel)
        put("printId", r.printId); put("printLabel", r.printLabel)
        put("mode", r.mode)
        put("exposureEv", r.exposureEv.toDouble())
        put("grain", r.grain.toDouble()); put("halation", r.halation.toDouble())
        put("edge", r.edge); put("colorSpace", r.colorSpace)
        put("width", r.width); put("height", r.height)
        put("formatLabel", r.formatLabel); put("destination", r.destination)
        put("mediaUri", r.mediaUri)
        put("viewPath", r.viewPath); put("thumbPath", r.thumbPath)
        put("shotInfo", r.shotInfo)
    }

    private fun fromJson(o: JSONObject?): ExportRecord? {
        o ?: return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        return ExportRecord(
            id = id, ts = o.optLong("ts"), name = o.optString("name"),
            filmId = o.optString("filmId"), filmLabel = o.optString("filmLabel"),
            printId = o.optString("printId"), printLabel = o.optString("printLabel"),
            mode = o.optString("mode", "相纸"),
            exposureEv = o.optDouble("exposureEv", 0.0).toFloat(),
            grain = o.optDouble("grain", 1.0).toFloat(),
            halation = o.optDouble("halation", 0.0).toFloat(),
            edge = o.optString("edge", "—"), colorSpace = o.optString("colorSpace", "sRGB"),
            width = o.optInt("width"), height = o.optInt("height"),
            formatLabel = o.optString("formatLabel", "JPEG"),
            destination = o.optString("destination", "照片"),
            mediaUri = o.optString("mediaUri"),
            viewPath = o.optString("viewPath"), thumbPath = o.optString("thumbPath"),
            shotInfo = o.optString("shotInfo"),
        )
    }
}
