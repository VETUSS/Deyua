package com.spektra.android

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 把 live 图编码成视频 / 动态照片（r50）。
 *
 * 两条产物：
 *  · **MP4**：H.264，存进 `Movies/Deyva`；
 *  · **Live Photo**：一张 JPEG，尾部直接接上这段 MP4。容器层所有厂商一致（JPEG +
 *    尾部 MP4 是事实标准），分岔只在"相册凭什么认定这是动态照片"：Google / 三星 /
 *    小米 / 华为 / 荣耀 / vivo 看 XMP 的 `GCamera:MotionPhoto` 与
 *    `Container:Directory`；OPPO / 一加 / realme 的 ColorOS 只看 EXIF `UserComment`
 *    里的暗号 `oplus_8388608`。
 *
 *    **r67 起只有一套布局**：把两边的判据都写进同一个文件 —— XMP 在前、116 字节的
 *    Exif 暗号紧随其后、不写 MPF。这是两个实测跑通的实现共同的结构，见 [attachMotionPhoto]。
 *    r63 至 r66 一直在按品牌分流、并且写反了段序，那些版本在 ColorOS 上都没成功。
 *
 * 封装写完会立刻用 [audit] 把字节读回来核一遍，结果进运行日志：相册不认时
 * 唯一的线索就是"文件里到底有什么"。
 *
 * 画面一律由 [LiveFx] 画，和屏幕上预览用的是同一个函数：所见即所存。
 */
object LiveMovie {

    data class Quality(val id: String, val label: String, val edge: Int) {
        companion object {
            val LIST = listOf(
                Quality("orig", "原图", 0),
                Quality("2048", "2048", 2048),
                Quality("1440", "1440", 1440),
                Quality("1080", "1080", 1080),
            )
        }
    }

    data class Result(
        val ok: Boolean,
        val message: String,
        val uri: String = "",
        val detail: String = "",
    )

    /**
     * 导出帧率。
     *
     * r59 从 24 改到 30。24 的问题不是"不够电影感"，而是**表达不了这台机器的动作**：
     * 反光镜那一下位移总共不到 0.15 s，24 fps 只采到 3 帧，衰减的摆动全被采样漏掉，
     * 剩下的就是"跳一格"。30 fps 每 33 ms 一帧，抖动按 7 Hz 排，一个半周期有 4–5 帧，
     * 才能读出"顿一下再回弹"。声音也一起受益：AAC 帧更密，声画对齐的粒度从 42 ms 到 33 ms。
     */
    private const val FPS = 30
    private const val MAX_STILL = 4096
    private const val MAX_VIDEO = 1440

    /**
     * OPPO 相册的"暗号"：Exif `UserComment` 里的 `oplus_8388608`。
     *
     * 大小写踩过两次：r63 写小写、r64 改成大写（当时以为固件是逐字节比较）。实测跑通的
     * 两份实现（flashlab/motion-live-photo、CYQawa/YunX）用的都是**小写**，而且整段
     * Exif 是固定 116 字节的模板 —— 见 [oppoExif]。这里跟着实测走。
     */
    private const val TOKEN = "oplus_8388608"

    /**
     * 116 字节的 OPPO Exif 模板，逐字节取自实测可行的合成方案。
     *
     * 结构：`FFE1 0072 "Exif\0\0" MM 002A 00000008` + IFD0 四条（宽 / 高 / ExifIFD
     * 指针 62 / Orientation）+ Exif 子 IFD 两条（UserComment ASCII 14 字节 @92 / 0x9208）
     * + `oplus_8388608\0`。宽高在两处 value 字段（段内 28..31、40..43）按静图回填。
     */
    private const val EXIF_TEMPLATE =
        "FFE100724578696600004D4D002A0000000800040100000400000001000005A001010004000000010000043C" +
            "87690004000000010000003E011200030000000100000000000000000002928600020000000E0000005C" +
            "920800040000000100000000000000006F706C75735F3833383836303800"


    /**
     * @param livePhoto true → 存成动态照片（JPEG + MP4）；false → 存成 MP4 视频
     * @param gate 成片里门洞的位置（[LiveFx.gateFracFor]）：取景器动画只看门洞里的像，
     *   最后一帧才把带片边的那张成片整张放出来。null = 这张没开片边。
     * @param onProgress 0…1，回调在编码线程上，调用方自己切主线程
     */
    fun export(
        ctx: Context, photo: Bitmap, style: Int, seed: Int,
        quality: Quality, seconds: Float = LiveFx.LOOP, livePhoto: Boolean,
        gate: RectF? = null,
        metaLine: String = "", onProgress: (Float) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): Result {
        var tmp: File? = null
        try {
            if (photo.isRecycled) return Result(false, "底片已释放，无法导出")

            val stillEdge = if (quality.edge == 0) minOf(maxOf(photo.width, photo.height), MAX_STILL)
                            else minOf(quality.edge, MAX_STILL)
            val videoEdge = minOf(stillEdge, MAX_VIDEO)
            val v = fitEven(photo.width, photo.height, videoEdge)

            tmp = File(ctx.cacheDir, "live_${System.currentTimeMillis()}.mp4")
            val encoded = encodeMp4(ctx, photo, style, seed, v.first, v.second, seconds, tmp, gate, onProgress, shouldStop)
            if (!encoded.ok) return encoded

            val hasSound = encoded.detail == "aac"
            val soundNote = if (hasSound) " · 含快门声" else ""
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = "Deyva_Live_$stamp"

            if (!livePhoto) {
                val uri = saveVideo(ctx, tmp, "Deyva_Live_$stamp.mp4")
                    ?: return Result(false, "写入相册失败（存储空间或权限）")
                Diagnostics.log("live", "视频已保存 ${v.first}x${v.second} ${seconds}s ${uri}")
                val capped = stillEdge > videoEdge
                return Result(true, "视频已保存到相册",
                    uri.toString(),
                    "${v.first}×${v.second} · ${"%.1f".format(seconds)} 秒$soundNote" +
                        if (capped) "（视频按 ${videoEdge} px 长边编码，保证速度与体积）" else "")
            }

            // ---- 动态照片：先出静帧，再接视频 ----
            val s = fitEven(photo.width, photo.height, stillEdge)
            val still = Bitmap.createBitmap(s.first, s.second, Bitmap.Config.ARGB_8888)
            val cv = Canvas(still)
            // 封面就是成片本身：和视频最后一帧、和导出那张照片是同一张。
            // 相册缩略图、点开、播完，看到的都是它 —— 三处必须是同一个画面。
            LiveFx.drawStill(cv, s.first, s.second, photo)
            val bos = ByteArrayOutputStream()
            still.compress(Bitmap.CompressFormat.JPEG, 94, bos)
            still.recycle()

            val mp4 = tmp.readBytes()
            val jpeg = bos.toByteArray()
            // 布局是统一的（见 [attachMotionPhoto]）：XMP 在前、116 字节 Exif 在后，无 MPF。
            // 主图时刻真机写 0，靠"文件末尾 − 视频长度"定位，所以不再需要 STILL_AT。
            val out = attachMotionPhoto(jpeg, mp4, s.first, s.second)
            // 写完立刻把自己写的字节读回来核一遍：相册认不认最终取决于文件里到底有什么，
            // 而这一行是唯一能随日志回传、又不必让用户发原件的证据。
            // 视频起点是**拼接后**的 JPEG 部分长度（jpeg.size + xmp + exif），拿原始
            // jpeg.size 去算会从 JPEG 正文里开始解析 box 链。
            val check = audit(out, out.size - mp4.size, mp4.size)
            val uri = saveImage(ctx, out, "$baseName.jpg")
                ?: return Result(false, "写入相册失败（存储空间或权限）")
            Diagnostics.log(
                "live", "动态照片已保存 ${s.first}x${s.second} jpeg=${out.size} mp4=${mp4.size}" +
                    " layout=unified comment=$TOKEN" +
                    (if (metaLine.isBlank()) "" else " meta=$metaLine") + " $check $uri"
            )
            return Result(true, "动态照片已保存到相册", uri.toString(),
                "${s.first}×${s.second} · ${"%.1f".format(seconds)} 秒$soundNote · ${fmtSize(out.size)}")
        } catch (e: Exception) {
            Diagnostics.log("live", "导出异常 ${e.message}")
            return Result(false, "导出失败：${e.message}")
        } finally {
            runCatching { tmp?.delete() }
        }
    }

    // ------------------------------------------------------------ 编码
    /** 一个 AAC 包：编码后的字节 + 时间戳。音频先整段编好，再按时戳插进视频流。 */
    private class AudioPacket(val data: ByteArray, val ptsUs: Long) {
        val info = MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, 0) }
    }

    /**
     * 复用的小状态机：轨道号、是否已 start、以及 drain 循环。
     *
     * r55 起多了一条音频轨。MediaMuxer 的规矩是**所有轨道都要在 start() 之前 addTrack**，
     * 而视频轨的 format 要等编码器吐出第一帧才知道 —— 所以音频轨在构造时就加进去，
     * 视频轨等它出现再加，然后立刻 start，之后按时间戳把音频包一路追着视频写。
     */
    private class Mux(
        private val muxer: MediaMuxer,
        private val audio: Pair<MediaFormat, List<AudioPacket>>?,
    ) {
        var track = -1
        var started = false
        private var audioTrack = -1
        private var ai = 0

        init {
            val a = audio
            if (a != null) {
                audioTrack = runCatching { muxer.addTrack(a.first) }.getOrDefault(-1)
                Diagnostics.log("live", "音频轨道=$audioTrack 包数=${a.second.size}")
            }
        }

        fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, blocking: Boolean): Boolean {
            val timeout = if (blocking) 10_000L else 0L
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, timeout)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!started) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && started && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                    }
                    else -> return false
                }
            }
        }

        /** 把时间戳已经追上的音频包写进去 —— 声画同步就是靠这个，不是靠最后一次性补。 */
        fun audioUpTo(ptsUs: Long) {
            val a = audio ?: return
            if (!started || audioTrack < 0) return
            val list = a.second
            while (ai < list.size && list[ai].ptsUs <= ptsUs) {
                val p = list[ai]
                runCatching { muxer.writeSampleData(audioTrack, ByteBuffer.wrap(p.data), p.info) }
                ai++
            }
        }

        fun audioRest() = audioUpTo(Long.MAX_VALUE)
    }

    private fun encodeMp4(
        ctx: Context, photo: Bitmap, style: Int, seed: Int, w: Int, h: Int, seconds: Float,
        out: File, gate: RectF?, onProgress: (Float) -> Unit, shouldStop: () -> Boolean,
    ): Result {
        val total = (seconds * FPS).toInt().coerceAtLeast(1)
        // 音轨按场景分：取景器是快门（落在 CUT），观片台是灯箱开灯（落在 LIGHT_ON 前 0.14 s）。
        // r58 之前两个场景共用快门声 —— 观片台里那声"咔"砸在曝光回落中间，既不同步也不对题。
        // 编不出来（个别机型没有 AAC 编码器）就退化成无声视频，不能因为一个声音让整条导出失败。
        val pcm = if (style == LiveFx.PRINT) LightboxSound.clip(ctx, seconds)
        else ShutterSound.clip(ctx, seconds)
        val audio = runCatching { encodeAac(pcm, Pcm.SR) }.getOrNull()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var mux: Mux? = null
        try {
            // 颜色格式要按机型退让：多数硬件编码器吃 YUV420Flexible，个别只认
            // SemiPlanar / Planar。configure() 不接受会抛异常，所以逐个试一遍，
            // 而不是一上来就拿 Flexible 去赌（赌输就是"这个功能在我手机上不能用"）。
            val candidates = intArrayOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            )
            var started = false
            for (cf in candidates) {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
                format.setInteger(MediaFormat.KEY_BIT_RATE,
                    (w.toLong() * h * FPS * 0.12).toInt().coerceIn(2_000_000, 16_000_000))
                format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    c.start()
                    codec = c
                    Diagnostics.log("live", "编码器颜色格式 $cf 可用 ${w}x$h")
                    started = true
                    break
                } catch (e: Exception) {
                    Diagnostics.log("live", "编码器不接受颜色格式 $cf：${e.message}")
                    runCatching { c.release() }
                }
            }
            if (!started) return Result(false, "本机 H.264 编码器不接受 YUV 输入")
            val enc = codec!!
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = Mux(muxer, audio)

            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val argb = IntArray(w * h)
            val info = MediaCodec.BufferInfo()
            val frameUs = 1_000_000L / FPS
            var capHint = w * h * 3 / 2

            for (i in 0 until total) {
                if (shouldStop()) return Result(false, "已取消")
                val t = i.toFloat() / FPS
                LiveFx.draw(canvas, w, h, style, t, photo, seed, gate)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                val pts = i * frameUs

                var fed = false
                var guard = 0
                while (!fed && guard < 600) {
                    guard++
                    mux.drain(enc, info, false)
                    val idx = enc.dequeueInputBuffer(10_000)
                    if (idx < 0) continue
                    val img = enc.getInputImage(idx)
                    if (img != null) {
                        fillImage(img, argb, w, h)
                        img.close()
                    } else {
                        val buf = enc.getInputBuffer(idx) ?: continue
                        capHint = buf.capacity()
                        fillSemiPlanar(buf, argb, w, h)
                    }
                    enc.queueInputBuffer(idx, 0, minOf(capHint, w * h * 3 / 2), pts, 0)
                    fed = true
                }
                if (!fed) return Result(false, "编码器一直不给输入缓冲，已中止")
                mux.drain(enc, info, false)
                mux.audioUpTo(pts)
                onProgress((i + 1).toFloat() / total)
            }

            // 收尾：送 EOS，把剩下的帧全部取出来
            var guard = 0
            while (guard < 300) {
                guard++
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    enc.queueInputBuffer(idx, 0, 0, total * frameUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                mux.drain(enc, info, false)
            }
            var eos = false
            var g2 = 0
            while (!eos && g2 < 4000) {
                g2++
                eos = mux.drain(enc, info, true)
            }
            bmp.recycle()
            if (!mux.started || mux.track < 0) return Result(false, "视频轨道没有建立（编码器无输出）")
            mux.audioRest()
            return Result(true, "ok", "", if (audio != null) "aac" else "")
        } catch (e: Exception) {
            Diagnostics.log("live", "编码异常 ${e.message}")
            return Result(false, "视频编码失败：${e.message}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (mux?.started == true) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /**
     * 把 PCM 编成 AAC。
     *
     * 整段一次编完再返回：4.2 秒的音频还不到 1 MB，没必要边写边编，
     * 先编完的好处是 muxer 建起来时就拿到了完整的 format 与全部时间戳。
     */
    private fun encodeAac(pcm: ShortArray, sr: Int): Pair<MediaFormat, List<AudioPacket>>? {
        var codec: MediaCodec? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c

            val packets = ArrayList<AudioPacket>(256)
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            val chunk = 2048
            var pos = 0
            var eosIn = false

            while (true) {
                if (!eosIn) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)
                        if (buf == null) {
                            c.queueInputBuffer(idx, 0, 0, pos * 1_000_000L / sr,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosIn = true
                        } else {
                            buf.clear()
                            val n = minOf(chunk, pcm.size - pos)
                            if (n <= 0) {
                                c.queueInputBuffer(idx, 0, 0, pos * 1_000_000L / sr,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosIn = true
                            } else {
                                buf.asShortBuffer().put(pcm, pos, n)
                                buf.position(0)
                                buf.limit(n * 2)
                                c.queueInputBuffer(idx, 0, n * 2, pos * 1_000_000L / sr, 0)
                                pos += n
                            }
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (eosIn) break
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = c.outputFormat
                    oi >= 0 -> {
                        val ob = c.getOutputBuffer(oi)
                        if (ob != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            ob.get(bytes)
                            packets.add(AudioPacket(bytes, info.presentationTimeUs))
                        }
                        c.releaseOutputBuffer(oi, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
            val f = outFormat ?: return null
            Diagnostics.log("live", "音频 AAC 编码完成：${packets.size} 包 / ${sr} Hz")
            return f to packets
        } catch (e: Exception) {
            Diagnostics.log("live", "音频编码不可用，改为无声视频：${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    // ------------------------------------------------------------ YUV
    private fun fillImage(img: Image, argb: IntArray, w: Int, h: Int) {
        val planes = img.planes
        val yP = planes[0]
        val yBuf = yP.buffer
        val yRow = yP.rowStride
        val yPix = yP.pixelStride

        val cw = w / 2; val ch = h / 2
        if (planes.size >= 3) {
            val uP = planes[1]; val vP = planes[2]
            for (y in 0 until h) {
                var src = y * w
                var dst = y * yRow
                for (x in 0 until w) {
                    yBuf.put(dst, luma(argb[src])); src++; dst += yPix
                }
            }
            for (y in 0 until ch) {
                var d = y * uP.rowStride
                var d2 = y * vP.rowStride
                for (x in 0 until cw) {
                    val (u, v) = chroma(argb, w, x * 2, y * 2)
                    uP.buffer.put(d, u); vP.buffer.put(d2, v)
                    d += uP.pixelStride; d2 += vP.pixelStride
                }
            }
        } else if (planes.size == 2) {
            // 半平面（NV12）：Y + 交错 UV
            val uvP = planes[1]
            for (y in 0 until h) {
                var src = y * w
                var dst = y * yRow
                for (x in 0 until w) {
                    yBuf.put(dst, luma(argb[src])); src++; dst += yPix
                }
            }
            for (y in 0 until ch) {
                var d = y * uvP.rowStride
                for (x in 0 until cw) {
                    val (u, v) = chroma(argb, w, x * 2, y * 2)
                    uvP.buffer.put(d, u); uvP.buffer.put(d + uvP.pixelStride, v)
                    d += uvP.pixelStride * 2
                }
            }
        }
    }

    private fun fillSemiPlanar(buf: ByteBuffer, argb: IntArray, w: Int, h: Int) {
        buf.clear()
        val n = w * h
        for (i in 0 until n) buf.put(luma(argb[i]))
        val cw = w / 2
        for (y in 0 until h / 2) {
            for (x in 0 until cw) {
                val (u, v) = chroma(argb, w, x * 2, y * 2)
                buf.put(u); buf.put(v)
            }
        }
        buf.position(0)
    }

    private fun luma(c: Int): Byte {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
    }

    private fun chroma(argb: IntArray, w: Int, x: Int, y: Int): Pair<Byte, Byte> {
        var r = 0; var g = 0; var b = 0
        for (dy in 0..1) for (dx in 0..1) {
            val c = argb[(y + dy) * w + (x + dx)]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        r /= 4; g /= 4; b /= 4
        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
        return (u.coerceIn(0, 255).toByte()) to (v.coerceIn(0, 255).toByte())
    }

    // ------------------------------------------------------------ 动态照片封装
    /**
     * 在 JPEG 头部插两段 APP1，再把 MP4 接在文件末尾。
     *
     * **只有一套布局，不再按品牌分流。** 依据是两个真正在 ColorOS 上跑通过、并且把
     * 结论写清楚了的实现：
     *
     *  · `flashlab/motion-live-photo`：issue #1 里贴出成功结构，作者在 V2EX 同帖复述；
     *  · `CYQawa/YunX` 的 `MotionPhotoWriter`：2026-10-22 对 realme / ColorOS 相机产物
     *    的逆向，明确写了"小米相册认 XMP，OPPO/realme 只认 Exif 里的暗号"。
     *
     * 两者的字节结构完全一致：
     *
     * ```
     * SOI → APP0(JFIF) → APP1 XMP → APP1 Exif(116 B) → 图像数据 → EOI → MP4
     * ```
     *
     * 三处与 r66 及之前不同的地方，都是照着实测结论纠的：
     *
     *  1. **XMP 在前、Exif 在后。** 原先反着放，注释里还猜"XMP 在前会被当 EXIF 读坏"——
     *     实测方案恰恰是 XMP 在前。OPPO 相册按段序取第一个 APP1 当 XMP 解析。
     *  2. **不写 MPF。** 两个能跑通的产物里都没有 MPF 段。真机样本里有，但那是相机
     *     自己写的多图索引，不是相册的判据；多写一段只多一个被解析错的机会。
     *  3. **Exif 用固定的 116 字节模板**：`UserComment` 类型是 ASCII、值是 14 字节的
     *     小写 `oplus_8388608\0`。原先自建的是 UNDEFINED 类型 + 8 字节字符集前缀 +
     *     大写 O —— 三样都和实测模板不一样。
     *
     * XMP 只写新式字段（`GCamera:MotionPhoto*` + `Container:Directory`），不写
     * `MicroVideo*`：真机产物没有；而小米从 HyperOS 3 起也已改为只要 XMP 规范就认。
     * 时间戳一律写 0、Primary 的 `Item:Length` 也写 0 —— 读取方按"文件末尾 − 视频长度"
     * 回推视频起点，静图长度和中间填充都不参与定位，所以不必回填长度、也不会陷入
     * "改一个字符长度又变"的回填死循环。
     *
     * @param width 静图宽高，回填进 Exif 模板的 ImageWidth / ImageLength
     */
    private fun attachMotionPhoto(
        jpeg: ByteArray, mp4: ByteArray, width: Int, height: Int,
    ): ByteArray {
        val ins = jfifEndOffset(jpeg)
        val body = splice(jpeg, ins, xmpSegment(mp4.size), oppoExif(width, height))
        val out = ByteArray(body.size + mp4.size)
        System.arraycopy(body, 0, out, 0, body.size)
        System.arraycopy(mp4, 0, out, body.size, mp4.size)
        return out
    }

    /**
     * 导出后把自己写出来的字节**再读一遍**，逐项核对相册认出动态照片所需的证据。
     *
     * 封装出错时相册的表现永远只是"显示静图"，没有任何反馈，排查全靠猜。这一层检查的是
     * "文件里到底有什么"，失败项直接写进运行日志，一次导出就能自证，不必让用户发原件。
     *
     * 核六项：
     *  1. 段序是 `XMP → Exif`（顺序反了相册就取不到）；
     *  2. Exif 段恰好 116 字节（模板被改动会立刻暴露）；
     *  3. Exif 里有暗号；
     *  4. XMP 有 `GCamera:MotionPhoto="1"`、Primary / MotionPhoto 两项、`VideoLength`；
     *  5. `JPEG 长度 + 视频长度 = 文件总长`；
     *  6. 没有多余的 MPF 段、没有 `MicroVideo*` 旧字段。
     *
     * 另外把尾部 MP4 的 box 链记下来（`moov` 在 `mdat` 之后是 MediaMuxer 的默认行为，
     * 只记录不判错，但位置本身有诊断价值）。
     */
    private fun audit(j: ByteArray, jpegLen: Int, videoLen: Int): String {
        val bad = ArrayList<String>(3)
        val segs = ArrayList<String>(3)
        var xmp: String? = null
        var exifSize = -1
        var tokenOk = false

        if (j.size < 4 || j[0] != 0xFF.toByte() || j[1] != 0xD8.toByte()) return "audit=FAIL(不是 JPEG)"
        var i = 2
        while (i + 3 < j.size) {
            if (j[i] != 0xFF.toByte()) break
            val m = j[i + 1].toInt() and 0xFF
            if (m !in 0xE0..0xEF) break
            val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > j.size) { bad.add("段长度越界"); break }
            val at = i + 4
            val bl = len - 2
            when {
                m == 0xE0 -> segs.add("JFIF")
                m == 0xE1 && bl > 6 && String(j, at, 4, Charsets.US_ASCII) == "Exif" -> {
                    segs.add("Exif")
                    exifSize = 2 + len                 // 段的整段字节数，含标记与长度字段
                    tokenOk = indexOf(j, at, bl, TOKEN.toByteArray(Charsets.US_ASCII)) >= 0
                }
                m == 0xE1 -> {
                    segs.add("XMP")
                    xmp = String(j, at, bl, Charsets.ISO_8859_1)
                }
                m == 0xE2 && bl > 8 && String(j, at, 3, Charsets.US_ASCII) == "MPF" -> segs.add("MPF")
                else -> segs.add("APP${m - 0xE0}")
            }
            i += 2 + len
        }

        // 只比相对顺序：原图若自带别的 APPn，全等比较会误报。
        val ix = segs.indexOf("XMP")
        val ie = segs.indexOf("Exif")
        if (ix < 0 || ie < 0 || ix > ie) bad.add("段序=${segs.joinToString(">")}")
        if (exifSize != 116) bad.add("Exif段=${exifSize}B")
        if (!tokenOk) bad.add("无暗号")
        if (segs.contains("MPF")) bad.add("多余MPF")
        if (xmp == null) bad.add("无XMP") else {
            if (!xmp.contains("GCamera:MotionPhoto=\"1\"")) bad.add("无MotionPhoto")
            if (!xmp.contains("Item:Semantic=\"Primary\"")) bad.add("无Primary项")
            if (!xmp.contains("Item:Semantic=\"MotionPhoto\"")) bad.add("无MotionPhoto项")
            if (!xmp.contains("OpCamera:VideoLength=\"$videoLen\"")) bad.add("VideoLength≠$videoLen")
            if (xmp.contains("MicroVideo")) bad.add("含MicroVideo旧字段")
        }
        if (jpegLen + videoLen != j.size) bad.add("长度不自洽")

        return buildString {
            append("audit=").append(if (bad.isEmpty()) "ok" else "FAIL(" + bad.joinToString(";") + ")")
            append(" segs=").append(segs.joinToString(","))
            append(" mp4=").append(mp4Boxes(j, jpegLen))
        }
    }

    /**
     * 走到文件尾部那段 MP4 里，把顶层 box 名按顺序列出来。
     *
     * 关心的只有一件事：`moov` 在 `mdat` 前面还是后面。MediaMuxer 默认写在尾部，
     * 想要 faststart 得整段重排 —— 这里先只做记录。
     */
    private fun mp4Boxes(j: ByteArray, start: Int): String {
        if (start + 8 > j.size) return "(空)"
        val out = ArrayList<String>(4)
        var p = start
        while (p + 8 <= j.size && out.size < 8) {
            val t = String(j, p + 4, 4, Charsets.US_ASCII)
            var size = u32(j, p)
            var head = 8
            if (size == 1L) {
                if (p + 16 > j.size) { out.add("$t?"); break }
                size = (u32(j, p + 8) shl 32) or u32(j, p + 12)
                head = 16
            } else if (size == 0L) {
                size = (j.size - p).toLong()
            }
            if (size < head || p + size > j.size) { out.add("$t(截断)"); break }
            out.add(t)
            p += size.toInt()
        }
        return out.joinToString(">")
    }

    private fun indexOf(hay: ByteArray, from: Int, len: Int, needle: ByteArray): Int {
        val last = from + len - needle.size
        var i = from
        while (i <= last) {
            var k = 0
            while (k < needle.size && hay[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }

    private fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /**
     * 插入点：有 APP0(JFIF) 就插在它**之后**，否则紧跟 SOI。
     *
     * APP0 段总长 = 标记 2 字节 + 长度字段的值（该值**含长度字段自身**），而长度字段从
     * 偏移 4 开始，所以下一段起点 = `4 + 长度值`。写成 `2 + 长度值` 会少 2 字节，把两段
     * APP1 塞进 JFIF 段内部、被它的声明长度"吞掉"——表现正是"字节结构看着对、相册却
     * 当静图"。
     */
    private fun jfifEndOffset(j: ByteArray): Int {
        if (j.size < 6) return 2
        if (j[0] != 0xFF.toByte() || j[1] != 0xD8.toByte()) return 2
        if (j[2] != 0xFF.toByte() || j[3] != 0xE0.toByte()) return 2
        val len = ((j[4].toInt() and 0xFF) shl 8) or (j[5].toInt() and 0xFF)
        return (4 + len).coerceIn(2, j.size)
    }

    private fun splice(j: ByteArray, at: Int, vararg segs: ByteArray): ByteArray {
        var n = j.size
        for (s in segs) n += s.size
        val out = ByteArray(n)
        System.arraycopy(j, 0, out, 0, at)
        var p = at
        for (s in segs) {
            System.arraycopy(s, 0, out, p, s.size)
            p += s.size
        }
        System.arraycopy(j, at, out, p, j.size - at)
        return out
    }

    /**
     * OPPO 相册的"暗号"：116 字节的 Exif，`UserComment`（0x9286）里写着 `oplus_8388608`。
     *
     * 模板来自 flashlab/motion-live-photo 的 oppo Exif（V2EX 那位作者就是靠它解决
     * "相册只显示静图"），YunX 的 `MotionPhotoWriter` 又原样用了同一份字节。结构：
     *
     * ```
     * FFE1 0072 "Exif\0\0" MM 002A 00000008
     *   IFD0 4 条：ImageWidth / ImageLength / ExifIFDPointer / Orientation
     *   Exif 子 IFD 2 条：UserComment(ASCII, 14B @92) / 0x9208
     *   "oplus_8388608\0"
     * ```
     *
     * 只有宽高要按实际静图回填（模板里是相机的样例尺寸）。**小写、ASCII 类型、
     * 不带字符集前缀** —— 三者都别改，实测可行的就是这一份字节。
     */
    private fun oppoExif(width: Int, height: Int): ByteArray {
        val b = ByteArray(EXIF_TEMPLATE.length / 2) { i ->
            EXIF_TEMPLATE.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        if (width > 0 && height > 0) {
            // IFD0 第 1、2 条的 value 字段，大端 32 位：段内偏移 28..31 与 40..43。
            b[28] = (width ushr 24).toByte(); b[29] = (width ushr 16).toByte()
            b[30] = (width ushr 8).toByte(); b[31] = width.toByte()
            b[40] = (height ushr 24).toByte(); b[41] = (height ushr 16).toByte()
            b[42] = (height ushr 8).toByte(); b[43] = height.toByte()
        }
        return b
    }

    private fun jpegSegment(marker: Int, body: ByteArray): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = 0xFF.toByte(); out[1] = marker.toByte()
        put16(out, 2, body.size + 2)
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v shr 8) and 0xFF).toByte()
        b[at + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Int) {
        b[at] = ((v shr 24) and 0xFF).toByte()
        b[at + 1] = ((v shr 16) and 0xFF).toByte()
        b[at + 2] = ((v shr 8) and 0xFF).toByte()
        b[at + 3] = (v and 0xFF).toByte()
    }

    /**
     * XMP 包：字段集与真机产物一致（真机多一项 `Semantic=GainMap`（Ultra HDR），
     * 我们没有增益图，不写）。
     *
     * 视频那一条**不写 `Item:Padding`** —— 真机的 MotionPhoto 项也没有这个属性，
     * 它的长度是按隐式填充算的；我们按"末尾 − 长度"定位，写不写都不影响，就照真机。
     */
    private fun xmpSegment(videoLen: Int): ByteArray {
        val x = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
   GCamera:MotionPhoto="1"
   GCamera:MotionPhotoVersion="1"
   GCamera:MotionPhotoPresentationTimestampUs="0"
   OpCamera:MotionPhotoPrimaryPresentationTimestampUs="0"
   OpCamera:MotionPhotoOwner="oplus"
   OpCamera:OLivePhotoVersion="2"
   OpCamera:VideoLength="$videoLen">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLen"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""
        val xb = x.toByteArray(Charsets.UTF_8)
        val id = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val body = ByteArray(id.size + xb.size)
        System.arraycopy(id, 0, body, 0, id.size)
        System.arraycopy(xb, 0, body, id.size, xb.size)
        return jpegSegment(0xE1, body)
    }

    // ------------------------------------------------------------ 落相册
    private fun saveVideo(ctx: Context, file: File, name: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Deyva")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val f = File(dir, "Deyva/$name"); f.parentFile?.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, f.absolutePath)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os, 128 * 1024) } }
            ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }.getOrNull()

    /**
     * 落盘位置：`DCIM/Camera`，而不是自己的 `Pictures/Deyva`。
     *
     * 这不是风格问题。各家相册的"动态照片"索引挂在**相机胶卷桶**上：ColorOS 的
     * MediaProvider 只对 `DCIM/Camera` 下新增的 JPEG 跑一次动态照片解析，写入
     * `Pictures/<App>` 的文件它只当普通图片建缩略图——元数据再全也不会去看。
     * 逆向 OPPO 实拍样本的那份实现也是明确要求放进相机目录，才拿到"确实能动"的结论。
     *
     * 文件名带 `Deyva_Live_` 前缀，不会和相机自己拍的重名。
     */
    private fun saveImage(ctx: Context, bytes: ByteArray, name: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                val f = File(dir, "Camera/$name"); f.parentFile?.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, f.absolutePath)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }.getOrNull()

    private fun fitEven(w: Int, h: Int, edge: Int): Pair<Int, Int> {
        val le = maxOf(w, h)
        val s = if (le <= edge) 1f else edge.toFloat() / le
        var ow = (w * s).toInt(); var oh = (h * s).toInt()
        ow = (ow / 2) * 2; oh = (oh / 2) * 2
        return ow.coerceAtLeast(16) to oh.coerceAtLeast(16)
    }

    private fun fmtSize(b: Int): String = if (b > 1024 * 1024)
        "%.1f MB".format(b / 1024f / 1024f) else "${b / 1024} KB"
}
