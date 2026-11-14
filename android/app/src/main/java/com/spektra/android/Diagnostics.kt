package com.spektra.android

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 轻量诊断日志：记录引擎初始化/导入/渲染每个关键步骤与耗时，
 * 供界面"诊断"按钮一键导出给开发者定位问题。
 *
 * 持久化：每次 log 实时追加并 flush 到 files/diagnostics.log。
 * 崩溃/闪退后重开 App，init() 会把文件里上一次会话(崩溃前已落盘的日志)
 * 读入 prevText，dump() 一并展示，从而能拿到崩溃前最后一步。
 */
object Diagnostics {
    private const val TAG = "SpektraDiag"
    private val lines = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val startMs = System.currentTimeMillis()
    private var file: File? = null
    private var prevText: String = ""
    private var appCtx: Context? = null
    private const val MAX = 800

    /** 必须在最早的 Activity.onCreate 调用一次。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val f = File(ctx.filesDir, "diagnostics.log")
        file = f
        // 上次会话(可能含崩溃前日志)原文
        prevText = runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")
        // 清空，开始本会话实时写（重开后可再次成为"上次会话"）
        runCatching { if (f.exists()) f.delete() }
    }

    fun log(tag: String, msg: String) {
        val line = "[${System.currentTimeMillis() - startMs}ms] $tag: $msg"
        lines.add(line)
        while (lines.size > MAX) lines.poll()
        // 实时落盘，崩溃瞬间之前写入的都能保留
        file?.let { f ->
            runCatching {
                FileOutputStream(f, true).use { fos ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
        }
        Log.i(TAG, "$tag: $msg")
    }

    fun dump(): String = buildString {
        append("==== Deyva 诊断日志 ====\n")
        // r64：补版本号。之前用户报"导出的实况图相册不认"，拿到日志却看不出装的是
        // 哪一版 —— 没有版本号就没法判断他到底有没有跑到修复之后的那一版。
        append("版本: ${versionLine()}\n")
        append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Android: API ${Build.VERSION.SDK_INT}\n")
        append("ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n")
        append("----\n")
        if (prevText.isNotBlank()) {
            append("【上次会话 · 崩溃/上次运行前已落盘】\n")
            append(prevText)
            if (!prevText.endsWith("\n")) append('\n')
            append("----\n")
        }
        append("【本次会话】\n")
        lines.forEach { append(it).append('\n') }
    }

    private fun versionLine(): String {
        val c = appCtx ?: return "(未知)"
        val pi = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
            ?: return "(未知)"
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        return "${pi.versionName} ($code)"
    }
}
