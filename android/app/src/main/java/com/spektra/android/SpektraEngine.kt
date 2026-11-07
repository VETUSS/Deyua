package com.spektra.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Spektrafilm 引擎的 Kotlin 封装。
 * - 通过 JNI 调用 C ABI（libjni_bridge.so -> libspektrafilm_engine.so）。
 * - 负责把 assets/engine 里的资源（SPIR-V + 胶片/相纸 profile）解压到私有目录供引擎按路径加载。
 */
class SpektraEngine(private val context: Context) {

    private var enginePtr: Long = 0
    // 引擎初始化失败时的真实原因（spk_last_error，线程局部全局错误，create 失败也可读取）
    var initError: String? = null
    var resourcesDir: File = File(context.filesDir, "engine")
        private set

    init {
        var unpackErr: String? = null
        try {
            // 增量同步：每次启动都对比 assets/engine，缺失或大小变更的文件补写覆盖。
            // 覆盖安装后 files/engine 保留旧文件、不会自动补进新增 profile，必须这里同步。
            syncAssets(context, resourcesDir)
        } catch (t: Throwable) {
            Log.e(TAG, "unpack assets failed", t)
            unpackErr = "解压资源失败：${t.message}"
        }
        enginePtr = nativeCreate(resourcesDir.absolutePath)
        initError = when {
            enginePtr != 0L -> null
            unpackErr != null -> unpackErr
            else -> nativeLastError(enginePtr) // engine 为空，spk_last_error 读全局错误
        }
        if (enginePtr != 0L) {
            Log.i(TAG, "engine created: caps=${nativeCapabilities(enginePtr).take(80)}…")
        } else {
            Log.e(TAG, "engine create failed: $initError")
        }
    }

    /** 把 assets/engine 增量同步到 dest：缺失或大小变化的文件覆盖，已一致的不动。 */
    private fun syncAssets(ctx: Context, dest: File) {
        dest.mkdirs()
        syncTree(ctx.assets, "engine", dest)
    }

    private fun syncTree(am: android.content.res.AssetManager, path: String, dest: File) {
        val children = am.list(path)
        if (children == null || children.isEmpty()) {
            // 叶子：缺失或大小变化才覆盖
            dest.parentFile?.mkdirs()
            val need = !dest.exists() || dest.length() != assetSize(am, path)
            if (need) {
                am.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        } else {
            dest.mkdirs()
            for (c in children) syncTree(am, "$path/$c", File(dest, c))
        }
    }

    private fun assetSize(am: android.content.res.AssetManager, path: String): Long =
        try { am.open(path).use { it.available().toLong() } }
        catch (_: Throwable) { -1L }

    fun destroy() {
        if (enginePtr != 0L) { nativeDestroy(enginePtr); enginePtr = 0L }
    }

    val capabilities: String get() = nativeCapabilities(enginePtr)
    val paramsSchema: String get() = nativeParamsSchema(enginePtr)

    /**
     * [r40] 本机单块 SSBO 上限（字节），0 表示引擎没报。
     *
     * 一张平面是 `w * h * 3 * 4` 字节且必须整块绑定，所以这个数除以 12 就是
     * 一次渲染能装下的最大像素数 —— 128 MiB 对应 11.2 MP（3:2 的 4096 长边），
     * 新一些的 Adreno / 桌面端会大好几倍。以前 App 里写死 128 MiB，等于让
     * 大缓冲的设备也按最小设备的分辨率导出。
     */
    val maxStorageBufferRange: Long by lazy {
        if (enginePtr == 0L) 0L else runCatching {
            org.json.JSONObject(capabilities)
                .optJSONObject("backend")
                ?.optLong("max_storage_buffer_range", 0L) ?: 0L
        }.getOrDefault(0L)
    }

    /** 打开一帧；pixels 为 float RGB(A)，返回 session 句柄。 */
    fun open(pixels: FloatArray, w: Int, h: Int, channels: Int, paramsJson: String?): Long =
        nativeOpen(enginePtr, pixels, w, h, channels, paramsJson)

    fun setParams(session: Long, deltaJson: String): String = nativeSetParams(session, deltaJson)
    fun getParams(session: Long): String = nativeGetParams(session)
    fun solve(session: Long, target: String = "exposure"): String = nativeSolve(session, target)
    fun overscanLayout(session: Long): String = nativeOverscanLayout(session)

    /** 渲染 tier，返回 rgba16（每通道 u16），并把宽高写入 outWH。 */
    fun render(session: Long, tier: String, outWH: IntArray): ShortArray =
        nativeRender(session, tier, outWH)

    /** 渲染结果转 Bitmap（RGBA8）。quiet=true 时不打渲染日志（缩略图逐张渲染，26 张会刷满日志）。 */
    fun renderToBitmap(session: Long, tier: String, quiet: Boolean = false): Bitmap? {
        val f = renderFrame(session, tier, quiet) ?: return null
        val t1 = System.currentTimeMillis()
        val bmp = f.toBitmap()
        if (!quiet) Diagnostics.log("render", "    bitmap ${System.currentTimeMillis() - t1}ms")
        return bmp
    }

    /**
     * [r37] 渲染成**未降位**的 rgba16，供 Layer 2 在 16 bit 上处理。
     *
     * 调色必须在 16 bit 上做：先降到 8 bit 再拉曲线，斜率大于 1 的段落每两级之间会
     * 空出 2–6 个码值，就是肉眼可见的色阶断层。这条路径把它留给 Layer2.apply16，
     * 只有在最后写 Bitmap 时才降到 8 bit（并在那里抖动）。
     */
    fun renderFrame(session: Long, tier: String, quiet: Boolean = false): Frame16? {
        val wh = IntArray(2)
        val t0 = System.currentTimeMillis()
        val rgba16 = render(session, tier, wh) ?: return null
        if (!quiet) Diagnostics.log("render", "    native ${System.currentTimeMillis() - t0}ms ${wh[0]}x${wh[1]}")
        if (wh[0] <= 0 || wh[1] <= 0) return null
        return Frame16(rgba16, wh[0], wh[1])
    }

    /**
     * [r11] PRINT 层重印：复用缓存的负片，只重新印相纸。
     *
     * 用于「只改了相纸 / 放大机 / 光泽」的情形 —— 跳过重冲负片，这是切相纸慢的根因解。
     * 第一次渲染前没有负片缓存，native 会抛错；这里返回 null，调用方回退到完整渲染。
     */
    fun reprintToBitmap(session: Long, tier: String, quiet: Boolean = false): Bitmap? {
        val f = reprintFrame(session, tier, quiet) ?: return null
        val t1 = System.currentTimeMillis()
        val bmp = f.toBitmap()
        if (!quiet) Diagnostics.log("render", "    reprint bitmap ${System.currentTimeMillis() - t1}ms")
        return bmp
    }

    /** [r37] 重印的 16 bit 版本，与 [reprintToBitmap] 同一份字节，只是不降位。 */
    fun reprintFrame(session: Long, tier: String, quiet: Boolean = false): Frame16? {
        val wh = IntArray(2)
        val t0 = System.currentTimeMillis()
        val rgba16 = try {
            nativeReprint(session, tier, wh)
        } catch (e: Throwable) {
            if (!quiet) Diagnostics.log("render", "    reprint 不可用：${e.message}")
            return null
        } ?: return null
        if (!quiet) Diagnostics.log("render", "    reprint native ${System.currentTimeMillis() - t0}ms ${wh[0]}x${wh[1]}")
        if (wh[0] <= 0 || wh[1] <= 0) return null
        return Frame16(rgba16, wh[0], wh[1])
    }

    /**
     * [r11] 取消正在进行的渲染。spk_cancel 不拿 session 锁，可以从另一个线程在
     * 渲染中调用；渲染会在下一个节点边界退出（比干等 25 s 超时好得多）。
     */
    fun cancel(session: Long) {
        if (session == 0L) return
        runCatching { nativeCancel(session, null) }
    }

    /**
     * [r11] 相纸 LUT 预览（近似）。**不用于主渲染路径** —— LUT 是烘焙的近似表，
     * 与精确渲染有可见色差；主路径用 reprintToBitmap（精确）。留给以后的"快速比对"。
     */
    fun previewStockLutToBitmap(session: Long, printStock: String, tier: String): Bitmap? {
        val wh = IntArray(2)
        val rgba16 = runCatching { nativePreviewStockLut(session, printStock, tier, wh) }.getOrNull() ?: return null
        return toBitmap(rgba16, wh[0], wh[1])
    }

    /** 哪些相纸有随包烘焙的 LUT（JSON）。 */
    fun printLutCatalog(): String = nativePrintLutCatalog(enginePtr)

    /** rgba16（每通道 u16）→ ARGB_8888。带抖动，见 [Frame16.convert]。 */
    private fun toBitmap(rgba16: ShortArray, w: Int, h: Int): Bitmap? =
        Frame16.convert(rgba16, w, h)

    fun sessionRelease(session: Long) = nativeSessionRelease(session)
    fun warmUp(film: String, print: String): String = nativeWarmUp(enginePtr, film, print)
    fun memoryReport(): String = nativeMemoryReport(enginePtr)

    /**
     * r40.5 把渲染池里所有空闲缓冲还给驱动，返回释放的字节数。
     *
     * 池只做**复用**不做**归还**：一块平面出了作用域，显存仍然留着等下一次
     * 同尺寸请求。预览时这是省时间的正确取舍，但导出前它就变成了负担 ——
     * 前面那几十张缩略图和预览留下的块，会和这次 full 渲染要的块叠在一起。
     */
    fun trimPool(): Long = nativeTrimPool(enginePtr)

    fun lastError(): String = nativeLastError(enginePtr)

    /** 渲染进度：引擎正在跑哪一个节点（卡死时看这个就知道卡在哪一步）。 */
    fun progressStage(session: Long): String = nativeProgress(session)

    private external fun nativeCreate(resourcesDir: String): Long
    private external fun nativeDestroy(enginePtr: Long)
    private external fun nativeCapabilities(enginePtr: Long): String
    private external fun nativeParamsSchema(enginePtr: Long): String
    private external fun nativeLastError(enginePtr: Long): String
    private external fun nativeOpen(enginePtr: Long, pixels: FloatArray, w: Int, h: Int,
                                    channels: Int, paramsJson: String?): Long
    private external fun nativeSessionRelease(sessionPtr: Long)
    private external fun nativeSetParams(sessionPtr: Long, deltaJson: String?): String
    private external fun nativeGetParams(sessionPtr: Long): String
    private external fun nativeSolve(sessionPtr: Long, target: String?): String
    private external fun nativeOverscanLayout(sessionPtr: Long): String
    private external fun nativeProgress(sessionPtr: Long): String
    private external fun nativeRender(sessionPtr: Long, tier: String?, outWH: IntArray): ShortArray
    // [r11·第0批] PRINT 层重印 / 取消 / 相纸 LUT
    private external fun nativeReprint(sessionPtr: Long, tier: String?, outWH: IntArray): ShortArray?
    private external fun nativeCancel(sessionPtr: Long, progressId: String?)
    private external fun nativePreviewStockLut(sessionPtr: Long, printStock: String?, tier: String?,
                                               outWH: IntArray): ShortArray?
    private external fun nativePrintLutCatalog(enginePtr: Long): String
    private external fun nativeWarmUp(enginePtr: Long, filmStock: String?, printStock: String?): String
    private external fun nativeMemoryReport(enginePtr: Long): String
    private external fun nativeTrimPool(enginePtr: Long): Long

    // ---- RAW 解码（libjni_raw.so，独立于渲染引擎） ----

    /**
     * [r40] 解码并直接开会话：像素从解码到上传全程留在 native 堆。
     *
     * 45 MP 的 float RGB 是 440 MB，先变成 Java float[] 再交给引擎等于在堆上
     * 多住一份，导入分辨率因此被堆大小而不是 GPU 预算决定。这条路径把那份
     * 拷贝整个省掉，也是「全分辨率导出」能成立的前提。
     *
     * maxPixels 由 [GpuBudget] 按设备实际能力算出（像素数，不是长边）；
     * <=0 表示不缩。
     */
    fun decodeRawOpen(path: String, maxPixels: Long, paramsJson: String?,
                      thumb: FloatArray? = null): RawSession? {
        if (enginePtr == 0L) return null
        val outSize = IntArray(10)
        val handle = try {
            nativeDecodeRawOpen(enginePtr, path, maxPixels, paramsJson, outSize, thumb)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "raw decode OOM(native 内存不足): ${e.message}")
            0L
        } catch (t: Throwable) {
            Log.e(TAG, "raw decode 异常: ${t.message}", t)
            0L
        }
        if (handle == 0L) { Log.e(TAG, "raw decode failed: ${rawLastError()}"); return null }
        return RawSession(
            handle, outSize[0], outSize[1], outSize[2], outSize[3],
            outSize[4], outSize[5], outSize[6], outSize[7], outSize[8], outSize[9],
        )
    }

    /**
     * 解码成 Java float[]（回退路径）。outSize[0..7] 依次是工作宽高、有效成像
     * 宽高、LibRaw 帧宽高、裁掉的右/下像素数。
     */
    fun decodeRaw(path: String, maxPixels: Long = 0L): Pair<FloatArray, Pair<Int, Int>>? {
        val outSize = IntArray(8)
        val rgb = try {
            nativeDecodeRaw(path, maxPixels, outSize)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "raw decode OOM(堆内存不足): ${e.message}")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "raw decode 异常: ${t.message}", t)
            null
        }
        if (rgb == null) { Log.e(TAG, "raw decode failed: ${rawLastError()}"); return null }
        return rgb to (outSize[0] to outSize[1])
    }

    /** 只读 RAW 头：「厂商 机型|有效尺寸|解码器|arw6=0/1」，失败返回 null。 */
    fun rawProbe(path: String): String? =
        runCatching { nativeRawProbe(path) }.getOrNull()

    fun rawLastError(): String = nativeRawLastError()
    private external fun nativeDecodeRawOpen(enginePtr: Long, path: String, maxPixels: Long,
                                             paramsJson: String?, outSize: IntArray,
                                             thumb: FloatArray?): Long
    private external fun nativeDecodeRaw(path: String, maxPixels: Long, outSize: IntArray): FloatArray?
    private external fun nativeRawProbe(path: String): String?
    private external fun nativeRawLastError(): String

    companion object {
        private const val TAG = "SpektraEngine"
        init {
            System.loadLibrary("jni_bridge")
            // 独立的 RAW 解码库（含 LibRaw + 引擎 raw_decoder）
            System.loadLibrary("jni_raw")
        }
    }
}

/**
 * 一次 RAW 导入的结果，尺寸口径有三个，界面要如实区分：
 *  - [width]/[height]    工作尺寸（已按设备预算缩过）；
 *  - [activeW]/[activeH] 有效成像尺寸（裁掉遮罩边之后）；
 *  - [frameW]/[frameH]   LibRaw 报的传感器帧尺寸（含遮罩边）。
 * [cutX]/[cutY] 是右/下被裁掉的遮罩像素数，0 表示没裁。
 */
data class RawSession(
    val session: Long,
    val width: Int, val height: Int,
    val activeW: Int, val activeH: Int,
    val frameW: Int, val frameH: Int,
    val cutX: Int, val cutY: Int,
    val thumbW: Int, val thumbH: Int,
)

/**
 * 一帧引擎原样的 rgba16（每通道 u16，RGBA 交错）。
 *
 * [r37] 引入它，是因为 `renderToBitmap` 原来的转换是 `v shr 8` —— 引擎辛苦算出来的
 * 16 bit 被直接砍掉低 8 位。只是看图没问题，但接下来要拉曲线：在 256 级上做斜率大于 1
 * 的曲线，相邻输入级会被映射到相差 2–6 个码值，成片上就是一条条色阶断层。
 * 把 16 bit 留到调色之后，断层就没有了 —— 只剩最后的 1 个码值舍入，交给 Bayer 抖动打散。
 */
class Frame16(val px: ShortArray, val w: Int, val h: Int) {

    fun toBitmap(): Bitmap? = convert(px, w, h)

    companion object {
        /** 8×8 Bayer，0–63。降到 8 bit 时用它做 ±0.5 码值的抖动。 */
        private val BAYER8 = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21,
        )

        fun convert(rgba16: ShortArray, w: Int, h: Int): Bitmap? {
            if (w <= 0 || h <= 0) return null
            if (rgba16.size < w * h * 4) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            var i = 0
            for (y in 0 until h) {
                val rowD = (y and 7) shl 3
                for (x in 0 until w) {
                    // 抖动幅度 ±128（16 bit 尺度）= ±0.5 个 8 bit 码值。
                    //
                    // 映射用 /65535 而不是 >>8：65535 / 255 = 257，右移会把 65534 也变成
                    // 255，白点提前 1/255 就焊死；而这里 65534 落在 254.996，抖动后仍能
                    // 一半像素落在 255 —— 亮部才有可分辨的层次，而不是死白一片。
                    val d = (BAYER8[rowD or (x and 7)] - 32) * 4
                    val r = (rgba16[i * 4].toInt() and 0xFFFF) * 255 + 32767 + d
                    val g = (rgba16[i * 4 + 1].toInt() and 0xFFFF) * 255 + 32767 + d
                    val b = (rgba16[i * 4 + 2].toInt() and 0xFFFF) * 255 + 32767 + d
                    px[i] = (0xFF shl 24) or
                        ((r / 65535).coerceIn(0, 255) shl 16) or
                        ((g / 65535).coerceIn(0, 255) shl 8) or
                        (b / 65535).coerceIn(0, 255)
                    i++
                }
            }
            bmp.setPixels(px, 0, w, 0, 0, w, h)
            return bmp
        }
    }
}
