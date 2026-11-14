package com.spektra.android

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.security.MessageDigest

/**
 * 应用内更新下载（r62）。
 *
 * 交给系统的 [DownloadManager]，不是自己写一条 socket 循环。三条理由：
 * 它跨进程存活（用户切走、甚至应用被回收，下载照旧）、断网/切网自动续传、
 * 通知栏里能看见进度 —— 这三点自己实现要写一整套前台服务与断点续传，而收益只是
 * 省下一行调用。
 *
 * 落盘位置是 `getExternalFilesDir("updates")`：应用私有、无需存储权限、卸载即清，
 * 且已在 `res/xml/file_paths.xml` 里登记给 FileProvider，安装时用 content:// 交给
 * 系统包安装器。
 *
 * **安装前按 Release 提供的 SHA-256 校验**。这一步是这个功能存在的意义：浏览器下载
 * 把"文件对不对"交给用户肉眼判断，应用内下载则可以自证——对不上就删掉，不允许安装。
 * 这正是 r61 的浏览器方案做不到的事。
 *
 * 需要 `REQUEST_INSTALL_PACKAGES`，且用户必须在系统设置里为本应用打开"安装未知应用"。
 * 没打开时不弹窗硬闯，直接把人送到那一页（[install] 的 NeedPermission）。
 */
object UpdateDownload {

    /** 与 `res/xml/file_paths.xml` 的 `updates` 条目必须一致。 */
    private const val DIR = "updates"

    private const val PREF = "deyva_update"
    private const val K_ID = "id"
    private const val K_NAME = "name"
    private const val K_SHA = "sha"
    private const val K_TAG = "tag"
    private const val K_OK = "ok"

    private const val MIME_APK = "application/vnd.android.package-archive"

    private const val POLL_MS = 350L

    data class Job(val id: Long, val name: String, val sha256: String?, val tag: String)

    sealed interface Start {
        data class Ok(val job: Job) : Start
        data class Fail(val reason: String) : Start
    }

    sealed interface Done {
        data class Ok(val file: File) : Done
        /** 下载成功但摘要不符 —— 文件已删除。 */
        data class Bad(val expected: String, val actual: String) : Done
        data class Fail(val reason: String) : Done
    }

    data class InstallResult(val ok: Boolean, val line: String)

    // ------------------------------------------------------------------ 入队

    fun start(ctx: Context, rel: UpdateCheck.Release): Start {
        val url = rel.apkUrl ?: return Start.Fail("该版本未提供安装包")
        val name = safeName(rel.apkName ?: "Deyva-${rel.tag}.apk")
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return Start.Fail("系统下载服务不可用")

        // 上一份不删干净，同名文件会被 DownloadManager 当成同一目标覆盖写，
        // 混合两次下载的字节 —— 校验必然失败，但原因会变得难以解释。
        clear(ctx)
        forget(ctx)

        // 外部存储不可用（个别模拟器 / 存储挂载异常）时 getExternalFilesDir 返回 null，
        // 构造函数会直接抛 IllegalArgumentException —— 不能让它崩在点击里。
        val req = runCatching {
            DownloadManager.Request(Uri.parse(url))
                .setTitle("Deyva ${rel.tag}")
                .setDescription("正在下载更新 · ${UpdateCheck.sizeText(rel.sizeBytes)}".trimEnd(' ', '·'))
                .setMimeType(MIME_APK)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, DIR, name)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
        }.getOrElse {
            Diagnostics.log("update", "构造下载请求失败：${it.message}")
            return Start.Fail("外部存储不可用，无法保存安装包")
        }

        val id = runCatching { dm.enqueue(req) }.getOrElse {
            Diagnostics.log("update", "入队失败：${it.javaClass.simpleName} ${it.message}")
            return Start.Fail("无法开始下载：${it.message ?: it.javaClass.simpleName}")
        }

        val job = Job(id, name, rel.apkSha256, rel.tag)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(K_ID, id).putString(K_NAME, name)
            .putString(K_SHA, job.sha256 ?: "").putString(K_TAG, rel.tag)
            .putBoolean(K_OK, false).apply()
        Diagnostics.log("update", "开始下载 tag=${rel.tag} id=$id name=$name sha=${job.sha256 ?: "none"}")
        return Start.Ok(job)
    }

    /**
     * 等到下载结束并完成校验。调用方放在 IO 线程。
     *
     * [onProgress] 传的是"已下载 / 总字节"，总字节未知时为 0 —— 由界面自己决定显示
     * 百分比还是已下载量。
     */
    suspend fun await(
        ctx: Context,
        job: Job,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Done {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return Done.Fail("系统下载服务不可用")
        val q = DownloadManager.Query().setFilterById(job.id)

        while (true) {
            val c = runCatching { dm.query(q) }.getOrNull()
                ?: return Done.Fail("无法查询下载状态")
            if (!c.moveToFirst()) {
                c.close()
                return Done.Fail("下载任务已不存在（可能被系统清理）")
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = runCatching {
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            }.getOrDefault(0)
            c.close()

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {}
                DownloadManager.STATUS_FAILED -> {
                    Diagnostics.log("update", "下载失败 reason=$reason")
                    return Done.Fail(failReason(reason))
                }
                else -> {
                    onProgress(got.coerceAtLeast(0L), total)
                    delay(POLL_MS)
                    continue
                }
            }
            break
        }

        val f = file(ctx, job.name)
        if (f == null || !f.isFile || f.length() <= 0L) return Done.Fail("下载完成但文件不存在")

        // 让界面把"下载完了、正在校验"画出来 —— 22 MiB 的摘要要读一遍文件，
        // 在慢一点的机器上有肉眼可见的一秒多。
        onProgress(f.length(), f.length())

        val expect = job.sha256
        if (expect.isNullOrBlank()) {
            // 摘要缺失不是致命错误：文件仍然来自 GitHub 的 releases 直链（HTTPS），
            // 只是少了"内容自证"这一层。如实告知，不假装校验过。
            Diagnostics.log("update", "无摘要可校验，跳过 ${f.length()} B")
            markOk(ctx)
            return Done.Ok(f)
        }
        val actual = runCatching { sha256(f) }.getOrElse {
            return Done.Fail("读取文件失败：${it.message}")
        }
        if (!actual.equals(expect, ignoreCase = true)) {
            Diagnostics.log("update", "摘要不符 expect=$expect actual=$actual")
            f.delete()
            forget(ctx)
            return Done.Bad(expect, actual)
        }
        Diagnostics.log("update", "下载完成并校验通过 ${f.length()} B")
        markOk(ctx)
        return Done.Ok(f)
    }

    // ------------------------------------------------------------------ 安装

    /** Android 8.0 起安装未知来源是"按应用授权"，没开就没法静默调起安装器。 */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 打开本应用的"安装未知应用"开关页。 */
    fun openInstallSettings(ctx: Context): Boolean = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        Diagnostics.log("update", "打开安装设置失败：${it.message}")
        false
    }

    fun install(ctx: Context, file: File): InstallResult {
        if (!canInstall(ctx)) {
            val ok = openInstallSettings(ctx)
            return InstallResult(
                false,
                if (ok) "需先允许「安装未知应用」，已在设置中打开；返回后再点一次"
                else "需在系统设置中允许 Deyva 安装应用",
            )
        }
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrNull() ?: return InstallResult(false, "无法提供安装文件（路径未登记）")

        return runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, MIME_APK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            InstallResult(true, "已交给系统安装程序")
        }.getOrElse {
            Diagnostics.log("update", "调起安装失败：${it.message}")
            InstallResult(false, "无法调起安装程序：${it.message ?: it.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ 断点续挂

    /**
     * 上次会话留下的任务。用户中途关掉面板、或进程被回收，下载在系统那边仍然继续 ——
     * 重新打开「关于」时接回去，而不是从头再下一遍。
     *
     * 已完成且校验过的返回 [Resume.Ready]；还在跑的返回 [Resume.Running]。
     */
    sealed interface Resume {
        data class Running(val job: Job) : Resume
        data class Ready(val file: File, val tag: String) : Resume
    }

    fun resume(ctx: Context, current: String = ""): Resume? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = p.getLong(K_ID, -1L)
        if (id < 0L) return null
        val name = p.getString(K_NAME, null) ?: return null
        val f = file(ctx, name) ?: return null
        val job = Job(id, name, p.getString(K_SHA, null)?.takeIf { it.isNotBlank() }, p.getString(K_TAG, "").orEmpty())

        // 已经装过了（或换了别的途径升上来）：这份文件没有意义，清掉，免得
        // 每次打开「关于」都劝你装一个比本机更旧的版本。
        if (current.isNotBlank() && job.tag.isNotBlank()) {
            val d = UpdateCheck.compareVersions(current, job.tag)
            if (d != null && d <= 0) {
                clear(ctx)
                return null
            }
        }

        if (p.getBoolean(K_OK, false) && f.isFile && f.length() > 0L) {
            return Resume.Ready(f, job.tag)
        }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val status = runCatching {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
            }
        }.getOrDefault(-1)
        return when (status) {
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED ->
                Resume.Running(job)
            // 下完了但校验/安装没走完（进程在中间被回收）：接回去只做校验与安装。
            DownloadManager.STATUS_SUCCESSFUL ->
                if (f.isFile && f.length() > 0L) Resume.Running(job) else null
            else -> null
        }
    }

    /** 丢弃已下载的文件与记录。 */
    fun clear(ctx: Context) {
        forget(ctx)
        val d = dir(ctx) ?: return
        runCatching { d.listFiles()?.forEach { it.delete() } }
    }

    /**
     * 已下载、已校验、且正是这一版。
     *
     * 用在"用户取消了系统安装器、又在同一个会话里再点一次"这种情况：文件就在本机，
     * 没有理由为了同一次更新再走 22 MiB 流量。
     */
    fun cached(ctx: Context, tag: String): File? {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.getBoolean(K_OK, false)) return null
        if (!p.getString(K_TAG, "").equals(tag, ignoreCase = true)) return null
        val name = p.getString(K_NAME, null) ?: return null
        val f = file(ctx, name) ?: return null
        return if (f.isFile && f.length() > 0L) f else null
    }

    // ------------------------------------------------------------------ 内部

    private fun dir(ctx: Context): File? = ctx.getExternalFilesDir(DIR)

    private fun file(ctx: Context, name: String): File? = dir(ctx)?.let { File(it, name) }

    private fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun markOk(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_OK, true).apply()
    }

    /** 只留文件名，去掉路径分隔与控制字符 —— 名字来自远端，不能直接当路径用。 */
    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filter { it.isLetterOrDigit() || it in "._-+ " }.trim()
        val name = cleaned.ifBlank { "Deyva-update.apk" }
        return if (name.endsWith(".apk", true)) name else "$name.apk"
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val d = md.digest()
        val sb = StringBuilder(64)
        for (x in d) {
            val v = x.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun failReason(code: Int): String = when (code) {
        DownloadManager.ERROR_CANNOT_RESUME -> "无法续传"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "外部存储不可用"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "目标文件已存在"
        DownloadManager.ERROR_FILE_ERROR -> "写入文件失败"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "传输中断"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向过多"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回异常"
        DownloadManager.ERROR_UNKNOWN -> "未知错误"
        else -> "下载失败（code $code）"
    }
}
