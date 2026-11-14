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
import android.media.ExifInterface
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
 *  · **Live Photo**：一张 JPEG，尾部直接接上这段 MP4，并在 XMP 里声明
 *    `GCamera:MicroVideoOffset` 与 Google 的 `Container:Directory` 两种写法 ——
 *    小米 / OPPO / 三星 / Google 相册各自认其中一种，只写一种就会出现
 *    "在我的手机上能动、在你的手机上是静图"。
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
        var stillTmp: File? = null
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
            stillTmp = File(ctx.cacheDir, "live_still_$stamp.jpg").apply { writeBytes(bos.toByteArray()) }
            runCatching {
                ExifInterface(stillTmp.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_SOFTWARE, "Deyva")
                    setAttribute(ExifInterface.TAG_MODEL, "Deyva Live")
                    setAttribute(ExifInterface.TAG_ORIENTATION, "1")
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
                        metaLine.ifBlank { "Deyva live photo" })
                }.saveAttributes()
            }.onFailure { Diagnostics.log("live", "静帧 EXIF 写入失败：${it.message}") }

            val mp4 = tmp.readBytes()
            val jpeg = stillTmp.readBytes()
            val out = attachMotionPhoto(jpeg, mp4, (seconds * 1_000_000).toInt())
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
            runCatching { stillTmp?.delete() }
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
     * 在 JPEG 末尾接上 MP4，并写入两种 XMP 声明。
     *
     * 数字宽度固定成 10 位：XMP 的长度会随"图像长度"这个数字的位数变化，
     * 而图像长度又包含 XMP 本身 —— 不留固定宽度就会陷入"改一个字符、长度又变"的死循环。
     */
    private fun attachMotionPhoto(jpeg: ByteArray, mp4: ByteArray, durationUs: Int): ByteArray {
        val xmp0 = xmp(0, mp4.size, durationUs)
        val probe = insertXmp(jpeg, xmp0)
        var primary = probe.size
        var xmp1 = xmp(primary, mp4.size, durationUs)
        var body = insertXmp(jpeg, xmp1)
        if (body.size != primary) {
            // 理论上不会发生（宽度固定）；真发生了就再对齐一次
            primary = body.size
            xmp1 = xmp(primary, mp4.size, durationUs)
            body = insertXmp(jpeg, xmp1)
        }
        val out = ByteArray(body.size + mp4.size)
        System.arraycopy(body, 0, out, 0, body.size)
        System.arraycopy(mp4, 0, out, body.size, mp4.size)
        return out
    }

    private fun insertXmp(jpeg: ByteArray, xmpBytes: ByteArray): ByteArray {
        if (jpeg.size < 2) return jpeg
        val payload = ByteArray(29 + xmpBytes.size)
        System.arraycopy("http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII), 0, payload, 0, 29)
        System.arraycopy(xmpBytes, 0, payload, 29, xmpBytes.size)
        val total = payload.size + 2
        val out = ByteArray(jpeg.size + 2 + total)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        out[2] = 0xFF.toByte(); out[3] = 0xE1.toByte()
        out[4] = ((total shr 8) and 0xFF).toByte(); out[5] = (total and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 6, payload.size)
        System.arraycopy(jpeg, 2, out, 6 + payload.size, jpeg.size - 2)
        return out
    }

    private fun xmp(primaryLen: Int, videoLen: Int, durationUs: Int): ByteArray {
        val p = primaryLen.toString().padStart(10, '0')
        val v = videoLen.toString().padStart(10, '0')
        val d = durationUs.coerceAtLeast(0).toString().padStart(10, '0')
        val x = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="$v"
    GCamera:MicroVideoPresentationTimestampUs="$d"
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="$d">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="$p" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$v" Item:Padding="0"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>"""
        return x.toByteArray(Charsets.UTF_8)
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
