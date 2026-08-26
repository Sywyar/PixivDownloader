// Vendored from ComposeWindowsDecoration commit f1aa3691fec439cc373053a805cea3fd9ed1d9c1 (Apache-2.0).
package cn.longzhengyi.windowsdecoration

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.longzhengyi.windowsdecoration.windowhelper.BorderlessWindowHelper

/**
 * 标题栏作用域，在 [BorderlessTitleBarScaffold] 的 content 内可用。
 *
 * 提供对 [cn.longzhengyi.windowsdecoration.windowhelper.BorderlessWindowHelper] 的访问和常用窗口操作的便捷封装。
 * 所有 Modifier 扩展（如 [cn.longzhengyi.windowsdecoration.windowhelper.windowDragArea]、
 * [cn.longzhengyi.windowsdecoration.windowhelper.windowMinimizeButton] 等）
 * 可直接使用 [helper] 参数。
 *
 * @see BorderlessTitleBarScaffold
 */
@Stable
class TitleBarScope internal constructor() {
    /**
     * 底层无边框窗口助手。初始为 `null`，安装完成后自动变为非空。
     * 可直接传给 [cn.longzhengyi.windowsdecoration.windowhelper.windowMinimizeButton]、[cn.longzhengyi.windowsdecoration.windowhelper.windowMaximizeButton]、
     * [cn.longzhengyi.windowsdecoration.windowhelper.windowCloseButton]、[cn.longzhengyi.windowsdecoration.windowhelper.windowInteractiveArea] 等 Modifier 扩展。
     */
    var helper: BorderlessWindowHelper? by mutableStateOf(null)
        internal set

    /** 当前窗口是否最大化 */
    var isMaximized: Boolean by mutableStateOf(false)
        internal set

    /** 最小化窗口 */
    fun minimize() { helper?.minimize() }

    /** 最大化窗口 */
    fun maximize() { helper?.maximize() }

    /** 还原窗口 */
    fun restore() { helper?.restore() }

    /** 切换最大化/还原状态，并同步更新 [isMaximized] */
    fun toggleMaximize() {
        helper?.toggleMaximize()
        isMaximized = helper?.isMaximized() ?: false
    }
}
