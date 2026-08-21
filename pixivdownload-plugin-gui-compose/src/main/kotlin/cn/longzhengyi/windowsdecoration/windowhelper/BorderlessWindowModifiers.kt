// Vendored from ComposeWindowsDecoration commit f1aa3691fec439cc373053a805cea3fd9ed1d9c1 (Apache-2.0).
package cn.longzhengyi.windowsdecoration.windowhelper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.FrameWindowScope
import cn.longzhengyi.windowsdecoration.windowhelper.utils.generateAutoId
import java.util.logging.Logger
import javax.swing.SwingUtilities

private val logger = Logger.getLogger("BorderlessWindowHelper")
private val osName: String = System.getProperty("os.name").lowercase()

/**
 * 在 Compose Window 作用域内创建并安装 [BorderlessWindowHelper]。
 *
 * 返回值初始为 `null`（安装在 EDT 线程异步完成），完成后自动触发重组。
 * 所有 Modifier 扩展（如 [windowDragArea]）均安全接受 `null`，无需额外判空。
 *
 * ```kotlin
 * Window(...) {
 *     val helper = rememberBorderlessWindowHelper()
 *     Row(Modifier.windowDragArea(helper)) { ... }
 * }
 * ```
 */
@Composable
fun FrameWindowScope.rememberBorderlessWindowHelper(): BorderlessWindowHelper? {
    return when {
        // windows
        "windows" in osName -> rememberWin32Helper()
        // macOS
        "mac" in osName || "darwin" in osName -> {
            LaunchedEffect(Unit) {
                logger.warning(
                    "BorderlessTitleBarScaffold: 暂不支持 macOS。将保留原生窗口装饰，自定义标题栏 UI 可能会发生重叠。" +
                            "macOS is not yet supported. Native window decorations will remain, custom title bar UI may overlap."
                )
            }
            null
        }
        // else
        else -> {
            LaunchedEffect(Unit) {
                logger.warning(
                    "BorderlessTitleBarScaffold: 暂不支持 '$osName' 平台。将保留原生窗口装饰，自定义标题栏 UI 可能会发生重叠。" +
                            "platform '$osName' is not yet supported. Native window decorations will remain, custom title bar UI may overlap."
                )
            }
            null
        }
    }
}

@Composable
private fun FrameWindowScope.rememberWin32Helper(): BorderlessWindowHelper? {
    val helperState = remember { mutableStateOf<BorderlessWindowHelper?>(null) }
    LaunchedEffect(Unit) {
        val jFrame = window
        SwingUtilities.invokeLater {
            val h = BorderlessWindowHelper(jFrame)
            h.install()
            helperState.value = h
        }
    }
    return helperState.value
}

/**
 * 标记此组件区域为拖拽背板。支持多个，通过 [id] 区分。
 *
 * 通常应用于整个标题栏行，其上的系统按钮和交互区域会自动镂空。
 * 鼠标点击露出的背板区域时，拖拽移动窗口；双击最大化/还原。
 * 组件退出 composition 时自动注销对应区域。
 *
 * **注意**：[id] 在所有使用 [windowDragArea] 的组件间必须唯一，
 * 重复的 id 会导致区域互相覆盖，其中一个组件移除时会连带注销另一个的区域。
 *
 * 内部使用 [composed] 引入 [DisposableEffect] 实现生命周期清理。
 * 如需更高性能（如在 LazyList 中大量使用），可考虑迁移至 `Modifier.Node` 实现。
 *
 * @param id 唯一标识符，默认为 `null`（自动分配，由 [generateAutoId] 生成）
 */
fun Modifier.windowDragArea(
    helper: BorderlessWindowHelper?,
    id: String? = null,
): Modifier =
    if (helper != null) composed {
        val resolvedId = id ?: remember { generateAutoId("caption") }
        DisposableEffect(helper, resolvedId) { onDispose { helper.removeCaptionArea(resolvedId) } }
        onGloballyPositioned { helper.addCaptionArea(resolvedId, it.boundsInWindow()) }
    } else this

/**
 * 标记此组件区域为系统最小化按钮（镂空拖拽背板）。
 */
fun Modifier.windowMinimizeButton(helper: BorderlessWindowHelper?): Modifier =
    if (helper != null) onGloballyPositioned { helper.setMinimizeButtonArea(it.boundsInWindow()) } else this

/**
 * 标记此组件区域为系统最大化按钮（镂空拖拽背板）。
 *
 * Windows 11 上鼠标悬停时会触发 Snap Layout 布局菜单。
 */
fun Modifier.windowMaximizeButton(helper: BorderlessWindowHelper?): Modifier =
    if (helper != null) onGloballyPositioned { helper.setMaximizeButtonArea(it.boundsInWindow()) } else this

/**
 * 标记此组件区域为系统关闭按钮（镂空拖拽背板）。
 */
fun Modifier.windowCloseButton(helper: BorderlessWindowHelper?): Modifier =
    if (helper != null) onGloballyPositioned { helper.setCloseButtonArea(it.boundsInWindow()) } else this

/**
 * 标记此组件区域为交互区域（镂空拖拽背板）。
 *
 * 放置在拖拽背板内的交互组件（搜索框、菜单、工具按钮等），
 * 点击该区域时事件交给 Compose 处理，不会触发窗口拖拽。
 * 支持多个，通过 [id] 区分。
 * 组件退出 composition 时自动注销对应区域。
 *
 * **注意**：[id] 在所有使用 [windowInteractiveArea] 的组件间必须唯一，
 * 重复的 id 会导致区域互相覆盖，其中一个组件移除时会连带注销另一个的区域。
 *
 * 内部使用 [composed] 引入 [DisposableEffect] 实现生命周期清理。
 * 如需更高性能（如在 LazyList 中大量使用），可考虑迁移至 `Modifier.Node` 实现。
 *
 * @param id 唯一标识符，默认为 `null`（自动分配，由 [generateAutoId] 生成）
 */
fun Modifier.windowInteractiveArea(
    helper: BorderlessWindowHelper?,
    id: String? = null,
): Modifier =
    if (helper != null) composed {
        val resolvedId = id ?: remember { generateAutoId("interactive") }
        DisposableEffect(helper, resolvedId) { onDispose { helper.removeInteractiveArea(resolvedId) } }
        onGloballyPositioned { helper.addInteractiveArea(resolvedId, it.boundsInWindow()) }
    } else this
