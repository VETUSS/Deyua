package com.spektra.android

import kotlin.math.pow

/**
 * 解码曲线：在**胶片看到这束光之前**给场景重新分配影调。
 *
 * 与「影调」页那条曲线的区别是物理位置：
 *  - 这里作用在场景本身（线性光），改完再送进胶片 —— 高光压下去，胶片就少曝一点，
 *    颗粒和光晕也跟着变；
 *  - 影调页那条作用在**印出来的相纸**上，只是给成片加反差。
 *
 * 上游（SpektraLab 2.3）把它放在 Decode 里，理由一样：先塑形场景，再看胶片怎么响应。
 */
object SceneCurve {

    data class Cfg(
        val ev: Float = 0f,          // 场景曝光，−3…+3 档
        val shadow: Float = 0f,      // 阴影提亮 0…1
        val highlight: Float = 0f,   // 高光压缩 0…1
    ) {
        val isIdentity: Boolean get() = ev == 0f && shadow == 0f && highlight == 0f
    }

    /**
     * 就地返回新的浮点数组（0–1 sRGB 编码值）。
     *
     * 计算在线性光里做：曝光是乘，阴影是幂（gamma < 1 提亮暗部），高光是分数式压缩
     * （分母随亮度增长，越亮压得越多，永远不会翻折）。
     */
    fun apply(px: FloatArray, cfg: Cfg): FloatArray {
        if (cfg.isIdentity) return px
        val gain = 2.0.pow(cfg.ev.toDouble()).toFloat()
        val g = 1f - 0.45f * cfg.shadow.coerceIn(0f, 1f)
        val h = 1.2f * cfg.highlight.coerceIn(0f, 1f)
        val out = FloatArray(px.size)
        var i = 0
        while (i < px.size) {
            for (c in 0..2) {
                var v = toLinear(px[i + c]) * gain
                if (g != 1f) v = v.coerceIn(0f, 1f).pow(g)
                if (h > 0f) v /= (1f + h * v)
                out[i + c] = toSrgb(v)
            }
            i += 3
        }
        return out
    }

    private fun toLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun toSrgb(v0: Float): Float {
        val v = v0.coerceAtLeast(0f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }
}
