package com.spektra.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * 采样素材的读取与预览播放（r59）。
 *
 * 以前这段逻辑写在 [ShutterSound] 里。观片台要一条**完全不同的**声音时，
 * 复制一遍就成了两份要同步维护的 AudioTrack 代码 —— 抽出来只留一份。
 *
 * 读取只认 PCM 16 bit 单声道 WAV，并且**按 chunk 找 data**，不假定 44 字节头：
 * Freesound 的导出器、Audacity 的导出器、ffmpeg 的导出器，头长度各不相同。
 */
internal object Pcm {

    const val SR = 44100

    /** 读 assets 里的 WAV。找不到 / 读坏了返回 null，由调用方决定兜底。 */
    fun load(ctx: Context, asset: String): ShortArray? = runCatching {
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        var i = 12
        var off = -1
        var len = 0
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val sz = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8) or
                ((bytes[i + 6].toInt() and 0xFF) shl 16) or ((bytes[i + 7].toInt() and 0xFF) shl 24)
            if (id == "data") { off = i + 8; len = minOf(sz, bytes.size - off); break }
            i += 8 + sz + (sz and 1)
        }
        require(off >= 0 && len > 1000) { "no data chunk in $asset" }
        val out = ShortArray(len / 2)
        for (k in out.indices) {
            val lo = bytes[off + k * 2].toInt() and 0xFF
            val hi = bytes[off + k * 2 + 1].toInt()
            out[k] = ((hi shl 8) or lo).toShort()
        }
        out
    }.getOrNull()

    /**
     * 把整段 PCM 播出去，播完自己释放。
     *
     * 走 **MODE_STREAM** 而不是 MODE_STATIC：静态模式要求整段一次性塞进 AudioTrack 的
     * 内核缓冲，短采样（快门 0.5 s / 44 KB）随便用，但观片台那条开灯声是 4.55 s / 401 KB，
     * 在部分机型上 setBufferSizeInBytes 会直接失败 —— 然后就是"观片台没声音"，
     * 而且失败是静默的，最难查。流模式只按 250 ms 的缓冲滚动写，多大都能播。
     */
    fun play(ctx: Context, pcm: ShortArray, sr: Int = SR) {
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sr / 4 * 2)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf)
                .build()
        }.getOrNull() ?: return

        runCatching {
            track.play()
            val life = pcm.size * 1000L / sr + 300L
            // 写完是指"喂进缓冲"，不是"放完"。所以释放要按音频长度定时，不能跟写线程走。
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { track.stop() }
                runCatching { track.release() }
            }, life)
            Thread({
                var off = 0
                runCatching {
                    while (off < pcm.size) {
                        val n = track.write(pcm, off, minOf(8192, pcm.size - off))
                        if (n <= 0) break
                        off += n
                    }
                }
            }, "pcm-play").apply { isDaemon = true }.start()
        }.onFailure {
            Diagnostics.log("sound", "播放失败：${it.message}")
            runCatching { track.release() }
        }
    }
}
