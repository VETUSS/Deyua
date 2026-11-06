package com.spektra.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 顶栏（r14）。
 *
 * 旧版是一条玻璃胶囊，里面塞了「照片 / 冲洗 / 编辑 / 相册 / 诊断」五个文字入口 ——
 * 三处导航（冲洗、编辑）和两处工具（相册、诊断）混在一行，视觉权重几乎一样，
 * 而"冲洗/编辑"其实已经在下面板里有一份一模一样的切换，属于重复。
 *
 * 新版：左边是**刊头**（DEYVA / FILM LAB，字距拉开，像一本胶片刊物的封头），
 * 右边只剩三个真正跨页面的入口：导入、相册、诊断。「冲洗 / 编辑」下沉到控制台里，
 * 与它控制的内容待在同一块面板上。
 */
@Composable
fun TopBar(
    onPickPhoto: () -> Unit,
    onGallery: () -> Unit,
    onDiagnose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("DEYVA", color = Dv.paper, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 5.5.sp)
            Text("FILM LAB", color = Dv.label3, fontSize = 7.5f.sp, letterSpacing = 2.6.sp,
                fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        DvIconButton(Glyph.Plus, "导入照片", onPickPhoto, tint = Dv.accent, glyphSize = 23.dp)
        DvIconButton(Glyph.Grid, "相册", onGallery, tint = Dv.label2, glyphSize = 20.dp)
        DvIconButton(Glyph.Pulse, "诊断日志", onDiagnose, tint = Dv.label2, glyphSize = 21.dp)
    }
}

/** 控制台顶部的拖拽把手：38×4 的胶囊，按下时略微变宽（暗示可以拖）。 */
@Composable
fun SheetGrabber(active: Boolean) {
    Box(
        Modifier.width(if (active) 46.dp else 38.dp).height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (active) Dv.accent else Color.White.copy(alpha = 0.22f)),
    )
}

/** 主操作按钮（导出）：琥珀实心 + 深色字。琥珀上放白字对比度只有 2:1，放炭黑才有 9:1。 */
@Composable
fun DvPrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp)
            .clip(RoundedCornerShape(Dv.rBtn))
            .background(if (enabled) Dv.accent else Dv.accent.copy(alpha = 0.25f))
            .iosTap(enabled = enabled, pressScale = 0.96f, pressDim = 0.9f, haptic = true, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Dv.root else Dv.root.copy(alpha = 0.5f),
            fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    }
}

/** 悬在画面上的状态提示（冲洗完成 / 已保存配方 / 失败原因）。 */@Composable
fun StatusPill(text: String, warn: Boolean) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (warn) Color(0xE633120F) else Dv.glassBar)
            .border(1.dp, if (warn) Dv.red.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape)
            .background(if (warn) Dv.red else Dv.accent))
        Text(text, color = if (warn) Dv.red else Dv.label1, fontSize = 11.sp, maxLines = 2)
    }
}

/**
 * 动作表里的一行（r14）：52dp 整行、左对齐、按下整行变暗。
 * 「更多」从 AlertDialog 改成底部上滑的动作表 —— 拇指在屏幕下半部，弹窗却出现在中间，
 * 是这类工具 App 最别扭的一处；动作表贴着底边，单手就能点完。
 */
@Composable
fun ActionRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .iosTap(pressDim = 0.5f) { onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = if (danger) Dv.red else Dv.label1, fontSize = 14.sp, letterSpacing = 0.3.sp)
    }
}
