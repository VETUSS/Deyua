package com.spektra.android

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 构建完整性与安装来源校验（r30）。
 *
 * 目的只有一个：让用户知道手上这一份是不是官方构建，以及它是从哪里装进来的。
 *
 * **刻意不做"校验失败就拒绝运行"**。Deyva 以 GPL-3.0-or-later 分发，GPL-3.0
 * 第 3 条禁止用技术手段阻止用户行使许可授予的权利 —— 用户有权修改并运行自己的
 * 版本。所以这里只报告，不设卡。真要防"二次打包分发"，手段是签名比对与渠道
 * 追踪，而不是让修改版跑不起来。
 *
 * 校验点在 Kotlin 层，能被 patch —— 这是公开的、可预期的。它的价值不是"攻不破"，
 * 而是让"重签一个包冒充官方"这件事从零成本变成需要改代码，并把改过的包显式
 * 标出来。真正的门槛应当来自签名密钥本身（私钥不泄露）与分发渠道。
 */
object BuildIntegrity {

    /**
     * 官方构建的签名证书 SHA-256，小写十六进制。
     *
     * 换签名密钥时必须同步改这里，否则官方包也会被标成"非官方"。
     * 取值：keytool -list -v -keystore deyva-release.keystore -alias deyva
     *
     * 目前这把密钥（RSA-4096 / 10000 天）在 work/deyva-release.keystore，
     * 密码在 work/.deyva-release.pass，两者都已排除出版本库 —— 私钥一旦进
     * 仓库或推到远端，这个常量就失去意义了。
     */
    const val OFFICIAL_CERT_SHA256 = "bd5b1581425b953da52ab9550bf5149c52c30277a36d8358443cb0fa358c8236"

    /** 源码获取地址。发布前必须填写 —— GPL-3.0 第 6 条要求随目标代码提供对应源码。 */
    const val SOURCE_URL = "https://github.com/VETUSS/Deyua"

    enum class State { OFFICIAL, UNOFFICIAL, DEV }

    data class Report(
        val state: State,
        /** 本机安装包的签名证书 SHA-256，读不到时为 null */
        val signer: String?,
        /** 安装来源包名（应用商店 / 文件管理器 / 侧载时为 null） */
        val installer: String?,
    ) {
        val official: Boolean get() = state == State.OFFICIAL

        val title: String
            get() = when (state) {
                State.OFFICIAL -> "官方构建"
                State.UNOFFICIAL -> "非官方构建"
                State.DEV -> "开发构建"
            }

        val detail: String
            get() = when (state) {
                State.OFFICIAL -> "签名与本机安装来源均通过校验"
                State.UNOFFICIAL -> "签名证书与官方不符：这不是 Vetus 签发的安装包"
                State.DEV -> "调试密钥签名，仅用于开发，不代表对外发布版本"
            }
    }

    fun check(ctx: Context): Report {
        val pm = ctx.packageManager
        val pkg = ctx.packageName

        // 用 FLAG_DEBUGGABLE 而不是 BuildConfig.DEBUG：本工程没有开 buildConfig，
        // 而 debuggable 标志恰好就是"这是开发构建"的权威来源。
        val debuggable = runCatching {
            (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)

        val signer = runCatching { signerSha256(pm, pkg) }.getOrNull()
        val installer = runCatching { installerOf(pm, pkg) }.getOrNull()

        val state = when {
            debuggable -> State.DEV
            signer == null -> State.UNOFFICIAL
            signer.equals(OFFICIAL_CERT_SHA256, ignoreCase = true) -> State.OFFICIAL
            else -> State.UNOFFICIAL
        }

        Diagnostics.log(
            "integrity",
            "构建校验 state=$state signer=${signer?.take(16) ?: "null"}… installer=${installer ?: "null"}"
        )
        return Report(state, signer, installer)
    }

    private fun signerSha256(pm: PackageManager, pkg: String): String? {
        val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull()
        }
        return cert?.toByteArray()?.let { sha256Hex(it) }
    }

    private fun installerOf(pm: PackageManager, pkg: String): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }

    private fun sha256Hex(b: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(b)
        val sb = StringBuilder(d.size * 2)
        for (x in d) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * 「关于 Deyva」（r30）。
 *
 * 原来这一项只是往 status 里写一行版本号，随即被 4.5 秒的自动退场抹掉。现在
 * 它是许可义务的落地处：GPL-3.0 要求分发者提供许可证副本与源码，CC BY-SA 4.0
 * 要求署名原作者并保留许可 —— 这些以前只写在 README 里，随包没有，等于没做。
 */
@Composable
fun AboutSheet(ctx: Context, onDismiss: () -> Unit) {
    val report = remember { BuildIntegrity.check(ctx) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 检查更新 → 应用内下载 → 按发布摘要校验 → 调起系统安装器。
    // 整个流程只用这一个状态，界面上的标签、说明、进度条都由它导出，不另设几组
    // 互斥的布尔量（那种写法迟早会同时为真）。
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<UpdState>(UpdState.Idle) }
    var got by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }

    // 进度回调在 IO 线程上（DownloadManager 的轮询在那边），写状态要回主线程。
    val push: (Long, Long) -> Unit = { g, t ->
        Handler(Looper.getMainLooper()).post {
            got = g
            total = t
            st = UpdState.Working(if (t > 0L) (g.toFloat() / t).coerceIn(0f, 1f) else 0f)
        }
    }

    // 下载在系统那边进行，与本面板的存亡无关。重新打开时接回去：
    // 还在跑就继续显示进度，已下完没装就直接给"安装更新"。
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { UpdateDownload.resume(ctx, version) } ?: return@LaunchedEffect
        when (r) {
            is UpdateDownload.Resume.Ready -> st = UpdState.Ready(r.file, r.tag)
            is UpdateDownload.Resume.Running -> {
                st = UpdState.Working(0f)
                val done = withContext(Dispatchers.IO) { UpdateDownload.await(ctx, r.job) { g, t -> push(g, t) } }
                st = settle(ctx, done, r.job.tag)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .iosTap(pressDim = 1f) { onDismiss() }
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = Dv.rSheet, topEnd = Dv.rSheet))
                .background(Dv.panel).padding(bottom = 10.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                SheetGrabber(active = false)
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("DEYVA", color = Dv.label1, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)
                Spacer(Modifier.weight(1f))
                Text(if (version.isBlank()) "" else "v$version",
                    color = Dv.label3, fontSize = 10.5f.sp, letterSpacing = 0.4.sp)
            }
            Text("胶片模拟冲洗 · 作者 Vetus", color = Dv.label3, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp))

            Spacer(Modifier.height(12.dp))
            BuildBadge(report)
            Spacer(Modifier.height(10.dp))

            Text(
                "签名与安装来源用于区分官方构建与二次打包。校验不通过不影响任何功能 —— " +
                    "Deyva 以 GPL-3.0 分发，你有权运行自己修改的版本。\n" +
                    "更新在应用内下载，落盘后按发布页给出的 SHA-256 校验，通过才交给系统安装。",
                color = Dv.label2, fontSize = 10.5f.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 18.dp),
            )

            Spacer(Modifier.height(8.dp))
            Hairline(alpha = 0.06f)
            ActionRow(updLabel(st)) {
                when (val s = st) {
                    is UpdState.Found -> {
                        // 上一轮已经下好、校验过、只是没装成（用户取消了安装器）：直接给安装。
                        val already = UpdateDownload.cached(ctx, s.rel.tag)
                        if (already != null) {
                            st = UpdState.Ready(already, s.rel.tag)
                            return@ActionRow
                        }
                        when (val begin = UpdateDownload.start(ctx, s.rel)) {
                            is UpdateDownload.Start.Ok -> {
                                got = 0L
                                total = s.rel.sizeBytes
                                st = UpdState.Working(0f)
                                scope.launch {
                                    val done = withContext(Dispatchers.IO) {
                                        UpdateDownload.await(ctx, begin.job) { g, t -> push(g, t) }
                                    }
                                    st = settle(ctx, done, begin.job.tag)
                                }
                            }
                            is UpdateDownload.Start.Fail -> st = UpdState.Note(
                                "无法开始下载：${begin.reason}。可用「打开发布页」在浏览器下载", true
                            )
                        }
                    }
                    // 已下完（校验过）还没装：再走一次安装，不重新下载。
                    is UpdState.Ready -> st = settle(ctx, UpdateDownload.Done.Ok(s.file), s.tag)
                    UpdState.Idle, is UpdState.Note -> {
                        st = UpdState.Checking
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                UpdateCheck.check(version.ifBlank { "0" })
                            }
                            st = when (r) {
                                is UpdateCheck.Result.Newer -> UpdState.Found(r.release)
                                is UpdateCheck.Result.Latest -> {
                                    val tag = r.release?.tag.orEmpty()
                                    UpdState.Note(
                                        if (tag.isBlank()) "当前已是最新版本" else "当前已是最新版本（$tag）",
                                        false,
                                    )
                                }
                                is UpdateCheck.Result.Failed -> UpdState.Note("检查失败：${r.reason}", true)
                            }
                        }
                    }
                    // 正在检查 / 正在下载：这一行是状态显示器，不是按钮。
                    UpdState.Checking, is UpdState.Working -> Unit
                }
            }
            val line = when (val s = st) {
                UpdState.Idle, UpdState.Checking -> ""
                is UpdState.Found -> {
                    val size = UpdateCheck.sizeText(s.rel.sizeBytes)
                    "发现新版本 ${s.rel.tag}" + (if (size.isBlank()) "" else "（$size）")
                }
                is UpdState.Working -> when {
                    s.pct >= 1f -> "已下载完成，正在按发布摘要校验…"
                    total > 0L -> "已下载 ${UpdateCheck.sizeText(got)} / ${UpdateCheck.sizeText(total)}"
                    else -> "正在下载…"
                }
                is UpdState.Ready -> "已下载到本机，覆盖安装不会影响已有照片与配方"
                is UpdState.Note -> s.line
            }
            if (line.isNotBlank()) {
                Text(
                    line,
                    color = if ((st as? UpdState.Note)?.fail == true) Dv.red else Dv.label3,
                    fontSize = 10.5f.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 4.dp),
                )
            }
            (st as? UpdState.Working)?.let { w ->
                Box(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
                        .height(2.dp).clip(RoundedCornerShape(1.dp))
                        .background(Dv.label3.copy(alpha = 0.14f))
                ) {
                    Box(
                        Modifier.fillMaxWidth(w.pct.coerceIn(0f, 1f)).fillMaxHeight()
                            .background(if (w.pct >= 1f) Dv.green else Dv.accent)
                    )
                }
            }
            ActionRow("打开发布页") {
                UpdateCheck.open(ctx, UpdateCheck.RELEASES_PAGE)
            }
            ActionRow("许可证全文 · GPL-3.0") {
                notice = "GNU General Public License v3.0" to readNotice(ctx, "GPL-3.0.txt")
            }
            ActionRow("许可证全文 · CC BY-SA 4.0") {
                notice = "spektrafilm profiles & LUTs" to readNotice(ctx, "SPEKTRAFILM-CC-BY-SA-4.0.txt")
            }
            ActionRow("署名与依赖说明") {
                notice = "Notices" to readNotice(ctx, "NOTICE.txt")
            }
            ActionRow(if (BuildIntegrity.SOURCE_URL.isBlank()) "获取源代码（未配置）" else "获取源代码") {
                val url = BuildIntegrity.SOURCE_URL
                if (url.isBlank()) {
                    // 宁可显眼地缺着，也不要假装已履行 —— 这项空着就不能对外发布。
                    notice = "源代码尚未配置" to
                        "本应用以 GPL-3.0-or-later 分发，发布前必须在 BuildIntegrity.SOURCE_URL " +
                        "填入源码仓库地址，或在安装包旁附上源码。\n\n" +
                        "相关条款：GPL-3.0 第 6 条 Conveying Non-Source Forms。\n\n" +
                        "当前签名证书 SHA-256：\n" + (report.signer ?: "（读取失败）")
                } else {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure { Diagnostics.log("about", "打开源码地址失败: ${it.message}") }
                }
            }
            Hairline(alpha = 0.06f)
            ActionRow("关闭") { onDismiss() }
            Spacer(Modifier.height(2.dp))
        }
    }

    notice?.let { (title, body) ->
        NoticeSheet(title, body) { notice = null }
    }
}

/**
 * 更新流程的状态。
 *
 * 只有一个状态变量：检查、发现、下载、校验、待安装、结果，同一时刻只可能处于其中一种。
 * 界面上的行标签、说明文字、进度条全部由它导出 —— 用几个布尔量拼状态会在"下载中同时
 * 显示检查失败"这类组合上露出马脚。
 */
private sealed interface UpdState {
    object Idle : UpdState
    object Checking : UpdState
    data class Found(val rel: UpdateCheck.Release) : UpdState
    /** [pct] 到 1.0 表示下载已完成，正在校验摘要。 */
    data class Working(val pct: Float) : UpdState
    data class Ready(val file: File, val tag: String) : UpdState
    data class Note(val line: String, val fail: Boolean) : UpdState
}

private fun updLabel(s: UpdState): String = when (s) {
    UpdState.Idle -> "检查更新"
    UpdState.Checking -> "正在检查更新…"
    is UpdState.Found -> "下载并安装更新"
    is UpdState.Working -> when {
        s.pct >= 1f -> "正在校验…"
        s.pct > 0f -> "正在下载 ${(s.pct * 100).toInt()}%"
        else -> "正在下载…"
    }
    is UpdState.Ready -> "安装已下载的更新"
    is UpdState.Note -> if (s.fail) "重新检查" else "检查更新"
}

/** 下载结束后的收口：装得起来就装，装不起来给出人能照着做的下一步。 */
private fun settle(ctx: Context, done: UpdateDownload.Done, tag: String): UpdState = when (done) {
    is UpdateDownload.Done.Ok -> {
        val ins = UpdateDownload.install(ctx, done.file)
        when {
            ins.ok -> UpdState.Note(
                "${tag.ifBlank { "更新" }} 已下载并通过校验，已交给系统安装程序", false
            )
            // 缺"安装未知应用"授权：设置页已经打开，回来点"安装已下载的更新"即可，
            // 不必重新下一遍 22 MiB。
            !UpdateDownload.canInstall(ctx) -> UpdState.Ready(done.file, tag)
            else -> UpdState.Note(ins.line, true)
        }
    }
    is UpdateDownload.Done.Bad -> UpdState.Note(
        "校验失败：文件与发布摘要不符，已删除（本地 ${done.actual.take(12)}…）", true
    )
    is UpdateDownload.Done.Fail -> UpdState.Note("下载失败：${done.reason}", true)
}

/** 构建状态徽标：一枚色点 + 标题 + 一行原因，长指纹单独一行等宽显示。 */
@Composable
private fun BuildBadge(r: BuildIntegrity.Report) {
    val tint = when (r.state) {
        BuildIntegrity.State.OFFICIAL -> Dv.green
        BuildIntegrity.State.UNOFFICIAL -> Dv.red
        BuildIntegrity.State.DEV -> Dv.accent
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).height(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(tint))
            Spacer(Modifier.width(7.dp))
            Text(r.title, color = tint, fontSize = 12.5f.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Spacer(Modifier.weight(1f))
            Text(r.installer ?: "侧载", color = Dv.label3, fontSize = 10.sp, maxLines = 1)
        }
        Spacer(Modifier.height(3.dp))
        Text(r.detail, color = Dv.label3, fontSize = 10.5f.sp, lineHeight = 15.sp)
        r.signer?.let {
            Spacer(Modifier.height(3.dp))
            Text("${it.take(16)}…${it.takeLast(8)}", color = Dv.label3,
                fontSize = 9.5f.sp, letterSpacing = 0.3.sp)
        }
    }
}

/**
 * 许可全文阅读面板。
 *
 * 按行切块丢给 LazyColumn：GPL 全文 35 KB，整段塞进一个 Text 会让首帧明显卡顿，
 * 而分块后只有可见的那几块参与布局。
 */
@Composable
private fun NoticeSheet(title: String, body: String, onBack: () -> Unit) {
    val chunks = remember(body) { body.lines().chunked(24).map { it.joinToString("\n") } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .iosTap(pressDim = 1f) { onBack() }
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(520.dp)
                .clip(RoundedCornerShape(topStart = Dv.rSheet, topEnd = Dv.rSheet))
                .background(Dv.panel)
        ) {
            Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                SheetGrabber(active = false)
            }
            Text(title, color = Dv.label1, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                items(chunks) { c ->
                    Text(c, color = Dv.label2, fontSize = 10.sp, lineHeight = 14.5.sp)
                }
            }
            ActionRow("返回") { onBack() }
            Spacer(Modifier.height(2.dp))
        }
    }
}

private fun readNotice(ctx: Context, name: String): String =
    runCatching {
        ctx.assets.open("notices/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse { "无法读取 $name\n\n${it.message}" }
