package com.spektra.android

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * 界面内返回（系统返回键 / 侧边滑动返回手势）。
 *
 * 为什么不用 `androidx.activity.compose.BackHandler`：它在拿不到派发者时会 `checkNotNull`
 * 直接抛异常。这个 app 里有些全屏页（相册、观片台）既可能被组合在 Activity 的窗口里，
 * 也可能出现在别的宿主里，拿不到就**安静地不注册**，比崩掉合理。
 *
 * 另一个要点是**必须自己接管**。以前这些全屏页没有注册任何返回回调，侧边手势一滑，
 * 事件就落到 Activity 的默认行为上 —— `finish()`，于是直接从观片台/相册退回桌面。
 * 现在每一层都注册一个"关掉我自己"，一层一层退回去。
 *
 * `enabled` 为 true 时注册（默认）。同一时刻多个都启用的场合，后注册的先响应
 * （OnBackPressedDispatcher 是栈式的），正好等于"最上层先关"。
 */
@Composable
fun DvBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val latest = rememberUpdatedState(onBack)
    DisposableEffect(dispatcher, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val cb = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                latest.value()
            }
        }
        dispatcher.addCallback(cb)
        onDispose { cb.remove() }
    }
}
