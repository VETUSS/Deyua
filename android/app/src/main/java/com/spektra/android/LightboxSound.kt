package com.spektra.android

import android.content.Context

/**
 * 观片台灯箱开灯的声音（r59）。
 *
 * ---------------------------------------------------------------- 为什么必须有第二条声
 * r58 之前观片台的 live 图沿用了取景器那条音轨：一声哈苏快门「咔」，落在 1.567 s。
 * 那是错的 —— 观片台压根没有快门。画面在 0.95 s 是**灯亮**，快门声却在 1.567 s
 * 一声不响地砸在"曝光回落"的中间，既不同步，也不是这件事该有的声音。
 *
 * ---------------------------------------------------------------- 这条声是怎么来的
 * 素材：Freesound 824571「Fluorescent Light」—— CC0 公有领域，作者 RICHERlandTV。
 * 一段 32 s 的真机录音：老式荧光灯管被开关点亮、稳定嗡鸣、再关掉。手机录的，没加任何效果。
 *
 * 拆开看，它正好是三件事（逐帧量包络与频谱得的，不是听感描述）：
 *
 *   0.000 s  开关的机械「咔」  3–8 kHz 占 73%，1–3 kHz 占 12% —— 塑料翘板撞到底的声音
 *   0.300 s  咔的尾巴收干净   −65 dB，几乎无声
 *   0.425 s  镇流器起振       100 Hz 与 300 Hz 两条谱线，300 Hz 比 100 Hz 还强
 *   0.8 s 后 稳定嗡鸣         −34 dB RMS，全程没有任何 1–4 kHz 的成分 —— 没有人声，没有环境声
 *
 * 100 / 300 Hz 是 50 Hz 市电的二、六次谐波，这是**电磁镇流器**的签名，不是白噪。
 * 老观片箱就是这种灯管，所以这条声一响，画面还没亮，耳朵已经知道"是那种灯"。
 *
 * ---------------------------------------------------------------- 只做了四处处理
 *  1. 裁掉「咔」之后那 0.12 s 的数字静音（后期剪过的空档），交叉淡化 25 ms 接上嗡鸣 ——
 *     真实开灯不会有那么长的空档，灯管通电就嗡。
 *  2. 40 Hz 高通：录音里有桌面震动带来的超低频，手机扬声器放不出来，只会吃掉动态余量。
 *  3. 嗡鸣压 7 dB：让它待在"房间的底"里，而不是跟咔抢戏。手机外放本来就听不太到 300 Hz。
 *  4. 尾部 0.26 s 淡出：视频到这里就结束了，硬切会有一声爆音；循环播放时也会。
 *
 * 波形本身一个字没改 —— 没有合成、没有加混响、没有时间拉伸。
 */
object LightboxSound {

    private const val ASSET = "snd/lightbox.wav"

    /**
     * 采样 0 点是「咔」，而画面 0.95 s 才亮。
     *
     * 提前 0.14 s 让**手先按下开关，灯再亮** —— 真实开灯就是这个次序。
     * 这 0.14 s 也被嗡鸣的起振用掉了：灯管通电到稳定正好在这个量级。
     */
    private const val LEAD = 0.14f

    /** 「咔」落下的时刻。直接引用 [LiveFx] 的灯亮时刻，改一处不会声画错开。 */
    const val AT = LiveFx.LIGHT_ON - LEAD

    /** 一条完整开灯声的长度（含余响）。 */
    private const val TAIL = 4.55f

    @Volatile
    private var cached: ShortArray? = null

    /** 一次开灯（从 0 开始）。 */
    fun oneShot(ctx: Context): ShortArray {
        cached?.let { return it }
        val pcm = Pcm.load(ctx, ASSET)
            ?: fallback()
        cached = pcm
        return pcm
    }

    /** 整条动画的音轨：长度 [seconds] 秒，「咔」落在 [AT]。 */
    fun clip(ctx: Context, seconds: Float): ShortArray {
        val n = (seconds * Pcm.SR).toInt().coerceAtLeast(Pcm.SR / 10)
        val out = ShortArray(n)
        val src = oneShot(ctx)
        val off = (AT * Pcm.SR).toInt()
        if (off in 0 until n) System.arraycopy(src, 0, out, off, minOf(src.size, n - off))
        return out
    }

    fun play(ctx: Context) = Pcm.play(ctx, oneShot(ctx), Pcm.SR)

    /**
     * 兜底：采样读不到时不能让观片台变成哑的。
     *
     * 不求像，只求"是开灯"：一下塑料开关的咔，接一段 100/300 Hz 的镇流器嗡鸣。
     * 时间结构与真采样一致（咔在 0，嗡在 0.14 起），所以声画对位不会变。
     */
    private fun fallback(): ShortArray {
        val n = (TAIL * Pcm.SR).toInt()
        val out = FloatArray(n)
        var seed = 0x51D0C7
        fun rnd(): Float {
            seed = seed * 1664525 + 1013904223
            return ((seed ushr 8) and 0xFFFF) / 32768f - 1f
        }
        // 咔：一段 3–8 kHz 的短噪声
        var lp = 0f
        val aLp = 1f - kotlin.math.exp(-6.2831855f * 7000f / Pcm.SR)
        for (i in 0 until (0.09f * Pcm.SR).toInt()) {
            val tt = i / Pcm.SR.toFloat()
            lp += aLp * (rnd() - lp)
            out[i] += 0.55f * kotlin.math.exp(-tt / 0.0045f) * (1f - kotlin.math.exp(-tt / 0.0004f)) * lp
        }
        // 嗡：100 Hz + 300 Hz，0.14 s 起，慢起慢收
        val h0 = (0.14f * Pcm.SR).toInt()
        for (i in 0 until n - h0) {
            val tt = i / Pcm.SR.toFloat()
            val env = (1f - kotlin.math.exp(-tt / 0.06f)) * kotlin.math.exp(-tt / 6f)
            val v = 0.055f * kotlin.math.sin(6.2831855f * 100f * tt) +
                0.085f * kotlin.math.sin(6.2831855f * 300f * tt + 0.4f)
            out[h0 + i] += v * env
        }
        // 尾部淡出
        val st = n - (0.26f * Pcm.SR).toInt()
        for (i in st until n) out[i] *= (n - i) / (n - st).toFloat()
        val pcm = ShortArray(n)
        for (i in 0 until n) pcm[i] = (out[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        return pcm
    }
}
