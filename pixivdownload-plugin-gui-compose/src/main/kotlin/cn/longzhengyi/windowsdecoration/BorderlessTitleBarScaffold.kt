// Vendored from ComposeWindowsDecoration commit f1aa3691fec439cc373053a805cea3fd9ed1d9c1 (Apache-2.0).
package cn.longzhengyi.windowsdecoration

import androidx.compose.runtime.*
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import cn.longzhengyi.windowsdecoration.windowhelper.rememberBorderlessWindowHelper
import kotlinx.coroutines.delay

/**
 * 无边框标题栏脚手架。
 *
 * 自动安装 [cn.longzhengyi.windowsdecoration.windowhelper.BorderlessWindowHelper] 并追踪最大化状态，不预设任何布局。
 * 调用方在 [content] 中自由组织布局并通过 [TitleBarScope] 访问窗口操作和 [helper][TitleBarScope.helper]。
 *
 * **注意**：[content] 中必须手动标记 [cn.longzhengyi.windowsdecoration.windowhelper.windowDragArea]，
 * 否则窗口不可拖拽。
 *
 * ```kotlin
 * BorderlessTitleBarScaffold(windowState = windowState) {
 *     Row(
 *         Modifier.fillMaxWidth()
 *             .height(40.dp)
 *             .windowDragArea(helper),   // 手动标记拖拽背板
 *     ) {
 *         Text("My App", Modifier.weight(1f))
 *         IconButton(
 *             onClick = { minimize() },
 *             modifier = Modifier.windowMinimizeButton(helper),
 *         ) { Icon(...) }
 *         IconButton(
 *             onClick = { toggleMaximize() },
 *             modifier = Modifier.windowMaximizeButton(helper),
 *         ) { Icon(...) }
 *         IconButton(
 *             onClick = onClose,
 *             modifier = Modifier.windowCloseButton(helper),
 *         ) { Icon(...) }
 *     }
 * }
 * ```
 *
 * @param windowState 窗口状态，用于追踪最大化变化
 * @param content 标题栏内容，拥有 [TitleBarScope] 接收者，可直接访问 [helper][TitleBarScope.helper]、[isMaximized][TitleBarScope.isMaximized]、[minimize][TitleBarScope.minimize] 等
 *
 * @see TitleBarScope
 */
@Composable
fun FrameWindowScope.BorderlessTitleBarScaffold(
    windowState: WindowState,
    content: @Composable TitleBarScope.() -> Unit,
) {
    val helper = rememberBorderlessWindowHelper()
    val scope = remember { TitleBarScope() }

    LaunchedEffect(helper) {
        scope.helper = helper
        snapshotFlow { windowState.placement }
            .collect {
                delay(50)
                scope.isMaximized = helper?.isMaximized() ?: false
            }
    }

    scope.content()
}
