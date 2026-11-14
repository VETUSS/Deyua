package com.spektra.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 更新检查（r61）。
 *
 * 只做两件事：向 GitHub 查询最新一次 Release，拿到安装包的下载直链后交给系统浏览器。
 * **不下载、不校验、不安装** —— 安装由浏览器与系统的包安装器负责。这样应用本身不需要
 * 存储权限、不需要 REQUEST_INSTALL_PACKAGES，也不必在版本迭代里维护一套下载器。
 *
 * 版本比较只认 tag：正式标签形如 `v0.8.116-r60`。先比三段语义版本，再比构建号（`r60`）。
 * 比较不出来（标签被改成别的写法）就明确报错，不猜。
 */
object UpdateCheck {

    private const val OWNER = "VETUSS"
    private const val REPO = "Deyua"

    /** `latest` 只返回已发布的正式版本，草稿与预发布会被跳过。 */
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 已是最新时的手动入口：仍然可以自己去翻发布页。 */
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"

    data class Release(
        val tag: String,
        val pageUrl: String,
        val apkUrl: String?,
        val apkName: String?,
        val sizeBytes: Long,
    )

    sealed interface Result {
        /** 远端版本高于本机。 */
        data class Newer(val release: Release) : Result

        /** 已是最新，或远端版本不高于本机。 */
        data class Latest(val release: Release?) : Result

        /** 查询失败：无网络、被限流、返回内容异常等。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 同步查询，调用方放在 IO 线程。传入本机 versionName 用于比较与 User-Agent。
     */
    fun check(currentVersion: String): Result {
        val body = try {
            fetch(currentVersion)
        } catch (e: Exception) {
            Diagnostics.log("update", "查询失败：${e.javaClass.simpleName} ${e.message}")
            return Result.Failed(friendly(e))
        }

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result.Failed("返回内容无法解析")

        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        if (tag.isBlank()) return Result.Failed("返回内容缺少版本号")

        val assets = json.optJSONArray("assets")
        val apks = (0 until (assets?.length() ?: 0))
            .mapNotNull { assets?.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        // 一个包里只有一个 ABI；多包时优先 arm64，其余取第一个。
        val apk = apks.firstOrNull { it.optString("name").contains("arm64", ignoreCase = true) }
            ?: apks.firstOrNull()

        val release = Release(
            tag = tag,
            pageUrl = json.optString("html_url"),
            apkUrl = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() },
            apkName = apk?.optString("name")?.takeIf { it.isNotBlank() },
            sizeBytes = apk?.optLong("size") ?: 0L,
        )

        val diff = compareVersions(currentVersion, tag)
        Diagnostics.log(
            "update",
            "当前=$currentVersion 远端=$tag 比较=${diff ?: "n/a"} 资产=${release.apkName ?: "none"}"
        )
        return when {
            diff == null -> Result.Failed("无法比较版本号：$tag")
            diff > 0 -> Result.Newer(release)
            else -> Result.Latest(release)
        }
    }

    /**
     * 远端是否比本机新。大于 0 = 远端更新，0 = 相同，小于 0 = 远端更旧，null = 无法解析。
     */
    fun compareVersions(current: String, remote: String): Int? {
        val cur = SEMVER.find(current) ?: return null
        val rem = SEMVER.find(remote) ?: return null
        for (i in 1..3) {
            val a = cur.groupValues[i].toIntOrNull() ?: return null
            val b = rem.groupValues[i].toIntOrNull() ?: return null
            if (a != b) return b - a
        }
        // 语义版本相同（例如修 bug 的 r 号递增），比构建号。
        val cb = BUILD.find(current)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        val rb = BUILD.find(remote)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        return if (rb > cb) 1 else if (rb < cb) -1 else 0
    }

    /** 交给系统浏览器。返回 false 表示设备上没有可处理 http(s) 的应用。 */
    fun open(ctx: Context, url: String): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        Diagnostics.log("update", "打开链接失败：${it.message} · $url")
        false
    }

    fun sizeText(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= (1L shl 20) -> "%.1f MiB".format(bytes / 1048576.0)
        else -> "%d KiB".format(bytes / 1024)
    }

    // ------------------------------------------------------------------ 内部

    private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)""")
    private val BUILD = Regex("""(?i)r(\d+(?:\.\d+)?)""")

    private fun fetch(ua: String): String {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 9_000
            requestMethod = "GET"
            // GitHub API 要求带 User-Agent，缺省会被直接拒绝。
            setRequestProperty("User-Agent", "Deyva/$ua (Android)")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.replace(Regex("\\s+"), " ")?.take(160).orEmpty()
                throw IllegalStateException("HTTP $code${if (detail.isBlank()) "" else " · $detail"}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "无法连接 github.com，请检查网络"
        is SocketTimeoutException -> "连接超时，请稍后重试"
        else -> e.message ?: e.javaClass.simpleName
    }
}
