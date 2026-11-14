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
 *  · **Live Photo**：一张 JPEG，尾部直接接上这段 MP4。各家相册认的元数据不一样：
 *    Google 系看 `GCamera:MicroVideoOffset` 与 `Container:Directory`，小米同此；
 *    OPPO / 一加 / realme（ColorOS）还要 EXIF `UserComment` 里的 `Oplus_8388608`
 *    暗号、`OpCamera` 私有字段，以及一个 MPF 段 —— 缺任何一样都只显示静图。
 *    r63 起全部补齐（r64 把暗号首字母改回大写），见 [attachMotionPhoto]。
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
     * ColorOS（OPPO / 一加 / realme）识别动态照片的暗号：EXIF `UserComment` 里的特征位。
     *
     * 8388608 = 0x800000，是 OPPO 相机写入 "MOTION_PHOTO" 那一位。相册只要在
     * UserComment 里读到它就把文件当动态照片，读不到就只显示一张静图 —— 与视频
     * 内容、与 XMP 是否正确都无关。
     *
     * r64：**首字母大写 `Oplus_`**。r63 写的是小写，社区里两种大小写都有人贴、
     * 都说"能用"，互相矛盾；唯一有真机出处的是 oppo-live-photo-maker 里那份
     * 按 Find X7 Ultra（ColorOS 14）实拍样本逆向出来的结构，写的是大写 O，
     * 这里跟真机对齐 —— 万一固件是逐字节比较，小写就是不认的那一种。
     */
    private const val TOKEN = "Oplus_8388608"

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
            // 主图时刻取"成片已稳定显示"的那一段中间，而不是视频末尾 —— 播到 5.6 s
            // 正好是最后一帧的边界，有的解码器会取到空帧，长按播放先黑一下。
            val tsUs = minOf(
                (LiveFx.STILL_AT * 1_000_000f).toInt(),
                (seconds * 1_000_000f).toInt() - 100_000,
            ).coerceAtLeast(0)
            val out = attachMotionPhoto(jpeg, mp4, tsUs, metaLine)
            val uri = saveImage(ctx, out, "$baseName.jpg")
                ?: return Result(false, "写入相册失败（存储空间或权限）")
            Diagnostics.log("live", "动态照片已保存 ${s.first}x${s.second} jpeg=${out.size} mp4=${mp4.size} $uri")
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
     * 在 JPEG 末尾接上 MP4，并把相册认出"这是动态照片"所需的元数据补齐。
     *
     * 三家认的东西不一样，缺一样就只是静图：
     *
     *  · **Google / 三星 / 小米**：XMP 里的 `GCamera:MicroVideoOffset` 与
     *    `Container:Directory`（比例未知，两种都写才能全覆盖）。
     *  · **OPPO / 一加 / realme（ColorOS）**：只写 XMP 一律当静图。它还要
     *    ① EXIF `UserComment` 里的暗号 `Oplus_8388608`（8 位特征位，0x800000）；
     *    ② `OpCamera` 私有命名空间的几个字段（`MotionPhotoOwner=oplus`、
     *       `OLivePhotoVersion=2`、`MotionPhotoFeatureFlag=1`、`VideoLength`）；
     *    ③ 一个 MPF（Multi-Picture Format）APP2 段，声明"本图是 Baseline MP
     *       Primary，共 1 张"。这三样是 OPPO 相册的判据，与视频本身无关。
     *
     * 段的顺序也照真机产物排：`APP0(JFIF) → APP1(Exif) → APP1(XMP) → APP2(MPF)`，
     * 全部插在 SOI 之后、DQT 之前。XMP 放到 EXIF 前面时，有的解析器把第一个
     * APP1 当 EXIF 读、读不出就判定"没有元数据"。
     *
     * 长度自洽：XMP 里 Primary 的长度包含 XMP 自身，MPF 里的单图长度又等于整段
     * JPEG 长度。所以两者都用固定 10 位宽度占位，先算一遍再回填 —— 否则会陷入
     * "改一个字符、长度又变"的死循环。
     */
    private fun attachMotionPhoto(
        jpeg: ByteArray, mp4: ByteArray, durationUs: Int, description: String,
    ): ByteArray {
        val ins = appInsertionPoint(jpeg)
        val exif = exifSegment(description)
        // MPF 段的长度与内容无关（只有一个定长条目），先取一个占位版本量尺寸。
        val mpfLen = mpfSegment(0).size

        var primary = jpeg.size + exif.size + mpfLen + xmpSegment(0, mp4.size, durationUs).size
        var xmp = xmpSegment(primary, mp4.size, durationUs)
        var mpf = mpfSegment(primary)
        var body = splice(jpeg, ins, exif, xmp, mpf)
        if (body.size != primary) {
            // 宽度固定时不会发生；真发生了就按实际长度再对齐一次。
            primary = body.size
            xmp = xmpSegment(primary, mp4.size, durationUs)
            mpf = mpfSegment(primary)
            body = splice(jpeg, ins, exif, xmp, mpf)
        }

        val out = ByteArray(body.size + mp4.size)
        System.arraycopy(body, 0, out, 0, body.size)
        System.arraycopy(mp4, 0, out, body.size, mp4.size)
        return out
    }

    /** 插在最后一个 APPn 段之后（也就是所有 EXIF / XMP / 其它 APPn 的后面）。 */
    private fun appInsertionPoint(j: ByteArray): Int {
        if (j.size < 4 || j[0] != 0xFF.toByte() || j[1] != 0xD8.toByte()) return 2
        var i = 2
        var last = 2
        while (i + 3 < j.size) {
            if (j[i] != 0xFF.toByte()) break
            val m = j[i + 1].toInt() and 0xFF
            if (m !in 0xE0..0xEF) break          // 不是 APPn：DQT / SOF / SOS，停
            val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > j.size) break
            i += 2 + len
            last = i
        }
        return last
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
     * 最小可用的 EXIF 段：IFD0 只放 ImageDescription 与指向 Exif 子 IFD 的指针，
     * 子 IFD 里只放 UserComment。
     *
     * 不放相机厂商、镜头、GPS —— OPPO 的判据只看 `UserComment` 里那个特征位，
     * 而"复制整段真机 EXIF"恰恰更容易因为子 IFD 的偏移对不上而写坏（社区里踩过）。
     * 暗号见 [TOKEN]，与真机逆向样本逐字节一致（含首字母大写）。
     */
    private fun exifSegment(description: String): ByteArray {
        val desc = (description.ifBlank { "Deyva live photo" }).toByteArray(Charsets.UTF_8)
        val pad = desc.size and 1                       // TIFF 里数据按偶数字节对齐
        val cmt = ByteArray(8 + TOKEN.length)
        // UserComment 以 8 字节字符集标识开头：ASCII + 三个 0。
        System.arraycopy(
            byteArrayOf(0x41, 0x53, 0x43, 0x49, 0x49, 0, 0, 0), 0, cmt, 0, 8
        )
        System.arraycopy(TOKEN.toByteArray(Charsets.US_ASCII), 0, cmt, 8, TOKEN.length)

        val ifd0 = 8
        val exifIfd = ifd0 + 2 + 2 * 12 + 4              // 38
        val data = exifIfd + 2 + 12 + 4                  // 56
        val cmtOff = data + desc.size + pad
        val tiff = ByteArray(cmtOff + cmt.size)

        tiff[0] = 'M'.code.toByte(); tiff[1] = 'M'.code.toByte()   // 大端
        put16(tiff, 2, 42); put32(tiff, 4, 8)

        put16(tiff, ifd0, 2)                                       // IFD0：两个条目
        put16(tiff, ifd0 + 2, 0x010E); put16(tiff, ifd0 + 4, 2)     // ImageDescription / ASCII
        put32(tiff, ifd0 + 6, desc.size); put32(tiff, ifd0 + 10, data)
        put16(tiff, ifd0 + 14, 0x8769); put16(tiff, ifd0 + 16, 4)   // ExifIFDPointer / LONG
        put32(tiff, ifd0 + 18, 1); put32(tiff, ifd0 + 22, exifIfd)
        put32(tiff, ifd0 + 26, 0)                                  // 没有下一个 IFD

        put16(tiff, exifIfd, 1)
        put16(tiff, exifIfd + 2, 0x9286); put16(tiff, exifIfd + 4, 7)  // UserComment / UNDEFINED
        put32(tiff, exifIfd + 6, cmt.size); put32(tiff, exifIfd + 10, cmtOff)
        put32(tiff, exifIfd + 14, 0)

        System.arraycopy(desc, 0, tiff, data, desc.size)
        System.arraycopy(cmt, 0, tiff, cmtOff, cmt.size)

        val body = ByteArray(6 + tiff.size)
        System.arraycopy("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII), 0, body, 0, 6)
        System.arraycopy(tiff, 0, body, 6, tiff.size)
        return jpegSegment(0xE1, body)
    }

    /**
     * MPF（Multi-Picture Format）APP2 段：声明本 JPEG 只有一张 Baseline MP Primary 图。
     *
     * `imageSize` 是**不含尾部 MP4** 的整段 JPEG 长度。OPPO 相册拿这个段确认
     * "这是一张多图文件的主图"，缺了它即使 XMP 全对也只显示静图。
     */
    private fun mpfSegment(imageSize: Int): ByteArray {
        val tiff = ByteArray(8 + 2 + 3 * 12 + 4 + 16)
        tiff[0] = 'M'.code.toByte(); tiff[1] = 'M'.code.toByte()
        put16(tiff, 2, 42); put32(tiff, 4, 8)

        val ifd = 8
        put16(tiff, ifd, 3)
        put16(tiff, ifd + 2, 0xB000); put16(tiff, ifd + 4, 7)       // MPFVersion / UNDEFINED
        put32(tiff, ifd + 6, 4)                                     // "0100"
        tiff[ifd + 10] = '0'.code.toByte(); tiff[ifd + 11] = '1'.code.toByte()
        tiff[ifd + 12] = '0'.code.toByte(); tiff[ifd + 13] = '0'.code.toByte()
        put16(tiff, ifd + 14, 0xB001); put16(tiff, ifd + 16, 4)      // NumberOfImages / LONG
        put32(tiff, ifd + 18, 1); put32(tiff, ifd + 22, 1)
        put16(tiff, ifd + 26, 0xB002); put16(tiff, ifd + 28, 7)      // MPEntry / UNDEFINED
        put32(tiff, ifd + 30, 16); put32(tiff, ifd + 34, 50)         // 条目数据紧接 IFD
        put32(tiff, ifd + 38, 0)                                     // 没有下一个 IFD

        val e = 50
        put32(tiff, e, 0x00030000)          // Baseline MP Primary Image / JPEG
        put32(tiff, e + 4, imageSize)       // 单图长度
        put32(tiff, e + 8, 0)               // 与主图的偏移（自己就是主图）
        put16(tiff, e + 12, 0); put16(tiff, e + 14, 0)

        val body = ByteArray(4 + tiff.size)
        System.arraycopy("MPF\u0000".toByteArray(Charsets.US_ASCII), 0, body, 0, 4)
        System.arraycopy(tiff, 0, body, 4, tiff.size)
        return jpegSegment(0xE2, body)
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

    private fun xmpSegment(primaryLen: Int, videoLen: Int, durationUs: Int): ByteArray {
        val p = primaryLen.toString().padStart(10, '0')
        val ts = durationUs.coerceAtLeast(0)
        val x = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:OpCamera="http://ns.oplus.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="$videoLen"
    GCamera:MicroVideoPresentationTimestampUs="$ts"
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="$ts"
    OpCamera:MotionPhotoOwner="oplus"
    OpCamera:OLivePhotoVersion="2"
    OpCamera:MotionPhotoFeatureFlag="1"
    OpCamera:MotionPhotoPrimaryPresentationTimestampUs="$ts"
    OpCamera:VideoLength="$videoLen">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="$p" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLen" Item:Padding="0"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""
        val body = ByteArray(29 + x.toByteArray(Charsets.UTF_8).size)
        System.arraycopy(
            "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII), 0, body, 0, 29
        )
        System.arraycopy(x.toByteArray(Charsets.UTF_8), 0, body, 29, x.toByteArray(Charsets.UTF_8).size)
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

    private fun saveImage(ctx: Context, bytes: ByteArray, name: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Deyva")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val f = File(dir, "Deyva/$name"); f.parentFile?.mkdirs()
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
