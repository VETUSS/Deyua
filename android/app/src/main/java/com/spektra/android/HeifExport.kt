package com.spektra.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecList
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.heifwriter.HeifWriter
import java.io.File
import java.io.FileOutputStream

/**
 * HEIF / HEIC 输出（r20 建立，r24 换编码通道）。
 *
 * ## 为什么 r20 在你机器上"不支持"
 *
 * 第一版直接反射系统里的 `android.media.HeifWriter`。它不是公开 API（`android.jar` 里没有、
 * `api-versions.xml` 里也没有），只对系统应用开放 —— Android 9 起有 hidden-API 拦截，
 * Android 15/16（API 35/37）的名单更严，`Class.forName` 直接取不到这个类，于是
 * `available` 判成 false，导出面板里 HEIF 那一档就一直是灰的。功能是做了的，只是**通道被系统封了**。
 *
 * ## 现在走 androidx.heifwriter
 *
 * 这是 AOSP 那份 HeifWriter 的 Jetpack 版（`androidx.heifwriter:heifwriter`）。同一份实现、
 * 同一个 MediaMuxer(HEIF) + MediaCodec(HEVC) 管线，但它只依赖**公开 API**：
 * `new MediaMuxer(fd, 3)`、`MediaCodecList`、`MediaCodec`。所以普通应用可以直接用。
 *
 * 编解码仍然走 **Surface 模式**：把已经冲洗好的 Bitmap 画到它给的输入 Surface 上，
 * 由系统 HEVC 编码器压成 HEIF。这条路上没有我们自己的容器封装，出来的就是标准 `.heic`
 * （ftyp heic），相册 / 微信 / 系统分享都能直接吃。
 *
 * 三点必须交代清楚：
 *  ① 只有 HEVC（video/hevc）**编码器**存在时才认为可用 —— 有 HEIC 解码器不等于能编码；
 *  ② HEIF 里没有 ICC 的位置（HeifWriter 不写 colr 盒），所以**强制 sRGB**：调用方
 *     用 sRGB 渲染，面板里也把色彩那行藏掉。否则 P3 的像素会被当成 sRGB 显示，偏色；
 *  ③ EXIF 由 `addExifData` 塞进去，内容是复用 JPEG 那条链路生成的 APP1 段
 *     （见 [Exporter.exifApp1]）—— 同一套白名单、同一套"成片覆盖源片"的规则。
 */
object HeifExport {
    /** 库里 HeifWriter 的 minSdk 是 28；低版本连类都不碰。 */
    private const val MIN_SDK = 28

    /**
     * 有没有 HEVC 编码器（有解码器 ≠ 能编码）。
     *
     * 两道判断：先查编解码器清单，再真去要一个编码器实例 —— 有些 ROM 的清单里
     * 漏报 HEVC 编码（或只把别名注册在 ALL_CODECS 里），只看清单会误伤成"本机不支持"。
     */
    private val hasHevcEncoder: Boolean by lazy {
        val listed = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
            }
        }.getOrDefault(false)
        if (listed) {
            Diagnostics.log("heif", "HEVC 编码器：清单里有")
            return@lazy true
        }
        val probe = runCatching { MediaCodec.createEncoderByType("video/hevc").release() }
        Diagnostics.log("heif", "HEVC 编码器：清单未列出，实例探测 ${if (probe.isSuccess) "成功" else "失败 " + (probe.exceptionOrNull()?.message ?: "")}")
        probe.isSuccess
    }

    /**
     * 能否调压缩质量。androidx 版的 Builder 一定有 setQuality（API 28 那条老路没有），
     * 所以只要通道可用就是 true —— 面板据此给滑杆，而不是给一句"最高"。
     */
    val supportsQuality: Boolean get() = available

    /**
     * 本机能不能导出 HEIF。面板据此决定这一档是可用还是"本机不支持"。
     */
    val available: Boolean get() = Build.VERSION.SDK_INT >= MIN_SDK && hasHevcEncoder

    /**
     * 把一张已经冲洗好的成片编码成 HEIF 字节。
     *
     * 失败返回 null（并写诊断日志），调用方据此报错，不静默回落成 JPEG —— 用户
     * 选了 HEIF 却拿到 jpg，文件在相册里看着一样，最容易变成"这功能是假的"。
     */
    fun encode(
        ctx: Context, src: Bitmap, quality: Int,
        metadata: String, sourceExif: Map<String, String>,
    ): ByteArray? {
        if (!available) {
            Diagnostics.log("heif", "跳过：sdk=${Build.VERSION.SDK_INT} hevc=$hasHevcEncoder")
            return null
        }
        // HEVC 用 4:2:0 子采样，宽高必须是偶数；差的那一列/行由绘制时的目标矩形裁掉，
        // 不复制整张 Bitmap（12MP 复制一次就是 48MB，低端机会直接 OOM）。
        val w = src.width and 1.inv()
        val h = src.height and 1.inv()
        if (w < 2 || h < 2) return null
        val exif = Exporter.exifApp1(ctx, src, metadata, sourceExif, w, h)
        val tmp = File(ctx.cacheDir, "deyva_heif_${System.nanoTime()}.heic")
        return try {
            FileOutputStream(tmp).use { fos ->
                writeHeif(fos, src, w, h, quality, exif)
            }
            val bytes = tmp.readBytes()
            // ftyp 在 4..8 字节；少于 512B 必然不是一个能看的图。
            val ok = bytes.size > 512 && bytes[4] == 'f'.code.toByte() &&
                bytes[5] == 't'.code.toByte() && bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte()
            if (ok) bytes else {
                Diagnostics.log("heif", "输出不是 HEIF（${bytes.size}B）")
                null
            }
        } catch (e: Throwable) {
            Diagnostics.log("heif", "编码失败 ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /**
     * 真正落盘的一段：建 writer → 取 Surface → start → 塞 EXIF → 画一帧 → stop。
     *
     * grid（拼贴）默认开，HeifWriter 会自己按 tile 切；小图关掉能少两层盒子、也少几条
     * tile 接缝。超过 4096px 的长边仍交给它切 —— 单帧 HEVC 有编码器自己的尺寸上限。
     */
    @RequiresApi(MIN_SDK)
    private fun writeHeif(
        fos: FileOutputStream, src: Bitmap, w: Int, h: Int, quality: Int, exif: ByteArray?,
    ) {
        val grid = maxOf(w, h) > 4096
        val writer = HeifWriter.Builder(fos.fd, w, h, HeifWriter.INPUT_MODE_SURFACE)
            // 只要一张：surface 模式在 maxImages 张到齐（或 EOS 时间戳到达）时才收尾
            .setMaxImages(1)
            .setQuality(quality.coerceIn(0, 100))
            .setGridEnabled(grid)
            .build()
        var surface: Surface? = null
        try {
            val s = writer.getInputSurface()
            surface = s
            writer.start()
            // EXIF 必须在 start() **之后**登记。WriterBase.addExifData 内部调的是
            // checkStarted(true)：mStarted 为 false 就抛 IllegalStateException(
            // "Already started"，措辞反了，但条件就是这样)。r26 真机日志里
            // "heif: EXIF 未写入 IllegalStateException" 就是它 —— 图照常导出，
            // 只是配方和拍摄信息全丢了。getInputSurface 反过来要求 start 之前调用，
            // 所以三者的次序只能是：surface → start → exif → 画帧 → stop。
            // EXIF 内容复用 JPEG 链路的 APP1 段（"Exif\0\0" + TIFF），正是
            // androidx HeifWriter 文档要求的格式。
            if (exif != null) {
                runCatching { writer.addExifData(0, exif, 0, exif.size) }
                    .onFailure { Diagnostics.log("heif", "EXIF 未写入 ${it.javaClass.simpleName}: ${it.message}") }
                    .onSuccess { Diagnostics.log("heif", "EXIF 已登记 ${exif.size}B") }
            }
            val canvas = s.lockCanvas(null) ?: error("lockCanvas 返回 null")
            // 先铺一层不透明底：Surface 首帧可能是脏的，PNG 带 alpha 的成片
            // 直接画上去会透出旧内容。
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(src, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            s.unlockCanvasAndPost(canvas)
            // stop 是同步的：等到编码器真正写完才返回，文件在这里才有效
            writer.stop(20_000L)
            Diagnostics.log("heif", "编码完成 ${w}x$h q=$quality grid=$grid exif=${exif?.size ?: 0}B")
        } finally {
            surface?.release()
            runCatching { writer.close() }
        }
    }
}
