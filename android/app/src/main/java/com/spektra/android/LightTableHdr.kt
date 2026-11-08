package com.spektra.android

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * 观片台的 HDR 支撑。
 *
 * 灯箱是**透射光源**，它的亮度应该高于界面上任何一块 SDR 白（SDR 白点约
 * 200 nits，灯箱可以到 600–1000 nits）。屏幕要表达这件事，像素值必须允许
 * 超过线性 1.0，也就是必须有 HDR headroom。
 *
 * 做法只有一步：把窗口切到 COLOR_MODE_HDR，再用 `RGBA_F16 + EXTENDED_SRGB`
 * 的贴图当灯箱面，贴图里的值可以到 1.4 甚至更高。
 *
 * 每台机器对 color mode 的支持并不一致：不支持时 `setColorMode` 会直接抛
 * 异常。所以这里所有入口都包了 runCatching，任何一步失败都退回 SDR 绘制，
 * 页面照常可用——观片台的稳定性不能押在 HDR 上。
 */
internal object LtHdr {

    /** 是否有可用的 HDR 面板。API 34 起才有扩展 sRGB 的显示管线。 */
    fun supported(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 34) return false
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return false
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        @Suppress("DEPRECATION")
        d.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
    }.getOrDefault(false)

    /** 记录当前 color mode，退出时原样还回去。 */
    fun prevMode(act: Activity?): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 26) act?.window?.colorMode ?: ActivityInfo.COLOR_MODE_DEFAULT
        else ActivityInfo.COLOR_MODE_DEFAULT
    }.getOrDefault(ActivityInfo.COLOR_MODE_DEFAULT)

    /** 切到 HDR；返回是否真的切成功（失败不抛，交给调用方回退 SDR）。 */
    fun enter(act: Activity?): Boolean {
        if (act == null || Build.VERSION.SDK_INT < 26) return false
        return runCatching {
            act.window.colorMode = ActivityInfo.COLOR_MODE_HDR
            true
        }.getOrDefault(false)
    }

    fun exit(act: Activity?, prev: Int) {
        if (act == null || Build.VERSION.SDK_INT < 26) return
        runCatching { act.window.colorMode = prev }
    }

    /**
     * 灯箱发光贴片：64×64 的 F16 暖白光斑，中心 1.46、边缘 1.02。
     * 拉伸铺满灯箱时值仍然 >1，于是在 HDR 窗口里它真的比界面白更亮；
     * 在 SDR 窗口里会被色调映射压到 1.0，看着就是一块干净的暖白灯箱，不会坏。
     */
    fun glowTile(): ImageBitmap? = runCatching {
        if (Build.VERSION.SDK_INT < 26) return null
        val n = 64
        val bmp = Bitmap.createBitmap(
            n, n, Bitmap.Config.RGBA_F16, true,
            ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)
        )
        val buf = LongBuffer.allocate(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val dx = (x + 0.5f) / n - 0.5f
                val dy = (y + 0.5f) / n - 0.5f
                val r = (sqrt(dx * dx + dy * dy) / 0.707f).coerceIn(0f, 1f)
                // 中心 1.46 → 边缘 1.02，平方衰减，灯箱该是"中间最亮、边缘略收"
                val f = (1f - r) * (1f - r)
                val l = 1.02f + (1.46f - 1.02f) * f
                buf.put(pack(l, l * 0.982f, l * 0.940f))
            }
        }
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        bmp.asImageBitmap()
    }.getOrNull()

    /** RGBA_F16 每像素 64 位：R 在低 16 位，A 在最高 16 位。 */
    private fun pack(r: Float, g: Float, b: Float): Long =
        (0x3C00L shl 48) or
            (half(b).toLong() shl 32) or
            (half(g).toLong() shl 16) or
            half(r).toLong()

    private fun half(f: Float): Int {
        if (f.isNaN()) return 0x7E00
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        if (exp == 0xFF) return sign or 0x7C00
        var e = exp - 127 + 15
        if (e >= 0x1F) return sign or 0x7C00
        if (e <= 0) {
            if (e < -10) return sign
            val m = (mant or 0x800000) ushr (14 - e)
            return sign or m
        }
        var m = mant ushr 13
        if (mant and 0x1000 != 0) {
            m += 1
            if (m == 0x400) {
                m = 0; e += 1
                if (e >= 0x1F) return sign or 0x7C00
            }
        }
        return sign or (e shl 10) or m
    }
}
