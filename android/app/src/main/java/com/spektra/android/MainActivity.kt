package com.spektra.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 全局渲染互斥锁：主预览渲染与缩略图渲染共享同一 Vulkan GPU，
// 必须串行执行，否则并发 setParams/renderToBitmap 会互相抢占导致 10 倍降速
//
// [v0.8.93-r3] 用**可替换**的 ReentrantLock，而不是 `synchronized(obj)`。
// 原因：原生调用一旦卡死（Vulkan 挂起），那个线程会**永久**持有 Java monitor，
// 之后每一次渲染都排在它后面等，界面就只剩一个转圈 —— 这就是用户说的
// "必须退出软件重进"。换成可替换的锁之后，超时可以换一把新锁并重建引擎，
// 卡死的那根线程只会烂在它自己那把旧锁上，不再连累后续渲染。
@Volatile private var renderGate = java.util.concurrent.locks.ReentrantLock()
// 主渲染优先标志：主渲染进行/等待时缩略图让位，避免缩略图占锁饿死主渲染(25s超时)
@Volatile private var mainRenderPending = false
// [r9] 上面这个标志什么时候被置起来的。原来看见 true 就永久让位 —— 一旦某次主渲染
// 超时被打断，那根线程还活着、finally 还没跑到，标志就一直 true，之后**所有**缩略图
// 都立刻"让位"，用户看到的就是"格子全是空白/全是同一张原片"。现在超过 20 s 的
// pending 视为陈旧（那次渲染已经被判定卡死并换过闸门了），缩略图照常渲染。
@Volatile private var mainRenderPendingAt = 0L
// 本次渲染被判定为引擎卡死（超时）：下一次渲染之前要先重建会话/引擎，
// 否则会对着一个永远不返回的 session 干等。
@Volatile private var renderWedged = false
// 分级自救用的"临时关掉"开关。用 volatile 而不是 Compose 状态，是因为它由渲染线程
// 在重试前改写、又被 buildDelta 在渲染线程立刻读回；走快照有可能读到旧值，
// 那二分结论就错了。用户重新打开对应开关时清掉。
@Volatile private var forceNoEdgeText = false
@Volatile private var forceNoOverscan = false

// [v0.8.93-r5] 引擎里**每一个**碰 Vulkan 设备的调用都要串行，不只是 setParams/render：
// `spk_open` 会向引擎共享的缓冲池要内存并建纹理，`spk_session_release` 把内存还回去。
// 缩略图那条路原来是「先 open、再抢锁」，导入是「release 旧会话 + open 新会话」也在锁外，
// 导出更是在裸线程里直接拿主会话渲染 —— 任何一条与渲染线程撞上，就是在同一个池上
// 一边分配一边提交命令。症状恰好是日志里那个样子：**同一组参数这次出图、下次卡死 25 秒**，
// 重建引擎后又能出图（新 device 新池）。所以这里把所有引擎调用都收进闸门。
private fun <T> withEngineGate(timeoutMs: Long, what: String, block: () -> T): T? {
    val gate = renderGate
    val ok = try {
        gate.tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt(); false
    }
    if (!ok) { Diagnostics.log("render", "闸门占用超过 ${timeoutMs}ms，跳过 $what"); return null }
    return try { block() } finally { gate.unlock() }
}

// [r13] fluidSpec / livelySpec 已抽到 IosUI.kt（连同 iosTap 按压反馈与 Haptics）。
// 拆两条轨的理由：默认 UI 变化要临界阻尼（不过冲），只有手势/位移动画才允许轻微回弹。

data class Variant(val id: String, val label: String)
data class StockGroup(val name: String, val variants: List<Variant>)

// 第二层=品牌/系列，第三层=具体型号。数据全部来自开源 profiles/engine/resources/profiles/*.json（28 个文件）。
val FILM_GROUPS = listOf(
    StockGroup("Kodak 彩色负片", listOf(
        Variant("kodak_portra_400", "Portra 400"),
        Variant("kodak_portra_800", "Portra 800"),
        Variant("kodak_portra_800_push1", "Portra 800 · Push 1 增感"),
        Variant("kodak_portra_800_push2", "Portra 800 · Push 2 增感"),
        Variant("kodak_portra_160", "Portra 160"),
        Variant("kodak_gold_200", "Gold 200"),
        Variant("kodak_ektar_100", "Ektar 100"),
        Variant("kodak_ultramax_400", "Ultramax 400"),
        Variant("kodak_verita_200d", "Verita 200D"),
    )),
    StockGroup("Kodak 电影卷 Vision3", listOf(
        Variant("kodak_vision3_50d", "Vision3 50D"),
        Variant("kodak_vision3_200t", "Vision3 200T"),
        Variant("kodak_vision3_250d", "Vision3 250D"),
        Variant("kodak_vision3_500t", "Vision3 500T"),
    )),
    StockGroup("Kodak 反转片", listOf(
        Variant("kodak_ektachrome_100", "Ektachrome 100"),
        Variant("kodak_kodachrome_64", "Kodachrome 64"),
    )),
    StockGroup("Fujifilm 彩色负片", listOf(
        Variant("fujifilm_pro_400h", "Pro 400H"),
        Variant("fujifilm_xtra_400", "X-Tra 400"),
        Variant("fujifilm_c200", "C200"),
    )),
    StockGroup("Fujifilm 反转片", listOf(
        Variant("fujifilm_velvia_100", "Velvia 100"),
        Variant("fujifilm_provia_100f", "Provia 100F"),
    )),
    StockGroup("CineStill 去碳层", listOf(
        Variant("cinestill_50d", "CineStill 50D"),
        Variant("cinestill_800t", "CineStill 800T"),
    )),
    StockGroup("黑白银盐胶片", listOf(
        Variant("fujifilm_neopan_acros_100_ii", "Neopan Acros 100 II"),
        Variant("ilford_hp5_plus_400", "HP5 Plus 400"),
        Variant("kodak_tmax_100", "T-Max 100"),
        Variant("kodak_tri_x_400", "Tri-X 400"),
    )),
)
val PRINT_GROUPS = listOf(
    StockGroup("Kodak Endura 相纸", listOf(
        Variant("kodak_portra_endura", "Portra Endura"),
        Variant("kodak_supra_endura", "Supra Endura"),
        Variant("kodak_ultra_endura", "Ultra Endura"),
        Variant("kodak_endura_premier", "Endura Premier"),
        Variant("kodak_ektacolor_edge", "Ektacolor Edge"),
    )),
    StockGroup("Fujifilm 相纸", listOf(
        Variant("fujifilm_crystal_archive_typeii", "Crystal Archive II"),
    )),
    StockGroup("Kodak Vision 印片", listOf(
        Variant("kodak_2383", "Vision 2383"),
        Variant("kodak_2393", "Vision Premier 2393"),
    )),
    StockGroup("黑白银盐相纸", listOf(
        Variant("ilford_multigrade_iv_rc", "Multigrade IV RC"),
    )),
)
val FILM_VARIANTS: List<Variant> = FILM_GROUPS.flatMap { it.variants }
val PRINT_VARIANTS: List<Variant> = PRINT_GROUPS.flatMap { it.variants }
val ALL_VARIANTS: List<Variant> = FILM_VARIANTS + PRINT_VARIANTS

val FORMATS = listOf("110 13×17", "APS 16.7×22", "135 24×32", "120 56×75")
val FORMAT_MM = listOf(17f, 22f, 32f, 75f)

// 常见 RAW 扩展名：命中则走引擎 LibRaw 解码，否则走 BitmapFactory 栅格解码
private val RAW_EXTS = listOf("dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srf", "x3f", "iiq", "3fr", "fff", "mef", "mos")

class MainActivity : ComponentActivity() {
    private val pendingImport = mutableStateOf<Uri?>(null)
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingImport.value = uri
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.init(applicationContext) // 持久化诊断日志：崩溃后保留上次会话日志
        setContent {
            SpektraScreen(
                onPickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                pendingImport = pendingImport,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpektraScreen(onPickPhoto: () -> Unit, pendingImport: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var engine by remember { mutableStateOf<SpektraEngine?>(null) }
    var engineError by remember { mutableStateOf<String?>(null) }

    var filmVariant by remember { mutableStateOf("kodak_portra_400") }
    var printVariant by remember { mutableStateOf("kodak_portra_endura") }
    // [r17] 胶片本色。这是"为什么 26 支胶片看着一模一样"的总开关。
    //
    // 引擎在 digest() 里读 engine/resources/neutral_print_filters.json：每张相纸 ×
    // 每种光 × **每支胶片**都有一组中性滤片（c/m/y），印相时按它把这支胶片配平到
    // 中性。这是彩扩机/暗房的做法，物理上完全正确 —— 但它的副作用是：18 支彩色负片
    // 全被各自的滤片拉回同一个中性点，印在 Portra Endura 上几乎就是同一张照片。
    // 数据能说明差多少：Fujifilm C200 需要 M70/Y84 才配平，Vision3 500T 只要 M29/Y35，
    // 差 40 CC —— 可两者印出来的平均色只差 1~2/255。
    //
    // 关（默认）：保持这套自动配平，出片与你现在看到的一模一样。
    // 开：所有胶片共用**一套**滤片（该相纸全部胶片补偿量的平均值），于是每支胶片
    //     自身的色偏重新显现 —— 这也是"同一台放大机、同一套滤片"的真实做法。
    var filmTrueColor by remember { mutableStateOf(false) }
    // 由「恢复默认参数」改写色彩模式时，不要再弹那句"原色/校色是什么"的提示 ——
    // 已经有一句"已恢复默认参数"了，两条提示会互相覆盖。
    val colorHintMute = remember { booleanArrayOf(false) }
    var formatIdx by remember { mutableIntStateOf(2) }
    var exposureEv by remember { mutableFloatStateOf(0f) }
    var halationAmount by remember { mutableFloatStateOf(1f) }
    var grainAmount by remember { mutableFloatStateOf(1f) }
    var antihalationRemoved by remember { mutableStateOf(false) }
    // 片边（overscan，RFC-032）：引擎真实片边，参数 schema 已原生支持
    var overscanActive by remember { mutableStateOf(false) }
    var overscanFormat by remember { mutableStateOf("135") }
    var overscanMode by remember { mutableStateOf("strip") }
    var overscanFog by remember { mutableFloatStateOf(1f) }
    var overscanLeaks by remember { mutableFloatStateOf(0f) }
    var overscanGate by remember { mutableStateOf("auto") }
    var overscanHoles by remember { mutableStateOf("white") }
    var overscanCarrier by remember { mutableStateOf("none") }
    // 片边上是否印字（胶片厂商的边码）。留个开关：
    // ① 有人就喜欢只有边、没有字；② 排查时可一键回到"完全不印字"的行为对照。
    var overscanEdgeText by remember { mutableStateOf(true) }
    var overscanCameraSeed by remember { mutableIntStateOf(1) }
    var overscanFrameSeed by remember { mutableIntStateOf(1) }
    // 日期后背（RFC-031）：相机在底片上烧的日期/拍摄数据。与片边同属"拍摄层"
    // （SHOOT，非 live），改一次就要重冲一次负片。
    var dateBack by remember { mutableStateOf(DateBackCfg()) }
    // 照片的拍摄日 [年, 月, 日]（EXIF DateTimeOriginal）；没有 EXIF 时用当天，
    // 与相机日期后背没设时间就跑一样。
    var photoDate by remember { mutableStateOf<IntArray?>(null) }
    // 拍摄数据行（RFC-033）：`1/250 F5.6 A +0.3 24mm`，style=data 时印这一行。
    var photoDataLine by remember { mutableStateOf("") }
    var renderTick by remember { mutableIntStateOf(0) }
    var section by remember { mutableIntStateOf(0) }   // 0=胶片 1=相纸
    var thumbReq by remember { mutableIntStateOf(0) }  // 缩略图渲染请求版本号，用于取消过期的缩略图渲染

    // [r33] previewBase 是引擎冲洗出来的原图，preview 是套过 Layer 2 之后真正显示的那张。
    //
    // 拆成两个状态不是为了整洁，是因为调色**不需要重新冲洗**：拖一下色温就重冲一遍负片
    // （几百毫秒到几秒）的话，这一整页滑杆就全是死的。有了 previewBase，调色只是拿它
    // 再跑一遍 Layer 2（十几毫秒），这才是实时。
    var previewBase by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    // [r37] 与 previewBase 配套的 16 bit 原帧。调色走 Layer2.apply16 —— 先降到 8 bit
    // 再拉曲线，斜率大于 1 的段落会空出色阶，就是"幅度稍大就断层"。渲染失败的帧这里是 null，
    // 调色会自动退回 8 bit 路径（画面正确，只是断层会回来）。
    var renderRaw by remember { mutableStateOf<Frame16?>(null) }
    // Layer 2 参数，以及曲线背后那张直方图（从**调色之后**的图算，所以它反映的是输出）
    var tone by remember { mutableStateOf(Layer2.Cfg()) }
    var toneHist by remember { mutableStateOf<Layer2.Hist?>(null) }
    var photoBmp by remember { mutableStateOf<Bitmap?>(null) }
    var photoData by remember { mutableStateOf<Triple<FloatArray, Int, Int>?>(null) }
    var thumbSrc by remember { mutableStateOf<Triple<FloatArray, Int, Int>?>(null) } // 缩略图专用小尺寸源（长边 ThumbStore.SRC_EDGE），避免每次拿全尺寸 float 渲染
    // [r38] 缩略图分两层，和主图完全同一个道理：
    //   thumbBase  = 引擎冲洗出来的原样（这也是磁盘缓存里存的那一份）；
    //   thumbCache = 套过 Layer 2 之后真正显示在格子里的那张。
    // 之前只有一层，于是"调完色看胶片条还是原色"—— 用户拿它挑胶片，看到的是错的。
    // 拆开之后调色不必重渲引擎（那要几秒），只是把已有的十几张小图重跑一遍 Layer 2。
    var thumbBase by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var thumbCache by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    // [r9] 当前照片的指纹：磁盘缩略图按它分文件名，换照片即失效，同一张照片重开 App 直接命中
    var thumbPhotoKey by remember { mutableStateOf("") }
    var session by remember { mutableLongStateOf(0L) }
    var sourceW by remember { mutableIntStateOf(0) }
    var sourceH by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    // [r30] 「关于」现在是独立面板：构建校验状态 + 两份许可证全文 + 源码入口
    var showAbout by remember { mutableStateOf(false) }
    // ===== 配方持久化（r11，第 0 批）=====
    // 原来只有一个 savedRecipe 内存字符串（写完无人读、杀进程即失），现在落盘到
    // filesDir/recipes.json，列表可应用/改名/删除。
    var recipes by remember { mutableStateOf<List<RecipeStore.Recipe>>(emptyList()) }
    var showSaveRecipe by remember { mutableStateOf(false) }
    var showRecipeList by remember { mutableStateOf(false) }
    var recipeRenameTarget by remember { mutableStateOf<RecipeStore.Recipe?>(null) }

    // ===== 暗房放大机（r11，第 1 批）=====
    // 真实暗房里，同一张底片用不同相纸、不同放大机滤色片，颜色差很多 —— 这是胶片
    // 模拟最有质感的一段。这 4 个都是 PRINT 层的 live 字段（params.cpp:36-38 的
    // 「不需要重建管线」判定），滑杆拖动不会重冲负片，只会重印相纸。
    // 默认值必须与引擎默认一致（print_exposure=1、滤片=0、预闪=0），否则一进 App
    // 就把现有出片效果改了。
    var printExposure by remember { mutableFloatStateOf(1f) }
    var yFilterShift by remember { mutableFloatStateOf(0f) }
    var mFilterShift by remember { mutableFloatStateOf(0f) }
    var preflashExposure by remember { mutableFloatStateOf(0f) }
    // ===== 相机侧（r11，第 1 批）=====
    // camera_filter 是 SHOOT 非 live：改一次会重冲负片（慢），所以 UI 用下拉，
    // 不做滑杆；默认 ""（无滤镜）与引擎一致。
    var cameraFilter by remember { mutableStateOf("") }
    var autoExposureMethod by remember { mutableStateOf("center_weighted") }
    var lensBlurUm by remember { mutableFloatStateOf(0f) }
    // ===== 胶片细节强度（r11，第 1 批）=====
    // 引擎默认 dir_couplers.active=true / glare.active=true / 各自 amount=1.0 /
    // halation.scatter_amount=1.0（params.hpp）。默认取 1.0 与原行为完全一致。
    var glareAmount by remember { mutableFloatStateOf(1f) }
    var dirCouplersAmount by remember { mutableFloatStateOf(1f) }
    var halationScatter by remember { mutableFloatStateOf(1f) }
    // ===== 负片反差（r38）=====
    //
    // 引擎里叫 density_curve_gamma：它把负片密度曲线的**横轴**（log 曝光）按 1/gamma 缩放。
    // 曲线形状不变、横轴被拉伸 —— 所以局部斜率整体除以 gamma：
    //   gamma > 1 → 更平（灰、宽容度大）；gamma < 1 → 更硬（反差大、更容易黑死或白死）。
    // 范围 0.2…4（params.cpp:59），1.0 是胶片原样。
    //
    // 之前 App 从来没下发过这一项：引擎支持 0.2–4，界面上却没有入口，是自查里唯一一个
    // 「引擎有、用户碰不到」的功能。它属 SHOOT 层，改一次要重冲负片，所以和「胶片曝光」
    // 一样只在松手后提交。
    var densityGamma by remember { mutableFloatStateOf(1f) }
    // ===== 对比度蒙版（r33，RFC-024）=====
    //
    // 这是暗房里的**遮挡（dodge）与加光（burn）**的自动化版本：引擎在印相时按画面
    // 结构分区改变曝光，让高光侧多曝几档、阴影侧少曝几档。它改的是印相曝光，
    // 不是像素 —— 所以拉高光不会连带把云也压平，这是像素曲线给不了的东西。
    //
    // 全在 PRINT 层：改一次只重印相纸，不重冲负片（见 PRINT_LAYER_KEYS）。
    // 但 scale 变会重算一遍高斯（上游实测 45MP 上 260–600ms），所以 UI 只在松手后生效。
    var cmActive by remember { mutableStateOf(false) }
    var cmHighlights by remember { mutableFloatStateOf(0f) }  // 0–3 档，高光侧提亮
    var cmShadows by remember { mutableFloatStateOf(0f) }     // 0–3 档，阴影侧压暗
    var cmCore by remember { mutableFloatStateOf(1f) }        // 0–3 档，中间调保护带半宽
    var cmScale by remember { mutableFloatStateOf(0.03f) }    // 0.002–0.12，蒙版空间尺度
    // ===== 几何：旋转 / 90°转 / 翻转 / 裁剪（r11，第 2 批）=====
    // 片边开启时全部锁住（上游 CropSection / RFC-032 §17.5：画面按片门比例装框，
    // 裁剪被片边占用）。这样可以保证绝不会破坏 r8 修好的片门画面。
    var rotationDeg by remember { mutableFloatStateOf(0f) }
    var quarterTurns by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var cropRatioIdx by remember { mutableIntStateOf(0) }   // 0 = 原始（不裁）
    // ===== 片边 / 日期补全（r11，第 3 批）=====
    var overscanFNumber by remember { mutableFloatStateOf(0f) }      // 0 = 不印光圈
    var overscanFrameNumber by remember { mutableIntStateOf(0) }     // 0 = 用随机种子（引擎语义）
    // 日期距画框边缘的距离（mm，仅 placement=frame 有效）。**默认必须等于引擎默认值**
    // 3.0 / 2.4（params.hpp:237），否则一传 0 就把日期贴到边角上，等于改变了现有出片。
    var dateInsetX by remember { mutableFloatStateOf(3f) }
    var dateInsetY by remember { mutableFloatStateOf(2.4f) }
    // ===== 输出模式（r11，第 4 批）=====
    // scan_film = 跳过相纸直接看底片（上游的 No Print Profile，带橙色 mask）。
    var scanFilm by remember { mutableStateOf(false) }
    // 导出用的源片信息：命名 token 的「原片名」，以及导出时继承 EXIF 的来源。
    // 不给源片留下引用就只能瞎编文件名、也拿不到相机/镜头/曝光 —— 这两项都在导入时记一次。
    var srcName by remember { mutableStateOf("IMG") }
    var srcUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // [r11·第0批] 上一次成功渲染用的 delta。用它判断「这次是不是只改了 PRINT 层」：
    // 是则走 spk_reprint 复用负片缓存（免重冲，切相纸从数秒降到毫秒级）。
    // 用 AtomicReference 而不是 Compose 状态：它由渲染线程写、渲染线程读，
    // 不该触发重组。
    val lastRenderedDelta = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportInfo by remember { mutableStateOf("") }
    var exportOk by remember { mutableStateOf<Boolean?>(null) }

    // 预览缩放/平移用 Animatable：手势期间 snapTo 保证 1:1 直接跟随手指，
    // 双击用弹簧动画（可随时被下一次手势打断，从当前值继续）
    val scaleAnim = remember { Animatable(1f) }
    val panAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val animScope = rememberCoroutineScope()
    // 预览区域尺寸（用于把平移限制在照片边界内，放大后不会拖出黑边/背景）
    var prevW by remember { mutableStateOf(0) }
    var prevH by remember { mutableStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }
    // 预览 HDR 开关（默认关）：开启后在预览上叠加高光恢复，直接验证 HDR 输出
    var previewHdr by remember { mutableStateOf(false) }
    var hdrPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
    // 系统"减少动态"（animator_duration_scale=0）时所有动画降级为瞬时
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0f }.getOrDefault(false)
    }

    var progress by remember { mutableStateOf(listOf(
        ProgressStep("解码"), ProgressStep("胶片"), ProgressStep("印放"), ProgressStep("扫描"), ProgressStep("存储"))) }

    // 当前照片是否 RAW（决定引擎 input_color_space：RAW→线性 ProPhoto，栅格→sRGB）
    var photoIsRaw by remember { mutableStateOf(false) }

    fun setStep(i: Int, done: Boolean, active: Boolean) {
        mainHandler.post { progress = progress.mapIndexed { j, s -> if (j == i) s.copy(done = done, active = active) else s.copy(active = false) } }
    }

    // [r9] 缩略图那一支也要的那几行"照片是什么颜色、按什么片幅处理"：和 buildDelta 的一段完全同源，
    // 否则缩略图会被引擎按默认（线性 ProPhoto）解释 sRGB 照片 —— 全部发灰，看不出各胶片的差别。
    fun inputDelta(): String {
        val inCs = if (photoIsRaw) "ProPhoto RGB" else "sRGB"
        return "\"input_color_space\":\"$inCs\",\"input_cctf_decoding\":${!photoIsRaw}," +
               "\"output_color_space\":\"sRGB\",\"film_format_mm\":${FORMAT_MM[formatIdx]},"
    }
    fun buildDelta(): String = buildString {
        append("{\"film_stock\":\"${filmVariant}\",\"print_stock\":\"${printVariant}\",")
        append("\"exposure_compensation_ev\":${exposureEv},")
        // —— 相机侧（r11 第 1 批）——
        // camera_filter 为空 = 不装滤色片（引擎认 ""）。它会重塑"照片由什么光组成"，
        // 但片边/雾/日期后背不过镜头，所以只影响画面。
        // auto_exposure_method 只影响下面 solve("exposure") 的测光结果；默认值与引擎
        // 默认一致（center_weighted），不动它时出片不变。
        append("\"camera_filter\":\"${cameraFilter}\",")
        append("\"auto_exposure_method\":\"${autoExposureMethod}\",")
        append("\"lens_blur_um\":${lensBlurUm},")
        append("\"film_format_mm\":${FORMAT_MM[formatIdx]},")
        // 色彩空间匹配：栅格照片是 sRGB 编码值(byte/255f)，引擎 input_cctf_decoding 需开，先线性化再配光，
        // 否则把编码值当线性会整体偏暗；RAW 解码输出已是线性 ProPhoto，input_cctf_decoding 保持 false。
        // input 对齐来源，output 固定 sRGB(预览 bitmap 默认 sRGB)。
        val inCs = if (photoIsRaw) "ProPhoto RGB" else "sRGB"
        append("\"input_color_space\":\"$inCs\",\"input_cctf_decoding\":${!photoIsRaw},")
        append("\"output_color_space\":\"sRGB\",")
        // 光晕完全对齐开源原版：halation_active 默认 true、boost_ev 默认 0，
        // per-stock 强度由引擎 apply_halation_preset 按 profile 的 antihalation(strong/weak) 自动设置
        append("\"halation_active\":true,\"halation_boost_ev\":0.0,")
        append("\"antihalation_removed\":$antihalationRemoved,")
        append("\"halation_amount\":$halationAmount,")
        // 去碳层胶片(CineStill: antihalation=no → back_reflection=true)会触发引擎未移植到
        // Android 的 spk_highlight_headroom kernel。headroom_ev=0 使其跳过该节点，
        // 去碳层红光晕散射其余部分不受影响。
        if (filmVariant.startsWith("cinestill_")) append("\"halation_headroom_ev\":0.0,")
        val grainOn = grainAmount > 0f
        append("\"grain_active\":$grainOn,\"grain_amount\":$grainAmount,\"grain_sublayers_active\":$grainOn")
        // —— 胶片细节强度（r11 第 1 批）——
        // 这三个在引擎里默认都开着且 amount=1.0（params.hpp：DirCouplers.active=true、
        // Glare.active=true、Halation.scatter_amount=1.0）。以前宿主一个都没下发，
        // 等于只能用默认强度；现在显式给值，默认 1.0 时与原来完全一致。
        // 注意 halation_amount 已有独立滑杆（0…4），这里是**散射**分量，不是同一项。
        append(",\"glare_active\":true,\"glare_amount\":${glareAmount},")
        append("\"dir_couplers_active\":true,\"dir_couplers_amount\":${dirCouplersAmount},")
        // [r38] 负片反差。放这儿是有讲究的：它必须和其它 film_render 参数一起下发，
        // 且**默认 1.0 时不改变任何现有出片**（引擎默认就是 1）。它是 SHOOT 层，
        // 一旦不等于 1 就会触发重冲负片 —— PRINT_LAYER_KEYS 里没有它，这正是对的。
        append("\"density_curve_gamma\":${densityGamma},")
        // 这一行**不能**以逗号结尾：紧接着的片边分支自己就以逗号开头，
        // 多一个逗号就成了 `1.0,,"overscan_active"`，引擎解析器读下一个 key 时报
        // "expected a string at byte N"，整帧渲染失败（r11 初版即此错，画面全黑）。
        append("\"halation_scatter_amount\":${halationScatter},")
        // [r33] 对比度蒙版插在这里（PRINT 层，紧挨其它印相参数）。关掉时把两个档数也
        // 一起清零：引擎在 active=false 时本来就跳过整个节点，但留着旧档数会让下次
        // 打开时"一开就跳回上次的强度"，看起来像开关自己动了。
        append("\"contrast_mask_active\":$cmActive,")
        append("\"contrast_mask_highlights\":${if (cmActive) cmHighlights else 0f},")
        append("\"contrast_mask_shadows\":${if (cmActive) cmShadows else 0f},")
        append("\"contrast_mask_core\":$cmCore,")
        append("\"contrast_mask_scale\":$cmScale")
        // （r18 起「原色」不再改写 c/m/y 中性滤片 —— digest() 会把它们改回去，
        //   偏移量已经并进上面的 m/y_filter_shift。）
        // 片边（引擎真实 overscan）：开启时把格式/模式/seed/雾/漏光/片门/齿孔/载体交给引擎
        // forceNoOverscan 只由分级自救写入：卡死重试时临时按"无片边"下发，不改用户的开关状态。
        if (overscanActive && !forceNoOverscan) {
            append(",\"overscan_active\":true,\"overscan_format\":\"$overscanFormat\",\"overscan_mode\":\"$overscanMode\",")
            append("\"overscan_camera_seed\":$overscanCameraSeed,\"overscan_frame_seed\":$overscanFrameSeed,")
            append("\"overscan_fog\":$overscanFog,\"overscan_leaks\":$overscanLeaks,")
            append("\"overscan_gate\":\"$overscanGate\",\"overscan_holes\":\"$overscanHoles\",\"overscan_carrier\":\"$overscanCarrier\",")
            // [r11 第 3 批] 光圈与帧号。
            // f_number = 0 → 不印光圈（引擎语义）；frame_number = 0 → 仍由随机种子决定
            // 帧号（overscan_port.cpp:1687「frame_number > 0 才采用」）。所以这两个默认值
            // 都是 0，不会改变现有片边的输出。
            append("\"overscan_f_number\":${overscanFNumber},")
            append("\"overscan_frame_number\":${overscanFrameNumber},")
            // [v0.8.93-r2] 片边上印什么字（RFC-032 的 edge_text）。
            // 引擎侧只认一行文字，位置/字体/字重/截断全由胶片自带的版式决定：
            // edge_look_for(stock) 分出 Kodak、Fujifilm 负片、Fujifilm 反转片、
            // Ilford、CineStill 几套印法，135 走 Helvetica 或 5×7 点阵，120 走
            // 点阵（Fuji 版式还会把 "FUJI PRO400H" 拆成厂标和型号）。所以这里
            // 唯一要做对的事是**给出该胶片在该片基上真实印的那串字**。
            // 诊断：delta 那一行太长，日志按 600 字符截断，边码文字正好被切掉。
            // 单独打一条短记录，免得"到底发没发文字"永远只能靠猜。
            val edgeTxt = if (overscanEdgeText && !forceNoEdgeText) edgeTextFor(filmVariant, edgeGauge(overscanFormat)) else ""
            Diagnostics.log("render", "EDGE fmt=$overscanFormat gauge=${edgeGauge(overscanFormat)} " +
                "text=\"$edgeTxt\" len=${edgeTxt.length} date=${dateBack.active}/${dateBack.style}/${dateBack.placement} " +
                "src=${sourceW}x$sourceH forceNoText=$forceNoEdgeText")
            append("\"overscan_edge_text\":\"$edgeTxt\"")
            // 开源要求「先裁到片门」(crop the picture to the gate first)：照片画幅必须等于所选门幅
            // 的长/短比，否则引擎拒绝。这里按所选格式自动居中裁到门幅（geometry crop 渲染链先于片边）。
            // geometry_crop 是粘性的：delta 不传会残留上一次的裁切，故每次都显式写全图或门幅裁切。
            var cw = 1.0; var ch = 1.0
            val w = sourceW.toDouble(); val h = sourceH.toDouble()
            if (w > 0 && h > 0) {
                val gate = osGateRatio(overscanFormat)
                val cur = maxOf(w, h) / minOf(w, h)
                if (kotlin.math.abs(cur / gate - 1.0) > 0.05) {
                    val (nw, nh) = if (cur > gate) {
                        val tl = minOf(w, h) * gate
                        if (w >= h) Pair(tl / w, 1.0) else Pair(1.0, tl / h)
                    } else {
                        val ts = maxOf(w, h) / gate
                        if (w >= h) Pair(1.0, ts / h) else Pair(ts / w, 1.0)
                    }
                    if (nw in 0.05..1.0 && nh in 0.05..1.0) { cw = nw; ch = nh }
                }
            }
            // [DEYVA-FIX v0.8.93] 片门画面破损的**根因**就在这里。
            // 引擎的 geometry_crop_x/y 是裁剪窗的**左上角**（nodes.metal /
            // spk_geometry_resample_df.comp 里用 (crop_x + crop_w/2) 求中心），
            // 不是中心点。原来固定写 0.5/0.5，而绝大多数照片本来就已是门幅比例、
            // 于是 crop_w=crop_h=1.0 —— 窗口变成 [0.5w, 1.5w] × [0.5h, 1.5h]：
            // 整幅画面右移半帧，右边界和下边界落到源图外，着色器把越界样本钳到
            // 最后一列/最后一行（`if (fx.x >= w1) { x0f = w1; ty = 0.0; }`），
            // 于是门内呈现：左上 1/4 真画面 + 右侧「最后一列」拉成的横条 +
            // 下方「最后一行」拉成的竖条 + 右下角纯色。
            // 居中裁剪的左上角就是 (1 - crop_w)/2、(1 - crop_h)/2；
            // crop_w/h == 1 时它恰好是 0，geometry 走 identity 分支（连节点都不跑）。
            val cx = (1.0 - cw) / 2.0
            val cy = (1.0 - ch) / 2.0
            android.util.Log.i("Deyva",
                "overscan: fmt=$overscanFormat mode=$overscanMode gate=$overscanGate holes=$overscanHoles carrier=$overscanCarrier " +
                "fog=$overscanFog leaks=$overscanLeaks src=${sourceW}x$sourceH crop=${"%.4f".format(cw)}x${"%.4f".format(ch)} " +
                "origin=${"%.4f".format(cx)},${"%.4f".format(cy)}")
            append(",\"geometry_crop_x\":$cx,\"geometry_crop_y\":$cy,\"geometry_crop_w\":$cw,\"geometry_crop_h\":$ch")
            // [r11 第 2 批] 片边锁住画框：旋转 / 转向 / 翻转全部复位为 identity。
            // 门槛比例是引擎按 crop 推出来的，插入旋转会让"先裁到片门"的前提失效 ——
            // 正是 r1 那次画面破损的同一雷区。上游 CropSection 也这么锁
            // （RFC-032 §17.5：片边开启时裁剪被占用，关掉才恢复用户的裁剪值）。
            append(",\"geometry_rotation_deg\":0.0,\"geometry_quarter_turns\":0,")
            append("\"geometry_flip_h\":false,\"geometry_flip_v\":false")
        } else {
            // [FIX 0.8.93-r1] 片边开不回去的**唯一**原因在这里。
            // 引擎的 apply_delta() 只写 delta 里**出现过**的字段（params.cpp 里
            // `for (const auto& kv : delta.fields())`），没出现的字段保持原值。
            // 所以关闭片边时若只复位 geometry_crop 而不下发 overscan_active，
            // 引擎侧的 overscan.active 仍是上一次的 true，片边就一直印在画面上
            // —— 这正是"开关拨回去没反应、必须杀进程才恢复"的成因。
            // overscan_active 是非 live 的 SHOOT 字段，传 false 会让引擎重建管线
            // （canvas 回落到源图尺寸）并丢弃负片缓存重渲，正是关掉片边该做的事。
            append(",\"overscan_active\":false,")
            // [r11 第 2 批] 片边关闭时几何交给用户：裁剪比例 + 拉直（带 refit）+ 90° 转 + 翻转。
            // computeCrop 处理了「左上角 vs 中心」这个 r1 的坑，以及拉直后的自动收缩。
            val c = computeCrop(sourceW.toDouble(), sourceH.toDouble(), cropRatioIdx, rotationDeg.toDouble())
            append("\"geometry_crop_x\":${c[0]},\"geometry_crop_y\":${c[1]},")
            append("\"geometry_crop_w\":${c[2]},\"geometry_crop_h\":${c[3]},")
            append("\"geometry_rotation_deg\":${rotationDeg},")
            append("\"geometry_quarter_turns\":${quarterTurns},")
            append("\"geometry_flip_h\":${flipH},\"geometry_flip_v\":${flipV}")
        }
        // ---- 日期后背（RFC-031）----
        // 与片边一样是粘性字段：active 必须**每帧**显式下发，否则关不掉
        // （apply_delta 只写 delta 里出现过的键）。它是 SHOOT 层，改动会重冲负片。
        append(",\"date_imprint_active\":${dateBack.active}")
        if (dateBack.active) {
            // 日期文本由宿主排好再交给引擎（引擎只画字形，不认日历）。
            // 开源默认日式：`'26 11 4` —— 两位年份带撇号、月日无前导零。
            val cal = java.util.Calendar.getInstance()
            val d0 = photoDate
            val yy = (d0?.get(0) ?: cal.get(java.util.Calendar.YEAR)) % 100
            val mm = d0?.get(1) ?: (cal.get(java.util.Calendar.MONTH) + 1)
            val dd = d0?.get(2) ?: cal.get(java.util.Calendar.DAY_OF_MONTH)
            val dateTxt = "'%02d %d %d".format(yy, mm, dd)
            // data 面印拍摄数据，lcd/dots 面印日期（RFC-031/033）
            // [r19] 没有 EXIF 的照片选 DATA 面，引擎会收到一个空串 —— 画面上什么都不出来，
            // 看起来就是"这个选项坏了"。这种时候退回日期字：印得出东西，也不会误导。
            val useData = dateBack.style == "data" && photoDataLine.isNotBlank()
            val styleOut = if (useData) "data" else if (dateBack.style == "data") "lcd" else dateBack.style
            val txt = if (useData) photoDataLine else dateTxt
            append(",\"date_imprint_style\":\"$styleOut\",")
            append("\"date_imprint_text\":\"${jsonEsc(txt)}\",")
            // placement：frame=画面内四角，rebate=齿孔间（135）/片边（120）。
            // 引擎在 120 的无片边情况下会直接 return：那行本来就没地方印。
            append("\"date_imprint_placement\":\"${dateBack.placement}\",")
            append("\"date_imprint_corner\":\"${dateBack.corner}\",")
            append("\"date_imprint_size\":${dateBack.size},\"date_imprint_ev\":${dateBack.ev},")
            // [r11 第 3 批] 日期距画框边缘的距离（mm，仅 placement=frame 有效）。
            // 默认 3.0 / 2.4 与引擎默认一致；只有用户拖过滑杆才不同。
            append("\"date_imprint_inset_x\":${dateInsetX},\"date_imprint_inset_y\":${dateInsetY},")
            // 数据行：仅当日期面额外带数据时空着（保持与开源同为一次曝光）
            append("\"date_imprint_data_text\":\"${jsonEsc(photoDataLine)}\"")
        }
        // —— 暗房放大机（r11 第 1 批）——
        // 这 4 个都是 PRINT 层中「改了不需要重建管线」的字段（params.cpp:36-38），
        // 所以拖滑杆只重印相纸，不重冲负片。
        // 引擎的 print_exposure 是**反**的：数值越小印得越亮，UI 里已经翻成正向（见 FormatTab）。
        // 默认值 1 / 0 / 0 / 0 与引擎默认完全一致。
        append(",\"print_exposure\":${printExposure},")
        // [r18] 滤片位移 = 用户自己的位移 + 「原色」的模式偏移（见 FILM_CAST_CC）。
        append(filterShiftDelta(filmVariant, filmTrueColor, mFilterShift, yFilterShift))
        append(",")
        append("\"preflash_exposure\":${preflashExposure}")
        // —— 直扫底片（r11 第 4 批）——
        // 上游的 No Print Profile：不走印相纸那一段，直接把冲好的底片当被摄物看
        // （负片带橙色片基 mask，反转片就是正像）。引擎里 print_stock 照旧保留有效值
        // —— 它只是被跳过，不是被清空，所以关掉开关立刻回到原来那张相纸。
        // 必须每帧显式下发：apply_delta 只写出现过的键（同 overscan / date_imprint）。
        append(",\"scan_film\":$scanFilm")
        append("}")
    }

    // [FIX 0.8.93-r11.1] delta 发出前的 JSON 自检。
    // 这段 JSON 是手工拼字符串拼出来的，最容易犯且最难查的错就是逗号（多一个 / 少一个）。
    // 引擎只会回一句 "params_delta: expected a string at byte 572" —— 既不说哪个字段，
    // 也不说多了还是少了，而结果是**整帧渲染全部失败**（r11 初版就是这个毛病）。
    // 这里用 org.json 先过一遍：合法就原样返回，不合法就把出错字节附近 100 字符
    // 打进诊断日志，一眼能看出坏在哪个键上。开销是解析 1.6 KB，可忽略。
    fun deltaSelfCheck(d: String): String {
        try {
            org.json.JSONObject(d)
        } catch (e: Exception) {
            val msg = (e.message ?: "").take(200)
            val at = Regex("character (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val win = if (at >= 0 && at <= d.length)
                d.substring(maxOf(0, at - 50), minOf(d.length, at + 50)) else ""
            Diagnostics.log("render", "DELTA 非法 len=${d.length} err=$msg" +
                if (win.isNotEmpty()) " 附近=\"$win\"" else "")
        }
        return d
    }

    // ===== 配方快照（r11 第 0 批）=====
    // 存**完整**快照，不是差异。引擎 apply_delta 只写「出现过」的键，若只存用户
    // 改过的字段，应用配方时上一套的片边 / 日期会残留下来 —— r1 那个「片边关不掉、
    // 必须杀进程」就是同一机制。所以这里把全部状态都写进去，应用时全量下发。
    fun recipeSnapshot(): String {
        val o = org.json.JSONObject()
        // 片基与配光
        o.put("film", filmVariant); o.put("print", printVariant)
        o.put("formatIdx", formatIdx); o.put("ev", exposureEv.toDouble())
        o.put("halation", halationAmount.toDouble()); o.put("grain", grainAmount.toDouble())
        o.put("antihalation", antihalationRemoved)
        // 胶片细节强度
        o.put("glareAmount", glareAmount.toDouble())
        o.put("dirCouplersAmount", dirCouplersAmount.toDouble())
        o.put("halationScatter", halationScatter.toDouble())
        // [r38] 负片反差也在配方里：它改的是冲洗出来的反差，是最该被复刻的一项
        o.put("densityGamma", densityGamma.toDouble())
        // [r33] 分区印相（对比度蒙版）
        o.put("cmActive", cmActive); o.put("cmHi", cmHighlights.toDouble())
        o.put("cmSh", cmShadows.toDouble()); o.put("cmCore", cmCore.toDouble())
        o.put("cmScale", cmScale.toDouble())
        // 暗房放大机
        o.put("printExposure", printExposure.toDouble())
        o.put("yShift", yFilterShift.toDouble()); o.put("mShift", mFilterShift.toDouble())
        o.put("preflash", preflashExposure.toDouble())
        // 相机侧
        o.put("cameraFilter", cameraFilter); o.put("aeMethod", autoExposureMethod)
        o.put("lensBlur", lensBlurUm.toDouble())
        // 几何
        o.put("rotation", rotationDeg.toDouble()); o.put("turns", quarterTurns)
        o.put("flipH", flipH); o.put("flipV", flipV); o.put("cropRatio", cropRatioIdx)
        // 片边
        o.put("osActive", overscanActive); o.put("osFormat", overscanFormat)
        o.put("osMode", overscanMode); o.put("osFog", overscanFog.toDouble())
        o.put("osLeaks", overscanLeaks.toDouble()); o.put("osGate", overscanGate)
        o.put("osHoles", overscanHoles); o.put("osCarrier", overscanCarrier)
        o.put("osEdgeText", overscanEdgeText)
        o.put("osCameraSeed", overscanCameraSeed); o.put("osFrameSeed", overscanFrameSeed)
        o.put("osFNumber", overscanFNumber.toDouble()); o.put("osFrameNumber", overscanFrameNumber)
        // 日期后背
        o.put("dateActive", dateBack.active); o.put("dateStyle", dateBack.style)
        o.put("datePlacement", dateBack.placement); o.put("dateCorner", dateBack.corner)
        o.put("dateSize", dateBack.size.toDouble()); o.put("dateEv", dateBack.ev.toDouble())
        o.put("dateInsetX", dateInsetX.toDouble()); o.put("dateInsetY", dateInsetY.toDouble())
        // 输出模式
        o.put("scanFilm", scanFilm)
        // [r19] 色彩模式也要进配方：否则存的时候是「原色」，应用回来变成「校色」，
        // 同一份配方两次出片颜色不一样（这就是"配方不完整"的那类隐蔽 bug）。
        o.put("trueColor", filmTrueColor)
        // [r33] 调色当然是配方的一部分：曲线、色温、暗角都在这里
        o.put("tone", tone.toJson())
        return o.toString()
    }

    /** 应用快照。缺的键保持当前值（旧配方向前兼容），改完触发一次渲染。 */
    fun applyRecipe(json: String): Boolean {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return false
        filmVariant = o.optString("film", filmVariant)
        printVariant = o.optString("print", printVariant)
        formatIdx = o.optInt("formatIdx", formatIdx).coerceIn(0, FORMATS.lastIndex)
        exposureEv = o.optDouble("ev", exposureEv.toDouble()).toFloat()
        halationAmount = o.optDouble("halation", halationAmount.toDouble()).toFloat()
        grainAmount = o.optDouble("grain", grainAmount.toDouble()).toFloat()
        antihalationRemoved = o.optBoolean("antihalation", antihalationRemoved)

        glareAmount = o.optDouble("glareAmount", glareAmount.toDouble()).toFloat()
        dirCouplersAmount = o.optDouble("dirCouplersAmount", dirCouplersAmount.toDouble()).toFloat()
        halationScatter = o.optDouble("halationScatter", halationScatter.toDouble()).toFloat()
        // [r38] 旧配方没有这一项 → 保持 1.0（负片原样），不会被写成 0
        densityGamma = o.optDouble("densityGamma", densityGamma.toDouble()).toFloat().coerceIn(0.2f, 4f)
        cmActive = o.optBoolean("cmActive", cmActive)
        cmHighlights = o.optDouble("cmHi", cmHighlights.toDouble()).toFloat()
        cmShadows = o.optDouble("cmSh", cmShadows.toDouble()).toFloat()
        cmCore = o.optDouble("cmCore", cmCore.toDouble()).toFloat()
        cmScale = o.optDouble("cmScale", cmScale.toDouble()).toFloat()

        printExposure = o.optDouble("printExposure", printExposure.toDouble()).toFloat()
        yFilterShift = o.optDouble("yShift", yFilterShift.toDouble()).toFloat()
        mFilterShift = o.optDouble("mShift", mFilterShift.toDouble()).toFloat()
        preflashExposure = o.optDouble("preflash", preflashExposure.toDouble()).toFloat()

        cameraFilter = o.optString("cameraFilter", cameraFilter)
        autoExposureMethod = o.optString("aeMethod", autoExposureMethod)
        lensBlurUm = o.optDouble("lensBlur", lensBlurUm.toDouble()).toFloat()

        rotationDeg = o.optDouble("rotation", rotationDeg.toDouble()).toFloat()
        quarterTurns = o.optInt("turns", quarterTurns)
        flipH = o.optBoolean("flipH", flipH); flipV = o.optBoolean("flipV", flipV)
        cropRatioIdx = o.optInt("cropRatio", cropRatioIdx)

        overscanActive = o.optBoolean("osActive", overscanActive)
        overscanFormat = o.optString("osFormat", overscanFormat)
        overscanMode = o.optString("osMode", overscanMode)
        overscanFog = o.optDouble("osFog", overscanFog.toDouble()).toFloat()
        overscanLeaks = o.optDouble("osLeaks", overscanLeaks.toDouble()).toFloat()
        overscanGate = o.optString("osGate", overscanGate)
        overscanHoles = o.optString("osHoles", overscanHoles)
        overscanCarrier = o.optString("osCarrier", overscanCarrier)
        overscanEdgeText = o.optBoolean("osEdgeText", overscanEdgeText)
        overscanCameraSeed = o.optInt("osCameraSeed", overscanCameraSeed)
        overscanFrameSeed = o.optInt("osFrameSeed", overscanFrameSeed)
        overscanFNumber = o.optDouble("osFNumber", overscanFNumber.toDouble()).toFloat()
        overscanFrameNumber = o.optInt("osFrameNumber", overscanFrameNumber)

        dateBack = dateBack.copy(
            active = o.optBoolean("dateActive", dateBack.active),
            style = o.optString("dateStyle", dateBack.style),
            placement = o.optString("datePlacement", dateBack.placement),
            corner = o.optString("dateCorner", dateBack.corner),
            size = o.optDouble("dateSize", dateBack.size.toDouble()).toFloat(),
            ev = o.optDouble("dateEv", dateBack.ev.toDouble()).toFloat(),
        )
        dateInsetX = o.optDouble("dateInsetX", dateInsetX.toDouble()).toFloat()
        dateInsetY = o.optDouble("dateInsetY", dateInsetY.toDouble()).toFloat()
        scanFilm = o.optBoolean("scanFilm", scanFilm)
        filmTrueColor = o.optBoolean("trueColor", filmTrueColor)
        // 旧配方没有这个键：fromJson(null) 给出默认值，也就是"不带调色"。
        // 这里刻意覆盖而不是保留当前值 —— 配方是一份完整快照，应用它就该回到那个状态。
        tone = Layer2.Cfg.fromJson(o.optJSONObject("tone"))

        // 新配方不继承"分级自救"留下的临时省略状态
        forceNoOverscan = false; forceNoEdgeText = false
        renderTick++
        thumbReq++
        return true
    }

    // ===== [r19] 重置：把"调整"一次清干净 =====
    //
    // 旧版这个 ↺ 只复位曝光/颗粒/光晕/防光晕/画幅 5 项，裁剪、拉直、翻转、片边、日期、
    // 暗房、相机、色彩模式全都留在原地 —— 按了"重置"画面还是歪的、还带片边，
    // 就是个半残的按钮（用户这次点名要清掉这类"摆设"）。
    //
    // 现在复位所有可调项，只保留「选的是哪支胶片、哪张相纸」：那是"看哪一卷"，
    // 不是"怎么调"。因为一下清掉的东西比原来多得多，改成先确认再执行（见右下角动作表）。
    fun resetAllAdjustments() {
        exposureEv = 0f; halationAmount = 1f; grainAmount = 1f; antihalationRemoved = false
        formatIdx = 2
        rotationDeg = 0f; quarterTurns = 0; flipH = false; flipV = false; cropRatioIdx = 0
        printExposure = 1f; yFilterShift = 0f; mFilterShift = 0f; preflashExposure = 0f
        glareAmount = 1f; dirCouplersAmount = 1f; halationScatter = 1f
        densityGamma = 1f
        // [r33] 分区印相（对比度蒙版）也复位
        cmActive = false; cmHighlights = 0f; cmShadows = 0f; cmCore = 1f; cmScale = 0.03f
        cameraFilter = ""; autoExposureMethod = "center_weighted"; lensBlurUm = 0f
        overscanActive = false; overscanFog = 1f; overscanLeaks = 0f
        overscanGate = "auto"; overscanHoles = "white"; overscanCarrier = "none"
        overscanEdgeText = true; overscanFNumber = 0f; overscanFrameNumber = 0
        dateBack = DateBackCfg(); dateInsetX = 3f; dateInsetY = 2.4f
        scanFilm = false; filmTrueColor = false
        // [r33] 调色也一起复位 —— 「重置」是"回到出厂"，留着一套曲线和暗角在画面里，
        // 用户会以为重置坏了（r30 那次已经踩过一次同类问题）。
        tone = Layer2.Cfg()
        colorHintMute[0] = true
        forceNoOverscan = false; forceNoEdgeText = false
        animScope.launch { scaleAnim.snapTo(1f); panAnim.snapTo(Offset.Zero) }
        renderTick++
        thumbReq++
    }

    // 冷启动读一次配方列表（文件不存在就是空表）
    LaunchedEffect(Unit) { recipes = RecipeStore.load(ctx) }

    fun doRender(eng: SpektraEngine, sess: Long, reason: String, timeoutMs: Long = 25000L): Bitmap? {
        Diagnostics.log("render", "[$reason] ${filmVariant}/${printVariant}")
        val t0 = System.currentTimeMillis()
        var result: Bitmap? = null
        // 本次渲染用的那把锁：中途可能被自愈换掉，线程必须持有"自己那一把"，
        // 否则解锁时会把新闸门错解（IllegalMonitorState）或放掉别人的锁。
        val gate = renderGate
        // 分阶段记录当前位置，卡死时能说出卡在哪儿（原生调用不可中断，只能靠这个定位）
        val stage = java.util.concurrent.atomic.AtomicReference("初始化")
        val t = Thread {
            try {
                mainRenderPending = true
                mainRenderPendingAt = System.currentTimeMillis()
                setStep(1, false, true)
                stage.set("buildDelta")
                val delta = deltaSelfCheck(buildDelta())
                if (overscanActive) Diagnostics.log("render", "delta=${delta.take(600)}")
                // 串行化：与缩略图渲染互斥，避免并发抢占 Vulkan GPU
                if (!gate.tryLock(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    Diagnostics.log("render", "闸门被卡死的旧渲染占用 20s，放弃本次")
                    stage.set("gate-blocked")
                } else {
                    try {
                        var st = System.currentTimeMillis()
                        stage.set("setParams")
                        eng.setParams(sess, delta)
                        Diagnostics.log("render", "  setParams ${System.currentTimeMillis() - st}ms")
                        st = System.currentTimeMillis()
                        stage.set("solve")
                        try { eng.solve(sess, "exposure") } catch (e: Exception) { Diagnostics.log("render", "solve 异常: ${e.message}") }
                        Diagnostics.log("render", "  solve ${System.currentTimeMillis() - st}ms")
                        // 渲染前先问一次布局：卡死时也能在日志里看到这一帧到底按哪种版式画、
                        // 画布多大。0.8.93 里是把这句放在渲染之后，一卡就什么都看不到。
                        if (overscanActive && sess != 0L) {
                            try {
                                val pre = eng.overscanLayout(sess)
                                Diagnostics.log("render", "PREOVS ${pre.take(300)}")
                            } catch (e: Exception) { Diagnostics.log("render", "PREOVS 失败: ${e.message}") }
                        }
                        st = System.currentTimeMillis()
                        stage.set("renderToBitmap")
                        setStep(1, true, false); setStep(2, false, true)
                        // [r11·第0批] 只改了 PRINT 层（相纸 / 放大机 / 光泽）时走 spk_reprint：
                        // 复用已缓存的负片直接重印相纸，跳过重冲 —— 这是"切相纸慢"的根因解。
                        // 首次渲染没有负片缓存会失败，此时自动回退到完整渲染，行为不变。
                        var frame: Frame16? = null
                        val prevDelta = lastRenderedDelta.get()
                        if (prevDelta != null && onlyPrintLayerChanged(prevDelta, delta)) {
                            stage.set("reprint")
                            val tRp = System.currentTimeMillis()
                            frame = eng.reprintFrame(sess, "live")
                            if (frame != null) {
                                Diagnostics.log("render", "  reprint 命中（只改 PRINT 层，免重冲）${System.currentTimeMillis() - tRp}ms")
                            } else {
                                Diagnostics.log("render", "  reprint 未命中，回退完整渲染")
                            }
                        }
                        if (frame == null) {
                            stage.set("renderToBitmap")
                            frame = eng.renderFrame(sess, "live")
                        }
                        val tb = System.currentTimeMillis()
                        val b = frame?.toBitmap()
                        Diagnostics.log("render", "    bitmap ${System.currentTimeMillis() - tb}ms")
                        result = b
                        // [r37] 原帧的 16 bit 也留着：调色必须在它上面做，降到 8 bit 再
                        // 拉曲线就会空出色阶（见 Layer2.apply16 的注释）。渲染失败时清空，
                        // 免得拿上一张图的 16 bit 去套这一张的 8 bit。
                        renderRaw = if (b != null) frame else null
                        // 只有渲染真的成功才记快照，否则下一次仍会走完整渲染
                        if (b != null) lastRenderedDelta.set(delta)
                        Diagnostics.log("render", "  render ${System.currentTimeMillis() - st}ms")
                    } finally {
                        gate.unlock()
                    }
                    stage.set("done")
                    Diagnostics.log("render", "renderToBitmap(live) OK ${System.currentTimeMillis() - t0}ms ${result?.width}x${result?.height}")
                    if (overscanActive && sess != 0L) {
                        try {
                            val ovs = eng.overscanLayout(sess)
                            Diagnostics.log("render", "OVSLAYOUT $ovs")
                            // [r7] 边码审计单独摘成一行一条：布局 JSON 有两三 KB，
                            // 眼睛找不到 "imp:" 那几个字段。这里把它们拎出来：
                            //   imp:text=..  宿主实际下发的边码字数（0 = 没给文字）
                            //   imp0:WxH+X+Y ink=..  这一组的遮罩矩形与"墨量"
                            //   imp1:DROP   这一组整个落在画布外，被丢了
                            for (m in Regex("imp[^;\"{}]*;").findAll(ovs)) {
                                Diagnostics.log("render", "IMPRINT ${m.value}")
                            }
                        } catch (e: Exception) { /* 诊断失败不影响渲染 */ }
                    }
                    setStep(2, true, false); setStep(3, true, false)
                }
            } catch (e: Exception) {
                Diagnostics.log("render", "异常: ${e.message} lastError=${runCatching { eng.lastError() }.getOrNull()}")
            } finally {
                mainRenderPending = false
            }
        }
        t.start()
        // 分片等待：每 5s 报一次当前阶段，卡死也能从日志看出卡在哪一步（原生调用无法中断）
        var waited = 0L
        while (waited < timeoutMs && t.isAlive) {
            t.join(5000L); waited += 5000L
            if (t.isAlive) {
                // 引擎自己报的"正在跑的节点"（spk_progress 不拿 session 锁，
                // 所以镜头卡死时这一句仍能读到）。这是区分"卡在片边光栅化"
                // 与"卡在 Vulkan 提交/等待"的唯一现场证据，比宿主阶段名准。
                val node = java.util.concurrent.atomic.AtomicReference("?")
                val probe = Thread {
                    runCatching {
                        val js = eng.progressStage(sess)
                        node.set(js.substringAfter("\"stage\":", "?").substringBefore(",").trim('"').take(60))
                    }
                }
                probe.isDaemon = true
                probe.start(); probe.join(500L)
                Diagnostics.log("render", "[$reason] 已等 ${waited / 1000}s，卡在 stage=${stage.get()}，引擎节点=${node.get()}")
            }
        }
        if (t.isAlive) {
            Diagnostics.log("render", "[$reason] 渲染超时(${timeoutMs / 1000}s)，卡在 stage=${stage.get()}，先请求取消")
            // [r11·第0批] 先让引擎在下一个节点边界退出（spk_cancel 不拿 session 锁，
            // 允许渲染进行中从别的线程调）。取消成功的话线程自己会跑完 finally 并解锁，
            // 闸门保持干净 —— 比原来"直接换一把新锁、让旧线程烂着"好得多。
            runCatching { eng.cancel(sess) }
            t.join(1500L)
            if (!t.isAlive) {
                Diagnostics.log("render", "[$reason] 取消生效，线程已退出，闸门未被污染")
                renderWedged = false
                mainRenderPending = false
                return result
            }
            Diagnostics.log("render", "[$reason] 取消未生效（卡在不可中断的位置），换闸门")
            // 换闸门：卡死线程仍握着旧锁，新渲染立刻可用（不换的话整机渲染就此报废）
            renderGate = java.util.concurrent.locks.ReentrantLock()
            renderWedged = true
            // [r9] 那根线程还活着，它自己的 finally 一时半会儿跑不到；这个标志若悬着，
            // 缩略图会被永久判定为"该让位主渲染"而集体不渲（格子全空）。这里直接放掉 ——
            // 真有一次主渲染在跑，它下一帧会重新置起来。
            mainRenderPending = false
            return null
        }
        renderWedged = false
        return result
    }

    // 自愈：主渲染超时（原生调用卡死，多半是 Vulkan 设备挂起）后，用**新引擎**
    // （新 Vulkan device）+ 新 session 顶上去。
    // 旧引擎故意不释放：卡死的 session 锁在它内部永远放不出来，sessionRelease 自己
    // 会卡住；直接丢句柄，让它烂在那根死线程里，别拖累界面。
    fun recoverRender(why: String) {
        val px = photoData
        if (px == null) { Diagnostics.log("heal", "无源图，跳过自愈"); renderWedged = false; return }
        renderWedged = false
        val t0 = System.currentTimeMillis()
        Diagnostics.log("heal", "开始重建引擎（$why）")
        try {
            val ne = SpektraEngine(ctx)
            if (ne.initError != null) {
                Diagnostics.log("heal", "新引擎创建失败：${ne.initError}"); engineError = "引擎重建失败：${ne.initError}"; return
            }
            val s = ne.open(px.first, px.second, px.third, 3, "{\"preview_long_edge\":1280}")
            if (s == 0L) { Diagnostics.log("heal", "重新打开图片失败：${ne.lastError()}"); return }
            engine = ne
            session = s
            renderGate = java.util.concurrent.locks.ReentrantLock()
            Diagnostics.log("heal", "自愈完成 ${System.currentTimeMillis() - t0}ms，会话已换新")
        } catch (t: Throwable) {
            Diagnostics.log("heal", "自愈异常：${t.message}")
        }
    }

    // 换引擎后重试一次
    fun retryAfterHeal(why: String): Bitmap? {
        recoverRender(why)
        if (session == 0L) { Diagnostics.log("heal", "$why：会话没换成，跳过该级"); return null }
        val e2 = engine ?: return null
        return doRender(e2, session, why, 12000L)
    }

    // 渲染 + 分级自救。卡死时逐级重试，最后一级回到 r1 基线（关掉片边），
    // 保证界面永远能出图，不再需要杀进程。
    // 每一级的结论都写进日志 —— 这本身就是二分：哪一级开始能出图，祸首就是上一级剥掉的那个参数。
    fun renderWithHeal(eng0: SpektraEngine, sess0: Long, reason: String): Bitmap? {
        val b0 = doRender(eng0, sess0, reason, 12000L)
        if (b0 != null) return b0
        if (!renderWedged) return null
        Diagnostics.log("heal", "=== $reason 卡死，开始分级自救 ===")
        // 第 1 级：同参数，换新引擎 + 新会话
        retryAfterHeal("$reason-r1换引擎")?.let {
            Diagnostics.log("heal", "结论：换引擎即恢复 —— 与参数无关，偏向设备/会话状态")
            return it
        }
        // 第 2 级：关掉边码文字
        if (overscanActive && overscanEdgeText) {
            forceNoEdgeText = true
            Diagnostics.log("heal", "第2级：关闭边码文字后重试")
            val b2 = retryAfterHeal("$reason-r2无边码")
            if (b2 != null) {
                // [r5] 不再把用户的开关悄悄拨到关：那正是"有时有字有时没字"的来源 ——
                // 一次卡死之后整场都印不出字，而开关看着还是开的/或者被悄悄关了用户没发现。
                // 现在只临时省略文字，并明确写在状态栏；换片幅或重开文字开关就再试一次。
                Diagnostics.log("heal", "结论：卡死由【边码文字】引起 —— 关字即出图；开关保持开启，换片幅后重试")
                status = "该片幅下边码文字会让引擎卡住，本次先省略；换片幅或重开「边码文字」会再试"
                return b2
            }
            forceNoEdgeText = false        // 不是文字的问题，把开关还给用户
        }
        // 第 3 级：关片边，回到 r1 基线
        if (overscanActive) {
            Diagnostics.log("heal", "第3级：临时关闭片边后重试")
            forceNoOverscan = true
            val b3 = retryAfterHeal("$reason-r3无片边")
            if (b3 != null) {
                overscanActive = false     // 界面同步关掉
                Diagnostics.log("heal", "结论：卡死由【片边 $overscanFormat】本身引起；已关片边出图")
                status = "该构图下片边会卡死引擎，已临时关闭片边（可用「诊断」导出日志）"
                return b3
            }
            forceNoOverscan = false
        }
        Diagnostics.log("heal", "三级重试都没出图（连片边都关了），问题在更底层")
        status = "渲染卡死（三级重试均失败），请用「诊断」导出日志"
        return null
    }

    // 按需渲染一组型号的缩略图，复用同一 session，缩略图不做曝光求解以提速。
    // key = "film@print"（双维度）：胶片区渲染「各胶片 × 当前相纸」，相纸区渲染「当前胶片 × 各相纸」，
    // 因此切胶片/切相纸都会让对应组合 key 变化 → 缺失 → 重渲，缩略图色调始终反映当前胶片×相纸。
    //
    // [r9] 尺寸从"引擎 preview tier(=3400，小源夹回 600px)"改成 live tier + 会话设置
    // `preview_long_edge`（下限 800，实际由 384 px 的缩略图源夹住）：像素降一个数量级，
    // 内存从 28 MB 降到 11 MB —— 这就是"缩略图要小、别吃内存和 CPU"。
    // 渲染结果按 照片指纹×胶片×相纸 落到 cacheDir（ThumbStore），重进 App 先命中磁盘，不重算。
    fun renderThumbs(eng: SpektraEngine, ts: Triple<FloatArray, Int, Int>, photoKey: String,
                     filmId: String, printId: String,
                     ids: List<Variant>, section: Int, shouldCancel: () -> Boolean): Map<String, Bitmap> {
        val out = mutableMapOf<String, Bitmap>()
        val t0 = System.currentTimeMillis()
        Diagnostics.log("thumb", "渲染 ${ids.size} 张缩略图 (section=$section film=$filmId print=$printId edge=${ThumbStore.EDGE})")
        // 与主渲染共用同一把（可替换的）闸门；抢不到就让位，绝不阻塞主渲染。
        // open/release 也必须在闸门里（r5）：它们和渲染共用引擎的 Vulkan 缓冲池。
        val gate = renderGate
        val locked = try {
            gate.tryLock(12, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!locked) { Diagnostics.log("thumb", "闸门被占用 12s，跳过本次缩略图"); return out }
        var s = 0L
        var gatePreempt = false
        var fail = 0
        var staleFlag = false
        // 每张缩略图的平均色：一行就能看出"到底渲没渲出不同的色调"（都相同 = 没生效）
        val avgs = ArrayList<String>(ids.size)
        // [r15] 平均色看不出"色偏有没有被压平"：一张偏青一张偏黄，RGB 平均可以正好抵平。
        // 所以同时收饱和度 —— 负片们如果全是 0.02~0.04，那就是真的没上色，不是"差别小"。
        val sats = ArrayList<Float>(ids.size)
        try {
            // 一组缩略图的输入侧参数是同一份（同一张照片、同一个片幅），循环外算一次
            val inDelta = inputDelta()
            s = eng.open(ts.first, ts.second, ts.third, 3, "{\"preview_long_edge\":${ThumbStore.EDGE}}")
            if (s == 0L) { Diagnostics.log("thumb", "open 失败 ${eng.lastError()}"); return out }
            for (v in ids) {
                // 主渲染优先：用户切胶片/相纸触发主渲染时，缩略图立即让位，避免饿死主渲染。
                // 但**陈旧**的 pending（那次渲染已超时）不算——否则一次卡死会让缩略图永久罢工。
                val pendingFresh = mainRenderPending &&
                    System.currentTimeMillis() - mainRenderPendingAt < 20_000L
                if (pendingFresh) { gatePreempt = true; Diagnostics.log("thumb", "让位主渲染，中断缩略图"); break }
                if (shouldCancel()) { staleFlag = true; Diagnostics.log("thumb", "取消渲染（切了 tab/换了胶片）"); break }
                try {
                    // 胶片区：film_stock=各胶片, print_stock=当前相纸；相纸区：film_stock=当前胶片, print_stock=各相纸
                    val fs = if (section == 0) v.id else filmId
                    val ps = if (section == 0) printId else v.id
                    // 去碳层(CineStill)缩略图同样需 headroom_ev=0 跳过未移植的 spk_highlight_headroom kernel
                    val hs = if (fs.startsWith("cinestill_")) ",\"halation_headroom_ev\":0.0" else ""
                    // 相纸差异（表面光泽 glare）在 52 dp 上仍看得出来，保留；颗粒在 256 px 上
                    // 只有一两个像素一颗，印出来是脏点而不是颗粒感，缩略图统一关掉。
                    val keepGlare = section == 1
                    // [r17] 颗粒要开。它在缩略图里是**唯一还能看出胶片差别的那一半**：
                    // 配平之后各胶片的色调几乎一样，剩下的就是颗粒结构与反差。原来这里
                    // 为了提速把颗粒一起关了，于是 26 张缩略图干净得像同一张图。
                    // 只开主颗粒层（sublayers 关掉，那是逐层叠加的多次噪声，最贵的一档）。
                    val grainOn = grainAmount > 0f
                    eng.setParams(s, "{\"film_stock\":\"$fs\",\"print_stock\":\"$ps\"$hs,"
                        + inDelta
                        // [r38] 负片反差必须带上：缩略图是"这卷片洗出来什么样"，反差不对
                        // 就又是一处"格子里和成片不一样"。粘性 delta 不传会沿用上一组的值，
                        // 所以每张都显式写。
                        + "\"density_curve_gamma\":$densityGamma,"
                        // [r15] 自动曝光必须开 —— 这是"缩略图颜色全一样"的真正原因。
                        // 原来这里写 auto_exposure:false（为了省掉每张一次的测光），可引擎在
                        // 关掉 AE 时用负片的固定密度印相：每种负片都被印到同一个中灰上，
                        // 26 张的平均色只差 1~2/255，看起来就是"负片全都一个色"。
                        // 主图一直是正常的，因为 buildDelta 从不下发这一项，走引擎默认的 true。
                        + "\"auto_exposure\":true,\"auto_exposure_method\":\"center_weighted\","
                        + "\"exposure_compensation_ev\":0.0,"
                        + "\"grain_active\":$grainOn,\"grain_amount\":$grainAmount,\"grain_sublayers_active\":false,"
                        // 片边/日期背印在 96 dp 上完全看不见，却是整个流水线里最贵的一步（CPU 光栅化 +
                        // GPU 合成），26 张里白白多花一倍时间。显式关掉，别依赖粘性 delta 的默认值。
                        + "\"overscan_active\":false,\"date_imprint_active\":false,"
                        + "\"halation_active\":false,\"dir_couplers_active\":false,\"glare_active\":$keepGlare,"
                        // [r18] 「原色」：按**这一格自己的胶片**算偏移（不是当前选中的那支）。
                        // 缩略图用的滤片刻度、偏移量与主图完全同一套，否则格子里看到的颜色
                        // 和选下去渲出来的成片对不上 —— 那比"看不清差别"更糟。
                        + filterShiftDelta(fs, filmTrueColor, 0f, 0f)
                        + "}")
                    val b = eng.renderToBitmap(s, "live", quiet = true)
                    if (b != null) {
                        out["$fs@$ps"] = b
                        ThumbStore.save(ctx, photoKey, fs, ps, b)
                        avgs.add(avgHex(b))
                        sats.add(avgSat(b))
                    } else { fail++ }
                } catch (e: Exception) { fail++; Diagnostics.log("thumb", "${v.id} 失败: ${e.message}") }
            }
        } finally {
            // 释放也在闸门里：它把缓冲池的引用还给引擎，与渲染同一份内存账本
            if (s != 0L) runCatching { eng.sessionRelease(s) }
            gate.unlock()
        }
        // 一行说清这次到底成了几张、为什么没成 —— 用户日志里一眼能定位。
        // 每张的平均色也打出来：全部相同就说明色调没跟着胶片变（stock 没生效或尺寸设置被拒）。
        val satsSorted = sats.sorted()
        Diagnostics.log("thumb", "缩略图完成 ${out.size}/${ids.size} 张 ${System.currentTimeMillis() - t0}ms" +
            (if (avgs.isNotEmpty()) " 色=[${avgs.joinToString(",")}] distinct=${avgs.distinct().size}" else "") +
            (if (satsSorted.isNotEmpty()) " 饱和=${"%.3f".format(satsSorted.first())}/${"%.3f".format(satsSorted[satsSorted.size / 2])}/${"%.3f".format(satsSorted.last())}" else "") +
            (if (fail > 0) " 渲染失败=$fail" else "") +
            (if (gatePreempt) " 让位主渲染" else "") +
            (if (staleFlag) " 已过期" else "") +
            " 色彩=" + (if (filmTrueColor) "原色(不校色)" else "校色"))
        return out
    }

    // 主渲染优先补渲：先吃磁盘缓存，再只渲当前组真正缺的那几张。
    fun renderMissingThumbs(eng: SpektraEngine, ts: Triple<FloatArray, Int, Int>, photoKey: String,
                            section: Int, filmId: String, printId: String) {
        val activeId = if (section == 1) printId else filmId
        // [r17] 「胶片本色」换的是滤片，同一张照片渲出来的颜色是另一套 —— 磁盘缓存必须分命名空间，
        // 否则开了本色却读到"自动配平"那批图，看起来就是开关没反应。
        val pk = if (filmTrueColor) "$photoKey#vc" else photoKey
        // [r15] 当前选中的那一格排到最前面：开了自动曝光以后每张都多一次测光，
        // 整组渲完要几秒；至少让用户先看到自己正看着的那个型号是对的。
        val need = (if (section == 1) PRINT_VARIANTS else FILM_VARIANTS)
            .sortedBy { if (it.id == activeId) 0 else 1 }
        // 1) 磁盘命中：同一张照片＋同一组胶片/相纸，颜色不会变，没必要重算
        val fromDisk = HashMap<String, Bitmap>()
        if (pk.isNotEmpty()) {
            for (v in need) {
                val k = if (section == 0) "${v.id}@$printId" else "$filmId@${v.id}"
                if (thumbBase[k] != null) continue
                val fs = if (section == 0) v.id else filmId
                val ps = if (section == 0) printId else v.id
                // [r38] 磁盘里存的是**引擎原样**（不带调色），所以命中即进 thumbBase，
                // 再由下面的派生 effect 统一套 Layer 2。
                ThumbStore.load(ctx, pk, fs, ps)?.let { fromDisk[k] = it }
            }
            if (fromDisk.isNotEmpty()) {
                thumbBase = thumbBase + fromDisk
                Diagnostics.log("thumb", "磁盘命中 ${fromDisk.size} 张（section=$section）")
            }
        }
        val missing = need.filter {
            val k = if (section == 0) "${it.id}@$printId" else "$filmId@${it.id}"
            thumbBase[k] == null
        }
        if (missing.isEmpty()) return
        val myReq = thumbReq
        runCatching {
            val add = renderThumbs(eng, ts, pk, filmId, printId, missing, section) { thumbReq != myReq }
            // 合并时以"渲染结果 + 已命中"为准；期间若被清空（换照片）就不再写回旧图
            if (thumbReq == myReq) thumbBase = thumbBase + add
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            try {
                Diagnostics.log("init", "创建引擎")
                val e = SpektraEngine(ctx); engine = e
                if (e.initError != null) { engineError = "引擎初始化失败：${e.initError}"; Diagnostics.log("init", "create FAIL: ${e.initError}") }
                else {
                    Diagnostics.log("init", "create OK")
                    val testPx = generateTestFrame(960, 720)
                    val s = e.open(testPx, 960, 720, 3, "{\"preview_long_edge\":1280}")
                    session = s
                    val b = doRender(e, s, "init")
                    previewBase = b
                    // [r9] 没导入照片时，网格里也先给出「每种胶片/相纸 × 内置测试图」的缩略图 ——
                    // 256 px 一套只要几百毫秒，而且写在磁盘缓存里（key=builtin-test），
                    // 之后每次冷启动直接命中。否则用户先看到的是 26 个空格子，会以为缩略图坏了。
                    val ts0 = downsampleFloat(testPx, 960, 720, ThumbStore.SRC_EDGE)
                    thumbSrc = ts0
                    thumbPhotoKey = ThumbStore.BUILTIN_KEY
                }
            } catch (t: Throwable) { engineError = t.stackTraceToString().take(1500); Diagnostics.log("init", "异常: ${t.message}") }
        }
    }

    val pending = pendingImport.value
    // 主渲染优先：导入/切换时置 true，缩略图渲染让位，避免缩略图抢锁饿死主渲染(25s超时)
    var importBusy by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        if (pending != null) {
            status = "正在解码照片…"
            Diagnostics.log("import", "收到 Uri=$pending")
            importBusy = true
            withContext(Dispatchers.Default) {
                try {
                    val eng = engine ?: throw IllegalStateException("引擎未就绪")
                    // 释放上一张照片的源数据与缩略图缓存（大 FloatArray，否则连续导入会内存累积 OOM；
                    // 不清空 thumbCache 则新照片缩略图不会重渲，仍显示上一张）。不强制 System.gc，交给系统回收避免停顿。
                    photoData = null
                    // [r38] 两层一起清：只清显示层的话，派生 effect 会立刻用旧照片的
                    // thumbBase 重新填回来，等于没清。
                    thumbBase = emptyMap()
                    thumbCache = emptyMap()
                    // RAW 检测：photopicker 的 Uri 尾段只有数字无扩展名，用 DISPLAY_NAME 拿真实文件名
                    val seg = rawDisplayName(ctx, pending)
                    photoIsRaw = RAW_EXTS.any { seg.endsWith(".$it") }
                    // [r11 第 4 批] 记下源片：导出命名 token 的「原片名」+ 继承 EXIF 的来源
                    srcUri = pending
                    srcName = displayStemName(ctx, pending)
                    Diagnostics.log("import", "源片名=$srcName")
                    val decoded = if (photoIsRaw) {
                        decodeRawPhoto(ctx, pending, eng)?.also { Diagnostics.log("import", "RAW 解码成功(引擎 LibRaw)") }
                            ?: decodePhotoToFloat(ctx, pending)?.also { photoIsRaw = false }
                            ?: throw IllegalArgumentException("无法解码所选图片")
                    } else decodePhotoToFloat(ctx, pending)
                        ?: throw IllegalArgumentException("无法解码所选图片")
                    photoData = decoded; sourceW = decoded.second; sourceH = decoded.third
                    Diagnostics.log("import", "解码 OK ${decoded.second}x${decoded.third}")
                    // 日期后背要的拍摄日与数据行：从 EXIF 取。读不到就让日期后背退回当天、
                    // 并且不印数据行 —— 和一台没设时间的相机一样，不编造数值。
                    runCatching { readExifShooting(ctx, pending) }.getOrNull()?.let { (d, line) ->
                        photoDate = d; photoDataLine = line
                        Diagnostics.log("import", "EXIF 拍摄日=${d?.joinToString("-") ?: "无"} 数据=\"$line\"")
                    }
                    // [r11 第 3 批] 把 EXIF 光圈交给片边 —— 真实胶片边上印着拍摄光圈。
                    // 读不到就归零（= 不印），不编造；换照片时总是以新照片为准。
                    overscanFNumber = runCatching { readExifAperture(ctx, pending) }.getOrDefault(0f)
                    Diagnostics.log("import", "EXIF 光圈=${if (overscanFNumber > 0f) "f/$overscanFNumber" else "无"}")
                    // 生成缩略图专用小源（长边 384）：缩略图渲染用它，避免每次拿全尺寸 float open，
                    // 显著降低内存与耗时；384 对 256 px 的输出还留着裁切余量。
                    val small = downsampleFloat(decoded.first, decoded.second, decoded.third, ThumbStore.SRC_EDGE)
                    thumbSrc = small
                    // [r9] 指纹只取小源的稀疏采样，成本是微秒级；换照片 → 指纹变 → 磁盘缓存自动失效
                    thumbPhotoKey = photoFingerprint(small.first, small.second, small.third)
                    Diagnostics.log("import", "缩略图源 ${small.second}x${small.third} key=$thumbPhotoKey")
                    // 清掉别的照片留下的缩略图文件（缓存目录不该无限长）
                    runCatching { ThumbStore.prune(ctx, thumbPhotoKey) }
                    // release + open 都要在闸门里（r5）：与正在跑的渲染串行，避免同池并发
                    val s = withEngineGate(30000L, "import open") {
                        if (session != 0L) { runCatching { eng.sessionRelease(session) }; session = 0L }
                        eng.open(decoded.first, decoded.second, decoded.third, 3, "{\"preview_long_edge\":1280}")
                    } ?: 0L
                    if (s == 0L) throw IllegalStateException("打开图片失败：${eng.lastError()}")
                    session = s
                    // [r25] RAW 的 float 是线性 ProPhoto：这里的 true 让 makeThumb 补上
                    // ProPhoto→sRGB 矩阵与 sRGB 编码曲线，否则长按「原图」是一片死黑。
                    photoBmp = makeThumb(decoded.first, decoded.second, decoded.third, 1600, photoIsRaw)
                    setStep(0, true, false)
                    status = "已导入 ${decoded.second}×${decoded.third}，正在冲洗…"
                    val b = renderWithHeal(eng, s, "import")
                    // 失败时保留上一帧，不要把画面清空成转圈（转圈会让人以为整个 App 挂了）
                    if (b != null) {
                        previewBase = b
                        // [r25] 色相诊断：把「原图」与「冲洗后」的平均色相并排打出来。
                        // 用户反馈"绿叶冲洗后变黄绿"时，这一行能直接回答是引擎把绿推向了黄
                        // （Δ 很大），还是胶片本身就该这样（Δ 在 10° 以内，属负片对叶绿素的
                        // 正常响应）。按饱和度加权做圆周平均，0°/360° 不会互相抵消。
                        val inH = photoBmp?.let { avgHueSat(it) }
                        val outH = avgHueSat(b)
                        if (inH != null) {
                            // [r26] 两个坑都在这儿：
                            //  ① 字符串用 + 拼起来再 .format(...) —— 必须整体加括号，否则
                            //    `.format` 只作用在后半截，前半截的 %.1f 原样打出来（r25 的日志
                            //    里就是 "原图=%.1f° 成片=%.1f°"）。顺带一提，那行里挤出来的数字
                            //    其实是错位的：73.852 是原图色相、63.530 是成片色相、−10.32 才是
                            //    Δ、0.54 是原图饱和。所以结论（Δ ≈ −10°）是能用的，只是没法看。
                            //  ② 全图平均色相会被占大面积的黄褐/天空稀释，看不出"叶子"到底变了多少，
                            //    所以再单打一行绿带（75°–165° 且饱和度 ≥0.12）的占比与平均色相。
                            logColourPair("导入", inH, outH)
                        }
                    }
                    status = if (b != null) "冲洗完成" else if (renderWedged) "引擎卡死，正在重建…" else "冲洗未完成，点'诊断'"
                } catch (t: Throwable) { status = "导入失败：${t.message}"; Diagnostics.log("import", "异常: ${t.message}") }
                finally { importBusy = false }
            }
        }
    }
    // 缩略图懒加载：依赖 section + 当前胶片/相纸，切胶片/切相纸都会让组合 key 失效而重渲（色调反映 胶片×相纸）。
    // 只渲当前 tab 对应组，用版本号让切 tab 取消过期渲染；importBusy 期间让位主渲染，避免抢锁饿死。
    // [r18] 「原色 / 校色」切换。
    //
    // r17 那版是靠重开会话换 c/m/y 中性滤片实现的，**根本没生效**：引擎在 set_params
    // 里换胶片时会 init_params + digest()，而 digest() 一定会用
    // engine/resources/neutral_print_filters.json 把 c/m/y 三支中性滤片改写回这支
    // 胶片自己的值（engine.cpp:1790）。所以"发一套共用滤片"只在**不在那张表里**的
    // 胶片上保留下来（目前只有 4 支黑白片），其余 22 支照旧被配平 —— 这就是日志里
    // 打开本色后 22 个平均色一模一样、只有最后 4 个变了的由来。
    //
    // 现在改成动 m_filter_shift / y_filter_shift：这两个是「在自身中性滤片上再加多少
    // CC」的增量，digest() 不碰它们，印相时直接参与光谱计算（printing.cpp:100），
    // 而且都是 live 字段 —— 切换只重印相纸，不重冲负片。每支胶片要的偏移量就是
    // 「共用滤片 − 自己的滤片」，见 FILM_CAST_CC。
    //
    // 切换时只需作废缩略图缓存（换的是一整套颜色），主图由下面的 renderTick 管线重渲。
    val trueColorFirst = remember { booleanArrayOf(true) }
    LaunchedEffect(filmTrueColor) {
        if (trueColorFirst[0]) { trueColorFirst[0] = false; return@LaunchedEffect }
        thumbBase = emptyMap()
        thumbCache = emptyMap()
        thumbReq++
        // [r19] 换挡时给一句人话：这个开关只写成「校色 / 原色」两个词，不说清它改了什么，
        // 用户只会看到颜色变暖变冷却不知道为什么。提示 4.5 秒后自动退场。
        if (colorHintMute[0]) colorHintMute[0] = false
        else status = if (filmTrueColor) "原色：所有胶片共用一套滤片，各片自身的色偏显现"
                      else "校色：各片按自己的中性滤片配平（彩扩机做法，互相接近）"
    }

    LaunchedEffect(section, thumbSrc, thumbPhotoKey, importBusy, filmVariant, printVariant, filmTrueColor) {
        val ts = thumbSrc; val eng = engine; val pk = thumbPhotoKey
        if (ts != null && eng != null && !importBusy) {
            withContext(Dispatchers.Default) {
                runCatching { renderMissingThumbs(eng, ts, pk, section, filmVariant, printVariant) }
            }
        }
    }

    // ===== [r38] 缩略图套 Layer 2：thumbBase（引擎原样）→ thumbCache（显示用）=====
    //
    // 之前的取舍是"缩略图不调色，省掉十几张图的一次调色"。代价是：调完之后看胶片条，
    // 格子里还是没调过的颜色 —— 而用户正是靠这几格挑胶片，等于给了一张错样片。
    //
    // 现在补齐，但不是每帧都补：几十张小图（256 长边）合起来和一张预览同量级，
    // 每帧算一遍会和用户真正在看的主图抢 CPU。所以停手 250 ms 后才算一轮；
    // 拖动期间 tone 一直在变，effect 不停重启，实际上不会跑。
    // 已经按同一个 tone 算过的格子直接复用（切 tab 时只补新来的那几张）。
    var thumbTone by remember { mutableStateOf<Layer2.Cfg?>(null) }
    LaunchedEffect(thumbBase, tone) {
        val baseMap = thumbBase
        if (baseMap.isEmpty()) {
            if (thumbCache.isNotEmpty()) thumbCache = emptyMap()
            thumbTone = null
            return@LaunchedEffect
        }
        // 没调色：直接拿引擎原样当显示图，一份内存、零计算
        if (tone.isIdentity) { thumbCache = baseMap; thumbTone = tone; return@LaunchedEffect }
        val prev = thumbCache
        val prevOk = thumbTone == tone
        if (prevOk && baseMap.keys.all { prev[it] != null }) return@LaunchedEffect
        delay(250)
        val conv = withContext(Dispatchers.Default) {
            baseMap.mapValues { (k, b) ->
                (if (prevOk) prev[k] else null) ?: Layer2.apply(b, tone)
            }
        }
        thumbCache = conv
        thumbTone = tone
    }

    val eng = engine
    // 去抖渲染：快速连续切换胶片/相纸时合并为最后一次渲染，避免每次 setParams(400-1500ms) 堆积卡顿
    LaunchedEffect(renderTick, filmVariant, printVariant, formatIdx, antihalationRemoved, filmTrueColor) {
        if (session != 0L && eng != null) {
            delay(250)
            withContext(Dispatchers.Default) {
                // 卡死不再"打到死"：分级自救会逐级剥参数重试，直到能出图，
                // 并且把"哪一级开始能出图"写进日志（这就是二分定位）。
                val b = renderWithHeal(eng, session, "change")
                if (b != null) previewBase = b
                // 主渲染完成后补渲被让位中断的缩略图
                thumbSrc?.let { ts -> renderMissingThumbs(engine ?: eng, ts, thumbPhotoKey, section, filmVariant, printVariant) }
            }
        }
    }

    // ===== r14 界面骨架：整幅画面 + 刊头顶栏 + 可拖拽的暗房控制台 =====
    //
    // 旧版是 Column 三等分：顶栏 52 / 预览（weight 1）/ 固定 320dp 卡片 + 56dp 工具条。
    // 三个问题：① 控制区永远是同一块面积，看片时也占着 40% 屏幕；② 顶栏里「冲洗/编辑」
    // 与下面板的分段控件是两份一模一样的切换；③ 底栏三个按钮把「导出」挤到最右，右手
    // 拇指最难够到的位置放的是最高频的动作。
    //
    // 新版把控制层做成一块**可以拖动的控制台**，浮在画面之上：
    //   · 预览独占整幅，顶栏浮在最上面（只剩刊头 + 导入/相册/诊断）；
    //   · 控制台三档：收起 / 半开（默认）/ 全开，可拖、可点把手切换；
    //   · 收起时只剩片名、重置/更多和导出 —— 看片时屏幕上几乎什么都没有；
    //   · 导出从最右的角落移到控制台右上（拇指自然落点），重置/更多收成图标。
    // 所有状态与回调一字未改，只换了摆放方式。
    var showMore by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val sheetFrac = remember { Animatable(0.58f) }
    // 第一次进来把手是琥珀色的（"这里可以拖"），动过一次就变回普通白
    var sheetHint by remember { mutableStateOf(true) }
    val sheetScope = rememberCoroutineScope()

    // 状态提示自动退场：成功类 4.5s 后消失，免得一条"冲洗完成"永远压在画面上；
    // 失败/卡死类保留，因为那就是用户需要读到的东西。
    LaunchedEffect(status) {
        val s = status
        if (s != null && !(s.contains("失败") || s.contains("卡死") || s.contains("未完成"))) {
            delay(4500)
            if (status == s) status = null
        }
    }

    // ===== [r33] Layer 2：引擎出图 → 调色 → 显示 =====
    //
    // 这一段是整页「影调」的引擎：只要 previewBase 或参数变了，就重跑一遍。
    //
    // delay(16) 是必要的，不是凑数 —— 拖滑杆时 onValueChange 每帧都在改 tone，
    // 没有这个下限，每次改都会起一轮全图处理，几轮叠在一起反而更卡（它们互相抢 CPU）。
    // 16 ms 刚好一帧：用户看到的是「跟手」，而不是「一卡一卡地追手」。
    // [r38] 调色耗时的实测值与「拖动期降档」。
    //
    // 1280 长边上一帧 apply16 大约 20–30 ms，而拖动时参数每 16 ms 变一次 —— 算得慢的
    // 机器上会出现"上一帧还没算完，下一帧又来了"。旧代码对此只能干看着：协程被取消，
    // 但已经跑起来的 CPU 循环不会中途停下，于是好几帧的计算叠在一起抢同一个 CPU，
    // 越拖越顿。现在三层处理：
    //   ① 实测每帧耗时，慢于 26 ms（不到 38 fps）就把下一帧降到半分辨率（隔行隔列），
    //      画面糊一档但跟手；快于 12 ms 再升回全分辨率；
    //   ② 松手（ToneTab 的 onCommit）时强制出一次全分辨率，用户最后看到的一定是清晰的；
    //   ③ 过期帧自己在行循环里退出（apply16 的 cancel 回调），不再白算。
    val toneMs = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var toneFast by remember { mutableStateOf(false) }         // 拖动期降档标志
    var toneSettle by remember { mutableIntStateOf(0) }        // 松手信号（+1 → 本轮全分辨率）
    var toneSettleReq by remember { mutableStateOf(false) }
    // 当前"该被算的是哪一帧"的令牌：换令牌即作废旧帧
    val toneToken = remember { java.util.concurrent.atomic.AtomicReference<Any?>(null) }

    LaunchedEffect(previewBase, tone, renderRaw, toneSettle) {
        val base = previewBase
        if (base == null) { preview = null; toneHist = null; return@LaunchedEffect }
        // 先把令牌换成自己：上一帧如果还在算，它下一次检查就会退出。
        // 放在 delay 之前是有意的 —— 拖动时这一轮很可能在 16 ms 内就被下一轮取代，
        // 若等到 delay 之后才换令牌，上一帧就白白多算了一整帧。
        val me = Any()
        toneToken.set(me)
        delay(16)
        if (tone.isIdentity) {
            preview = base
            toneHist = withContext(Dispatchers.Default) { Layer2.histogram(base) }
            // 回到"没调色"就等于回到了 8 bit 原图的零成本路径，降档状态要一起清掉，
            // 否则下次刚拖第一下就出半分辨率，看着像坏了
            toneFast = false
            return@LaunchedEffect
        }
        // 松手那一轮强制全分辨率；其余时候沿用上一帧的实测结论
        val forceFull = toneSettleReq
        toneSettleReq = false
        val step = if (toneFast && !forceFull) 2 else 1
        val raw = renderRaw
        val t0 = System.nanoTime()
        val r: Pair<Bitmap, Layer2.Hist>? = withContext(Dispatchers.Default) {
            // 16 bit 优先；帧对不上（换图、渲染失败、自愈换了 session）就退回 8 bit。
            // 退回时画面仍然正确，只是大幅曲线会重新出现色阶断层 —— 宁可这样也不要
            // 拿上一张图的像素去调这一张。
            val out = if (raw != null && raw.w == base.width && raw.h == base.height)
                Layer2.apply16(raw.px, raw.w, raw.h, tone, step) { toneToken.get() !== me }
            else null
            // apply16 交白卷有两种原因：① 没有 16 bit 帧（要退回 8 bit）；② 这一帧已经
            // 过期（被新参数取代）。后者绝不能再拿 8 bit 重算一遍 —— 那正是"越拖越卡"，
            // 而且算出来的东西马上就要被下一帧覆盖。直接作废，画面停在上一帧。
            if (out == null && toneToken.get() !== me) return@withContext null
            val b = out ?: Layer2.apply(base, tone)
            b to Layer2.histogram(b)
        }
        if (r == null) return@LaunchedEffect     // 过期帧：不更新画面，也不动降档状态
        val ms = (System.nanoTime() - t0) / 1_000_000
        toneMs.set(ms)
        preview = r.first
        toneHist = r.second
        // 迟滞：慢才降、明显快了才升，避免在阈值附近来回跳（每跳一次画面清晰度都会变）
        if (step == 1 && ms > 26) toneFast = true
        else if (step == 2 && ms < 12) toneFast = false
    }

    // 预览 HDR：开关开启时把预览叠加高光恢复（与导出 gain map 同一曲线），后台计算避免卡顿
    LaunchedEffect(preview, previewHdr) {
        hdrPreviewBmp = if (previewHdr && preview != null)
            withContext(Dispatchers.Default) { UltraHdrJpeg.hdrLook(preview!!) } else null
    }

    Scaffold(containerColor = Dv.root) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val maxH = maxHeight
            val maxHPx = with(LocalDensity.current) { maxH.toPx() }
            // 三档高度。全开档不是固定的 0.9 —— 屏幕矮的机器上那样会把画面压成一条缝，
            // 所以按"顶栏 56 + 画面至少 150dp"倒算，再夹在 0.62…0.90 之间。
            val peekFrac = 0.16f
            val halfFrac = 0.58f
            val fullFrac = ((maxH - 56.dp - 150.dp) / maxH).coerceIn(0.62f, 0.90f)
            val detents = listOf(peekFrac, halfFrac, fullFrac)
            val springBack = spring<Float>(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow)
            fun animTo(target: Float) {
                sheetScope.launch { sheetFrac.animateTo(target, springBack) }
            }
            val snapSheet: () -> Unit = {
                animTo(detents.minByOrNull { kotlin.math.abs(it - sheetFrac.value) } ?: halfFrac)
            }
            val toggleSheet: () -> Unit = {
                animTo(if (sheetFrac.value > 0.35f) peekFrac else halfFrac)
            }
            val dragSheet: (Float) -> Unit = { dy ->
                sheetScope.launch {
                    sheetHint = false
                    sheetFrac.snapTo((sheetFrac.value - dy / maxHPx).coerceIn(peekFrac * 0.92f, fullFrac))
                }
            }
            val collapsed = sheetFrac.value < 0.32f
            val sheetH = maxH * sheetFrac.value
            // 「冲洗中」只在这两件事真的在进行时出现：某一步被标记为 active，或导入/重冲正在跑。
            // 旧的判定是 `progress.any { !it.done } && session != 0` —— 冷启动时那五步本来就
            // 一步都没跑过，于是 App 一打开就永远显示"冲洗中 · 解码"，进度条停在 0%。
            val busy = progress.any { it.active } || importBusy
            val doneN = progress.count { it.done }
            val washFrac by animateFloatAsState(
                if (progress.isEmpty()) 0f else doneN.toFloat() / progress.size,
                fluidSpec(reduceMotion), label = "washFrac")

            Column(Modifier.fillMaxSize()) {
                TopBar(
                    onPickPhoto = onPickPhoto,
                    onGallery = { showGallery = true },
                    // [r26] 点「诊断」时顺手量一次当前成片的色相，再弹日志。
                    // 导入那条只在换照片时打一次；用户要是"换了胶片再看绿"，就得有按需测一次的入口，
                    // 否则为了拿一行日志还要重导一遍同一张照片。tag 里带上当前胶片/相纸，日志才对得上号。
                    onDiagnose = {
                        val cur = preview
                        val inBmp = photoBmp
                        if (cur != null && inBmp != null) {
                            runCatching {
                                logColourPair("当前 $filmVariant/$printVariant",
                                    avgHueSat(inBmp), avgHueSat(cur))
                            }
                        }
                        showLog = true
                    },
                )
                // ---------- 画面舞台：一张照片，别的什么都没有 ----------
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .onSizeChanged { prevW = it.width; prevH = it.height }
                        // 底纹：暖炭黑向中心微亮的径向渐变，像灯箱上的散光，让照片"浮"起来
                        .background(Brush.radialGradient(
                            listOf(Color(0xFF1A1611), Dv.root),
                            radius = 1400f,
                        ))
                        // 手势 1:1 直接操纵：缩放围绕图片中心（保持居中），平移被限制在照片边界内（不露黑底）
                        .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ ->
                            val ns = (scaleAnim.value * zoom).coerceIn(1f, 4f)
                            animScope.launch {
                                scaleAnim.snapTo(ns)
                                // 只有纯拖动(zoom≈1)才更新平移，缩放时保持图片居中、不跑位
                                if (zoom <= 1.0001f) {
                                    val mul = if (ns > 1f) ns else 1f
                                    val ib = preview; val iw = ib?.width ?: 0; val ih = ib?.height ?: 0
                                    val dw = if (iw > 0 && ih > 0 && prevW > 0) minOf(prevW.toFloat(), prevH.toFloat() * iw / ih) else prevW.toFloat()
                                    val dh = if (iw > 0 && ih > 0 && prevH > 0) minOf(prevH.toFloat(), prevW.toFloat() * ih / iw) else prevH.toFloat()
                                    val pw = dw * ns; val ph = dh * ns
                                    val mx = maxOf(0f, (pw - prevW) / 2f)
                                    val my = maxOf(0f, (ph - prevH) / 2f)
                                    panAnim.snapTo(Offset((panAnim.value.x + pan.x * mul).coerceIn(-mx, mx), (panAnim.value.y + pan.y * mul).coerceIn(-my, my)))
                                }
                            }
                        } }
                        // 长按看原图 + 双击弹簧缩放（可中断：手指再次落下即打断，从当前值继续）
                        .pointerInput(Unit) { detectTapGestures(
                            onPress = { showOriginal = true; tryAwaitRelease(); showOriginal = false },
                            onDoubleTap = {
                                if (scaleAnim.value > 1.6f) {
                                    animScope.launch {
                                        scaleAnim.animateTo(1f, fluidSpec<Float>(reduceMotion))
                                        panAnim.animateTo(Offset.Zero, fluidSpec<Offset>(reduceMotion))
                                    }
                                } else {
                                    animScope.launch { scaleAnim.animateTo(2f, fluidSpec<Float>(reduceMotion)) }
                                }
                            }
                        ) },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = when {
                        showOriginal -> photoBmp
                        previewHdr && hdrPreviewBmp != null -> hdrPreviewBmp
                        else -> preview
                    }
                    when {
                        bmp != null -> Image(bmp.asImageBitmap(), "预览",
                            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)
                                .graphicsLayer(scaleX = scaleAnim.value, scaleY = scaleAnim.value,
                                    translationX = panAnim.value.x, translationY = panAnim.value.y),
                            contentScale = ContentScale.Fit)
                        engineError != null -> Text(engineError ?: "", color = Dv.red, fontSize = 11.sp,
                            modifier = Modifier.padding(16.dp))
                        else -> CircularProgressIndicator(color = Dv.accent, strokeWidth = 2.dp)
                    }
                    // 长按看原图：玻璃小标签（90ms 淡入 / 140ms 淡出，出得比进得快是 iOS 惯例）
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showOriginal && photoBmp != null,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        enter = fadeIn(tween(90)), exit = fadeOut(tween(140)),
                    ) {
                        Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Dv.glassBar)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 11.dp, vertical = 5.dp)) {
                            Text("原图 · 松手返回成片", color = Dv.label1, fontSize = 10.5f.sp)
                        }
                    }
                    // HDR 预览开关（默认关）。[r27] 明确叫「模拟」：这里只是把 SDR 位图
                    // 按导出 gain map 的同一条高光曲线提亮，画在普通 ARGB_8888 上，
                    // 并没有把屏幕切成 HDR 模式，也不是在解码文件里的增益图。
                    Box(Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (previewHdr) Dv.accentSoft else Dv.glassBar)
                        .border(1.dp, if (previewHdr) Dv.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(14.dp))
                        .iosTap(pressScale = 0.94f, pressDim = 0.8f, haptic = true) { previewHdr = !previewHdr }
                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).clip(CircleShape)
                                .background(if (previewHdr) Dv.accent else Dv.label3))
                            Spacer(Modifier.width(6.dp))
                            Text("HDR 模拟", color = if (previewHdr) Dv.accent else Dv.label2,
                                fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                        }
                    }
                    // 状态提示：悬在画面底部（不再占一行布局高度，界面不会因为一句话上下跳）
                    status?.let { s ->
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = true, enter = fadeIn(tween(140)), exit = fadeOut(tween(140))) {
                                StatusPill(s, warn = s.contains("失败") || s.contains("卡死") || s.contains("未完成"))
                            }
                        }
                    }
                }
                // 预留给控制台的高度（控制台绝对定位浮在上面，画面不会被它挡住）
                Spacer(Modifier.height(sheetH))
            }

            // ---------- 台沿：画面与面板之间的过渡带（r15） ----------
            // 面板顶是 20dp 圆角，竖构图照片恰好压到它的下沿，圆弧外侧被切成两块月牙：
            // 那里是画面径向渐变最亮的一段（#1A1611），比面板 #12100D 更亮，
            // 于是看上去像面板被咬掉两个口子、照片下面凭空多了两块深色台阶。
            // 在面板底下垫一条向上渐隐的同色带：圆弧外先被面板色填满，弧线自己消失，
            // 顺带把照片下沿压暗一档 —— 像胶片搁在灯箱台面上，而不是悬在一道硬边上。
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .height(sheetH + 46.dp)
                    .drawBehind {
                        val f = (46.dp.toPx() / size.height).coerceIn(0.02f, 0.6f)
                        drawRect(Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Dv.panel.copy(alpha = 0f),
                                0.42f * f to Dv.panel.copy(alpha = 0.62f),
                                f to Dv.panel,
                                1f to Dv.panel,
                            )
                        ))
                    }
            )

            // ---------- 暗房控制台 ----------
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetH)
                    .clip(RoundedCornerShape(topStart = Dv.rSheet, topEnd = Dv.rSheet))
                    .background(Dv.panel)
                    .clipToBounds()
                    .drawBehind {
                        // 顶边一道 1px 暖白高光：玻璃/金属面板被光打到的边缘。
                        // 两端各留 34dp 淡出 —— 直线如果一路顶到圆角，会在弧的起点处
                        // 断成一个亮疙瘩，反而把弧线勾得更清楚。
                        val edge = 34.dp.toPx()
                        drawLine(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                (edge / size.width).coerceAtMost(0.4f) to Color.White.copy(alpha = 0.10f),
                                (1f - edge / size.width).coerceAtLeast(0.6f) to Color.White.copy(alpha = 0.10f),
                                1f to Color.Transparent,
                            ),
                            Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f,
                        )
                    }
            ) {
                // 冲洗进度：一条 2dp 细线压在面板最上沿（不占布局、不抢注意力）
                if (busy) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Dv.accentDim)) {
                        Box(Modifier.fillMaxWidth(washFrac.coerceIn(0f, 1f)).fillMaxHeight()
                            .background(Dv.accent))
                    }
                }
                // 拖拽把手（28dp 热区，点一下在半开/收起之间切换）
                Box(
                    Modifier.fillMaxWidth().height(32.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { sheetHint = false },
                                onDragEnd = { snapSheet() },
                                onDragCancel = { snapSheet() },
                            ) { change, dy -> change.consume(); dragSheet(dy) }
                        }
                        .iosTap(pressDim = 0.7f) { toggleSheet() },
                    contentAlignment = Alignment.Center,
                ) { SheetGrabber(active = sheetHint || busy) }

                // 片名 / 工具 / 导出
                Row(
                    Modifier.fillMaxWidth().height(46.dp).padding(start = 16.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f)
                            // 片名区也可以拖（它不是按钮，不会和点击冲突）
                            .pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragStart = { sheetHint = false },
                                    onDragEnd = { snapSheet() },
                                    onDragCancel = { snapSheet() },
                                ) { change, dy -> change.consume(); dragSheet(dy) }
                            }
                    ) {
                        Text(
                            labelOf(filmVariant),
                            color = Dv.label1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp, maxLines = 1,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (busy) "冲洗中 · ${progress.firstOrNull { it.active }?.label ?: "准备"}"
                                else if (scanFilm) "直扫底片 · 未经相纸" else labelOf(printVariant),
                                color = if (busy) Dv.orangeLight else Dv.label3,
                                fontSize = 9.5f.sp, maxLines = 1,
                            )
                            Text("  ·  ", color = Dv.label4, fontSize = 9.5f.sp)
                            Text("%+.2f EV".format(exposureEv), color = Dv.label3,
                                fontSize = 9.5f.sp, fontFamily = Dv.mono)
                        }
                    }
                    DvIconButton(Glyph.Undo, "重置参数", box = 40.dp, glyphSize = 19.dp,
                        tint = Dv.label2, onClick = { showResetConfirm = true })
                    DvIconButton(Glyph.More, "更多", box = 40.dp, glyphSize = 20.dp,
                        tint = Dv.label2, onClick = { showMore = true })
                    Spacer(Modifier.width(6.dp))
                    DvPrimaryButton("导出", enabled = session != 0L) { if (session != 0L) showExport = true }
                }

                // 收起状态只留片名/工具/导出：内容不参与布局，也不会在窄高度里溢出
                if (!collapsed) {
                // 一级切换：胶片 / 影调 / 调校
                //
                // [r33] 「影调」插在中间，而不是并进「调校」：调校页改的是**冲洗本身**
                // （重冲负片、重印相纸，一次几百毫秒到几秒），影调页改的是**扫描之后
                // 的照片**（十几毫秒，实时）。两种手感混在一页里，用户没法判断
                // "我这一下动了什么、为什么要等"。
                UnderlineTabs(
                    labels = listOf("胶片", "影调", "调校"), index = tab,
                    onSelect = { tab = it }, reduceMotion = reduceMotion,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )

                // 内容区（clipToBounds：面板被拖到很矮时宁可裁掉，也不让它盖住上面的片名）
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    when (tab) {
                        0 -> FilmPaperTab(
                            thumbCache = thumbCache,
                            section = section, onSection = { section = it; thumbReq++ },
                            filmVariant = filmVariant, onFilmVariant = { filmVariant = it; renderTick++ },
                            printVariant = printVariant,
                            // 点相纸 = 明确要印这张相纸，于是关掉直扫底片（上游同规则）
                            onPrintVariant = { printVariant = it; if (scanFilm) scanFilm = false; renderTick++ },
                            scanFilm = scanFilm, onScanFilm = { scanFilm = it; renderTick++ },
                            exposureEv = exposureEv, onEvChange = { exposureEv = it }, onEvCommit = { renderTick++ },
                            filmTrueColor = filmTrueColor, onFilmTrueColor = { filmTrueColor = it },
                            reduceMotion = reduceMotion,
                        )
                        1 -> ToneTab(
                            cfg = tone,
                            hist = toneHist,
                            onChange = { tone = it },
                            // 松手时打一行：调色不进引擎，所以"没反应"只可能是 Layer 2
                            // 自己出问题。这一行把当时的参数原样记下来，日志能复现。
                            onCommit = {
                                // [r38] 松手：把这一轮钉成全分辨率（拖动期可能降到了半分辨率），
                                // 顺便把实测耗时写进日志 —— "卡不卡"以后有数字可查，不用凭感觉。
                                toneSettleReq = true
                                toneSettle++
                                if (!tone.isIdentity) {
                                    Diagnostics.log("tone", "Layer2 ${previewBase?.width}x${previewBase?.height} " +
                                        "色温=${"%.0f".format(tone.temp)} 色调=${"%.0f".format(tone.tint)} " +
                                        "曝光=${"%.2f".format(tone.exposure)} 对比=${"%.0f".format(tone.contrast)} " +
                                        "饱和=${"%.0f".format(tone.saturation)} 暗角=${"%.0f".format(tone.vignette)} " +
                                        "耗时=${toneMs.get()}ms${if (toneFast) "(拖动期降半分辨率)" else ""}")
                                }
                            },
                            reduceMotion = reduceMotion,
                        )
                        2 -> FormatTab(
                            formatIdx = formatIdx, onFormat = { formatIdx = it; renderTick++ },
                            antihalationRemoved = antihalationRemoved, onAntihalation = { antihalationRemoved = it; renderTick++ },
                            halation = halationAmount, onHalationChange = { halationAmount = it }, onHalationCommit = { renderTick++ },
                            grain = grainAmount, onGrainChange = { grainAmount = it }, onGrainCommit = { renderTick++ },
                            // [r38] 负片反差：SHOOT 层，松手才重冲负片
                            densityGamma = densityGamma, onDensityGamma = { densityGamma = it },
                            onDensityGammaCommit = { renderTick++ },
                            overscanActive = overscanActive, onOverscanActive = { on ->
                                // 开启片边时按当前照片比例自动匹配最接近的 gate 格式，避免照片被大量裁切破坏画面
                                if (on) { overscanFormat = osBestFormat(sourceW, sourceH); forceNoOverscan = false }
                                overscanActive = on; renderTick++
                            },
                            overscanFormat = overscanFormat, onOverscanFormat = {
                                // [r5] 换片幅就把"临时省略边码文字"撤销：上一片幅卡住不代表这一片幅也卡
                                forceNoEdgeText = false
                                overscanFormat = it; renderTick++
                            },
                            overscanFog = overscanFog, onOverscanFog = { overscanFog = it }, onOverscanFogCommit = { renderTick++ },
                            overscanLeaks = overscanLeaks, onOverscanLeaks = { overscanLeaks = it }, onOverscanLeaksCommit = { renderTick++ },
                            overscanGate = overscanGate, onOverscanGate = { overscanGate = it; renderTick++ },
                            overscanHoles = overscanHoles, onOverscanHoles = { overscanHoles = it; renderTick++ },
                            overscanCarrier = overscanCarrier, onOverscanCarrier = { overscanCarrier = it; renderTick++ },
                            overscanEdgeText = overscanEdgeText,
                            onOverscanEdgeText = { if (it) forceNoEdgeText = false; overscanEdgeText = it; renderTick++ },
                            onReseed = { overscanCameraSeed = 1 + (Math.random() * 1e6).toInt(); overscanFrameSeed = 1 + (Math.random() * 1e6).toInt(); renderTick++ },
                            dateBack = dateBack, onDateBack = { dateBack = it }, onDateBackCommit = { renderTick++ },
                            // [r11 第 3 批] 片边/日期补充
                            edgeExtra = EdgeExtraCfg(
                                fNumber = overscanFNumber, frameNumber = overscanFrameNumber,
                                insetX = dateInsetX, insetY = dateInsetY,
                            ),
                            onEdgeExtra = { e ->
                                overscanFNumber = e.fNumber; overscanFrameNumber = e.frameNumber
                                dateInsetX = e.insetX; dateInsetY = e.insetY
                            },
                            onEdgeExtraCommit = { renderTick++ },
                            // [r11 第 2 批] 几何。滑杆拖动只改值，松手才渲染。
                            cropRatioIdx = cropRatioIdx, onCropRatio = { cropRatioIdx = it; renderTick++ },
                            rotationDeg = rotationDeg, onRotation = { rotationDeg = it },
                            onRotationCommit = { renderTick++ },
                            quarterTurns = quarterTurns, onQuarterTurns = { quarterTurns = it; renderTick++ },
                            flipH = flipH, onFlipH = { flipH = it; renderTick++ },
                            flipV = flipV, onFlipV = { flipV = it; renderTick++ },
                            // [r11 第 1 批] 暗房 / 相机：状态仍是各自独立的 var，这里组装成
                            // Cfg 传进去，回调再写回 —— 不重构成大对象，避免动到已有渲染逻辑。
                            darkroom = DarkroomCfg(
                                exposure = printExposure, yShift = yFilterShift, mShift = mFilterShift,
                                preflash = preflashExposure, glare = glareAmount,
                                dirCouplers = dirCouplersAmount, halationScatter = halationScatter,
                                cmActive = cmActive, cmHighlights = cmHighlights,
                                cmShadows = cmShadows, cmCore = cmCore, cmScale = cmScale,
                            ),
                            onDarkroom = { d ->
                                printExposure = d.exposure; yFilterShift = d.yShift; mFilterShift = d.mShift
                                preflashExposure = d.preflash; glareAmount = d.glare
                                dirCouplersAmount = d.dirCouplers; halationScatter = d.halationScatter
                                cmActive = d.cmActive; cmHighlights = d.cmHighlights
                                cmShadows = d.cmShadows; cmCore = d.cmCore; cmScale = d.cmScale
                            },
                            onDarkroomCommit = { renderTick++ },
                            // [r18] 「校色 / 原色」的入口在胶片页（现在是导航行右端的微型两档）；
                            // 暗房组里另有一份带解释的开关，两处共用一个状态。
                            camera = CameraCfg(
                                filter = cameraFilter, aeMethod = autoExposureMethod, lensBlur = lensBlurUm,
                            ),
                            onCamera = { c ->
                                cameraFilter = c.filter; autoExposureMethod = c.aeMethod; lensBlurUm = c.lensBlur
                            },
                            onCameraCommit = { renderTick++ },
                            // [r19] 色彩模式的第二个入口（暗房组里），与胶片页那排
                            // 「校色 / 原色」共享同一个状态。
                            filmTrueColor = filmTrueColor, onFilmTrueColor = { filmTrueColor = it },
                            trueColorCastM = if (filmTrueColor) (filmCastCc(filmVariant)?.get(0) ?: 0f) else 0f,
                            trueColorCastY = if (filmTrueColor) (filmCastCc(filmVariant)?.get(1) ?: 0f) else 0f,
                            // 日期后背的「DATA」面需要这张照片自己的 EXIF 数据
                            hasShootingData = photoDataLine.isNotBlank(),
                            reduceMotion = reduceMotion,
                        )
                    }
                }
                }
            }

            // ---------- 「更多」动作表（底部上滑，iOS 动作表形态） ----------
            if (showMore) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .iosTap(pressDim = 1f) { showMore = false }) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = Dv.rSheet, topEnd = Dv.rSheet))
                            .background(Dv.panel).padding(bottom = 10.dp)
                    ) {
                        Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                            SheetGrabber(active = false)
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("更多", color = Dv.label1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp)
                            Spacer(Modifier.weight(1f))
                            DvIconButton(Glyph.Close, "关闭", { showMore = false }, tint = Dv.label3, box = 36.dp,
                                glyphSize = 16.dp, bg = Dv.panelRaised)
                        }
                        Hairline(alpha = 0.07f)
                        ActionRow("复制成片到相册") {
                            showMore = false
                            val bmp = preview
                            if (bmp != null) {
                                // [r19] 原来固定叫 "copy.png"：连复制两张就成了同一个名字，
                                // 相册里分不出先后。改成 片名_胶片_时间戳。
                                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                    .format(java.util.Date())
                                val nm = "Deyva_${labelOf(filmVariant).replace(" ", "")}_$stamp"
                                val meta = "胶片: ${labelOf(filmVariant)} · 相纸: ${labelOf(printVariant)}\n" +
                                    "曝光: ${"%.1f".format(exposureEv)} EV\n复制自 Deyva 预览"
                                val r = Exporter.encodeAndSave(ctx, bmp, ExportFormat.PNG, "sRGB", 100, true, nm, meta)
                                status = if (r.first) "已复制一份到相册" else "复制失败"
                            } else status = "暂无成片可复制"
                        }
                        ActionRow("分享当前成片") {
                            showMore = false
                            val p = preview
                            if (p == null) { status = "还没有可分享的成片，请先冲洗照片" } else {
                                Thread {
                                    runCatching {
                                        val f = File(ctx.cacheDir, "share_${System.currentTimeMillis()}.jpg")
                                        val bos = ByteArrayOutputStream()
                                        p.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                                        FileOutputStream(f).use { it.write(bos.toByteArray()) }
                                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                                        val itn = Intent(Intent.ACTION_SEND).apply {
                                            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching { ctx.startActivity(Intent.createChooser(itn, "分享成片")) }
                                            .onFailure { e -> Diagnostics.log("share", "分享启动失败: ${e.message}") }
                                    }.onFailure { e -> Diagnostics.log("share", "分享编码失败: ${e.message}") }
                                }.start()
                                status = "正在准备分享成片…"
                            }
                        }
                        ActionRow("保存配方…") {
                            showMore = false
                            showSaveRecipe = true
                        }
                        ActionRow("我的配方（${recipes.size}）") {
                            showMore = false
                            recipes = RecipeStore.load(ctx)
                            showRecipeList = true
                        }
                        ActionRow("关于 Deyva") {
                            showMore = false
                            showAbout = true
                        }
                        // [r15] 署名：动作表最底一行，不抢功能项，但一定看得到
                        Hairline(alpha = 0.06f)
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("DEYVA · FILM LAB", color = Dv.label4, fontSize = 9.5f.sp,
                                letterSpacing = 1.6.sp)
                            Spacer(Modifier.weight(1f))
                            Text("作者 Vetus", color = Dv.label3, fontSize = 11.sp, letterSpacing = 0.4.sp)
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }

    // ===== 关于（r30）=====
    // 许可证全文与构建校验都在这里落地。以前这一项只往 status 写一行版本号，
    // 4.5 秒后自动消失 —— 等于"关于"页什么也没告诉用户。
    if (showAbout) {
        AboutSheet(ctx) { showAbout = false }
    }

    // ===== 重置确认（r19）=====
    // 这个 ↺ 现在会清掉全部可调项，误触一次代价太高，所以先亮清楚"会清什么"再动手。
    // 形态与「更多」同一套底部动作表：贴在拇指落点上，不挡画面中间。
    if (showResetConfirm) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .iosTap(pressDim = 1f) { showResetConfirm = false }) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Dv.rSheet, topEnd = Dv.rSheet))
                    .background(Dv.panel).padding(bottom = 10.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                    SheetGrabber(active = false)
                }
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("恢复默认参数", color = Dv.label1, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${labelOf(filmVariant)} · ${labelOf(printVariant)}",
                        color = Dv.label3, fontSize = 10.5f.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Text("会清空：曝光与影调 · 裁剪/拉直/转向/翻转 · 片边与日期后背 · 暗房滤片与预闪 · 相机 · 色彩模式。\n胶片与相纸的选择保留。",
                    color = Dv.label2, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp))
                Spacer(Modifier.height(10.dp))
                ActionRow("恢复默认参数", danger = true) {
                    showResetConfirm = false
                    resetAllAdjustments()
                    status = "已恢复默认参数（胶片与相纸保持不变）"
                }
                ActionRow("取消") { showResetConfirm = false }
                Spacer(Modifier.height(2.dp))
            }
        }
    }

    // ===== 配方对话框（r11 第 0 批）=====
    if (showSaveRecipe) {
        SaveRecipeDialog(
            defaultName = "${labelOf(filmVariant)} + ${labelOf(printVariant)}",
            onDismiss = { showSaveRecipe = false },
            onSave = { name ->
                val rec = RecipeStore.add(ctx, name, recipeSnapshot())
                recipes = RecipeStore.load(ctx)
                showSaveRecipe = false
                status = if (rec != null) "已保存配方「${rec.name}」" else "保存配方失败（详见日志）"
            },
        )
    }
    if (showRecipeList) {
        RecipeListDialog(
            recipes = recipes,
            onDismiss = { showRecipeList = false },
            onApply = { r ->
                status = if (applyRecipe(r.snapshot)) "已应用配方「${r.name}」" else "配方数据损坏，未应用"
                showRecipeList = false
            },
            onRename = { r -> recipeRenameTarget = r },
            onDelete = { r ->
                RecipeStore.remove(ctx, r.id)
                recipes = RecipeStore.load(ctx)
                status = "已删除配方「${r.name}」"
            },
        )
    }
    recipeRenameTarget?.let { r ->
        RenameRecipeDialog(
            current = r.name,
            onDismiss = { recipeRenameTarget = null },
            onRename = { nn ->
                RecipeStore.rename(ctx, r.id, nn)
                recipes = RecipeStore.load(ctx)
                recipeRenameTarget = null
                status = "配方已改名为「$nn」"
            },
        )
    }

    if (showExport) {
        AlertDialog(onDismissRequest = { if (!exporting) showExport = false }, containerColor = Dv.panelRaised,
            // 导出中不要标题：卡片自己带眉标和读数，再压一行"正在导出"就是同一句话说两遍
            // [r21] 标题栏交还给面板自己：面板顶栏已经有「导出 + 源片尺寸 + ×」，
            // 再压一行 Material 的标题就是同一句话说两遍，白占 40dp。
            title = null,
            text = {
                if (exporting) {
                    // [r15] 导出进度重做。旧版是 Material 对话框里塞一张玻璃卡片：
                    // 一条 4dp 的条 + 「✓ ● ○」三个字符 + 五段 9sp 中文横排，
                    // 字号小到要凑近看，字符状态和标签还分两行，读起来像日志而不是进度。
                    //
                    // 新版按"仪表 + 轨道"两段组织：
                    //   · 上段是读数：等宽大字「3/5」+ 右侧百分比，一眼知道到哪；
                    //   · 下段是一条贯穿的五点轨道，已过点实心、当前点带光环、未到点灰，
                    //     步名只在下方出一行（"正在印相…"），不再五个小字挤一排。
                    val doneN = progress.count { it.done }
                    val total = progress.size.coerceAtLeast(1)
                    val frac by animateFloatAsState(doneN.toFloat() / total, fluidSpec(reduceMotion))
                    val cur = progress.firstOrNull { it.active }?.label
                    val allDone = doneN == total
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Dv.glassBar).padding(horizontal = 18.dp, vertical = 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("EXPORTING", color = Dv.accent, fontSize = 9.5f.sp,
                                fontWeight = FontWeight.SemiBold, letterSpacing = 2.2.sp)
                            Spacer(Modifier.weight(1f))
                            Text(if (allDone) "完成" else "阶段 ${doneN + 1}/$total",
                                color = Dv.label3, fontSize = 10.sp, letterSpacing = 0.6.sp)
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(exportInfo, color = Dv.label1, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(16.dp))
                        // 读数：整数用等宽大字，右侧百分比跟随平滑过的进度
                        Row(verticalAlignment = Alignment.Bottom) {
                            Mono("$doneN", color = Dv.paper, size = 34f, weight = FontWeight.Light)
                            Text("/$total", color = Dv.label3, fontSize = 15.sp,
                                modifier = Modifier.padding(start = 3.dp, bottom = 6.dp))
                            Spacer(Modifier.weight(1f))
                            Mono("${doneN * 100 / total}%", color = Dv.accent, size = 16f)
                        }
                        Spacer(Modifier.height(9.dp))
                        // 进度条：3dp 细条，走过的部分用一段很短的渐变收口，
                        // 不做整条高光，免得在暗房里像根霓虹灯管
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Dv.accentDim)) {
                            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight()
                                .background(Brush.horizontalGradient(
                                    listOf(Dv.accent.copy(alpha = 0.55f), Dv.accent))))
                        }
                        Spacer(Modifier.height(17.dp))
                        // 五点轨道
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            progress.forEachIndexed { i, s ->
                                val lit = s.done || s.active
                                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                    if (s.active && !allDone) Box(Modifier.size(14.dp).clip(CircleShape)
                                        .background(Dv.accent.copy(alpha = 0.18f)))
                                    Box(Modifier.size(7.dp).clip(CircleShape)
                                        .background(if (lit) Dv.accent else Dv.label4))
                                }
                                if (i != progress.lastIndex) {
                                    Box(Modifier.weight(1f).height(1.dp)
                                        .background(if (s.done) Dv.accent.copy(alpha = 0.45f)
                                                    else Dv.label4.copy(alpha = 0.55f)))
                                }
                            }
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(if (allDone) "已完成" else "正在${cur ?: "冲洗"}…",
                            color = if (allDone) Dv.paper else Dv.orangeLight, fontSize = 12.5f.sp,
                            letterSpacing = 0.3.sp)
                        val ok = exportOk
                        when (ok) {
                            true -> {
                                Spacer(Modifier.height(6.dp))
                                Text("已加入${if (exportInfo.contains("照片")) "“照片”" else "文件"}。",
                                    color = Dv.label2, fontSize = 12.sp)
                            }
                            false -> {
                                Spacer(Modifier.height(6.dp))
                                Text("导出失败，请点“诊断”导出日志。", color = Dv.red, fontSize = 12.sp)
                            }
                            else -> {}
                        }
                    }
                } else {
                    Column {
                        ExportPanel(
                            // [r19] 这里原来显示 "IMG_72031 · 1张" —— 那个 IMG_ 号是当场随机
                            // 生成的，既不是文件名（文件名在下面按命名 token 拼）也不是时间，
                            // 还永远写着"1张"。换成源片的真实像素尺寸：唯一有信息量的一项。
                            imgName = if (sourceW > 0 && sourceH > 0) "$sourceW×$sourceH" else "—",
                            sourceW = sourceW, sourceH = sourceH,
                            // [r11 第 4 批] 命名 token 的三份取值。直扫底片时相纸位写 film-scan：
                            // 那份文件确实没有经过相纸，名字里不该出现纸名。
                            srcName = srcName, filmName = labelOf(filmVariant),
                            printName = if (scanFilm) "film-scan" else labelOf(printVariant),
                            onExport = { fmt, color, sizeL, q, saveDest, stem, onDone ->
                                val eng = engine; val sess = session
                                if (eng == null || sess == 0L) { status = "引擎未就绪，无法导出"; onDone(); return@ExportPanel }
                                exporting = true; exportOk = null
                                exportInfo = "$stem.${fmt.ext}"
                                Thread {
                                    try {
                                        // [r20] HEIF 容器里没有 ICC 的位置，像素只能按 sRGB 解释：
                                        // 这一项固定用 sRGB 渲染（面板里也把"色彩"藏了），
                                        // 否则 P3 的像素会被相册当 sRGB 显示，整体偏灰。
                                        val renderColor = if (fmt == ExportFormat.HEIF) "sRGB" else color
                                        Diagnostics.log("export", "开始导出 fmt=${fmt.label} color=$renderColor size=$sizeL q=$q dest=$saveDest stem=$stem")
                                        // [r5] 导出原来在裸线程里直接用主会话渲染，与预览渲染并发跑在
                                        // 同一个 VkDevice 上（GPU 挂起的一个来源）。收进闸门串行执行。
                                        val fullFrame = withEngineGate(60000L, "export full") {
                                            eng.setParams(sess, "{\"output_color_space\":\"$renderColor\",\"output_cctf_encoding\":true}")
                                            setStep(1, true, false); setStep(2, false, true)
                                            eng.renderFrame(sess, "full")
                                        }
                                        // [r37] 导出走和预览同一条 16 bit 路径：全尺寸是断层最容易
                                        // 被看见的地方（放大看天空、看灰阶过渡），降到 8 bit 再调色
                                        // 等于把引擎算出的精度扔掉。
                                        val fullRaw = fullFrame?.toBitmap()
                                        if (fullRaw == null || fullFrame == null) { Diagnostics.log("export", "full 渲染失败 ${eng.lastError()}"); exportOk = false; status = "导出失败：全尺寸渲染失败"; onDone(); return@Thread }
                                        setStep(2, true, false); setStep(3, false, true)
                                        Diagnostics.log("export", "full 渲染 ${fullRaw.width}x${fullRaw.height}")
                                        // [r33] 调色必须在 HDR 封装**之前**，而且必须作用在 full 本身
                                        // 而不是放在最后对成品再套一遍：
                                        //  · gain map 是相对 SDR 主图算的，主图先调色、增益图后生成，
                                        //    两者的色温和曲线才一致；反过来做，高光会和主图对不上；
                                        //  · EXIF 里的尺寸与指纹都取自 full，晚套一层就全错位了。
                                        // 用的是和预览完全相同的 Layer2 —— 预览看到什么就导出什么。
                                        // [r37] 优先走 16 bit：全尺寸导出是断层最容易被看见的地方。
                                        val full = if (tone.isIdentity) fullRaw else
                                            (Layer2.apply16(fullFrame.px, fullFrame.w, fullFrame.h, tone)
                                                ?: Layer2.apply(fullRaw, tone))
                                        if (!tone.isIdentity) {
                                            Diagnostics.log("export", "Layer2 已应用 ${full.width}x${full.height} " +
                                                "色温=${"%.0f".format(tone.temp)} 对比=${"%.0f".format(tone.contrast)} " +
                                                "曲线=${if (tone.curveRgb != Layer2.LINEAR) "已改" else "线性"} " +
                                                "暗角=${"%.0f".format(tone.vignette)}")
                                        }
                                        var out = full
                                        // [r27] 配方文本抽成一个带尺寸入参的小函数：HDR JPEG 要在
                                        // 缩放之前就把 EXIF 算好（它走预编码字节，绕过了后面那条
                                        // 写 EXIF 的链），而普通格式要用缩放**之后**的真实像素数。
                                        val nowS = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
                                        val srcExif = ExifInherit.read(ctx, srcUri)
                                        fun exportMeta(mw: Int, mh: Int): String = buildString {
                                            append("胶片: ${labelOf(filmVariant)}")
                                            append(if (scanFilm) " · 直扫底片（未经相纸）" else " · 相纸: ${labelOf(printVariant)}")
                                            append("\n曝光: ${"%.1f".format(exposureEv)} EV")
                                            append("\n颗粒: ${"%.2f".format(grainAmount)}×")
                                            append("\n光晕: ${"%.2f".format(halationAmount)}×")
                                            append("\n移除防光晕层: ${if (antihalationRemoved) "是" else "否"}")
                                            append("\n色彩: $renderColor")
                                            // [r33] 调色摘要：一张看起来"和预览不一样"的成片，第一个
                                            // 要回答的问题就是"到底调了什么"。全零时不写这一行。
                                            if (!tone.isIdentity) {
                                                append("\n调色: 色温 ${"%.0f".format(tone.temp)}" +
                                                    " · 色调 ${"%.0f".format(tone.tint)}" +
                                                    " · 曝光 ${"%.2f".format(tone.exposure)} EV" +
                                                    " · 对比 ${"%.0f".format(tone.contrast)}" +
                                                    " · 饱和 ${"%.0f".format(tone.saturation)}")
                                                if (tone.curveRgb != Layer2.LINEAR || tone.curveR != Layer2.LINEAR ||
                                                    tone.curveG != Layer2.LINEAR || tone.curveB != Layer2.LINEAR)
                                                    append(" · 曲线已调")
                                                if (tone.vignette != 0f)
                                                    append(" · 暗角 ${"%.0f".format(tone.vignette)}")
                                            }
                                            append("\n尺寸: ${mw}×${mh}")
                                            append("\n冲洗于: $nowS")
                                        }
                                        // ---- Ultra HDR JPEG：全尺寸，主图=SDR 渲染（高光恢复派生 gain map）----
                                        // [r20] 这里从"只装 HDR 字节"改成"装任何已经在别处编好的字节"：
                                        // HDR JPEG（libultrahdr）和 HEIF（HeifWriter）都是这样，后面
                                        // 保存/分享两条路共用同一份，不需要再各写一遍分支。
                                        var preEncoded: ByteArray? = null
                                        if (fmt == ExportFormat.UHDR_JPEG) {
                                            // [r29] 真·HDR：先印一张「高光帧」——同一张负片、同一台放大机，
                                            // 只把印相曝光加大 8 倍（+3 档），整张照片变暗，原本被相纸趾部
                                            // 压成纸白的那一段高光因此重新有了梯度。
                                            //
                                            // 只有 RAW 值得这么做：8bit 输入里所有"白"都是同一个 255，
                                            // 负片密度相同，第二帧印出来也是一片平，读不出任何新信息。
                                            // 那种片子照旧走单帧（曲线提亮），并在说明里讲清楚。
                                            var hdrFrame: Bitmap? = null
                                            // [r37] 高光帧也留一份 16 bit，调色要和主图走同一精度
                                            var hdrFrame16: Frame16? = null
                                            var evStep = 0.0
                                            if (photoIsRaw) {
                                                val pe0 = printExposure
                                                val pe1 = (pe0 * 8f).coerceIn(0.05f, 20f)
                                                evStep = Math.log((pe1 / pe0).toDouble()) / Math.log(2.0)
                                                if (evStep >= 1.0) {
                                                    hdrFrame16 = withEngineGate(120000L, "export hdr frame") {
                                                        try {
                                                            eng.setParams(sess, "{\"print_exposure\":$pe1}")
                                                            eng.reprintFrame(sess, "full")
                                                                ?: run {
                                                                    // 没有负片缓存（比如刚导入就导出）就退回完整渲染
                                                                    Diagnostics.log("export", "高光帧 reprint 不可用，回退完整渲染")
                                                                    eng.renderFrame(sess, "full")
                                                                }
                                                        } finally {
                                                            // 无论成败都要把印相曝光还回去，否则接下来的
                                                            // 预览/再导出会全部偏暗
                                                            runCatching { eng.setParams(sess, "{\"print_exposure\":$pe0}") }
                                                        }
                                                    }
                                                    hdrFrame = hdrFrame16?.toBitmap()
                                                    Diagnostics.log("export",
                                                        "HDR 高光帧 print_exposure $pe0→$pe1 (+${"%.2f".format(evStep)}EV) " +
                                                        "ok=${hdrFrame != null} ${hdrFrame?.let { "${it.width}x${it.height}" }}")
                                                } else {
                                                    Diagnostics.log("export", "HDR 高光帧跳过：印相曝光已顶到 20，" +
                                                        "加不了档（$pe0→$pe1）")
                                                }
                                            } else {
                                                Diagnostics.log("export", "HDR 单帧：输入不是 RAW，没有超出纸白的余量可读")
                                            }
                                            // [r33] 高光帧也要过同一套调色。它是同一张负片、同一台
                                            // 放大机印的，只是曝光多三档 —— 扫描之后的调色当然要一致。
                                            // 只调主图会让 gain map 带着未调色的色偏，HDR 一亮起来就偏色。
                                            if (!tone.isIdentity) {
                                                val f16 = hdrFrame16
                                                val tinted = if (f16 != null)
                                                    Layer2.apply16(f16.px, f16.w, f16.h, tone) else null
                                                if (tinted != null) {
                                                    hdrFrame?.let { if (!it.isRecycled) it.recycle() }
                                                    hdrFrame = tinted
                                                    Diagnostics.log("export", "HDR 高光帧已同步调色（16 bit ${tinted.width}x${tinted.height}）")
                                                } else if (hdrFrame != null) {
                                                    // 16 bit 拿不到（不是 RAW、或渲染失败）时退回 8 bit：
                                                    // 色偏必须一致，这一点比精度重要。
                                                    val t8 = Layer2.apply(hdrFrame, tone)
                                                    if (t8 !== hdrFrame) { hdrFrame.recycle(); hdrFrame = t8 }
                                                    Diagnostics.log("export", "HDR 高光帧已同步调色（8 bit 回退）")
                                                }
                                            }
                                            // 16 bit 缓冲在 full 尺寸下是 4 字节/像素（24 MP ≈ 96 MB 的
                                            // ShortArray），调色用完立即放掉，别留到编码阶段和新分配抢内存。
                                            if (hdrFrame16 != null) hdrFrame16 = null
                                            runCatching {
                                                val exif1 = Exporter.exifApp1(ctx, full,
                                                    exportMeta(full.width, full.height), srcExif,
                                                    full.width, full.height)
                                                val hb = UltraHdrJpeg.encode(full, full.width, full.height, q, exif1,
                                                    hdrFrame, if (hdrFrame != null) evStep else UltraHdrJpeg.HDR_EV_STEP,
                                                    // 增益表按相纸选：8 张相纸的纸白锚点差到 100 个码值，
                                                    // 共用一张会把换相纸的增益整体算高或算低。直扫底片没有相纸，传 null。
                                                    if (scanFilm) null else printVariant)
                                                preEncoded = hb
                                                Diagnostics.log("export", "HDR JPEG 封装 ${full.width}x${full.height} bytes=${hb.size} exif=${exif1?.size ?: 0}B 模式=${if (hdrFrame != null) "双帧 +${"%.2f".format(evStep)}EV" else "单帧合成"}")
                                            }.onFailure { e -> Diagnostics.log("export", "HDR JPEG 编码异常 ${e.message}") }
                                            hdrFrame?.let { if (!it.isRecycled) it.recycle() }
                                            if (preEncoded == null) { exportOk = false; status = "导出失败：HDR 数据获取失败"; onDone(); return@Thread }
                                        }
                                        if (sizeL != "全尺寸" && preEncoded == null) {
                                            val targetEdge = sizeL.toInt()
                                            if (maxOf(out.width, out.height) > targetEdge) {
                                                val s = targetEdge.toFloat() / maxOf(out.width, out.height)
                                                out = Bitmap.createScaledBitmap(out, (out.width * s).toInt(), (out.height * s).toInt(), true)
                                            }
                                        }
                                        setStep(3, true, false); setStep(4, false, true)
                                        // [r11 第 4 批] 文件名由命名 token 拼出来（原片名/胶片/相纸/日期），
                                        // 不再是 "IMG_秒数"。重名时 Exporter 会在文件目录里自动加 _2，
                                        // 相册那边由 MediaStore 自己去重 —— 都不会静默覆盖上一张。
                                        val name = stem
                                        // [r11 第 4 批] 配方详情 + 继承的拍摄信息，两条保存路径共用
                                        // （共享出去的也是成片，凭什么不带 EXIF）。EXIF 是旁路信息，
                                        // 永远不进引擎；读不到源片就退回"只写成片标签"的老行为。
                                        val meta = exportMeta(out.width, out.height)
                                        Diagnostics.log("export", "继承源片 EXIF ${srcExif.size} 条 ${srcExif.keys.take(6)}")
                                        // [r20] HEIF 必须缩放之后再编码（HeifWriter 的花费跟像素数成正比，
                                        // 先编全尺寸再缩等于白算一遍）；也不能走 Exporter.encodeBytes ——
                                        // 那条路只有一条 JPEG 编解码链，没有 HEVC。
                                        if (fmt == ExportFormat.HEIF) {
                                            val hb = HeifExport.encode(ctx, out, q, meta, srcExif)
                                            if (hb == null) {
                                                exportOk = false
                                                status = if (HeifExport.available) "导出失败：HEIF 编码失败"
                                                         else "此设备不支持 HEIF 编码（缺 HEVC 编码器）"
                                                onDone(); return@Thread
                                            }
                                            preEncoded = hb
                                            Diagnostics.log("export", "HEIF 编码 ${out.width}x${out.height} bytes=${hb.size}")
                                        }
                                        if (saveDest == 2) {
                                            // 共享：编码到缓存文件后调系统分享，不落相册/文件
                                            val f = File(ctx.cacheDir, "$name.${fmt.ext}")
                                            val hb = preEncoded
                                            val ok = runCatching {
                                                val bytes = hb ?: (Exporter.encodeBytes(out, fmt, renderColor, q, meta, srcExif)
                                                    ?: error("编码失败"))
                                                FileOutputStream(f).use { it.write(bytes) }
                                            }.isSuccess
                                            setStep(4, true, false)
                                            Diagnostics.log("export", "共享编码 ok=$ok 实际格式=${fmt.ext} ${out.width}x${out.height}")
                                            if (ok) {
                                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                                                val itn = Intent(Intent.ACTION_SEND).apply {
                                                    // [r20] 别再靠三元表达式数格式：ext 已经是权威的
                                                    // 扩展名，heic 之前就是在这条 if 链里被漏成 tiff 的。
                                                    type = when (fmt) {
                                                        ExportFormat.PNG -> "image/png"
                                                        ExportFormat.TIFF -> "image/tiff"
                                                        ExportFormat.HEIF -> "image/heic"
                                                        else -> "image/jpeg"
                                                    }
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching { ctx.startActivity(Intent.createChooser(itn, "分享成片")) }
                                                    .onFailure { e -> Diagnostics.log("export", "分享启动失败: ${e.message}") }
                                                exportInfo = "$name.${fmt.ext} · ${out.width}×${out.height} · $renderColor · 已打开分享"
                                            }
                                            exportOk = ok
                                            status = if (ok) "已导出成片（${fmt.label}·${out.width}×${out.height}）" else "导出失败"
                                        } else {
                                            val hb2 = preEncoded
                                            if (hb2 != null) {
                                                val ok = Exporter.saveBytes(ctx, hb2, fmt.ext, saveDest == 0, name)
                                                setStep(4, true, false)
                                                Diagnostics.log("export", "保存(预编码) ok=$ok ${fmt.label} ${out.width}x${out.height}")
                                                exportInfo = "$name.${fmt.ext} · ${out.width}×${out.height} · ${fmt.label} · 已存入${if (saveDest == 0) "“照片”" else "“文件”"}"
                                                exportOk = ok
                                                status = if (ok) "已导出 ${fmt.label}（${out.width}×${out.height}）" else "导出失败"
                                            } else {
                                                val (ok, actualExt) = Exporter.encodeAndSave(ctx, out, fmt, renderColor, q, saveDest == 0, name, meta, srcExif)
                                                setStep(4, true, false)
                                                Diagnostics.log("export", "保存 ok=$ok 实际格式=$actualExt ${out.width}x${out.height}")
                                                val dest = if (saveDest == 0) "照片" else "文件"
                                                exportInfo = "$name.$actualExt · ${out.width}×${out.height} · $renderColor · 已存入“$dest”"
                                                exportOk = ok
                                                status = if (ok) "已导出成片（${fmt.label}→${actualExt}·${out.width}×${out.height}）" else "导出失败"
                                            }
                                        }
                                        runCatching { eng.setParams(sess, "{\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true}") }
                                    } catch (e: Exception) {
                                        Diagnostics.log("export", "异常 ${e.message}")
                                        exportOk = false; status = "导出失败：${e.message}"
                                    } finally { onDone() }
                                }.start()
                            },
                            onClose = { showExport = false },
                        )
                    }
                }
            },
            confirmButton = { if (exporting) TextButton({ showExport = false; exporting = false }) { Text("完成", color = Orange) } },
            dismissButton = { if (!exporting) TextButton({ showExport = false }) { Text("取消", color = Dv.label2) } })
    }

    if (showLog) {
        val eng = engine
        val text = remember(eng) {
            buildString {
                append("==== 引擎能力 ====\n").append(eng?.capabilities ?: "(未创建)").append("\n\n")
                append("==== 参数 Schema ====\n").append(eng?.paramsSchema ?: "").append("\n\n")
                append("==== 运行日志 ====\n").append(Diagnostics.dump())
            }
        }
        AlertDialog(onDismissRequest = { showLog = false }, containerColor = Dv.panelRaised,
            title = { Text("诊断日志", color = Color.White, fontSize = 16.sp) },
            text = { Text(text, color = Dv.blue, fontSize = 8.sp, modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) },
            confirmButton = { TextButton({
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("spektra_log", text)); showLog = false; status = "诊断日志已复制，请粘贴发给开发者"
            }) { Text("复制全文", color = Orange) } },
            dismissButton = { TextButton({ showLog = false }) { Text("关闭", color = Dv.label2) } })
    }
    if (showGallery) GalleryPage(onClose = { showGallery = false })

    DisposableEffect(Unit) { onDispose { val e = engine; if (session != 0L) e?.sessionRelease(session); e?.destroy() } }
}

fun labelOf(variantId: String): String =
    (FILM_GROUPS + PRINT_GROUPS).firstOrNull { g -> g.variants.any { it.id == variantId } }?.variants?.firstOrNull { it.id == variantId }?.label ?: variantId

// 片边格式显示名 -> 引擎 kFormats 键
internal fun osFormatKey(label: String): String = when (label) {
    "135 Half" -> "135_half"
    "135 宽幅" -> "135_xpan"
    "120 6×4.5" -> "120_645"
    "120 6×6" -> "120_6x6"
    "120 6×7" -> "120_6x7"
    "120 6×8" -> "120_6x8"
    "120 6×9" -> "120_6x9"
    "120 6×12" -> "120_6x12"
    "120 6×17" -> "120_6x17"
    else -> label.lowercase()
}

// 片边门幅的长/短比（与 overscan_port.cpp 的 kFormats 实测 gate 尺寸一致，非臆想）
private fun osGateRatio(format: String): Double = when (format) {
    "135_half" -> 24.0 / 18.0          // gate 24×18（portrait 半幅）
    "120_645" -> 56.0 / 41.5           // gate 56×41.5（Mamiya）
    "120_6x6" -> 1.0                   // 方幅 56×56
    "120_6x7" -> 69.5 / 56.0           // gate 69.5×56
    "120_6x8" -> 76.0 / 56.0
    "120_6x9" -> 84.0 / 56.0
    "135_xpan" -> 65.0 / 24.0
    "120_6x12" -> 112.0 / 56.0
    "120_6x17" -> 168.0 / 56.0
    else -> 36.25 / 24.3               // "135"：实测 36.25×24.3，3:2
}

// 开启片边时按当前照片比例自动选最接近的 gate 格式，让照片几乎不被裁切（片边仍引擎真实）
private fun osBestFormat(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return "135"
    val r = maxOf(w, h).toDouble() / minOf(w, h).toDouble()
    return listOf(
        "135" to 36.25 / 24.3, "135_half" to 24.0 / 18.0, "120_645" to 56.0 / 41.5,
        "120_6x6" to 1.0, "120_6x7" to 69.5 / 56.0, "120_6x8" to 76.0 / 56.0,
        "120_6x9" to 84.0 / 56.0, "135_xpan" to 65.0 / 24.0, "120_6x12" to 112.0 / 56.0,
        "120_6x17" to 168.0 / 56.0
    ).minBy { kotlin.math.abs(it.second - r) }.first
}

// ---------------------------------------------------------------------------
// 日期后背（RFC-031）的界面状态。
// 与片边一样属于"拍摄层"（SHOOT，非 live）：改一个值就要重冲一次负片。
// ---------------------------------------------------------------------------
data class DateBackCfg(
    val active: Boolean = false,
    val style: String = "lcd",        // lcd 七段液晶 | dots 点阵 | data 拍摄数据
    val placement: String = "frame",  // frame 画面内四角 | rebate 齿孔间(135)/片边(120)
    val corner: String = "br",        // br 右下 | bl 左下 | tr 右上 | tl 左上
    val size: Float = 1f,             // 0.4…3，相对基准字号
    val ev: Float = 3.5f,             // −2…8，印字的曝光量（浓淡）
)

/**
 * 暗房（放大机 + 胶片细节强度）。r11 第 1 批。
 *
 * 默认值全部与引擎默认一致（print_exposure=1、滤片=0、预闪=0、
 * glare/dir_couplers/scatter=1.0），所以「一进 App 不动它」的出片与 r10 完全相同。
 *
 * 前 4 项是 PRINT 层里**不需要重建管线**的字段（params.cpp:36-38 的判定函数），
 * 滑杆拖动只会重印相纸；后 3 项分别是 PRINT 的 glare 与 SHOOT 的 dir_couplers /
 * halation.scatter，改后 2 项仍要重冲（但滑杆松手才提交，可接受）。
 */
data class DarkroomCfg(
    val exposure: Float = 1f,        // print_exposure 0.05…20（引擎是反的：少曝光更亮）
    val yShift: Float = 0f,          // y_filter_shift −1…1（黄滤片 → 更蓝的相）
    val mShift: Float = 0f,          // m_filter_shift −1…1（品红滤片 → 更绿的相）
    val preflash: Float = 0f,        // preflash_exposure 0…1，有用区间 0…0.03，UI 用 ×100
    val glare: Float = 1f,           // glare_amount 1…30 的「倍数」语义：这里 0…3
    val dirCouplers: Float = 1f,     // dir_couplers_amount 0…4
    val halationScatter: Float = 1f, // halation_scatter_amount 0…1
    // 对比度蒙版（r33）：全部是 PRINT 层，改只重印相纸。默认全是引擎默认值
    // （active=false、两个档数=0、core=1.0、scale=0.03），不动它时出片完全不变。
    val cmActive: Boolean = false,
    val cmHighlights: Float = 0f,    // contrast_mask_highlights 0…3 档
    val cmShadows: Float = 0f,       // contrast_mask_shadows 0…3 档
    val cmCore: Float = 1f,          // contrast_mask_core 0…3 档
    val cmScale: Float = 0.03f,      // contrast_mask_scale 0.002…0.12
)

/** 相机侧（r11 第 1 批）。滤片与测光都是 SHOOT 非 live：改一次要重冲负片。 */
data class CameraCfg(
    val filter: String = "",              // "" = 无；w8…w58 见 CAMERA_FILTERS
    // 必须是引擎默认值 "center_weighted"（params.hpp:325）：换成别的会让 solve()
    // 的测光结果变化，等于一进 App 就改掉了现有出片的曝光。
    val aeMethod: String = "center_weighted",
    val lensBlur: Float = 0f,             // lens_blur_um 0…200
)

// Kodak Wratten 相机滤色片（engine/src/core/camera_filters.hpp 的实际光谱表）
val CAMERA_FILTERS: List<Pair<String, String>> = listOf(
    "" to "无",
    "w8" to "黄 No.8",
    "w11" to "黄绿 No.11",
    "w12" to "深黄 No.12",
    "w15" to "深橙 No.15",
    "w21" to "橙 No.21",
    "w25" to "红 No.25",
    "w47" to "蓝 No.47",
    "w58" to "绿 No.58",
)

// 测光方式（params.cpp:361-366 的合法值）。第一个是引擎默认，保持现状用。
val AE_METHODS: List<Pair<String, String>> = listOf(
    "center_weighted" to "平均·加权（默认）",
    "balanced" to "均衡",
    "center" to "中央重点",
    "average" to "平均",
    "median" to "中值",
    "protect_highlights" to "保高光",
    "protect_shadows" to "保暗部",
)

/**
 * 片边 / 日期的补充参数（r11 第 3 批）。默认值全部与引擎默认一致，
 * 所以「一进 App 不动它」的片边输出与 r8 修好的版本完全相同。
 */
data class EdgeExtraCfg(
    val fNumber: Float = 0f,       // overscan_f_number：0 = 不印（引擎语义 >0 才印）
    val frameNumber: Int = 0,      // overscan_frame_number：0 = 由随机种子决定帧号
    val insetX: Float = 3f,        // date_imprint_inset_x，mm（引擎默认 3.0）
    val insetY: Float = 2.4f,      // date_imprint_inset_y，mm（引擎默认 2.4）
)

// 常见光圈档位（0 = 不印）。用胶囊而不是滑杆：光圈是离散档位，
// 拖滑杆永远停不到 f/5.6。
val F_STOPS: List<Float> = listOf(0f, 1.4f, 2f, 2.8f, 4f, 5.6f, 8f, 11f, 16f, 22f)

// 片幅 -> 胶片规格。同一卷胶片在 135 与 120 上印的字并不一样，边码表按规格分两列。
private fun edgeGauge(format: String): String =
    if (format == "135" || format == "135_half" || format == "135_xpan") "135" else "120"

// 片边的印刷名，逐条照搬开源 Session+FilmEdge.swift::edgeText(for:gauge:) 的实测表
// （owner 的 reference_film/ 片条，2026-10-03）：值为 (135 印的字, 120 印的字)。
// 这些是胶片厂商自己印在片基上的字，不是产品目录名 —— Gold 200 印 "GB 200-7"，
// Ektachrome 印 "E100"，Provia 印 "FUJI RDPIII"，Vision3 印 Eastman 编号。
private val EDGE_PRINT: Map<String, Pair<String, String>> = mapOf(
    // 表内字符串与原版引擎 `Session+FilmEdge.swift::edgeText(for:gauge:)` 逐字一致：
    // 胶片自己印在边上的字（不是 App 里显示的名字）。表外的胶片沿用原版的规则——
    // 整条型号名大写（含厂商），两边同一种印法。
    "kodak_portra_400" to ("KODAK PORTRA 400" to "KODAK PORTRA 400"),
    "kodak_portra_160" to ("KODAK PORTRA 160" to "KODAK PORTRA 160"),
    "kodak_ektar_100" to ("KODAK EKTAR 100" to "KODAK EKTAR 100"),
    "kodak_gold_200" to ("KODAK GB 200-7" to "KODAK 200"),
    "kodak_portra_800" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
    "kodak_portra_800_push1" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
    "kodak_portra_800_push2" to ("KODAK PORTRA 800-3" to "KODAK PORTRA 800"),
    "kodak_ultramax_400" to ("KODAK GC 400" to "KODAK GC 400"),
    "kodak_ektachrome_100" to ("KODAK E100" to "KODAK E100"),
    "fujifilm_c200" to ("FUJI 200" to "FUJI 200"),
    "fujifilm_xtra_400" to ("S-400" to "FUJI S-400"),
    "fujifilm_pro_400h" to ("PRO400H" to "FUJI PRO400H"),
    "fujifilm_provia_100f" to ("FUJI RDPIII" to "FUJI RDPIII"),
    "fujifilm_velvia_100" to ("RVP100" to "FUJI RVP100"),
    "kodak_vision3_50d" to ("EASTMAN 5203" to "EASTMAN 5203"),
    "kodak_vision3_250d" to ("EASTMAN 5207" to "EASTMAN 5207"),
    "kodak_vision3_200t" to ("EASTMAN 5213" to "EASTMAN 5213"),
    "kodak_vision3_500t" to ("EASTMAN 5219" to "EASTMAN 5219"),
    "cinestill_800t" to ("CINESTILL 800T" to "CINESTILL 800T"),
    "kodak_tri_x_400" to ("KODAK 400TX" to "KODAK 400TX"),
    "kodak_tmax_100" to ("KODAK 100TMX" to "KODAK 100TMX"),
    "fujifilm_neopan_acros_100_ii" to ("FUJI 100 ACROS II" to "FUJI 100 ACROS II"),
    "ilford_hp5_plus_400" to ("ILFORD HP5 PLUS" to "ILFORD HP5 PLUS"),
    "cinestill_50d" to ("CINESTILL 50D" to "CINESTILL 50D"),
    "kodak_verita_200d" to ("KODAK VERITA 200D" to "KODAK VERITA 200D"),
    "kodak_kodachrome_64" to ("KODAK KODACHROME 64" to "KODAK KODACHROME 64"),
)

// 表外的胶片退回型号名本身（大写）。与开源的 `stock.name.uppercased()` 同义：
// 引擎会按该胶片的版式再截断到能放下的长度，所以这里不必手工缩短。
private fun edgeTextFor(filmId: String, gauge: String): String {
    EDGE_PRINT[filmId]?.let { return if (gauge == "120") it.second else it.first }
    val label = ALL_VARIANTS.firstOrNull { it.id == filmId }?.label ?: filmId
    return label.substringBefore(" · ").trim().uppercase()
}

// JSON 字符串转义（片边名与日期都在 JSON 里，引号/反斜杠必须转义）
// ───────────────────────────── 原色 / 校色 ─────────────────────────────
//
// 引擎对每支胶片都有一套「中性滤片」（engine/resources/neutral_print_filters.json：
// 按 相纸 → 光源 → 胶片 给出 c/m/y 三支滤片的补偿量），印相前由 digest() 装进
// enlarger.*_filter_neutral。它的作用是把每支片的负片都**配平到中性** —— 这是彩扩机
// 的做法，物理上没错，但它同时也是"26 支胶片印出来一模一样"的根因：
// Portra 400 用 M52/Y53、Fuji C200 用 M70/Y84、Vision3 500T 用 M29/Y35，各自被自己的
// 滤片拉回同一个点，最后就只剩一点点曲线差异。
//
// 「原色」= 不再各配各的，所有胶片共用同一套滤片（这张表里的均值），于是每支片自己
// 的色偏就按「自己的滤片 − 共用滤片」显出来。注意这不是随便加的滤镜：数值全部来自
// 上面那个 JSON，只是把"配平"这一步关掉。
//
// 为什么不能直接改 c/m/y 三个中性滤片：换胶片时 set_params 会 init_params + digest()，
// digest() 一定会用数据库把 c/m/y 改写回这支片自己的值（engine.cpp:1790）。所以这里
// 走的是 m/y_filter_shift —— 它是"在自身中性滤片之上再加多少 CC"的增量，digest 不碰，
// 印相时直接参与光谱计算（printing.cpp:100），并且是 live 字段（切换只重印，不重冲）。
//
// 一个 shift 单位 = FILTER_CC_SCALE CC。引擎默认 1，即滑杆推到头也只有 1 CC，等于没
// 接线；60 与真实放大机的滤片量程一致。
private const val FILTER_CC_SCALE = 60f

/** 胶片 id → 相对共用滤片的偏移（品红 CC，黄 CC）。数据取自 neutral_print_filters.json
 *  的 kodak_portra_endura / TH-KG3 一栏（各相纸之间同一支片的偏移差在 ±5 CC 以内，
 *  取一张相纸的量足够代表方向与幅度）。正数 = 比共用的更"黄/品红"。 */
private val FILM_CAST_CC: Map<String, FloatArray> = mapOf(
    "kodak_portra_400" to floatArrayOf(+1.9f, +1.7f),
    "kodak_portra_160" to floatArrayOf(-6.5f, +1.0f),
    "kodak_portra_800" to floatArrayOf(-12.4f, -11.8f),
    "kodak_portra_800_push1" to floatArrayOf(-12.6f, -13.8f),
    "kodak_portra_800_push2" to floatArrayOf(-12.8f, -15.3f),
    "kodak_gold_200" to floatArrayOf(-5.2f, -1.3f),
    "kodak_ektar_100" to floatArrayOf(-20.9f, -6.5f),
    "kodak_ultramax_400" to floatArrayOf(-5.2f, -4.9f),
    "kodak_verita_200d" to floatArrayOf(-9.8f, -12.9f),
    "kodak_vision3_50d" to floatArrayOf(+17.7f, +17.1f),
    "kodak_vision3_200t" to floatArrayOf(+23.6f, +21.0f),
    "kodak_vision3_250d" to floatArrayOf(+17.6f, +20.9f),
    "kodak_vision3_500t" to floatArrayOf(+24.2f, +18.9f),
    "fujifilm_pro_400h" to floatArrayOf(+20.0f, +29.6f),
    "fujifilm_xtra_400" to floatArrayOf(-17.5f, -31.2f),
    "fujifilm_c200" to floatArrayOf(-16.2f, -29.6f),
    // 反转片在表里本来统一是 50/50，与均值只差 3~4 CC —— 几乎看不出，但保持数据一致
    "kodak_ektachrome_100" to floatArrayOf(+3.5f, +4.3f),
    "kodak_kodachrome_64" to floatArrayOf(+3.5f, +4.3f),
    "fujifilm_velvia_100" to floatArrayOf(+3.5f, +4.3f),
    "fujifilm_provia_100f" to floatArrayOf(+3.5f, +4.3f),
    // 黑白片不在那张表里（digest 保留引擎默认 65/55），偏移就是"共用 − 默认"
    "fujifilm_neopan_acros_100_ii" to floatArrayOf(-11.5f, -0.7f),
    "ilford_hp5_plus_400" to floatArrayOf(-11.5f, -0.7f),
    "kodak_tmax_100" to floatArrayOf(-11.5f, -0.7f),
    "kodak_tri_x_400" to floatArrayOf(-11.5f, -0.7f),
    "cinestill_50d" to floatArrayOf(+17.7f, +17.1f),   // 与 Vision3 50D 同乳剂
    "cinestill_800t" to floatArrayOf(+24.2f, +18.9f),  // 与 Vision3 500T 同乳剂
)

private fun filmCastCc(filmId: String): FloatArray? = FILM_CAST_CC[filmId]

/**
 * 拼出「滤片刻度 + 两支滤片位移」这一段 delta（不含前后逗号）。
 * 校色模式（trueColor=false）时偏移为 0，只把用户自己的位移按 CC 刻度发下去。
 */
private fun filterShiftDelta(filmId: String, trueColor: Boolean,
                             userM: Float, userY: Float): String {
    val c = if (trueColor) filmCastCc(filmId) else null
    val m = (userM + (c?.get(0) ?: 0f) / FILTER_CC_SCALE).coerceIn(-0.999f, 0.999f)
    val y = (userY + (c?.get(1) ?: 0f) / FILTER_CC_SCALE).coerceIn(-0.999f, 0.999f)
    return "\"filter_shift_scale\":$FILTER_CC_SCALE,\"y_filter_shift\":$y,\"m_filter_shift\":$m"
}

// [r11·第0批] PRINT 层的参数字段（对应 params.cpp 里 LAYER == PRINT 的那些）。
// 只改这些时负片完全不变，可以用 spk_reprint 跳过重冲。
private val PRINT_LAYER_KEYS = setOf(
    "print_stock", "print_exposure", "m_filter_shift", "y_filter_shift",
    "c_filter_neutral", "m_filter_neutral", "y_filter_neutral",
    "preflash_exposure", "enlarger_illuminant", "glare_active", "glare_amount",
    "scanner_white_correction", "scanner_black_correction", "scanner_lens_blur",
    "output_color_space", "output_cctf_encoding", "extended_dynamic_range",
    "filter_shift_scale", "preview_long_edge",
    // [r33] 对比度蒙版全在 PRINT 层（params.cpp:108-116 标的就是 PRINT）：
    // 它只改变印相曝光，负片一个像素都不变。漏掉这几个键的话，拖一下蒙版滑杆
    // 会走完整渲染（重冲负片），白白多等一两秒。
    "contrast_mask_active", "contrast_mask_highlights", "contrast_mask_shadows",
    "contrast_mask_core", "contrast_mask_scale",
)
// 注意：`scan_film` **故意不在这张表里**。它虽然也是 PRINT 层字段，但引擎在
// overscan 的布局计算里按它选「被扫描的是胶片还是相纸」（overscan.cpp:767
// `params_.io.scan_film ? params_.film : params_.print`）—— 那是负片阶段产出的
// 片边缓存。若走 reprint（复用缓存负片）就沿用了旧布局，片基颜色会错。
// 所以切换直扫底片一律走完整渲染：一个罕见开关，不值得为它冒缓存不一致的险。

/**
 * 两个 delta 之间是否「只有 PRINT 层变化」。
 *
 * PRINT 层是配光之后印相纸那一段：换相纸、拨放大机滤片、改光泽强度，负片一个
 * 像素都不变，所以能 reprint 而不是重冲。这是"切相纸慢"的根因解。
 *
 * 返回 true 的条件：所有非 PRINT 字段完全相同，且至少有一个 PRINT 字段不同
 * （完全相同就没必要渲染，返回 false 让上层走正常路径）。
 */
private fun onlyPrintLayerChanged(prevJson: String, curJson: String): Boolean {
    val a = runCatching { org.json.JSONObject(prevJson) }.getOrNull() ?: return false
    val b = runCatching { org.json.JSONObject(curJson) }.getOrNull() ?: return false
    val keys = a.keys().asSequence().toSet() + b.keys().asSequence().toSet()
    var printDiff = false
    for (k in keys) {
        val same = (a.opt(k)?.toString() ?: "") == (b.opt(k)?.toString() ?: "")
        if (k in PRINT_LAYER_KEYS) {
            if (!same) printDiff = true
        } else if (!same) {
            return false          // 非 PRINT 字段变了 → 必须重冲
        }
    }
    return printDiff
}

// [r11·第2批] 裁剪比例：值是「目标宽 / 高」，0 = 不裁（原始构图）。
val CROP_RATIOS: List<Pair<String, Double>> = listOf(
    "原始" to 0.0,
    "1:1" to 1.0,
    "4:5" to 4.0 / 5.0,
    "3:4" to 3.0 / 4.0,
    "2:3" to 2.0 / 3.0,
    "16:9" to 16.0 / 9.0,
    "9:16" to 9.0 / 16.0,
    "65:24" to 65.0 / 24.0,
)

/**
 * 算出居中裁剪窗（左上角 + 宽高，都是 0…1 归一化），并针对拉直做 refit。
 *
 * 引擎的 `geometry_crop_x/y` 是裁剪窗**左上角**（pipeline.cpp 用 crop_x + crop_w/2
 * 求中心），不是中心点 —— 这点写错就是 r1 那次「片门画面破损」。
 *
 * refit 的必要性：引擎先裁再按中心旋转，输出尺寸 = 裁剪尺寸。若旋转后源坐标
 * 落到裁剪窗之外，会取到窗外内容或被钳位，四角就出现拉伸。收缩到「旋转后仍能
 * 覆盖输出框」的最大内接矩形，就没有这个现象 —— 与上游 Geometry 的 fit 行为一致
 * （拖到 45° 时画面自动缩小到还放得下的部分）。
 */
private fun computeCrop(w: Double, h: Double, ratioIdx: Int, rotDeg: Double): DoubleArray {
    var cw = 1.0
    var ch = 1.0
    val ratio = CROP_RATIOS.getOrNull(ratioIdx)?.second ?: 0.0
    if (ratio > 0.0 && w > 0.0 && h > 0.0) {
        val srcR = w / h
        if (srcR > ratio) cw = (ratio * h) / w      // 源更宽 → 左右裁
        else ch = w / (ratio * h)                   // 源更高 → 上下裁
        cw = cw.coerceIn(0.05, 1.0)
        ch = ch.coerceIn(0.05, 1.0)
    }
    // 拉直 refit
    if (rotDeg != 0.0 && cw > 0.0 && ch > 0.0) {
        val th = Math.abs(rotDeg) * Math.PI / 180.0
        val cs = Math.cos(th); val sn = Math.sin(th)
        val cwPx = cw * w; val chPx = ch * h
        if (cwPx > 1e-6 && chPx > 1e-6) {
            // 旋转矩形覆盖轴对齐框 (W*s, H*s) 的条件：
            //   W*s*cos + H*s*sin <= W  且  W*s*sin + H*s*cos <= H
            val k1 = cs + sn * (chPx / cwPx)
            val k2 = sn * (cwPx / chPx) + cs
            val s = 1.0 / maxOf(k1, k2)
            if (s > 0.0 && s < 1.0) { cw *= s; ch *= s }
        }
    }
    val cx = (1.0 - cw) / 2.0
    val cy = (1.0 - ch) / 2.0
    return doubleArrayOf(cx, cy, cw, ch)
}

private fun jsonEsc(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")

// 一位小数、去掉末尾的 .0：1.8 / 2 / 11（与开源 trimmed() 一致）
private fun trimNum(v: Double): String {
    val s = "%.1f".format(v)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

// 读照片的拍摄日与拍摄数据（RFC-031/033）。RAW 与栅格都由 ExifInterface 覆盖；
// 读不到就返回 null/空，界面会退回当天日期、并且不印数据行。
private fun readExifShooting(ctx: Context, uri: Uri): Pair<IntArray?, String> {    var day: IntArray? = null
    var line = ""
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val ex = ExifInterface(ins)
            val dt = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            dt?.let { s ->
                Regex("""(\d{4})[:\-](\d{1,2})[:\-](\d{1,2})""").find(s)?.let { m ->
                    day = intArrayOf(
                        m.groupValues[1].toIntOrNull() ?: 0,
                        m.groupValues[2].toIntOrNull() ?: 0,
                        m.groupValues[3].toIntOrNull() ?: 0
                    )
                }
            }
            // 数据行：快门、光圈、模式、补偿、等效焦距；上限 22 字符（RFC-033 §5），
            // 放不下就从右往左丢字段 —— 与开源 ShootingData.dataText 同一规则。
            val f = mutableListOf<String>()
            ex.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { t ->
                if (t > 0) f += if (t >= 1.0) "${trimNum(t)}s" else "1/${(1.0 / t).toInt()}"
            }
            ex.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { f += "F${trimNum(it)}" }
            ex.getAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM)?.toIntOrNull()?.let { p ->
                when (p) { 1 -> f += "M"; 2 -> f += "P"; 3 -> f += "A"; 4 -> f += "S" }
            }
            ex.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.toDoubleOrNull()?.let { b ->
                val r = kotlin.math.round(b * 10) / 10.0
                if (r != 0.0) f += "%+.1f".format(r)
            }
            ex.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.toIntOrNull()?.let { f += "${it}mm" }
            while (f.isNotEmpty() && f.joinToString(" ").length > 22) f.removeAt(f.lastIndex)
            line = f.joinToString(" ")
        }
    }
    return day to line
}

/**
 * [r11 第 3 批] 读 EXIF 光圈。读不到返回 0（= 片边不印光圈），不编造。
 * 只在导入时调一次，重复开一次流换取"不改动已有 readExifShooting 的签名"。
 */
private fun readExifAperture(ctx: Context, uri: Uri): Float = runCatching {
    ctx.contentResolver.openInputStream(uri)?.use { ins ->
        val ex = ExifInterface(ins)
        val f = ex.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()
        if (f != null && f > 0.0) f.toFloat() else 0f
    } ?: 0f
}.getOrDefault(0f)

// 拿 Uri 的真实文件名（photopicker 尾段只有数字），用于 RAW 扩展名检测
private fun rawDisplayName(ctx: Context, uri: Uri): String {
    var name = uri.lastPathSegment?.lowercase() ?: ""
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { if (it.isNotBlank()) name = it.lowercase() }
            }
        }
    }
    return name
}

// [r11 第 4 批] 源文件名（不含扩展名，保留原始大小写）——导出命名 token「原片名」用。
// 与 rawDisplayName 的区别：那个为了判扩展名统一转了小写，直接拿来当文件名不好看。
private fun displayStemName(ctx: Context, uri: Uri): String {
    var name = uri.lastPathSegment ?: "IMG"
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { if (it.isNotBlank()) name = it }
            }
        }
    }
    return name.substringBeforeLast('.').ifEmpty { "IMG" }
}

// RAW 解码：content Uri 拷贝到缓存文件，走引擎 LibRaw 解码（decodeRawPhoto）→ packed RGB float(线性 ProPhoto)
private fun decodeRawPhoto(ctx: Context, uri: Uri, eng: SpektraEngine): Triple<FloatArray, Int, Int>? {
    val f = File(ctx.cacheDir, "import_raw_${System.currentTimeMillis()}.dng")
    try {
        ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return null
        val r = eng.decodeRaw(f.absolutePath) ?: return null
        return Triple(r.first, r.second.first, r.second.second)
    } finally {
        runCatching { f.delete() }
    }
}

private fun decodePhotoToFloat(ctx: Context, uri: Uri): Triple<FloatArray, Int, Int>? {
    val resolver = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val maxEdge = 3000
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    bmp = applyExifRotation(bmp, uri, ctx)
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(bmp.width, bmp.height))
    if (scale < 1f) {
        val nw = (bmp.width * scale).toInt().coerceAtLeast(1); val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
        bmp = Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }
    val fw = bmp.width; val fh = bmp.height; val n = fw * fh
    val px = IntArray(n); bmp.getPixels(px, 0, fw, 0, 0, fw, fh)
    val f = FloatArray(n * 3); var i = 0
    for (p in px) { f[i++] = ((p shr 16) and 0xFF) / 255f; f[i++] = ((p shr 8) and 0xFF) / 255f; f[i++] = (p and 0xFF) / 255f }
    if (!bmp.isRecycled) bmp.recycle()
    return Triple(f, fw, fh)
}

/** 采样平均色（16×16 网格）：只用于日志里判"缩略图是不是真的被印上了、有没有跟着胶片变"。 */
/** 平均 HSV 饱和度（采样 16×16 点）。用来判断"缩略图之间到底是色偏不同，还是根本没上色"。 */
private fun avgSat(b: Bitmap): Float {
    val stepX = (b.width / 16).coerceAtLeast(1); val stepY = (b.height / 16).coerceAtLeast(1)
    var acc = 0f; var n = 0
    var y = 0
    while (y < b.height) {
        var x = 0
        while (x < b.width) {
            val c = b.getPixel(x, y)
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val bl = (c and 0xFF) / 255f
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            if (mx > 0.004f) acc += (mx - mn) / mx
            n++
            x += stepX
        }
        y += stepY
    }
    return if (n == 0) 0f else acc / n
}

/** 平均色相 / 饱和 / 亮度（64×64 采样）。色相按饱和度加权做圆周平均 —— 直接算术平均会在
 *  0°/360° 处把红紫拉成青绿。只用于日志里判断"冲洗有没有把某种颜色推走"。 */
private class HsvStat(val h: Float, val s: Float, val v: Float,
                       val greenShare: Float = 0f, val greenHue: Float = 0f,
                       val coherence: Float = 0f)

/**
 * 把"原图 vs 成片"的色相并排写进诊断日志。
 *
 * 一问一答：用户说"绿叶冲洗后变黄绿"，到底是引擎把绿推走了（Δ 大），还是负片本身就该这样
 * （Δ 在 10° 量级）？两行就够判断 —— 一行整体（会被大面积黄褐/天空稀释），一行只取绿带
 * （75°–165° 且饱和度 ≥0.12）的占比与平均色相，那一行才是"叶子"本身。
 *
 * 注意：色相必须按**饱和度加权做圆周平均**。直接算术平均会在 0°/360° 处把红紫拉成青绿，
 * 而且大面积灰（s≈0，色相无意义）会把结果拖向随便哪个方向。
 */
private fun logColourPair(tag: String, inH: HsvStat, outH: HsvStat) {
    val weak = minOf(inH.coherence, outH.coherence) < 0.25f
    Diagnostics.log("colour",
        ("$tag 整体 原图 %.1f°/s%.3f/v%.2f → 成片 %.1f°/s%.3f/v%.2f | Δ色相 %+.1f° " +
            "| 集中 R=%.2f/%.2f" + if (weak) " 分布分散，Δ 不可信" else "")
            .format(inH.h, inH.s, inH.v, outH.h, outH.s, outH.v, hueDelta(inH.h, outH.h),
                inH.coherence, outH.coherence))
    if (inH.greenShare > 0.02f && outH.greenShare > 0.02f) {
        Diagnostics.log("colour",
            ("$tag 绿带(75–165°) 占比 %.0f%%→%.0f%% 色相 %.1f°→%.1f° | Δ色相 %+.1f°")
                .format(inH.greenShare * 100f, outH.greenShare * 100f,
                    inH.greenHue, outH.greenHue, hueDelta(inH.greenHue, outH.greenHue)))
    } else if (inH.greenShare > 0.02f || outH.greenShare > 0.02f) {
        // 一边有绿一边没有（比如整片被推到黄），色相就没意义了，只报占比
        Diagnostics.log("colour",
            ("$tag 绿带(75–165°) 占比 %.0f%%→%.0f%% —— 有一侧不足 2%%，Δ 不作数")
                .format(inH.greenShare * 100f, outH.greenShare * 100f))
    }
}

private fun avgHueSat(b: Bitmap): HsvStat {
    val stepX = (b.width / 64).coerceAtLeast(1); val stepY = (b.height / 64).coerceAtLeast(1)
    var hx = 0.0; var hy = 0.0; var wsum = 0.0; var sSum = 0.0; var vSum = 0.0; var n = 0
    // 绿带（75°–165°）单独统计：一张图里叶子往往只占一小块，混进全图平均就被黄褐、天空稀释掉了
    var gx = 0.0; var gy = 0.0; var gw = 0.0; var gn = 0
    var y = 0
    while (y < b.height) {
        var x = 0
        while (x < b.width) {
            val c = b.getPixel(x, y)
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val bl = (c and 0xFF) / 255f
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            val d = mx - mn
            val s = if (mx > 1e-6f) d / mx else 0f
            var h = when {
                d < 1e-6f -> 0f
                mx == r -> 60f * (((g - bl) / d) % 6f)
                mx == g -> 60f * (((bl - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }
            if (h < 0f) h += 360f       // max==r 且 g<bl 时 %6 会给负值，别让它污染圆周平均
            val rad = Math.toRadians(h.toDouble())
            hx += Math.cos(rad) * s; hy += Math.sin(rad) * s; wsum += s
            if (s >= 0.12f && h >= 75f && h <= 165f) {
                gx += Math.cos(rad) * s; gy += Math.sin(rad) * s; gw += s; gn++
            }
            sSum += s; vSum += mx; n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0) return HsvStat(0f, 0f, 0f)
    var h = if (wsum > 1e-9) Math.toDegrees(Math.atan2(hy, hx)).toFloat() else 0f
    if (h < 0f) h += 360f
    var gh = if (gw > 1e-9) Math.toDegrees(Math.atan2(gy, gx)).toFloat() else 0f
    if (gh < 0f) gh += 360f
    // [r27] 圆周平均的"合力长度" R=|Σ s·e^{i·h}| / Σs。
    // R→1 表示全图色相集中（Δ 有意义）；R→0 表示橙/蓝两团势均力敌，
    // 这时合成向量几乎为零，平均色相会随一点点权重变化整个翻到对面 ——
    // 真机日志里那行 "32.1° → 206.8° Δ+174.7°" 就是这么来的，不是画面反色。
    val coh = if (wsum > 1e-9) (Math.sqrt(hx * hx + hy * hy) / wsum).toFloat() else 0f
    return HsvStat(h, (sSum / n).toFloat(), (vSum / n).toFloat(),
        gn.toFloat() / n, gh, coh)
}

/** 两个色相之间的最短夹角（−180°…+180°）。 */
private fun hueDelta(a: Float, b: Float): Float {
    var d = b - a
    while (d > 180f) d -= 360f
    while (d < -180f) d += 360f
    return d
}

private fun avgHex(b: Bitmap): String {
    val stepX = (b.width / 16).coerceAtLeast(1); val stepY = (b.height / 16).coerceAtLeast(1)
    var r = 0L; var g = 0L; var bl = 0L; var n = 0
    var y = 0
    while (y < b.height) {
        var x = 0
        while (x < b.width) {
            val c = b.getPixel(x, y)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF; n++
            x += stepX
        }
        y += stepY
    }
    if (n == 0) return "?"
    return "%02x%02x%02x".format(r / n, g / n, bl / n)
}

// [r25] 长按「原图」用的位图。
//
// **两种输入不能同样对待**（这正是 RAW 长按一片死黑的原因）：
//   · 普通照片：decodePhotoToFloat 直接把 8bit sRGB 值 /255 存进 float，
//     已经是**显示编码值**，×255 就是屏上该有的样子。
//   · RAW：LibRaw 解码出来的是**线性 ProPhoto**（raw_headroom.cpp 明确
//     "omit both that stage and its output curve"，即没有 gamma），数值大多
//     落在 0.05~0.2。直接 ×255 得到 13~51 —— 看得出轮廓，但整体死黑。
//     必须补上「ProPhoto→sRGB 矩阵 + sRGB 编码曲线」这两步，才是能看的原图。
//
// 引擎自己那条路不用管：MainActivity 已经在 delta 里按 photoIsRaw 分别下发
// input_color_space=sRGB/ProPhoto 与 input_cctf_decoding，成片一直是对的。
// 错的只有这块旁路出来的预览位图。
private fun makeThumb(f: FloatArray, w: Int, h: Int, edge: Int, linearProPhoto: Boolean = false): Bitmap {
    val scale = edge.toFloat() / maxOf(w, h)
    val nw = (w * scale).toInt().coerceAtLeast(1); val nh = (h * scale).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
    val px = IntArray(nw * nh); var k = 0
    for (y in 0 until nh) for (x in 0 until nw) {
        val sx = (x / scale).toInt().coerceIn(0, w - 1); val sy = (y / scale).toInt().coerceIn(0, h - 1)
        val o = (sy * w + sx) * 3
        px[k++] = if (linearProPhoto) proPhotoToSrgbPacked(f[o], f[o + 1], f[o + 2])
        else {
            val r = (f[o] * 255).toInt().coerceIn(0, 255); val g = (f[o + 1] * 255).toInt().coerceIn(0, 255); val b = (f[o + 2] * 255).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    bmp.setPixels(px, 0, nw, 0, 0, nw, nh)
    return bmp
}

/** 线性 ProPhoto(D50) → sRGB 编码值，返回 0xFFRRGGBB。矩阵取自 ICC / sRGB 规范。 */
private fun proPhotoToSrgbPacked(r0: Float, g0: Float, b0: Float): Int {
    // ProPhoto RGB → XYZ(D50)
    val x = 0.7976749f * r0 + 0.1351917f * g0 + 0.0313534f * b0
    val y = 0.2880402f * r0 + 0.7118741f * g0 + 0.0000857f * b0
    val z = 0.0000000f * r0 + 0.0000000f * g0 + 0.8252100f * b0
    // Bradford 适应：D50 → D65（sRGB 的白点）
    val x2 = 0.9555766f * x - 0.0230393f * y + 0.0631636f * z
    val y2 = -0.0282895f * x + 1.0099416f * y + 0.0210077f * z
    val z2 = 0.0122982f * x - 0.0204830f * y + 1.3299098f * z
    // XYZ(D65) → 线性 sRGB，再按 sRGB 曲线编码
    val lr = 3.2404542f * x2 - 1.5371385f * y2 - 0.4985314f * z2
    val lg = -0.9692660f * x2 + 1.8760108f * y2 + 0.0415560f * z2
    val lb = 0.0556434f * x2 - 0.2040259f * y2 + 1.0572252f * z2
    val r = (srgbEncode(lr) * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (srgbEncode(lg) * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (srgbEncode(lb) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** sRGB 传输函数（线性 → 编码）。负值和大过 1 的 headroom 都先夹住。 */
private fun srgbEncode(v0: Float): Float {
    val v = v0.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) v * 12.92f else 1.055f * Math.pow(v.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
}

// 对全尺寸 float 源做 box 平均降采样到长边≤maxEdge，供缩略图渲染使用（避免每次拿全尺寸 float open）
private fun downsampleFloat(f: FloatArray, w: Int, h: Int, maxEdge: Int): Triple<FloatArray, Int, Int> {
    val longEdge = maxOf(w, h)
    if (longEdge <= maxEdge) return Triple(f, w, h)
    val scale = maxEdge.toFloat() / longEdge
    val nw = maxOf(1, (w * scale).toInt()); val nh = maxOf(1, (h * scale).toInt())
    val out = FloatArray(nw * nh * 3)
    for (y in 0 until nh) {
        val y0 = (y.toLong() * h / nh).toInt(); val y1 = ((y + 1).toLong() * h / nh).toInt().coerceAtMost(h)
        for (x in 0 until nw) {
            val x0 = (x.toLong() * w / nw).toInt(); val x1 = ((x + 1).toLong() * w / nw).toInt().coerceAtMost(w)
            var r = 0.0; var g = 0.0; var b = 0.0; var cnt = 0
            for (sy in y0 until y1) {
                var i = (sy * w + x0) * 3
                for (sx in x0 until x1) { r += f[i]; g += f[i + 1]; b += f[i + 2]; i += 3; cnt++ }
            }
            if (cnt > 0) { r /= cnt; g /= cnt; b /= cnt }
            val o = (y * nw + x) * 3
            out[o] = r.toFloat(); out[o + 1] = g.toFloat(); out[o + 2] = b.toFloat()
        }
    }
    return Triple(out, nw, nh)
}

private fun applyExifRotation(bmp: Bitmap, uri: Uri, ctx: Context): Bitmap {
    return try {
        val orientation = ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    } catch (_: Exception) { bmp }
}

private fun generateTestFrame(w: Int, h: Int): FloatArray {
    val px = FloatArray(w * h * 3); var i = 0
    for (y in 0 until h) for (x in 0 until w) {
        val r = x.toFloat() / w; val g = y.toFloat() / h
        val b = 0.5f + 0.5f * kotlin.math.sin((x + y) / 40f)
        val dx = (x - w / 2f) / (w / 2f); val dy = (y - h / 2f) / (h / 2f)
        val glow = 0.35f * kotlin.math.exp(-(dx * dx + dy * dy) * 3f)
        px[i++] = (r * 0.9f + glow).coerceIn(0f, 1f); px[i++] = (g * 0.8f + glow * 0.8f).coerceIn(0f, 1f); px[i++] = (b * 0.9f + glow * 0.9f).coerceIn(0f, 1f)
    }
    return px
}
