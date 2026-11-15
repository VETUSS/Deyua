package com.spektra.android

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager

/**
 * Camera2 控制器（r69 重写）。
 *
 * 只做"把相机的帧送到一个 Surface、把一张 JPEG 取回来、把命令排进同一条线程"，
 * 不碰界面、不碰渲染。相比 r68 多出来的部分，都是"一台正常相机该有的"：
 *
 *  · **多镜头**：先按 [CamOptics] 扫出来的镜头直开；物理子相机不允许直开时，
 *    退回逻辑相机 + 变焦比（`CONTROL_ZOOM_RATIO`，没有就走裁切）。这就是
 *    系统相机里 0.5× / 1× / 3× 的真实实现方式。
 *  · **曝光补偿**：读 `CONTROL_AE_COMPENSATION_RANGE`，按步长换算成整数档再下发。
 *  · **AE/AF 锁定**：长按取景点锁定，再点一次解锁 —— 逆光构图时最常用的一条。
 *  · **拍摄比例/像素**：都从 `SCALER_STREAM_CONFIGURATION_MAP` 的真实出图尺寸里选，
 *    不写死分辨率（48MP 机型要能吃到 48MP，老机型也不会选到不存在的档）。
 *  · **手电筒**：闪光灯除了 off/auto/on，还有常亮（录视频/对焦辅助）。
 *
 * 线程模型没变：所有相机会话动作都在一条 `HandlerThread` 上排队。Camera2 要求同一个
 * 会话的回调与请求顺序一致，混线程调用会出现"请求被丢掉且不报错"。
 */
class CamController(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val manager = appCtx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("deyva-cam").apply { start() }
    private val handler = Handler(thread.looper)

    /** 一次扫描的结果，界面用它画镜头条与设置页。 */
    val lenses: List<Lens> = CamOptics.scan(appCtx)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null

    @Volatile private var previewSurface: Surface? = null
    private var previewSize: Size = Size(1440, 1080)
    private var captureSize: Size = Size(4000, 3000)
    /** 想要的比例与像素上限（界面设定）；真正的出图尺寸由 HAL 支持的档位决定。 */
    private var aspect = 4f / 3f
    private var maxMp = Int.MAX_VALUE

    /** 当前实际打开的相机 id（可能不等于请求的镜头 id：物理子相机打不开时会退回逻辑相机）。 */
    @Volatile var activeId: String? = null
        private set
    @Volatile var caps: CamCaps? = null
        private set
    /** 请求的镜头与最终落到的相机不一致时，这里给出实际倍率（界面据此显示）。 */
    @Volatile var resolvedZoom: Float = 1f
        private set

    private var sensorOrientation = 90
    private var activeRect = Rect(0, 0, 1, 1)
    private var afMode: Int? = null
    private var afRegions = 0
    private var afAutoSupported = false
    private var aeRegions = 0
    @Volatile private var flashAvailable = false
    private var facing = CameraCharacteristics.LENS_FACING_BACK

    private var zoom = 1f
    private var zoomRatioSupported = false
    private var maxZoom = 8f
    private var ev = 0
    private var evRange: IntRange = 0..0
    private var evStep = 1.0f
    private var aeLocked = false

    var flashMode = FLASH_OFF
        private set

    var jpegQuality = 96
        private set

    /** 拿到一张 JPEG。 */
    var onJpeg: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** 预览流已经开始（尺寸可直接用于摆放画面）。 */
    var onStarted: ((Size) -> Unit)? = null

    /** 相机的静态能力，界面画镜头条、比例、像素、EV 都靠它。 */
    data class CamCaps(
        val id: String,
        val facing: Int,
        val sensorOrientation: Int,
        val focalMm: Float,
        val equivMm: Float,
        val aperture: Float,
        val hasFlash: Boolean,
        val evRange: IntRange,
        /** 一档步进等于多少 EV（常见 1/3 或 1/6）。 */
        val evStep: Float,
        val maxZoom: Float,
        val zoomRatioSupported: Boolean,
        val afModes: Set<Int>,
        val maxAfRegions: Int,
        val maxAeRegions: Int,
        val previewSizes: List<Size>,
        val captureSizes: List<Size>,
        val aspectRatios: List<Float>,
    )

    companion object {
        const val FLASH_OFF = 0
        const val FLASH_AUTO = 1
        const val FLASH_ON = 2
        const val FLASH_TORCH = 3
    }

    // ------------------------------------------------------------ 对外查询

    fun hasLens(f: Int): Boolean = lenses.any { it.facing == f }

    fun lensesOf(f: Int): List<Lens> = lenses.filter { it.facing == f }.sortedBy { it.equivMm }

    fun anchorsOf(f: Int): List<ZoomAnchor> = CamOptics.anchors(lenses, f)

    fun hasFlash(): Boolean = flashAvailable

    fun displayRotationDegrees(): Int = runCatching {
        val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /**
     * 预览要转多少度才是正的（**未**计入 HAL 自己在纹理变换里做的那部分，
     * 那部分由 [CamGlView] 从 `getTransformMatrix` 里反解后扣掉）。
     *
     * 后摄：传感器方向减屏幕方向；前摄：两者相加，并且要镜像。
     */
    fun previewRotation(f: Int): Int {
        val d = displayRotationDegrees()
        return if (f == CameraCharacteristics.LENS_FACING_FRONT) (sensorOrientation + d) % 360
        else (sensorOrientation - d + 360) % 360
    }

    // ------------------------------------------------------------ 生命周期

    /**
     * 打开（或复用）相机并开始预览。
     *
     * 同一个 Surface + 同一颗镜头 + 同样的比例/像素上限 = 已经开着，不重开
     * （重开时预览会黑一下）。
     */
    fun start(surface: Surface, lens: Lens, aspect: Float, maxMp: Int) {
        handler.post {
            val same = previewSurface == surface && device != null &&
                activeId != null && lensMatches(lens, activeId!!) &&
                kotlin.math.abs(this.aspect - aspect) < 0.01f && this.maxMp == maxMp
            previewSurface = surface
            facing = lens.facing
            this.aspect = aspect
            this.maxMp = maxMp
            if (same) {
                applyRepeating()
                return@post
            }
            openLocked(lens)
        }
    }

    private fun lensMatches(want: Lens, id: String): Boolean =
        want.id == id || lenses.firstOrNull { it.id == id }?.let { cur ->
            cur.facing == want.facing && kotlin.math.abs(cur.equivMm - want.equivMm) < 0.5f
        } == true

    /**
     * 真正开相机。
     *
     * 优先直开请求的镜头；物理子相机不允许直开时（`openCamera` 抛 IllegalArgumentException /
     * SecurityException / MAX_CAMERAS_IN_USE），退回同朝向的逻辑相机，并把变焦比设成
     * "这颗镜头相对逻辑相机的等效焦距比" —— 效果一样，只是 HALL 内部替你切。
     */
    private fun openLocked(lens: Lens) {
        closeLocked()
        val logical = pickLogical(lens.facing)
        // 试机顺序：请求的镜头 → 同朝向逻辑相机
        val tries = ArrayList<Pair<String, Float>>()
        if (lens.openable) tries += lens.id to 1f
        if (logical != null && logical.id !in tries.map { it.first }) {
            val ratio = if (logical.equivMm > 1f) (lens.equivMm / logical.equivMm) else 1f
            tries += logical.id to ratio.coerceIn(1f, 8f)
        }
        if (tries.isEmpty()) {
            onError?.invoke("这台设备没有可用的相机")
            return
        }
        openFirst(tries, 0)
    }

    private fun pickLogical(f: Int): Lens? =
        lenses.filter { it.facing == f && !it.physical && it.openable }.minByOrNull { it.equivMm }

    private fun openFirst(tries: List<Pair<String, Float>>, index: Int) {
        val (id, ratio) = tries[index]
        val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        if (chars == null) {
            if (index + 1 < tries.size) openFirst(tries, index + 1)
            else onError?.invoke("读不到相机 $id 的参数")
            return
        }
        readCaps(id, chars, ratio)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        // 尺寸以相机实际支持为准：界面传下来的只是愿望，HAL 不认就得改
        previewSize = CamOptics.pickPreview(previewSizes, aspect, 1600)
        captureSize = CamOptics.pickCapture(jpegSizes, aspect, maxMp)
        val jpeg = captureSize
        val r = runCatching {
            ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        }.getOrNull()
        if (r == null) {
            onError?.invoke("无法建立出图缓冲（${jpeg.width}×${jpeg.height}）")
            return
        }
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                onJpeg?.invoke(bytes)
            } catch (t: Throwable) {
                Diagnostics.log("cam", "读 JPEG 缓冲失败：${t.message}")
            } finally {
                runCatching { img.close() }
            }
        }, handler)
        reader = r

        try {
            @Suppress("MissingPermission")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    activeId = id
                    resolvedZoom = ratio
                    zoom = 1f
                    createSessionLocked(previewSize)
                }

                override fun onDisconnected(cam: CameraDevice) {
                    runCatching { cam.close() }
                    device = null
                    onError?.invoke("相机被其它应用占用，请回到这里重试")
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    runCatching { cam.close() }
                    device = null
                    if (index + 1 < tries.size) {
                        Diagnostics.log("cam", "打开 $id 失败（$error），退到 ${tries[index + 1].first}")
                        openFirst(tries, index + 1)
                    } else {
                        onError?.invoke("相机打开失败（错误码 $error）")
                        Diagnostics.log("cam", "CameraDevice onError=$error id=$id")
                    }
                }
            }, handler)
        } catch (e: Throwable) {
            // 物理子相机不允许直开时，异常就出在这里 —— 立刻退到下一档，别让用户看到黑屏
            if (index + 1 < tries.size) {
                Diagnostics.log("cam", "直开 $id 被拒（${e.javaClass.simpleName}: ${e.message}），退到 ${tries[index + 1].first}")
                runCatching { reader?.close() }; reader = null
                openFirst(tries, index + 1)
            } else {
                onError?.invoke(if (e is SecurityException) "没有相机权限" else "打开相机失败：${e.message}")
                Diagnostics.log("cam", "openCamera 异常：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /** 把静态能力位一次性读干净。能力位必须查过再下发，否则 `Builder.set` 会抛 IllegalArgumentException。 */
    private fun readCaps(id: String, chars: CameraCharacteristics, ratio: Float) {
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        val afs = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
        afMode = when {
            afs.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            afs.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            afs.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> null
        }
        afRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        afAutoSupported = afs.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
        aeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false

        // CONTROL_AE_COMPENSATION_RANGE 的单位**就是步数**，EV 值 = 步数 × STEP。
        // 早先的写法把范围又除了一次步长，结果 ±2 步被当成 ±2EV（实际只有 ±0.67EV）。
        val evR = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 1f
        if (evStep <= 0f) evStep = 1f
        evRange = if (evR != null) evR.lower..evR.upper else 0..0

        val zr = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }.getOrNull()
        } else null
        zoomRatioSupported = zr != null
        maxZoom = zr?.upper?.coerceIn(1f, 12f) ?: 8f

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val captureSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty().sortedByDescending { it.width.toLong() * it.height }
        val previewSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
        val f = lenses.firstOrNull { it.id == id }
        caps = CamCaps(
            id = id, facing = facing, sensorOrientation = sensorOrientation,
            focalMm = f?.focalMm ?: 0f, equivMm = f?.equivMm ?: 0f, aperture = f?.aperture ?: 0f,
            hasFlash = flashAvailable, evRange = evRange, evStep = evStep, maxZoom = maxZoom,
            zoomRatioSupported = zoomRatioSupported, afModes = afs,
            maxAfRegions = afRegions, maxAeRegions = aeRegions,
            previewSizes = previewSizes, captureSizes = captureSizes,
            aspectRatios = CamOptics.aspectsOf(captureSizes),
        )
        Diagnostics.log("cam", "相机 $id：等效 ${f?.equivMm?.toInt() ?: 0}mm，变焦 $ratio×，" +
            "EV ${evRange.first}..${evRange.last}，flash=$flashAvailable，" +
            "ratioZoom=$zoomRatioSupported，出图 ${captureSizes.size} 档")
    }

    private fun createSessionLocked(preview: Size) {
        val dev = device ?: return
        val pv = previewSurface ?: return
        val rd = reader ?: return
        try {
            dev.createCaptureSession(listOf(pv, rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(pv)
                        afMode?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                        set(CaptureRequest.CONTROL_AE_MODE, when (flashMode) {
                            FLASH_ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                            FLASH_AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                            else -> CaptureRequest.CONTROL_AE_MODE_ON
                        })
                        if (flashAvailable && flashMode == FLASH_TORCH) {
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        }
                        applyZoom(this)
                        applyEv(this)
                    }
                    applyRepeating()
                    onStarted?.invoke(preview)
                    Diagnostics.log("cam", "预览已开始 ${preview.width}×${preview.height}，" +
                        "拍照 ${captureSize.width}×${captureSize.height}，传感器 ${sensorOrientation}°，" +
                        "屏幕 ${displayRotationDegrees()}°")
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError?.invoke("相机初始化失败（会话配置被拒）")
                    Diagnostics.log("cam", "createCaptureSession onConfigureFailed")
                }
            }, handler)
        } catch (e: Throwable) {
            onError?.invoke("相机初始化失败：${e.message}")
            Diagnostics.log("cam", "createCaptureSession 异常：${e.message}")
        }
    }

    private fun applyRepeating() {
        val s = session ?: return
        val b = request ?: return
        runCatching { s.setRepeatingRequest(b.build(), null, handler) }
            .onFailure { Diagnostics.log("cam", "预览请求失败：${it.message}") }
    }

    // ------------------------------------------------------------ 控制

    /**
     * 变焦。有 `CONTROL_ZOOM_RATIO` 就用它（HAL 会在多摄之间自动选镜，画质最好），
     * 没有就退到裁切传感器区域（所有机型都有的通用做法）。
     */
    fun setZoom(ratio: Float) {
        zoom = ratio.coerceIn(1f, maxZoom)
        handler.post {
            val b = request ?: return@post
            applyZoom(b)
            applyRepeating()
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        val total = zoom * resolvedZoom
        if (Build.VERSION.SDK_INT >= 30 && zoomRatioSupported) {
            runCatching { b.set(CaptureRequest.CONTROL_ZOOM_RATIO, total.coerceAtLeast(1f)) }
            // 设了 ratio 就别同时设 crop region：两者一起给时行为由 HAL 决定，容易互相打脸
            return
        }
        val r = activeRect
        if (total <= 1.001f) {
            b.set(CaptureRequest.SCALER_CROP_REGION, r)
            return
        }
        val w = (r.width() / total).toInt().coerceAtLeast(16)
        val h = (r.height() / total).toInt().coerceAtLeast(16)
        val cx = r.centerX(); val cy = r.centerY()
        val left = (cx - w / 2).coerceIn(r.left, r.right - w)
        val top = (cy - h / 2).coerceIn(r.top, r.bottom - h)
        b.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + w, top + h))
    }

    /** 曝光补偿（整数档，界面按 [CamCaps.evRange] 给范围）。 */
    fun setEv(value: Int) {
        ev = value.coerceIn(evRange.first, evRange.last)
        handler.post {
            val b = request ?: return@post
            applyEv(b)
            applyRepeating()
        }
    }

    private fun applyEv(b: CaptureRequest.Builder) {
        if (evRange.first == evRange.last) return
        runCatching { b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev) }
    }

    fun setFlash(mode: Int) {
        flashMode = mode
        handler.post {
            val b = request ?: return@post
            b.set(CaptureRequest.CONTROL_AE_MODE, when (mode) {
                FLASH_ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                FLASH_AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                else -> CaptureRequest.CONTROL_AE_MODE_ON
            })
            if (flashAvailable) {
                // 常亮（手电筒）走 TORCH；拍照用 SINGLE 由 AE_MODE 决定
                b.set(CaptureRequest.FLASH_MODE, when (mode) {
                    FLASH_TORCH -> CaptureRequest.FLASH_MODE_TORCH
                    else -> CaptureRequest.FLASH_MODE_OFF
                })
            }
            applyRepeating()
        }
    }

    fun setJpegQuality(q: Int) {
        jpegQuality = q.coerceIn(70, 100)
    }

    /**
     * 点按对焦：把测光/对焦区域挪到点击处，再触发一次 AF。
     *
     * 三段都要先看能力位，缺一段就退一档：
     *  · 相机根本不支持区域测光（前摄、超广角很常见）→ 什么都不做，别假装对焦了；
     *  · 支持区域但不支持单次 AF → 只挪测光区域，对焦仍交给连续对焦；
     *  · 都支持 → 挪区域 + 触发一次 AF，完成后回到连续对焦。
     */
    fun focusAt(nx: Float, ny: Float) {
        lockAeAf(locked = false)
        if (afRegions <= 0 && aeRegions <= 0) return
        handler.post {
            val s = session ?: return@post
            val dev = device ?: return@post
            val pv = previewSurface ?: return@post
            val rect = activeRect
            val cx = rect.left + (nx.coerceIn(0f, 1f) * rect.width()).toInt()
            val cy = rect.top + (ny.coerceIn(0f, 1f) * rect.height()).toInt()
            val half = (minOf(rect.width(), rect.height()) * 0.08f).toInt().coerceAtLeast(20)
            val area = Rect(
                (cx - half).coerceIn(rect.left, rect.right - 1),
                (cy - half).coerceIn(rect.top, rect.bottom - 1),
                (cx + half).coerceIn(rect.left + 1, rect.right),
                (cy + half).coerceIn(rect.top + 1, rect.bottom),
            )
            val mr = MeteringRectangle(area, MeteringRectangle.METERING_WEIGHT_MAX)
            try {
                val r = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(pv)
                    if (afRegions > 0) set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(mr))
                    if (aeRegions > 0) set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(mr))
                    if (afAutoSupported) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    }
                    applyZoom(this)
                }
                s.capture(r.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s2: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult,
                    ) {
                        // 单次 AF 只跑一轮，触发后必须回连续对焦，否则之后的画面会一直虚着
                        request?.let {
                            afMode?.let { m -> it.set(CaptureRequest.CONTROL_AF_MODE, m) }
                            it.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                            runCatching { s2.setRepeatingRequest(it.build(), null, handler) }
                        }
                    }
                }, handler)
            } catch (t: Throwable) {
                Diagnostics.log("cam", "focusAt 失败：${t.message}")
            }
        }
    }

    /** AE/AF 锁定。锁定后曝光与对焦停在当前值，逆光构图时不会因为移动画面而漂。 */
    fun lockAeAf(locked: Boolean) {
        aeLocked = locked
        handler.post {
            val b = request ?: return@post
            runCatching { b.set(CaptureRequest.CONTROL_AE_LOCK, locked) }
            runCatching { b.set(CaptureRequest.CONTROL_AWB_LOCK, locked) }
            if (locked) {
                // 锁 AE 时把 AF 也停住：否则半按构图里"对焦还在追"，锁定就只是半个锁
                if (afAutoSupported) runCatching { b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO) }
            } else {
                afMode?.let { runCatching { b.set(CaptureRequest.CONTROL_AF_MODE, it) } }
            }
            applyRepeating()
        }
    }

    val isAeLocked: Boolean get() = aeLocked

    fun takePicture() {
        handler.post {
            val s = session ?: run { onError?.invoke("相机还没准备好"); return@post }
            val dev = device ?: return@post
            val rd = reader ?: return@post
            val pv = previewSurface ?: return@post
            try {
                val r = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(rd.surface)
                    addTarget(pv)
                    afMode?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                    set(CaptureRequest.CONTROL_AE_MODE, when (flashMode) {
                        FLASH_ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                        FLASH_AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                        else -> CaptureRequest.CONTROL_AE_MODE_ON
                    })
                    if (flashMode == FLASH_ON && flashAvailable) {
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    }
                    // JPEG 里写入方向：系统相册、我们的解码器、第三方 App 都能对上
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                    if (aeLocked) set(CaptureRequest.CONTROL_AE_LOCK, true)
                    applyZoom(this)
                    applyEv(this)
                }
                s.capture(r.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        s2: CameraCaptureSession, req: CaptureRequest, f: android.hardware.camera2.CaptureFailure,
                    ) {
                        Diagnostics.log("cam", "拍照失败 reason=${f.reason}")
                        onError?.invoke("拍照失败（${f.reason}）")
                    }
                }, handler)
            } catch (e: Throwable) {
                Diagnostics.log("cam", "capture 异常：${e.message}")
                onError?.invoke("拍照失败：${e.message}")
            }
        }
    }

    private fun jpegOrientation(): Int {
        val d = displayRotationDegrees()
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) (sensorOrientation + d) % 360
        else (sensorOrientation - d + 360) % 360
    }

    fun stop() {
        handler.post { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { reader?.close() }; reader = null
        request = null
        activeId = null
    }

    /** 页面退出时调用：关相机并结束线程（不调用会留下一个空转的 HandlerThread）。 */
    fun release() {
        handler.post {
            closeLocked()
            thread.quitSafely()
        }
    }
}
