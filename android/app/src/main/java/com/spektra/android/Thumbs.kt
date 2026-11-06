package com.spektra.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 胶片/相纸选择网格里的小缩略图（一张照片 × 每个胶片/相纸）。
 *
 * 三条约束决定了这里怎么做：
 *  1. **尺寸**：格子里只有 52 dp（三倍屏约 156 px），源图缩到 384 px 足够；
 *     以前走的是引擎的 "preview" tier（长边 3400），每张都跑一遍 600 px 的整条
 *     胶片流水线 —— 26 张要 1-3 秒，还常被主渲染挤掉。
 *     r9 改成 live tier + `preview_long_edge` 会话设置，像素少一个数量级。
 *  2. **内存**：600 px 的 ARGB_8888 每张 1.1 MB，26 张 28 MB，切几张胶片就 OOM；
 *     384 px 每张 440 KB，26 张 11 MB。
 *  3. **重复**：同一个照片 + 同一个胶片/相纸，颜色永远是那一张，没必要每次重算 ——
 *     存成 PNG 放在 cacheDir，重进 App 也是先命中磁盘再补渲。
 *
 * PNG 而不是 JPEG：4:2:0 色度抽样会把"每种胶片色调差别"抹掉一半，
 * 而 384 px 的 PNG 只有几十 KB，几十张也就几 MB。
 */
object ThumbStore {
    /**
     * 会话设置 `preview_long_edge` 的长边。引擎对它的合法区间是 **[800, 8192]**
     * （`params.cpp` 的字段范围），传更小的值 `spk_open` 会直接失败 ——
     * r9 首版就是传了 256，26 张缩略图一张都没渲出来，格子里全退回原图占位。
     *
     * 这里给下限 800，实际输出仍由源图夹住：缩略图源长边只有 [SRC_EDGE]，
     * `downscale` 遇到"源比目标还小"就直接复用源本身，所以真正跑流水线的
     * 是 384 px，不是 800 px。
     */
    const val EDGE = 800
    /** 缩略图专用小源的长边：比 800 小，直接决定真正渲染的像素数。 */
    const val SRC_EDGE = 384
    /** 目录带版本号：尺寸/算法一变就换目录，免得读到上一版的旧图。
     *  v4（r15）：缩略图渲染改成开自动曝光 —— v3 里那批是"关 AE、被压到同一个中灰"的图，
     *  颜色彼此只差 1~2/255。不换目录的话升级后仍会命中它们，"缩略图全一样"看起来没修。
     *  v5（r17）：缩略图开始带颗粒、并且「胶片本色」用另一套滤片 —— v4 那批是
     *  "无颗粒 + 每片各自配平"的图，和现在的渲染不是同一件事。 */
    private const val DIR = "thumbs-v5"
    /** 旧版本目录，进 App 时顺手清掉（都是纯缓存，删了只会重渲）。 */
    private val STALE_DIRS = listOf("thumbs-v1", "thumbs-v2", "thumbs-v3", "thumbs-v4")
    private const val KEEP_BYTES = 48L * 1024 * 1024   // 48 MB 上限，超了按最旧删

    private fun dir(ctx: Context): File = File(ctx.cacheDir, DIR).apply { mkdirs() }

    private fun safe(key: String): String {
        val sb = StringBuilder(key.length)
        for (c in key) sb.append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_')
        return sb.toString()
    }

    fun file(ctx: Context, photoKey: String, fs: String, ps: String): File =
        File(dir(ctx), "${safe(photoKey)}__${safe(fs)}__${safe(ps)}.png")

    fun load(ctx: Context, photoKey: String, fs: String, ps: String): Bitmap? = try {
        val f = file(ctx, photoKey, fs, ps)
        if (!f.exists() || f.length() == 0L) null
        else BitmapFactory.decodeFile(f.absolutePath)
            ?: null.also { runCatching { f.delete() } }
    } catch (t: Throwable) { null }

    fun save(ctx: Context, photoKey: String, fs: String, ps: String, bmp: Bitmap) {
        try {
            val f = file(ctx, photoKey, fs, ps)
            val tmp = File(f.absolutePath + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (t: Throwable) {
            Diagnostics.log("thumb", "写盘失败 $fs@$ps: ${t.message}")
        }
    }

    /** 清掉不属于这张照片的旧缩略图，再把总量压到上限内（按修改时间，最旧先删）。
     *  `BUILTIN_KEY` 那套（未导入照片时的内置测试图）保留下来 —— 那是每次冷启动都要用的。 */
    fun prune(ctx: Context, keepPhotoKey: String) {
        try {
            // 旧版本目录里是被淘汰的算法渲出来的图，留着既占地方又永远不会被读到
            for (n in STALE_DIRS) runCatching { File(ctx.cacheDir, n).deleteRecursively() }
            val all = dir(ctx).listFiles() ?: return
            val keepA = safe(keepPhotoKey) + "__"
            val keepB = safe(BUILTIN_KEY) + "__"
            var total = 0L
            for (f in all) {
                if (!f.name.startsWith(keepA) && !f.name.startsWith(keepB)) { f.delete(); continue }
                total += f.length()
            }
            if (total <= KEEP_BYTES) return
            for (f in all.filter { it.exists() }.sortedBy { it.lastModified() }) {
                if (total <= KEEP_BYTES) break
                total -= f.length()
                f.delete()
            }
        } catch (t: Throwable) { /* 缓存清理失败不影响功能 */ }
    }

    /** 还没导入照片时，网格用内置测试图的缩略图撑着（不是空白格）。 */
    const val BUILTIN_KEY = "builtin-test"
}

/** 照片指纹：尺寸 + 稀疏采样像素，用来给磁盘缩略图分文件名。
 *  不追求抗碰撞，只要"换一张照片就不会命中上一张的缓存"，而这一点点采样已经足够。 */
fun photoFingerprint(px: FloatArray, w: Int, h: Int): String {
    var acc = 17L
    val n = minOf(px.size, 8192)
    var i = 0
    while (i < n) {
        acc = acc * 131 + java.lang.Float.floatToIntBits(px[i])
        i += 13
    }
    return "${w}x${h}-" + java.lang.Long.toHexString(acc)
}
