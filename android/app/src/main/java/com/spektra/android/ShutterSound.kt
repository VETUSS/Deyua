package com.spektra.android

import android.content.Context
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 哈苏 503CW 的快门声（r56）。
 *
 * r55 之前这里是**合成**的：按机构顺序摆四段 burst + 阻尼正弦。参数对得上"机构顺序"，
 * 但对不上**音色**。实测真机录音之后发现分错了地方：
 *
 *   攻击段（前 20 ms）  能量在 1–3 kHz 与 4–12 kHz，是宽带的金属"啪"；
 *   尾巴（20–70 ms）    **68% 的能量压在 250–500 Hz**，主峰 275 Hz —— 机身与镜筒的共鸣；
 *   60–120 Hz          只有 0.8%。真机根本"不轰"，那些低频是我脑补出来的。
 *
 * 所以 r56 直接用**真机录音**（assets/snd/shutter.wav，495 ms）：
 * 素材是 Freesound 520683「Hasselblad C500 shutter」—— CC0 公有领域，作者 Tonik1105。
 * 一次完整操作：快门「咔」→ 反光镜回位「嗒」→ 卷片机构的余响。
 * 只做了 40 Hz 高通、噪声门、归一化、淡出，波形本身一个字没改。
 * r55 的合成器留作兜底（采样缺失时才启用），因为它至少节奏是对的。
 *
 * 采样率 44.1 kHz、单声道 —— 导出时直接送进 AAC 编码器，不经过任何中间文件。
 */
object ShutterSound {

    const val SR = Pcm.SR

    /** 采样里攻击点之前留了 25 ms 的空气，播放/混流时按这个量提前对齐画面。 */
    private const val PRE = 0.025f

    private const val ASSET = "snd/shutter.wav"

    @Volatile
    private var cached: ShortArray? = null

    /** 快门落下的时刻。直接引用 [LiveFx] 的 CUT —— 声画不会因为改了一处而错开。 */
    const val AT = LiveFx.CUT

    /** 一次快门的完整长度（含余响）。 */
    private const val TAIL = 1.15f

    /** 一次快门（从 0 开始）。优先真机采样，读不到才退回合成。 */
    fun oneShot(ctx: Context): ShortArray {
        cached?.let { return it }
        val pcm = load(ctx) ?: synth()
        cached = pcm
        return pcm
    }

    /** 整条动画的音轨：长度 [seconds] 秒，快门落在 [AT]。大部分是静音，AAC 压得极小。 */
    fun clip(ctx: Context, seconds: Float): ShortArray {
        val n = (seconds * SR).toInt().coerceAtLeast(SR / 10)
        val out = ShortArray(n)
        val src = oneShot(ctx)
        // 采样前留了 8 ms 空气：提前 8 ms 写入，让攻击点正好落在画面上
        val off = ((AT - PRE) * SR).toInt()
        if (off < n) System.arraycopy(src, 0, out, off, minOf(src.size, n - off))
        return out
    }

    /** 读 assets 里的真机录音（WAV / PCM 16 bit 单声道）。 */
    private fun load(ctx: Context): ShortArray? =
        Pcm.load(ctx, ASSET).also {
            if (it == null) Diagnostics.log("sound", "快门采样读取失败，退回合成")
        }

    /** 预览用：一次性把这段 PCM 丢给 AudioTrack，播完自己释放。 */
    fun play(ctx: Context) = Pcm.play(ctx, oneShot(ctx), SR)

    // ---------------------------------------------------------------- 合成
    private fun synth(): ShortArray {
        val n = (SR * TAIL).toInt()
        val wet = FloatArray(n)
        var seed = 0x2F6E2B1

        // 确定性噪声：同一个包每次听都一样，导出与预览也完全一致
        fun rnd(): Float {
            seed = seed * 1664525 + 1013904223
            return ((seed ushr 8) and 0xFFFF) / 32768f - 1f
        }

        fun add(i: Int, v: Float) {
            if (i in 0 until n) wet[i] += v
        }

        /**
         * 一段塑形过的噪声：二阶低通 + 一阶高通，再套指数包络。
         * [hpHz] 决定它"脆"还是"闷"，[lpHz] 决定它的上限在哪。
         */
        fun burst(t0: Float, tau: Float, amp: Float, lpHz: Float, hpHz: Float) {
            val i0 = (t0 * SR).toInt()
            val len = ((tau * 7f).coerceAtLeast(0.01f) * SR).toInt()
            val aLp = (1f - exp(-6.2831855f * lpHz / SR)).coerceIn(0.0005f, 1f)
            val aHp = (1f - exp(-6.2831855f * hpHz / SR)).coerceIn(0.00002f, 1f)
            var l1 = 0f
            var l2 = 0f
            var h1 = 0f
            for (i in 0 until len) {
                val tt = i / SR.toFloat()
                // 0.35 ms 起振：真机构不会瞬间到满幅，那 0.3 ms 决定"硬"还是"炸"
                val env = exp(-tt / tau) * (1f - exp(-tt / 0.00035f))
                val x = rnd()
                l1 += aLp * (x - l1)
                l2 += aLp * (l1 - l2)
                h1 += aHp * (l2 - h1)
                add(i0 + i, amp * env * (l2 - h1))
            }
        }

        /** 一根会滑音的阻尼正弦：金属件与机身的共鸣都是这个形状。 */
        fun tone(t0: Float, f0: Float, f1: Float, tau: Float, amp: Float, ph0: Float = 0f) {
            val i0 = (t0 * SR).toInt()
            val len = (tau * 7f * SR).toInt()
            var ph = ph0
            for (i in 0 until len) {
                val tt = i / SR.toFloat()
                val u = (tt / (tau * 7f)).coerceIn(0f, 1f)
                val f = f0 + (f1 - f0) * u
                ph += 6.2831855f * f / SR
                val env = exp(-tt / tau) * (1f - exp(-tt / 0.0006f))
                add(i0 + i, sin(ph) * env * amp)
            }
        }

        // ---- 1. 释放：金属脱钩 ----
        // 两个噪声层：一层宽（"咔"的芯），一层更短更高（"咔"的边）。缺了第二层就只是一声闷响。
        burst(0.0000f, 0.0032f, 0.90f, 16000f, 1600f)
        burst(0.0000f, 0.0018f, 0.55f, 18000f, 2500f)
        tone(0.0000f, 1372f, 1180f, 0.017f, 0.34f)
        tone(0.0002f, 2650f, 2200f, 0.012f, 0.35f)
        tone(0.0006f, 4200f, 3400f, 0.008f, 0.22f)
        tone(0.0008f, 2310f, 2060f, 0.010f, 0.24f)
        tone(0.0012f, 3180f, 2760f, 0.008f, 0.16f)
        // 机身：低频只给"厚度"，不能给"主体" —— 全堆在 100 Hz 以下就变成捂住的鼓
        tone(0.0010f, 96f, 88f, 0.105f, 0.30f)
        tone(0.0020f, 143f, 132f, 0.082f, 0.22f)

        // ---- 2. 反光镜上行：一段闷的行程 ----
        burst(0.0115f, 0.0150f, 0.46f, 2600f, 300f)
        tone(0.0125f, 1600f, 1350f, 0.025f, 0.18f)
        tone(0.0130f, 208f, 184f, 0.044f, 0.20f)
        tone(0.0140f, 412f, 372f, 0.026f, 0.16f)
        tone(0.0148f, 1180f, 1040f, 0.020f, 0.13f)

        // ---- 3. 镜间快门：叶片开合，细节都在高频 ----
        burst(0.0260f, 0.0042f, 0.34f, 16000f, 3200f)
        tone(0.0260f, 5300f, 2850f, 0.0095f, 0.26f)
        tone(0.0268f, 7900f, 6200f, 0.0050f, 0.14f)

        // ---- 4. 反光镜回位：整台机器的那一下 ----
        burst(0.1180f, 0.0085f, 0.62f, 6800f, 400f)
        burst(0.1180f, 0.0030f, 0.55f, 15000f, 2000f)
        tone(0.1180f, 88f, 73f, 0.130f, 0.45f)
        tone(0.1192f, 126f, 106f, 0.112f, 0.32f)
        tone(0.1205f, 191f, 168f, 0.090f, 0.24f)
        tone(0.1210f, 884f, 792f, 0.028f, 0.45f)
        tone(0.1216f, 1463f, 1310f, 0.019f, 0.30f)
        tone(0.1222f, 2410f, 2180f, 0.013f, 0.15f)

        // ---- 5. 残余机械：卷片齿轮、连杆。间隔不规则才是"机械"，等距就是"音效" ----
        var tt = 0.150f
        for (k in 0 until 17) {
            val g = 1f - k / 18f
            burst(tt, 0.0014f + g * 0.0012f, 0.115f * g, 11000f, 1900f)
            tt += 0.0035f + hash01(k * 7 + 1) * 0.0075f
        }
        // 快门帘/光圈拨杆回位，略沉一点的一声
        tone(0.205f, 620f, 560f, 0.020f, 0.07f)

        // ---- 早期反射：机身外壳不大，几根抽头就够；多了就是教堂 ----
        val out = FloatArray(n)
        System.arraycopy(wet, 0, out, 0, n)
        val taps = floatArrayOf(0.011f, 0.019f, 0.028f, 0.041f, 0.057f, 0.076f, 0.099f)
        val gains = floatArrayOf(0.20f, 0.14f, 0.105f, 0.075f, 0.052f, 0.036f, 0.024f)
        for (k in taps.indices) {
            val d = (taps[k] * SR).toInt()
            val g = gains[k]
            for (i in 0 until n - d) out[i + d] += wet[i] * g
        }
        // 扩散尾巴：极轻的一层，只负责"这不是一条干声"
        run {
            var lp = 0f
            val a = 1f - exp(-6.2831855f * 2600f / SR)
            for (i in 0 until n) {
                lp += a * (rnd() - lp)
                val tt2 = i / SR.toFloat()
                if (tt2 > 0.02f) out[i] += lp * 0.020f * exp(-(tt2 - 0.02f) / 0.22f)
            }
        }

        // ---- 归一 + 软限幅 ----
        var peak = 0f
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
        val g = if (peak > 1e-6f) 0.92f / peak else 1f
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            val v = tanh(out[i] * g * 1.15f) / tanh(1.15f) * 0.97f
            pcm[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    private fun hash01(n: Int): Float {
        var h = n * 374761393 + 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7FFFFFFF) / 2147483647f
    }
}
