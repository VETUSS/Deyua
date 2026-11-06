package com.spektra.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 保存配方：让用户起个名字。
 *
 * 名字默认取「胶片 + 相纸」，用户直接点保存就能用；想区分同胶片的不同调法再改。
 * 同名保存 = 覆盖该条（由 RecipeStore.add 保证），所以用户改了几个参数再存同名，
 * 不会堆出一串重名条目。
 */
@Composable
fun SaveRecipeDialog(defaultName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(defaultName.take(40)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dv.panelRaised,
        title = { Text("保存配方", color = Color.White, fontSize = 15.sp) },
        text = {
            Column {
                Text("把当前这一整套设置存到本机，重开 App 也还在", color = Dv.label2, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Orange,
                        unfocusedBorderColor = Dv.label4,
                        cursorColor = Orange,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text("同名会覆盖之前那条", color = Dv.label3, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton({ if (name.isNotBlank()) onSave(name.trim()) }) {
                Text("保存", color = if (name.isNotBlank()) Orange else Dv.label3)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消", color = Dv.label2) } },
    )
}

/**
 * 我的配方：应用 / 改名 / 删除。
 *
 * 点整行 = 应用（最高频动作，点按区最大）；右侧 ✎ 改名、✕ 删除。
 * 删除不做二次确认 —— 配方重存一次成本很低，二确认反而挡住高频的"试完就删"。
 */
@Composable
fun RecipeListDialog(
    recipes: List<RecipeStore.Recipe>,
    onDismiss: () -> Unit,
    onApply: (RecipeStore.Recipe) -> Unit,
    onRename: (RecipeStore.Recipe) -> Unit,
    onDelete: (RecipeStore.Recipe) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dv.panelRaised,
        title = { Text("我的配方（${recipes.size}）", color = Color.White, fontSize = 15.sp) },
        text = {
            if (recipes.isEmpty()) {
                Text("还没有配方。在「更多」里点「保存配方」，把当前设置存下来。",
                    color = Dv.label2, fontSize = 12.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(recipes, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .iosTap(pressDim = 0.85f, haptic = true) { onApply(r) }
                                .clip(RoundedCornerShape(12.dp)).background(Dv.panel)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = Color.White, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(runtimeLabel(r.at), color = Dv.label3, fontSize = 10.sp)
                            }
                            // [r13] 改名 / 删除：图标从 ✎ ✕ 字形换成 40dp 触控区（原来 ~24dp，很容易点错行）
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                .iosTap(pressDim = 0.5f) { onRename(r) },
                                contentAlignment = Alignment.Center) {
                                Text("✎", color = Dv.label2, fontSize = 15.sp)
                            }
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                                .iosTap(pressDim = 0.5f) { onDelete(r) },
                                contentAlignment = Alignment.Center) {
                                Text("✕", color = Dv.red, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("关闭", color = Orange) } },
    )
}

/** 重命名对话框：只改名字，不动快照。 */
@Composable
fun RenameRecipeDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current.take(40)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dv.panelRaised,
        title = { Text("配方改名", color = Color.White, fontSize = 15.sp) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = Dv.label4,
                    cursorColor = Orange,
                ),
            )
        },
        confirmButton = {
            TextButton({ if (name.isNotBlank()) onRename(name.trim()) }) {
                Text("确定", color = if (name.isNotBlank()) Orange else Dv.label3)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消", color = Dv.label2) } },
    )
}

/** 相对时间：今天只显示时分，更早显示月日。配方列表不需要精确到秒。 */
private fun runtimeLabel(at: Long): String {
    if (at <= 0L) return ""
    val now = System.currentTimeMillis()
    val d = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val today = java.util.Calendar.getInstance()
    val sameDay = d.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
        d.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    val hh = "%02d".format(d.get(java.util.Calendar.HOUR_OF_DAY))
    val mm = "%02d".format(d.get(java.util.Calendar.MINUTE))
    val days = (now - at) / 86_400_000L
    return when {
        sameDay -> "今天 $hh:$mm"
        days < 7 -> "$days 天前"
        else -> "${d.get(java.util.Calendar.MONTH) + 1} 月 ${d.get(java.util.Calendar.DAY_OF_MONTH)} 日"
    }
}
