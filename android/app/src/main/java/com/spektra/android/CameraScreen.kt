@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.spektra.android

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 机内相机（r69 重写）。
 *
 * ## 为什么不跳系统相机
 *
 * 系统的相机 Activity 拍完只交回一张 JPEG，中间那一段（取景、构图、什么时候按）
 * 全在别人手里，胶片的选择也无从谈起。这里要的是：**取景器里看到的就是这卷胶片**，
 * 拍下来直接进冲洗台。所以自己开 Camera2、自己画。
 *
 * ## 界面为什么长这样
 *
 * 参照的是系统相机与专业相机 App 的共同骨架，而不是一张"能按快门"的排版：
 *
 *  · 顶栏只放**改变拍摄结果**的东西：闪光、定时、网格、更多。前后摄切换放在取景器
 *    右上角 —— 它是"换一台相机"，和调参数不是一类操作；
 *  · 取景器上有：三分/九宫网格、水平仪、对焦点、AE/AF 锁、变焦读数；
 *  · 底部是**四层**而不是一层：状态条（比例/像素/格式/EV）→ 变焦档位 → 快门行 →
 *    最下面的胶片入口。真实相机的取景器上永远能看到"现在是什么设置"；
 *  · 胶片不再拉一条横向长条（28 卷要滑十屏）：右下角一个滤镜键，点开是分类面板。
 *
 * ## 预览为什么能流畅
 *
 * 逐帧调引擎是不可能的（一帧几十到上百毫秒）。这里引擎只负责把 33³ 的色立方烘成一张
 * 查找表（每卷胶片/每组参数烘一次，之后读缓存），取景器每帧只做"查表 + 加质感"，
 * 见 [CamLut] 与 [CamGlView]。开销与帧率无关。
 */
@Composable
fun CameraScreen(
    engine: SpektraEngine?,
    film: String,
    print: String,
    onFilmChange: (String) -> Unit,
    onPrintChange: (String) -> Unit,
    onUse: (Uri) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val view = LocalView.current

    // ---------------------------------------------------------------- 权限
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (!ok) Diagnostics.log("cam", "相机权限被拒绝")
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    // 构图时屏幕自己黑掉是最恼人的那种失效
    DisposableEffect(Unit) {
        val w = (view.context as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val controller = remember { CamController(ctx) }
    DisposableEffect(Unit) { onDispose { controller.release() } }

    val gl = remember { CamGlView(ctx) }

    val hasFront = remember { controller.hasLens(CameraCharacteristics.LENS_FACING_FRONT) }
    val hasBack = remember { controller.hasLens(CameraCharacteristics.LENS_FACING_BACK) }

    // ---------------------------------------------------------------- 设置
    val cfg = remember { CamCfg.load(ctx) }
    // 任何一项变了就落盘（snapshotFlow 只在真的变化时触发，拖滑杆不会写爆）
    LaunchedEffect(cfg) {
        snapshotFlow { cfg.persistKey() }.distinctUntilChanged().collect { cfg.save(ctx) }
    }

    var surface by remember { mutableStateOf<Surface?>(null) }
    // 只有前摄的设备（少数平板/车机）不能默认后摄，否则一路都打不开
    var facing by remember {
        mutableIntStateOf(
            if (hasBack) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        )
    }
    val anchors = remember(facing) { controller.anchorsOf(facing) }
    // 默认落在 1× 那一档（最接近 26mm 等效）
    var anchorIdx by remember(facing) {
        mutableIntStateOf(
            anchors.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(anchors[it].ratio.toDouble())) } ?: 0
        )
    }
    val lens: Lens? = anchors.getOrNull(anchorIdx)?.lens
        ?: controller.lensesOf(facing).firstOrNull()

    var caps by remember { mutableStateOf<CamController.CamCaps?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var ev by remember { mutableIntStateOf(0) }
    var flash by remember { mutableIntStateOf(CamController.FLASH_OFF) }
    var hasFlash by remember { mutableStateOf(false) }
    var aeLocked by remember { mutableStateOf(false) }

    var shots by remember { mutableStateOf<List<Shot>>(emptyList()) }
    var reviewAt by remember { mutableStateOf<Int?>(null) }
    var panel by remember { mutableStateOf(Panel.None) }
    var countdown by remember { mutableIntStateOf(0) }
    var baking by remember { mutableStateOf(false) }
    var lutNote by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var flashFx by remember { mutableFloatStateOf(0f) }
    var focusFx by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var evOpen by remember { mutableStateOf(false) }
    var swatches by remember { mutableStateOf<Map<String, IntArray>>(emptyMap()) }
    // 色卡兜底：恒等表的灰阶，只建一次（143KB 的数组，别每次重组都造一个）
    val neutralSwatch = remember { CamLut.swatch(CamLut.identity()) }

    // ---------------------------------------------------------------- Surface
    // GL 的上下文是挂上窗口之后才建的，所以 Surface 只能等。两条路都留着：
    // 回调（正常情况） + 轮询兜底 —— 回调有可能在 onSurfaceReady 被赋值之前就发了。
    DisposableEffect(gl) {
        gl.onSurfaceReady = { s -> gl.post { surface = s } }
        onDispose { gl.onSurfaceReady = null }
    }
    LaunchedEffect(gl) {
        var waited = 0
        while (surface == null && waited < 4000) {
            gl.previewSurface()?.let { surface = it }
            if (surface == null) { delay(40); waited += 40 }
        }
        if (surface == null) {
            err = "取景器没有启动（GL 上下文创建失败）"
            Diagnostics.log("cam", "等不到渲染 Surface，GL 上下文可能没建立")
        }
    }

    // ---------------------------------------------------------------- 胶片表
    LaunchedEffect(film, print, cfg.look) {
        baking = true
        lutNote = ""
        val cached = withContext(Dispatchers.IO) { CamLut.cached(ctx, film, print, cfg.look) }
        if (cached != null) {
            gl.setLut(cached)
            baking = false
            return@LaunchedEffect
        }
        // 烘焙期间先用恒等表：不做这一步的话取景器会先显示"上一卷"的颜色，比原色更误导
        gl.setLut(CamLut.identity())
        val built = withContext(Dispatchers.IO) { CamLut.obtain(ctx, engine, film, print, cfg.look) }
        if (built != null) {
            gl.setLut(built)
            val key = "$film|$print"
            if (!swatches.containsKey(key)) swatches = swatches + (key to CamLut.swatch(built))
        } else {
            gl.setLut(CamLut.identity())
            lutNote = "这卷暂时只能看到相机原色"
            Diagnostics.log("cam", "LUT 不可用（$film/$print），取景器退回原色")
        }
        baking = false
    }

    // ---------------------------------------------------------------- 画质
    LaunchedEffect(cfg.quality) { controller.setJpegQuality(cfg.quality) }
    LaunchedEffect(cfg.mix, cfg.grain, cfg.halation, cfg.glow, cfg.vignette) {
        gl.setLook(cfg.mix, cfg.grain, cfg.halation, cfg.glow, cfg.vignette)
    }

    // ---------------------------------------------------------------- 相机生命周期
    fun applyOrientation() {
        gl.setOrientation(controller.previewRotation(facing), facing == CameraCharacteristics.LENS_FACING_FRONT && cfg.mirrorFront)
    }

    LaunchedEffect(surface, lens, cfg.aspect, cfg.maxMp, granted) {
        val s = surface
        val l = lens
        if (!granted || s == null) return@LaunchedEffect
        if (l == null) {
            err = "没有发现可用的镜头"
            Diagnostics.log("cam", "镜头列表为空（lenses=${controller.lenses.size}）")
            return@LaunchedEffect
        }
        err = null
        // maxMp = 0 表示"这台相机能给多大就给多大"
        controller.start(s, l, cfg.aspect, if (cfg.maxMp <= 0) Int.MAX_VALUE else cfg.maxMp)
        applyOrientation()
    }

    // 转屏时窗口不会重建（Activity 声明了 configChanges），方向要自己重算
    LaunchedEffect(config.orientation, facing, cfg.mirrorFront, cfg.orientOverride) {
        if (cfg.orientOverride >= 0) {
            gl.setManual(cfg.orientOverride, facing == CameraCharacteristics.LENS_FACING_FRONT && cfg.mirrorFront)
        } else {
            applyOrientation()
        }
    }

    DisposableEffect(controller) {
        controller.onError = { msg ->
            gl.post { err = msg }
            Diagnostics.log("cam", "错误：$msg")
        }
        controller.onStarted = { size ->
            gl.post {
                gl.setPreviewSize(size.width, size.height)
                if (cfg.orientOverride >= 0) {
                    gl.setManual(cfg.orientOverride, facing == CameraCharacteristics.LENS_FACING_FRONT && cfg.mirrorFront)
                } else {
                    applyOrientation()
                }
                caps = controller.caps
                hasFlash = controller.hasFlash()
            }
        }
        controller.onJpeg = { bytes ->
            scope.launch(Dispatchers.IO) {
                val shot = saveShot(ctx, bytes, cfg.heif, cfg.saveGallery)
                if (shot == null) {
                    gl.post { err = "原片写入失败（存储空间不足？）" }
                } else {
                    gl.post {
                        shots = shots + shot
                        flashFx = 1f
                    }
                }
            }
        }
        onDispose {
            controller.onError = null
            controller.onStarted = null
            controller.onJpeg = null
        }
    }

    // 白闪衰减：拍下那一刻闪一下，比"什么都不发生"更明确（快门声也有）
    LaunchedEffect(flashFx) {
        if (flashFx > 0f) { delay(90); flashFx = 0f }
    }

    fun shoot() {
        if (!granted || countdown > 0) return
        if (cfg.timer > 0) {
            scope.launch {
                countdown = cfg.timer
                Haptics.tick(ctx)
                while (countdown > 0) { delay(1000); countdown -= 1 }
                Haptics.detent(ctx)
                runCatching { ShutterSound.play(ctx) }
                controller.takePicture()
            }
            return
        }
        Haptics.detent(ctx)
        runCatching { ShutterSound.play(ctx) }
        controller.takePicture()
    }

    // ---------------------------------------------------------------- 权限页
    if (!granted) {
        Box(Modifier.fillMaxSize().background(Dv.root), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(36.dp)) {
                GlyphIcon(Glyph.Camera, Dv.label2, 40.dp)
                Spacer(Modifier.height(18.dp))
                Text("需要相机权限", color = Dv.paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("取景、对焦、拍摄都在应用内完成，画面不会离开这台设备。",
                    color = Dv.label3, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                Box(Modifier.clip(RoundedCornerShape(Dv.rBtn)).background(Dv.accent)
                    .iosTap { permLauncher.launch(Manifest.permission.CAMERA) }
                    .padding(horizontal = 22.dp, vertical = 11.dp)) {
                    Text("授权并打开相机", color = Dv.root, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.iosTap { onClose() }.padding(10.dp)) {
                    Text("先不用", color = Dv.label3, fontSize = 12.sp)
                }
            }
        }
        return
    }

    // ---------------------------------------------------------------- 回看
    val reviewing = reviewAt
    if (reviewing != null) {
        ShotReview(
            shots = shots,
            startAt = reviewing.coerceIn(0, (shots.size - 1).coerceAtLeast(0)),
            onBack = { reviewAt = null },
            onUse = { shot -> onUse(shot.uri) },
            onDelete = { shot ->
                scope.launch(Dispatchers.IO) {
                    runCatching { deleteShot(ctx, shot) }
                    gl.post {
                        shots = shots.filterNot { it.uri == shot.uri }
                        if (shots.isEmpty()) reviewAt = null else reviewAt = 0
                    }
                }
            },
        )
        return
    }

    // ---------------------------------------------------------------- 取景器
    // 铺满的页面必须自己接住触摸：漏下去的话，在取景器上捏合会同时缩放背后那张照片
    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        AndroidView(
            factory = { gl },
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { off ->
                            // 画面矩形由着色器每帧算出（含黑边），点按要按它换算，否则对焦点会偏
                            val (hx, hy) = gl.renderer.imageHalf()
                            val nx = off.x / size.width * 2f - 1f
                            val ny = 1f - off.y / size.height * 2f
                            val px = nx / hx
                            val py = ny / hy
                            if (kotlin.math.abs(px) > 1f || kotlin.math.abs(py) > 1f) return@detectTapGestures
                            // 屏幕坐标 → 图像坐标（与着色器同一套旋转），这样对焦点才落在手指上
                            val deg = gl.renderer.appliedDeg()
                            val rad = Math.toRadians(deg.toDouble())
                            val cs = Math.cos(rad).toFloat(); val sn = Math.sin(rad).toFloat()
                            val qx = cs * px - sn * py
                            val qy = sn * px + cs * py
                            controller.focusAt((qx + 1f) / 2f, (1f - qy) / 2f)
                            focusFx = (off.x / size.width) to (off.y / size.height)
                            if (aeLocked) { aeLocked = false; controller.lockAeAf(false) }
                        },
                        onLongPress = { off ->
                            // 长按锁 AE/AF：逆光构图时最常用的一条
                            aeLocked = !aeLocked
                            controller.lockAeAf(aeLocked)
                            Haptics.detent(ctx)
                            focusFx = (off.x / size.width) to (off.y / size.height)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ ->
                        if (z != 1f) {
                            zoom = (zoom * z).coerceIn(1f, caps?.maxZoom ?: 8f)
                            controller.setZoom(zoom)
                        }
                    }
                },
        )

        if (cfg.grid > 0) GridOverlay(cfg.grid)
        if (cfg.level) LevelIndicator(Modifier.align(Alignment.Center))

        // 对焦点
        val fx = focusFx
        if (fx != null) {
            LaunchedEffect(fx, aeLocked) { delay(640); if (focusFx == fx) focusFx = null }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val px = maxWidth * fx.first
                val py = maxHeight * fx.second
                Box(
                    Modifier.offset(x = px - 27.dp, y = py - 27.dp).size(54.dp)
                        .border(1.3.dp, if (aeLocked) Dv.accent else Color.White.copy(alpha = 0.85f),
                            RoundedCornerShape(4.dp))
                )
                if (aeLocked) {
                    Box(Modifier.offset(x = px - 9.dp, y = py - 9.dp)) {
                        GlyphIcon(Glyph.Lock, Dv.accent, 18.dp)
                    }
                }
            }
        }

        // ---------------- 顶栏 ----------------
        CamTopBar(
            flash = flash,
            hasFlash = hasFlash,
            timer = cfg.timer,
            grid = cfg.grid,
            onFlash = {
                flash = when (flash) {
                    CamController.FLASH_OFF -> CamController.FLASH_AUTO
                    CamController.FLASH_AUTO -> CamController.FLASH_ON
                    CamController.FLASH_ON -> if (hasFlash) CamController.FLASH_TORCH else CamController.FLASH_OFF
                    else -> CamController.FLASH_OFF
                }
                controller.setFlash(flash)
                Haptics.tick(ctx)
            },
            onTimer = {
                cfg.timer = when (cfg.timer) { 0 -> 3; 3 -> 10; else -> 0 }
                Haptics.tick(ctx)
            },
            onGrid = {
                cfg.grid = (cfg.grid + 1) % 3
                Haptics.tick(ctx)
            },
            onMore = { panel = Panel.Settings },
            onClose = { onClose() },
            onFlip = if (hasFront && hasBack) { {
                facing = if (facing == CameraCharacteristics.LENS_FACING_BACK)
                    CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                zoom = 1f; controller.setZoom(1f)
                flash = CamController.FLASH_OFF; controller.setFlash(CamController.FLASH_OFF)
                Haptics.tick(ctx)
            } } else null,
        )

        // 变焦读数
        if (zoom > 1.02f) {
            Text(
                String.format(Locale.US, "%.1f×", zoom),
                color = Dv.paper, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 150.dp)
                    .clip(RoundedCornerShape(9.dp)).background(Color(0x6612100D))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }

        // ---------------- 底部 ----------------
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding()) {

            // EV 滑杆（点 EV 胶囊展开）
            AnimatedVisibility(visible = evOpen, enter = fadeIn(), exit = fadeOut()) {
                val range = caps?.evRange ?: (0..0)
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("曝光补偿", color = Dv.label3, fontSize = 10.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.weight(1f))
                        val step = caps?.evStep ?: (1f / 3f)
                        Mono(if (ev == 0) "±0.0 EV" else String.format(Locale.US, "%+.1f EV", ev * step),
                            Dv.accent, 11f)
                    }
                    if (range.first < range.last) {
                        CamSlider(
                            value = ev.toFloat(),
                            range = range.first.toFloat()..range.last.toFloat(),
                            steps = (range.last - range.first).coerceAtMost(12),
                            onChange = { v ->
                                val n = v.toInt()
                                if (n != ev) { ev = n; controller.setEv(n) }
                            },
                            label = null,
                        )
                    } else {
                        Text("这台相机不支持曝光补偿", color = Dv.label3, fontSize = 10.sp)
                    }
                }
            }

            // 状态条：一眼看到"现在是什么设置"
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cap = caps
                val mpText = cap?.let { c ->
                    val s = CamOptics.sizesAt(c.captureSizes, cfg.aspect).firstOrNull()
                    s?.let { CamOptics.mpLabel(it.width, it.height) } ?: "—"
                } ?: "—"
                StatusChip("${CamOptics.aspectLabel(cfg.aspect)} · $mpText") { panel = Panel.Settings }
                StatusChip(if (cfg.heif) "HEIF" else "JPEG") { panel = Panel.Settings }
                val evStep = caps?.evStep ?: (1f / 3f)
                StatusChip(if (ev == 0) "EV ±0" else String.format(Locale.US, "EV %+.1f", ev * evStep)) {
                    evOpen = !evOpen
                }
                if (baking) {
                    Text("冲洗中…", color = Dv.accent, fontSize = 9.sp, letterSpacing = 0.8.sp)
                }
                Spacer(Modifier.weight(1f))
            }

            // 变焦档位（真实镜头焦段）
            ZoomRail(
                anchors = anchors,
                current = anchorIdx,
                zoom = zoom,
                onPick = { i ->
                    anchorIdx = i
                    zoom = 1f
                    controller.setZoom(1f)
                    Haptics.tick(ctx)
                },
            )

            // 快门行
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumThumb(
                    shot = shots.lastOrNull(),
                    count = shots.size,
                    onClick = { if (shots.isNotEmpty()) reviewAt = shots.size - 1 },
                )
                Spacer(Modifier.weight(1f))
                ShutterButton(enabled = countdown == 0, onClick = { shoot() })
                Spacer(Modifier.weight(1f))
                FilterButton(
                    filmLabel = FILM_VARIANTS.firstOrNull { it.id == film }?.label ?: film,
                    swatch = swatches["$film|$print"] ?: neutralSwatch,
                    onClick = { panel = Panel.Film },
                    onLongClick = { panel = Panel.Adjust },
                )
            }
            Text(
                if (lutNote.isEmpty()) "长按滤镜键可调冲洗参数 · 取景器为同参数预览"
                else lutNote,
                color = Dv.label3, fontSize = 9.sp, letterSpacing = 0.5.sp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                textAlign = TextAlign.Center,
            )
        }

        // 定时倒数
        if (countdown > 0) {
            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0x9912100D)), contentAlignment = Alignment.Center) {
                    Text("$countdown", color = Dv.accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 拍摄白闪
        if (flashFx > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashFx * 0.7f)))

        // 错误条
        err?.let { msg ->
            Box(Modifier.align(Alignment.Center).padding(28.dp)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xE633120F))
                .border(1.dp, Dv.red.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(msg, color = Dv.red, fontSize = 11.5.sp)
            }
            LaunchedEffect(msg) { delay(2600); if (err == msg) err = null }
        }

        // ---------------- 面板 ----------------
        CamSheet(visible = panel == Panel.Film, onClose = { panel = Panel.None }) {
            FilmSheet(
                title = "胶片",
                groups = FILM_GROUPS,
                current = film,
                print = print,
                swatches = swatches,
                onPick = { onFilmChange(it) },
                onClose = { panel = Panel.None },
            )
        }
        CamSheet(visible = panel == Panel.Print, onClose = { panel = Panel.None }) {
            FilmSheet(
                title = "相纸",
                groups = PRINT_GROUPS,
                current = print,
                print = print,
                swatches = emptyMap(),
                onPick = { onPrintChange(it) },
                onClose = { panel = Panel.None },
            )
        }
        CamSheet(visible = panel == Panel.Adjust, onClose = { panel = Panel.None }) {
            AdjustSheet(
                cfg = cfg,
                onClose = { panel = Panel.None },
            )
        }
        CamSheet(visible = panel == Panel.Settings, onClose = { panel = Panel.None }) {
            SettingsSheet(
                cfg = cfg,
                caps = caps,
                lenses = controller.lensesOf(facing),
                currentLens = lens,
                hasFront = hasFront,
                hasBack = hasBack,
                anchorIdx = anchorIdx,
                onLensPick = { l -> anchors.indexOfFirst { it.lens.id == l.id }.let { if (it >= 0) anchorIdx = it } },
                onFilmPanel = { panel = Panel.Film },
                onPrintPanel = { panel = Panel.Print },
                filmLabel = FILM_VARIANTS.firstOrNull { it.id == film }?.label ?: film,
                printLabel = PRINT_VARIANTS.firstOrNull { it.id == print }?.label ?: print,
                onClose = { panel = Panel.None },
            )
        }
    }

    // 胶片色卡：面板打开后按顺序把还没烘过的卷烘一遍（有缓存就是读文件，很快）
    LaunchedEffect(panel) {
        if (panel != Panel.Film) return@LaunchedEffect
        for (g in FILM_GROUPS) for (v in g.variants) {
            val key = "${v.id}|$print"
            if (swatches.containsKey(key)) continue
            val lut = withContext(Dispatchers.IO) {
                CamLut.cached(ctx, v.id, print, CamLut.CamLook())
                    ?: CamLut.obtain(ctx, engine, v.id, print, CamLut.CamLook())
            }
            if (lut != null) swatches = swatches + (key to CamLut.swatch(lut))
            delay(1)
        }
    }

    DvBackHandler {
        when {
            panel != Panel.None -> panel = Panel.None
            else -> onClose()
        }
    }
}

// ================================================================ 设置模型

private enum class Panel { None, Film, Print, Settings, Adjust }

private data class Shot(val uri: Uri, val ts: Long, val heif: Boolean)

/**
 * 相机的持久设置。放在一个类里而不是十个 `remember` 的原因：
 * 落盘要一次写全，散着写迟早漏一项，用户会发现"改了网格，下次进来又没了"。
 */
private class CamCfg(
    aspect: Float, maxMp: Int, heif: Boolean, quality: Int,
    grid: Int, level: Boolean, timer: Int, saveGallery: Boolean,
    orientOverride: Int, mirrorFront: Boolean,
    look: CamLut.CamLook,
    mix: Float, grain: Float, halation: Float, glow: Float, vignette: Float,
) {
    var aspect by mutableFloatStateOf(aspect)
    var maxMp by mutableIntStateOf(maxMp)
    var heif by mutableStateOf(heif)
    var quality by mutableIntStateOf(quality)
    var grid by mutableIntStateOf(grid)
    var level by mutableStateOf(level)
    var timer by mutableIntStateOf(timer)
    var saveGallery by mutableStateOf(saveGallery)
    var orientOverride by mutableIntStateOf(orientOverride)
    var mirrorFront by mutableStateOf(mirrorFront)
    var look by mutableStateOf(look)
    var mix by mutableFloatStateOf(mix)
    var grain by mutableFloatStateOf(grain)
    var halation by mutableFloatStateOf(halation)
    var glow by mutableFloatStateOf(glow)
    var vignette by mutableFloatStateOf(vignette)

    fun persistKey(): String = "$aspect|$maxMp|$heif|$quality|$grid|$level|$timer|$saveGallery|" +
        "$orientOverride|$mirrorFront|${look.key()}|$mix|$grain|$halation|$glow|$vignette"

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("aspect", aspect)
            .putInt("maxMp", maxMp)
            .putBoolean("heif", heif)
            .putInt("quality", quality)
            .putInt("grid", grid)
            .putBoolean("level", level)
            .putInt("timer", timer)
            .putBoolean("saveGallery", saveGallery)
            .putInt("orient", orientOverride)
            .putBoolean("mirror", mirrorFront)
            .putString("look", look.key())
            .putFloat("mix", mix)
            .putFloat("grain", grain)
            .putFloat("halation", halation)
            .putFloat("glow", glow)
            .putFloat("vignette", vignette)
            .apply()
    }

    companion object {
        private const val PREF = "cam_prefs"

        fun load(ctx: Context): CamCfg {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            fun look(): CamLut.CamLook {
                val k = p.getString("look", null) ?: return CamLut.CamLook()
                val a = k.split("_").mapNotNull { it.toIntOrNull() }
                if (a.size != 4) return CamLut.CamLook()
                return CamLut.CamLook(
                    pushEv = a[0] / 10f, gamma = a[1] / 100f,
                    ccM = a[2] / 10f, ccY = a[3] / 10f,
                )
            }
            return CamCfg(
                aspect = p.getFloat("aspect", 4f / 3f),
                maxMp = p.getInt("maxMp", 0),
                heif = p.getBoolean("heif", false),
                quality = p.getInt("quality", 96),
                grid = p.getInt("grid", 1),
                level = p.getBoolean("level", false),
                timer = p.getInt("timer", 0),
                saveGallery = p.getBoolean("saveGallery", true),
                orientOverride = p.getInt("orient", -1),
                mirrorFront = p.getBoolean("mirror", true),
                look = look(),
                mix = p.getFloat("mix", 1f),
                grain = p.getFloat("grain", 0.022f),
                halation = p.getFloat("halation", 0.35f),
                glow = p.getFloat("glow", 0.18f),
                vignette = p.getFloat("vignette", 0.16f),
            )
        }
    }
}

// ================================================================ 顶栏

@Composable
private fun CamTopBar(
    flash: Int, hasFlash: Boolean, timer: Int, grid: Int,
    onFlash: () -> Unit, onTimer: () -> Unit, onGrid: () -> Unit,
    onMore: () -> Unit, onClose: () -> Unit, onFlip: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().systemBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DvIconButton(Glyph.Close, "关闭相机", onClose, tint = Dv.label1, glyphSize = 19.dp)
        Spacer(Modifier.weight(1f))
        if (flash != CamController.FLASH_OFF && hasFlash) {
            Text(
                when (flash) {
                    CamController.FLASH_AUTO -> "自动"
                    CamController.FLASH_ON -> "强制"
                    else -> "常亮"
                },
                color = Dv.accent, fontSize = 9.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        if (hasFlash) {
            DvIconButton(Glyph.Flash, "闪光灯", onFlash,
                tint = if (flash == CamController.FLASH_OFF) Dv.label2 else Dv.accent, glyphSize = 18.dp)
        }
        DvIconButton(Glyph.Timer, "定时拍摄", onTimer,
            tint = if (timer == 0) Dv.label2 else Dv.accent, glyphSize = 18.dp)
        DvIconButton(Glyph.Grid, "网格", onGrid,
            tint = if (grid == 0) Dv.label2 else Dv.accent, glyphSize = 18.dp)
        onFlip?.let {
            DvIconButton(Glyph.Flip, "切换镜头", it, tint = Dv.label1, glyphSize = 18.dp)
        }
        DvIconButton(Glyph.More, "更多设置", onMore, tint = Dv.label1, glyphSize = 18.dp)
    }
}

// ================================================================ 取景器装饰

/** 网格。0=关，1=三分，2=九宫（四线）。 */
@Composable
private fun GridOverlay(style: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.14f)
        val w = 0.7.dp.toPx()
        val splits = if (style == 1) listOf(1f / 3f, 2f / 3f) else listOf(0.25f, 0.5f, 0.75f)
        for (f in splits) {
            drawLine(line, Offset(size.width * f, 0f), Offset(size.width * f, size.height), w)
            drawLine(line, Offset(0f, size.height * f), Offset(size.width, size.height * f), w)
        }
        // 中心十字：没有它，取景器上很难判断是否端平
        drawLine(Color.White.copy(alpha = 0.22f), Offset(size.width / 2 - 9.dp.toPx(), size.height / 2),
            Offset(size.width / 2 + 9.dp.toPx(), size.height / 2), 1.dp.toPx())
    }
}

/**
 * 水平仪：靠重力传感器算屏幕平面内的倾角。
 *
 * 不用 `TYPE_ROTATION_VECTOR`：那个给的是姿态角，还要自己换到"当前屏幕方向"上，
 * 横竖屏切换时符号会翻。重力矢量直接就是"哪边是下"，倾角只有一行 atan2，
 * 而且和屏幕方向无关 —— 这类小工具，能少一层换算就少一类 bug。
 */
@Composable
private fun LevelIndicator(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]
                if (kotlin.math.abs(x) < 0.001f && kotlin.math.abs(y) < 0.001f) return
                roll = Math.toDegrees(kotlin.math.atan2(x.toDouble(), -y.toDouble())).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (g != null) sm.registerListener(l, g, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    val level = kotlin.math.abs(roll) < 0.7f
    Canvas(modifier.size(width = 180.dp, height = 40.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val rad = Math.toRadians(roll.toDouble())
        val dx = kotlin.math.cos(rad).toFloat()
        val dy = kotlin.math.sin(rad).toFloat()
        val len = size.width * 0.36f
        val tint = if (level) Dv.accent else Color.White.copy(alpha = 0.55f)
        val gap = if (level) 0f else 10.dp.toPx()
        drawLine(tint, Offset(cx - dx * len, cy - dy * len), Offset(cx - dx * gap, cy - dy * gap), 1.4.dp.toPx())
        drawLine(tint, Offset(cx + dx * gap, cy + dy * gap), Offset(cx + dx * len, cy + dy * len), 1.4.dp.toPx())
        if (level) drawCircle(tint, radius = 1.8.dp.toPx(), center = Offset(cx, cy))
    }
}

// ================================================================ 底部组件

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "shutter")
    Box(
        Modifier.size(76.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f }
            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color.White)
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { if (enabled) onClick() },
                )
            },
    )
}

@Composable
private fun AlbumThumb(shot: Shot?, count: Int, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, shot?.uri) {
        value = shot?.let { withContext(Dispatchers.IO) { decodeScaled(ctx, it.uri, 240) } }
    }
    Box(
        Modifier.size(52.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Dv.panelRaised)
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(9.dp))
            .iosTap(enabled = shot != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = thumb
        if (b != null) {
            Image(b.asImageBitmap(), "最近拍摄", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            GlyphIcon(Glyph.Roll, Dv.label3, 21.dp)
        }
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCC0B0A08))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text("$count", color = Dv.paper, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 右下角滤镜键：显示当前胶片的色卡与名字，长按进调整面板。 */
@Composable
private fun FilterButton(
    filmLabel: String,
    swatch: IntArray,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
                .background(Dv.panelRaised)
                .border(1.dp, Dv.accent.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
                },
            contentAlignment = Alignment.Center,
        ) {
            // 色卡：这卷片子的灰阶响应，比一个抽象的图标有用
            Box(
                Modifier.size(width = 26.dp, height = 26.dp).clip(RoundedCornerShape(7.dp))
                    .background(Brush.verticalGradient(colors = swatch.toColors()))
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            filmLabel.take(11), color = Dv.label3, fontSize = 8.sp, maxLines = 1,
            modifier = Modifier.widthIn(max = 66.dp), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x8C12100D))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .iosTap(haptic = false, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = Dv.label2, fontSize = 9.5.sp, letterSpacing = 0.4.sp, maxLines = 1)
    }
}

/** 变焦档位条：0.5× / 1× / 3× … 来自真实镜头的等效焦距比。 */
@Composable
private fun ZoomRail(anchors: List<ZoomAnchor>, current: Int, zoom: Float, onPick: (Int) -> Unit) {
    if (anchors.size <= 1) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x9912100D))
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            anchors.forEachIndexed { i, a ->
                val on = i == current
                Box(
                    Modifier.size(width = 40.dp, height = 28.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Dv.accent else Color.Transparent)
                        .iosTap(haptic = false) { onPick(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (on && zoom > 1.05f) String.format(Locale.US, "%.1f×", a.ratio * zoom)
                        else a.label,
                        color = if (on) Dv.root else Dv.label2,
                        fontSize = if (on) 11.sp else 10.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ================================================================ 面板骨架

@Composable
private fun CamSheet(visible: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }, animationSpec = spring()),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 4 }, animationSpec = spring()),
    ) {
        Box(Modifier.fillMaxSize()) {
            val src = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(interactionSource = src, indication = null) { onClose() })
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(Dv.panel)
                    .navigationBarsPadding(),
            ) {
                // 抓手
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.16f)))
                }
                content()
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String? = null, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dv.paper, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Dv.label3, fontSize = 10.sp)
        }
        DvIconButton(Glyph.Close, "收起", onClose, tint = Dv.label3, glyphSize = 15.dp, box = 34.dp)
    }
}

// ================================================================ 胶片面板

@Composable
private fun FilmSheet(
    title: String,
    groups: List<StockGroup>,
    current: String,
    print: String,
    swatches: Map<String, IntArray>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().heightIn(max = 470.dp)) {
        SheetHeader(
            title = title,
            subtitle = if (title == "胶片") "选中即刻在取景器里显影 · $print" else "相纸决定反差与色彩落点",
            onClose = onClose,
        )
        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
            for (g in groups) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp)) {
                    Label(g.name, Dv.label3, 9, 1.4f)
                }
                for (v in g.variants) {
                    val on = v.id == current
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) Dv.accentDim else Color.Transparent)
                            .iosTap(haptic = false) { onPick(v.id) }
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val sw = swatches["${v.id}|$print"]
                        Box(
                            Modifier.size(width = 40.dp, height = 26.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = sw?.toColors() ?: listOf(Dv.raisedHigh, Dv.label4)
                                    )
                                )
                                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            v.label,
                            color = if (on) Dv.accent else Dv.label1,
                            fontSize = 12.5.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (on) Text("选用中", color = Dv.accent, fontSize = 9.sp, letterSpacing = 0.8.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// ================================================================ 调整面板

@Composable
private fun AdjustSheet(cfg: CamCfg, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
        SheetHeader("冲洗参数", "这几项都进查找表 —— 改完取景器里就是成片的样子", onClose)
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 18.dp)) {

            Label("冲洗", Dv.label3, 9, 1.4f)
            CamSlider(
                label = "增感 / 减感",
                value = cfg.look.pushEv,
                range = -1f..2f,
                steps = 5,
                fmt = { if (kotlin.math.abs(it) < 0.05f) "标准" else String.format(Locale.US, "%+.1f EV", it) },
            ) { cfg.look = cfg.look.copy(pushEv = it) }
            CamSlider(
                label = "密度曲线",
                value = cfg.look.gamma,
                range = 0.85f..1.20f,
                steps = 6,
                fmt = { String.format(Locale.US, "γ %.2f", it) },
            ) { cfg.look = cfg.look.copy(gamma = it) }
            CamSlider(
                label = "CC 品红 ↔ 绿",
                value = cfg.look.ccM,
                range = -18f..18f,
                steps = 11,
                fmt = { if (kotlin.math.abs(it) < 0.2f) "中性" else String.format(Locale.US, "%+.0f M", it) },
            ) { cfg.look = cfg.look.copy(ccM = it) }
            CamSlider(
                label = "CC 黄 ↔ 蓝",
                value = cfg.look.ccY,
                range = -18f..18f,
                steps = 11,
                fmt = { if (kotlin.math.abs(it) < 0.2f) "中性" else String.format(Locale.US, "%+.0f Y", it) },
            ) { cfg.look = cfg.look.copy(ccY = it) }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label("取景质感", Dv.label3, 9, 1.4f)
                Spacer(Modifier.weight(1f))
                Box(Modifier.iosTap(haptic = false) {
                    cfg.look = CamLut.CamLook()
                    cfg.mix = 1f; cfg.grain = 0.022f; cfg.halation = 0.35f
                    cfg.glow = 0.18f; cfg.vignette = 0.16f
                }.padding(4.dp)) {
                    Text("复位", color = Dv.label3, fontSize = 10.sp)
                }
            }
            CamSlider(
                label = "胶片强度", value = cfg.mix, range = 0f..1f, steps = 10,
                fmt = { "${(it * 100).toInt()}%" },
            ) { cfg.mix = it }
            CamSlider(
                label = "颗粒", value = cfg.grain, range = 0f..0.08f, steps = 8,
                fmt = { "${(it / 0.08f * 100).toInt()}%" },
            ) { cfg.grain = it }
            CamSlider(
                label = "光晕", value = cfg.halation, range = 0f..1f, steps = 10,
                fmt = { "${(it * 100).toInt()}%" },
            ) { cfg.halation = it }
            CamSlider(
                label = "眩光", value = cfg.glow, range = 0f..1f, steps = 10,
                fmt = { "${(it * 100).toInt()}%" },
            ) { cfg.glow = it }
            CamSlider(
                label = "暗角", value = cfg.vignette, range = 0f..1f, steps = 10,
                fmt = { "${(it * 100).toInt()}%" },
            ) { cfg.vignette = it }
            Text(
                "颗粒/光晕/眩光/暗角是着色器按同一套参数做的近似 —— 真胶片里这几样是光在乳剂层里横向散射，" +
                    "没法塞进查找表。成片会由主引擎按更完整的模型重算。",
                color = Dv.label4, fontSize = 8.5.sp, lineHeight = 12.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
        }
    }
}

// ================================================================ 设置面板

@Composable
private fun SettingsSheet(
    cfg: CamCfg,
    caps: CamController.CamCaps?,
    lenses: List<Lens>,
    currentLens: Lens?,
    hasFront: Boolean,
    hasBack: Boolean,
    anchorIdx: Int,
    onLensPick: (Lens) -> Unit,
    onFilmPanel: () -> Unit,
    onPrintPanel: () -> Unit,
    filmLabel: String,
    printLabel: String,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
        SheetHeader("拍摄设置", "像素与比例来自这台机器真实支持的出图尺寸", onClose)
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 18.dp)) {

            // ---- 镜头 ----
            if (lenses.size > 1) {
                Label("镜头", Dv.label3, 9, 1.4f)
                for (l in lenses) {
                    val on = currentLens?.id == l.id
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) Dv.accentDim else Color.Transparent)
                            .iosTap(haptic = false) { onLensPick(l) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            CamOptics.zoomLabel(
                                l.equivMm / (lenses.minByOrNull { kotlin.math.abs(it.equivMm - 26f) }?.equivMm ?: 1f)
                            ),
                            color = if (on) Dv.accent else Dv.label1,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(44.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(CamOptics.lensLine(l), color = if (on) Dv.accent else Dv.label2, fontSize = 11.sp)
                            Text("焦距 ${l.focalText} · ${l.equivText}",
                                color = Dv.label3, fontSize = 9.sp)
                        }
                        if (on) Text("使用中", color = Dv.accent, fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // ---- 比例 ----
            val ratios = caps?.aspectRatios?.take(4) ?: listOf(4f / 3f, 16f / 9f, 1f)
            Label("画幅比例", Dv.label3, 9, 1.4f)
            ChipRow(ratios.map { CamOptics.aspectLabel(it) to (kotlin.math.abs(cfg.aspect - it) < 0.02f) }) { i ->
                cfg.aspect = ratios[i]
            }

            // ---- 像素 ----
            val sizes = caps?.captureSizes.orEmpty()
            val mpOptions = remember(sizes, cfg.aspect) {
                val at = CamOptics.sizesAt(sizes, cfg.aspect)
                val seen = ArrayList<Int>()
                val out = ArrayList<Int>()
                for (s in at) {
                    val mp = ((s.width.toLong() * s.height) / 1_000_000L).toInt()
                    if (seen.none { kotlin.math.abs(it - mp) <= 1 }) { seen += mp; out += mp }
                }
                out.take(4)
            }
            if (mpOptions.isNotEmpty()) {
                Label("像素", Dv.label3, 9, 1.4f)
                ChipRow(mpOptions.map { "${it}MP" to (cfg.maxMp == it || (cfg.maxMp == 0 && it == mpOptions.first())) }) { i ->
                    cfg.maxMp = mpOptions[i]
                }
            }

            // ---- 格式 ----
            Label("格式", Dv.label3, 9, 1.4f)
            val heifOk = HeifExport.available && (cfg.maxMp == 0 || cfg.maxMp <= 16)
            ChipRow(listOf("JPEG" to !cfg.heif, "HEIF" to cfg.heif)) { i ->
                cfg.heif = i == 1
            }
            CamSlider(
                label = "JPEG 质量", value = cfg.quality.toFloat(), range = 80f..100f, steps = 9,
                fmt = { cfg.quality.toInt().toString() },
            ) { cfg.quality = it.toInt() }
            Text(
                when {
                    !HeifExport.available -> "本机没有 HEVC 编码器，只能用 JPEG"
                    !heifOk -> "超过 16MP 时 HEIF 关闭：编码要先整张解码，48MP 会吃掉两百多兆内存"
                    else -> "HEIF 体积约为 JPEG 的一半，画质相同；部分老设备/老软件不认"
                },
                color = Dv.label4, fontSize = 8.5.sp, modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(Modifier.height(8.dp))
            Label("取景辅助", Dv.label3, 9, 1.4f)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("水平仪", color = Dv.label1, fontSize = 12.sp, modifier = Modifier.weight(1f))
                DvToggle(checked = cfg.level, onChange = { cfg.level = it })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("网格", color = Dv.label1, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("关", "三分", "九宫").forEachIndexed { i, s ->
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (cfg.grid == i) Dv.accent else Dv.raisedHigh)
                                .iosTap(haptic = false) { cfg.grid = i }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(s, color = if (cfg.grid == i) Dv.root else Dv.label2, fontSize = 10.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("定时", color = Dv.label1, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 3, 10).forEach { t ->
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (cfg.timer == t) Dv.accent else Dv.raisedHigh)
                                .iosTap(haptic = false) { cfg.timer = t }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(if (t == 0) "关" else "${t}s",
                                color = if (cfg.timer == t) Dv.root else Dv.label2, fontSize = 10.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保存到系统相册", color = Dv.label1, fontSize = 12.sp)
                    Text("关掉则只留在应用内，冲洗完再决定", color = Dv.label4, fontSize = 8.5.sp)
                }
                DvToggle(checked = cfg.saveGallery, onChange = { cfg.saveGallery = it })
            }

            Spacer(Modifier.height(8.dp))
            Label("取景方向", Dv.label3, 9, 1.4f)
            Text(
                "自动档会扣掉 HAL 自己在纹理里已经转过的角度。万一画面还是躺着的，在这里定一档。",
                color = Dv.label4, fontSize = 8.5.sp, lineHeight = 12.sp,
            )
            ChipRow(
                listOf("-1", "0", "90", "180", "270").map { s ->
                    val v = s.toInt()
                    (if (v < 0) "自动" else "$v°") to (cfg.orientOverride == v)
                }
            ) { i -> cfg.orientOverride = listOf(-1, 0, 90, 180, 270)[i] }
            if (hasFront) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("前置镜像", color = Dv.label1, fontSize = 12.sp)
                        Text("只影响取景，存下来的照片不镜像", color = Dv.label4, fontSize = 8.5.sp)
                    }
                    DvToggle(checked = cfg.mirrorFront, onChange = { cfg.mirrorFront = it })
                }
            }

            Spacer(Modifier.height(8.dp))
            Label("胶片 / 相纸", Dv.label3, 9, 1.4f)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetRowButton(filmLabel, Modifier.weight(1f), onFilmPanel)
                SheetRowButton(printLabel, Modifier.weight(1f), onPrintPanel)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SheetRowButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(Dv.rChip)).background(Dv.panelRaised)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(Dv.rChip))
            .iosTap(haptic = true, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(text, color = Dv.label1, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ChipRow(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, (txt, on) ->
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (on) Dv.accent else Dv.raisedHigh)
                    .border(1.dp, if (on) Color.Transparent else Color.White.copy(alpha = 0.07f),
                        RoundedCornerShape(8.dp))
                    .iosTap(haptic = false) { onPick(i) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(txt, color = if (on) Dv.root else Dv.label2, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

// ================================================================ 滑杆

/**
 * 相机面板用的极简滑杆。
 *
 * 不用 Material Slider：它的轨道厚度、拇指阴影、停顿点都和这块暗色界面不合，
 * 而且在取景器上拖动时 Material 的水波纹会跟着手指跑。
 */
@Composable
private fun CamSlider(
    label: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    fmt: ((Float) -> String)? = null,
    onChange: (Float) -> Unit,
) {
    val span = (range.endInclusive - range.start)
    val step = if (steps > 0) span / (steps + 1) else 0f
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        if (label != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Dv.label2, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Mono(fmt?.invoke(value) ?: String.format(Locale.US, "%.2f", value), Dv.accent, 10f)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(24.dp).pointerInput(range, steps) {
                fun emit(x: Float) {
                    val f = (x / size.width.toFloat()).coerceIn(0f, 1f)
                    var v = range.start + f * span
                    if (step > 0f) v = range.start + (Math.round((v - range.start) / step) * step)
                    onChange(v.coerceIn(range.start, range.endInclusive))
                }
                detectTapGestures { emit(it.x) }
            }.pointerInput(range, steps) {
                detectHorizontalDragGestures { change, _ ->
                    val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    var v = range.start + f * span
                    if (step > 0f) v = range.start + (Math.round((v - range.start) / step) * step)
                    onChange(v.coerceIn(range.start, range.endInclusive))
                }
            }
        ) {
            val cy = size.height / 2f
            val t = ((value - range.start) / span).coerceIn(0f, 1f)
            val knobX = size.width * t
            drawLine(Dv.raisedHigh, Offset(0f, cy), Offset(size.width, cy), 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Dv.accent, Offset(0f, cy), Offset(knobX, cy), 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(Dv.accent, radius = 7.dp.toPx(), center = Offset(knobX, cy))
            drawCircle(Dv.panel, radius = 3.dp.toPx(), center = Offset(knobX, cy))
        }
    }
}

// ================================================================ 回看

@Composable
private fun ShotReview(
    shots: List<Shot>,
    startAt: Int,
    onBack: () -> Unit,
    onUse: (Shot) -> Unit,
    onDelete: (Shot) -> Unit,
) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = startAt) { shots.size.coerceAtLeast(1) }
    val cur = shots.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val s = shots.getOrNull(page)
            val bmp by produceState<Bitmap?>(null, s?.uri) {
                value = s?.let { withContext(Dispatchers.IO) { decodeScaled(ctx, it.uri, 1600) } }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), "原片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else CircularProgressIndicator(color = Dv.accent)
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().systemBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DvIconButton(Glyph.Close, "返回相机", { onBack() }, tint = Dv.label1, glyphSize = 19.dp)
            Spacer(Modifier.weight(1f))
            Text("${pager.currentPage + 1} / ${shots.size}", color = Dv.label2, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(40.dp))
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.height(42.dp).clip(RoundedCornerShape(Dv.rBtn))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(Dv.rBtn))
                    .iosTap { cur?.let { onDelete(it) } }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("删除", color = Dv.red, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(Dv.rBtn)).background(Dv.accent)
                    .iosTap(haptic = true) { cur?.let { onUse(it) } }
                    .padding(horizontal = 26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("用这张冲洗", color = Dv.root, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp)
            }
        }

        DvBackHandler { onBack() }
    }
}

// ================================================================ 存取

/**
 * 落盘。
 *
 * 默认进系统相册（`DCIM/Deyva`，走 MediaStore）：**相机拍的东西就该在相册里**，
 * 藏在应用私有目录里，用户拿数据线连电脑都找不到。关掉这个开关才写私有目录
 * （"底片"模式：拍摄时不想污染相册，冲洗完再决定往哪存）。
 *
 * 不论哪种，返回的 Uri 都能直接当作导入源 —— 相册那条是 content://，
 * 主界面的 `decodePhotoToFloat` 走 contentResolver，两种都吃。
 */
private fun saveShot(ctx: Context, jpeg: ByteArray, heif: Boolean, toGallery: Boolean): Shot? = runCatching {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    var bytes = jpeg
    var ext = "jpg"
    var mime = "image/jpeg"
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val px = bounds.outWidth.toLong() * bounds.outHeight
    // 48MP 解成 ARGB 要两百多兆，低端机直接 OOM。格式选了 HEIF 但分辨率太高时退回 JPEG。
    if (heif && px in 1..16_000_000L) {
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        if (raw != null) {
            // HEIF 那条路是"位图 → HEVC"，EXIF 方向在解码时不会被套用，必须自己转正。
            // 不转的话，存下来是一张躺着的 HEIC —— 而 JPEG 那条路靠 EXIF 标记，看着是正的。
            val upright = uprightByExif(raw, jpeg)
            val enc = HeifExport.encode(ctx, upright, 92, "", emptyMap())
            upright.recycle()
            if (enc != null) { bytes = enc; ext = "heic"; mime = "image/heif" }
            else Diagnostics.log("cam", "HEIF 编码失败，这张按 JPEG 存")
        }
    }
    val name = "Deyva_$stamp.$ext"

    if (toGallery && Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Deyva")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            Diagnostics.log("cam", "已存入相册 DCIM/Deyva/$name ${bytes.size / 1024}KB")
            return@runCatching Shot(uri, System.currentTimeMillis(), heif && ext == "heic")
        }
    }

    // 私有目录。Android 9 及以下没有 RELATIVE_PATH，写公共目录要额外申请存储权限 ——
    // 相机为了"存一张底片"去要全盘读写权限不值得，宁可先留在应用内。
    val dir = File(ctx.filesDir, "negatives").apply { mkdirs() }
    val f = File(dir, name)
    f.writeBytes(bytes)
    Shot(Uri.fromFile(f), System.currentTimeMillis(), heif && ext == "heic")
}.getOrNull()

private fun deleteShot(ctx: Context, shot: Shot) {
    if (shot.uri.scheme == "content") ctx.contentResolver.delete(shot.uri, null, null)
    else shot.uri.path?.let { File(it).delete() }
}

/** 解码到指定长边（回看与缩略图都用它，避免把 12MP 的位图整个读进内存）。 */
private fun decodeScaled(ctx: Context, uri: Uri, edge: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = ctx.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    val deg = runCatching {
        val ex = android.media.ExifInterface(ctx.contentResolver.openInputStream(uri)!!)
        when (ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
    if (deg == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
        Matrix().apply { postRotate(deg.toFloat()) }, true)
}.getOrNull()

/** 色卡用的 IntArray(ARGB) → Compose Color 列表。 */
private fun IntArray.toColors(): List<Color> = List(size) { Color(this[it]) }

/** 按 JPEG 里的 EXIF 方向把位图转正（HEIF 重编码前必须先做这一步）。 */
private fun uprightByExif(bmp: Bitmap, jpeg: ByteArray): Bitmap {
    val o = runCatching {
        val ex = android.media.ExifInterface(java.io.ByteArrayInputStream(jpeg))
        ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
    }.getOrDefault(1)
    val m = Matrix()
    when (o) {
        6 -> m.postRotate(90f)
        3 -> m.postRotate(180f)
        8 -> m.postRotate(270f)
        2 -> m.postScale(-1f, 1f)
        4 -> { m.postRotate(180f); m.postScale(-1f, 1f) }
        5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        else -> return bmp
    }
    val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    if (out !== bmp) bmp.recycle()
    return out
}
