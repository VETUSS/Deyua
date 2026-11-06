package com.spektra.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 配方（Recipe）磁盘存储。
 *
 * 「配方」= 一整套可复现的冲洗设置快照：胶片 + 相纸 + 曝光 + 颗粒/光晕 +
 * 片边/日期 + 放大机 + 相机 + 画幅 …… 用户起个名字存下来，之后一键应用。
 *
 * 为什么要落盘（对照上游 `Export/ExportRecipe.swift`）：
 *  r9 之前「保存配方」只写一个 `remember { mutableStateOf("") }` 字符串 ——
 *  进程一结束就没了，而且全工程没有读取点，等于什么都没做。
 *
 * 文件位置：`filesDir/recipes.json`（不是 cacheDir）。
 *  - filesDir 不会被系统在低存储时自动清理，配方必须活到用户主动删；
 *  - 纯文本、结构自描述，以后要「导出/导入配方」不用改结构。
 *
 * 写入用 `.tmp` 再 rename（与 Thumbs.kt 同一套写法）：中途被打断时
 * 旧文件仍然完整，不会出现半截 JSON 导致配方全丢。
 *
 * 快照本身存成**完整的 JSON 对象**（不是差异）：引擎的 apply_delta 只写
 * 「出现过」的键，应用配方时如果只下发差异，上一次的片边/日期会残留。
 */
object RecipeStore {

    private const val FILE = "recipes.json"
    private const val VERSION = 1
    /** 上限：配方是几百字节一条，200 条远超过正常使用，也防住异常写入把文件撑爆。 */
    private const val MAX_RECIPES = 200

    data class Recipe(
        val id: String,
        val name: String,
        /** 保存时间（epoch ms），列表按它倒序。 */
        val at: Long,
        /** 完整快照的 JSON 文本。 */
        val snapshot: String,
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    /** 读全部配方；文件缺失/损坏都返回空表（不崩、不覆盖，损坏文件留作排查）。 */
    fun load(ctx: Context): List<Recipe> {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) return emptyList()
        return try {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("recipes") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val name = o.optString("name")
                    val snap = o.optJSONObject("snapshot")?.toString() ?: continue
                    if (id.isEmpty()) continue
                    add(Recipe(id, name.ifEmpty { "未命名" }, o.optLong("at", 0L), snap))
                }
            }.sortedByDescending { it.at }
        } catch (t: Throwable) {
            Diagnostics.log("recipe", "读取配方失败（保留原文件）：${t.message}")
            emptyList()
        }
    }

    /** 新增一条配方；同名时覆盖旧的那条（用户再存一次同名 = 更新，不产生重名项）。 */
    fun add(ctx: Context, name: String, snapshot: String): Recipe? {
        val clean = name.trim().ifEmpty { "未命名" }.take(40)
        return try {
            val all = load(ctx).toMutableList()
            val now = System.currentTimeMillis()
            val idx = all.indexOfFirst { it.name == clean }
            val rec = if (idx >= 0) {
                // 同名：更新快照与时间，id 保持不变（应用中的引用不会失效）
                all[idx].copy(snapshot = snapshot, at = now).also { all[idx] = it }
            } else {
                Recipe(nextId(now), clean, now, snapshot).also { all.add(it) }
            }
            write(ctx, all)
            Diagnostics.log("recipe", "已保存配方「$clean」共 ${all.size} 条")
            rec
        } catch (t: Throwable) {
            Diagnostics.log("recipe", "保存配方失败：${t.message}")
            null
        }
    }

    fun rename(ctx: Context, id: String, newName: String): Boolean = try {
        val clean = newName.trim().ifEmpty { return false }.take(40)
        val all = load(ctx).toMutableList()
        val i = all.indexOfFirst { it.id == id }
        if (i < 0) false else {
            all[i] = all[i].copy(name = clean)
            write(ctx, all); true
        }
    } catch (t: Throwable) {
        Diagnostics.log("recipe", "改名校验失败：${t.message}"); false
    }

    fun remove(ctx: Context, id: String): Boolean = try {
        val all = load(ctx).toMutableList()
        val ok = all.removeAll { it.id == id }
        if (ok) write(ctx, all)
        ok
    } catch (t: Throwable) {
        Diagnostics.log("recipe", "删除配方失败：${t.message}"); false
    }

    /** 整套原子写盘。超过上限时保留最新的 MAX_RECIPES 条。 */
    private fun write(ctx: Context, list: List<Recipe>) {
        val root = JSONObject()
        root.put("version", VERSION)
        val arr = JSONArray()
        for (r in list.sortedByDescending { it.at }.take(MAX_RECIPES)) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("name", r.name)
            o.put("at", r.at)
            // 快照解析失败时存空对象，至少不让整个文件写坏
            o.put("snapshot", runCatching { JSONObject(r.snapshot) }.getOrElse { JSONObject() })
            arr.put(o)
        }
        root.put("recipes", arr)

        val f = file(ctx)
        val tmp = File(f.absolutePath + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) {
            // 某些文件系统上目标已存在会 rename 失败：删掉再试一次
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** 时间戳 + 序列，够用且无需引入 UUID。 */
    private fun nextId(now: Long): String = "r${now}_${(0..999).random()}"
}
