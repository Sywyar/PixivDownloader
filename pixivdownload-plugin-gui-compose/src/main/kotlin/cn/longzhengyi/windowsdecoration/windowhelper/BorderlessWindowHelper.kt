// Vendored from ComposeWindowsDecoration commit f1aa3691fec439cc373053a805cea3fd9ed1d9c1 (Apache-2.0).
package cn.longzhengyi.windowsdecoration.windowhelper

import androidx.compose.ui.geometry.Rect
import com.sun.jna.*
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR
import com.sun.jna.platform.win32.WinDef.*
import cn.longzhengyi.windowsdecoration.windowhelper.skialayer.SkiaLayerWindowProcedure
import cn.longzhengyi.windowsdecoration.windowhelper.win32.DWMWA_WINDOW_CORNER_PREFERENCE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.DWMWCP_ROUND
import cn.longzhengyi.windowsdecoration.windowhelper.win32.DwmApi
import cn.longzhengyi.windowsdecoration.windowhelper.win32.GWL_STYLE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.GWL_WNDPROC
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTBOTTOM
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTBOTTOMLEFT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTBOTTOMRIGHT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTCAPTION
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTCLIENT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTCLOSE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTLEFT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTMAXBUTTON
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTMINBUTTON
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTRIGHT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTTOP
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTTOPLEFT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.HTTOPRIGHT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.MARGINS
import cn.longzhengyi.windowsdecoration.windowhelper.win32.MINMAXINFO
import cn.longzhengyi.windowsdecoration.windowhelper.win32.MONITORINFO
import cn.longzhengyi.windowsdecoration.windowhelper.win32.MONITOR_DEFAULTTONEAREST
import cn.longzhengyi.windowsdecoration.windowhelper.win32.RECT
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SM_CXEDGE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SM_CXFRAME
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SM_CXPADDEDBORDER
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SM_CYEDGE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SM_CYFRAME
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SWP_FRAMECHANGED
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SWP_NOACTIVATE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SWP_NOMOVE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SWP_NOSIZE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SWP_NOZORDER
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SW_MINIMIZE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SW_RESTORE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.SW_SHOWMAXIMIZED
import cn.longzhengyi.windowsdecoration.windowhelper.win32.User32Ex
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WM_GETMINMAXINFO
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WM_NCACTIVATE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WM_NCCALCSIZE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WM_NCHITTEST
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WM_NCMOUSEMOVE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WM_SIZE
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WS_CAPTION
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WS_SYSMENU
import cn.longzhengyi.windowsdecoration.windowhelper.win32.WndProcCallback
import org.jetbrains.skiko.SkiaLayer
import java.awt.Container
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Windows 无边框窗口助手，为 Compose Desktop 提供自绘标题栏支持。
 *
 * 采用 **"背板 + 镂空"** 命中测试模型：将整个标题栏标记为可拖拽背板，
 * 在其上放置的系统按钮和交互组件会自动镂空背板、阻止拖拽。
 *
 * 命中测试优先级（高 -> 低）：
 * 1. 系统按钮 (Minimize / Maximize / Close)
 * 2. 交互区域 (Interactive，支持多个)
 * 3. 拖拽背板 (Caption)
 * 4. 客户区 (默认)
 *
 * ### 用法
 *
 * 推荐通过 [rememberBorderlessWindowHelper] 在 Compose Window 中一行代码安装，
 * 然后使用 Modifier 扩展标记区域：
 *
 * ```kotlin
 * Window(...) {
 *     val helper = rememberBorderlessWindowHelper()
 *
 *     Row(Modifier.windowDragArea(helper)) {                          // 背板
 *         SearchBox(Modifier.windowInteractiveArea(helper, "search")) // 镂空
 *         Spacer(Modifier.weight(1f))
 *         MinBtn(Modifier.windowMinimizeButton(helper))               // 镂空
 *         MaxBtn(Modifier.windowMaximizeButton(helper))               // 镂空
 *         CloseBtn(Modifier.windowCloseButton(helper))                // 镂空
 *     }
 * }
 * ```
 *
 * 也可以直接调用区域注册 API：[setCaptionArea]、[setMinimizeButtonArea]、
 * [setMaximizeButtonArea]、[setCloseButtonArea]、[addInteractiveArea]。
 *
 * @see rememberBorderlessWindowHelper
 * @see windowDragArea
 * @see windowInteractiveArea
 */
// ─── 实现原理 ───
// 1. 保留 WS_CAPTION 窗口样式，通过 DWM 扩展帧实现阴影和边框效果
// 2. 子类化 JFrame 窗口过程，拦截 WM_NCCALCSIZE（移除默认标题栏）、
//    WM_NCHITTEST（返回命中测试结果）、WM_GETMINMAXINFO（修正最大化尺寸）等消息
// 3. 子类化 SkiaLayer 内部 Canvas 窗口过程，将非客户区鼠标事件
//    (WM_NCMOUSEMOVE 等) 转发为客户区事件，使 Compose 按钮在非客户区正常响应
// 4. 窗口调整大小和 Aero Snap 由系统原生处理（通过正确的 HT 返回值实现）
class BorderlessWindowHelper(
    private val jFrame: JFrame,
) {
    private val user32 = User32Ex.INSTANCE
    private val dwmApi = DwmApi.INSTANCE

    private var hwnd: HWND? = null
    private var originalWndProc: Pointer? = null

    // JNA 回调对象必须被强引用持有，否则 JVM GC 会回收该对象，导致 native 回调指针悬空，引发进程崩溃
    @Suppress("unused") // 防止 GC 回收回调对象
    private var wndProcCallbackRef: WndProcCallback? = null

    private var installed = false
    private var skiaLayerProc: SkiaLayerWindowProcedure? = null

    // ─── 命中测试区域（由外部通过 API 或 Modifier 注册） ───
    private val captionAreas = ConcurrentHashMap<String, Rect>()
    @Volatile private var minimizeButtonArea: Rect = Rect.Zero
    @Volatile private var maximizeButtonArea: Rect = Rect.Zero
    @Volatile private var closeButtonArea: Rect = Rect.Zero
    private val interactiveAreas = ConcurrentHashMap<String, Rect>()

    // ─── DPI 感知的边框尺寸 ───
    private var dpi = 96
    private var frameX = 0
    private var frameY = 0
    private var edgeX = 0
    private var edgeY = 0
    private var padding = 0
    private var windowWidth = 0
    private var windowHeight = 0
    private var isMaximizedState = false

    // ─── 缓存的 hitTest 结果，供 SkiaLayer 和 JFrame 共享 ───
    @Volatile internal var currentHitResult: Int = HTCLIENT

    // ═══════════════════════════════════════════════════
    // 区域注册 API
    // ═══════════════════════════════════════════════════

    /**
     * 设置拖拽背板区域。整个标题栏行应标记为此区域，
     * 其上的系统按钮和交互区域会自动镂空（优先级更高）。
     * 鼠标点击露出的背板区域时，拖拽移动窗口，双击最大化/还原。
     */
    fun setCaptionArea(rect: Rect) { captionAreas["default"] = rect }

    /**
     * 添加一个拖拽背板区域。支持多个，通过 [id] 区分。
     * 在其上放置的系统按钮和交互组件会自动镂空背板、阻止拖拽。
     * 鼠标点击露出的背板区域时，拖拽移动窗口，双击最大化/还原。
     *
     * @param id 唯一标识符，用于后续更新或移除
     * @param rect 区域坐标（窗口内像素坐标）
     */
    fun addCaptionArea(id: String, rect: Rect) { captionAreas[id] = rect }

    /**
     * 移除指定的拖拽背板区域。
     */
    fun removeCaptionArea(id: String) { captionAreas.remove(id) }

    /**
     * 清除所有已注册的拖拽背板区域。
     */
    fun clearCaptionAreas() { captionAreas.clear() }

    /**
     * 设置最小化按钮区域。
     */
    fun setMinimizeButtonArea(rect: Rect) { minimizeButtonArea = rect }

    /**
     * 设置最大化按钮区域。
     * Windows 11 上鼠标悬停时会触发 Snap Layout 布局菜单。
     */
    fun setMaximizeButtonArea(rect: Rect) { maximizeButtonArea = rect }

    /**
     * 设置关闭按钮区域。
     */
    fun setCloseButtonArea(rect: Rect) { closeButtonArea = rect }

    /**
     * 添加一个交互区域（镂空拖拽背板）。
     * 放置在拖拽背板内的交互组件（搜索框、下拉菜单、工具按钮等），
     * 点击该区域时不会触发窗口拖拽，而是将事件交给 Compose 处理。
     * 支持多个，通过 [id] 区分。
     *
     * @param id 唯一标识符，用于后续更新或移除
     * @param rect 区域坐标（窗口内像素坐标）
     */
    fun addInteractiveArea(id: String, rect: Rect) { interactiveAreas[id] = rect }

    /**
     * 移除指定的交互区域。
     */
    fun removeInteractiveArea(id: String) { interactiveAreas.remove(id) }

    /**
     * 清除所有已注册的交互区域。
     */
    fun clearInteractiveAreas() { interactiveAreas.clear() }

    companion object {
        /**
         * 递归查找容器中的指定类型组件
         */
        @Suppress("UNCHECKED_CAST")
        fun <T : JComponent> findComponent(container: Container, klass: Class<T>): T? {
            val components = container.components.asSequence()
            return components.filter { klass.isInstance(it) }.ifEmpty {
                components.filterIsInstance<Container>()
                    .mapNotNull { findComponent(it, klass) }
            }.map { klass.cast(it) }.firstOrNull()
        }
    }

    /**
     * 安装无边框窗口支持。必须在 EDT 线程上调用。
     *
     * 推荐使用 [rememberBorderlessWindowHelper] 自动安装，无需手动调用此方法。
     */
    fun install() {
        require(SwingUtilities.isEventDispatchThread()) { "Must be called on EDT" }
        require(jFrame.isDisplayable) { "JFrame must be displayable" }
        check(!installed) { "BorderlessWindowHelper is already installed on this window" }
        installed = true

        hwnd = HWND(Native.getComponentPointer(jFrame))
        val hWnd = hwnd ?: error("Failed to get HWND")

        // 1. 窗口样式：保留 WS_CAPTION 并移除 WS_SYSMENU（隐藏系统按钮但保留帧）
        val currentStyle = user32.GetWindowLongPtrW(hWnd, GWL_STYLE)
        val newStyle = (currentStyle.toLong() or WS_CAPTION) and WS_SYSMENU.inv()
        user32.SetWindowLongPtrW(hWnd, GWL_STYLE, LONG_PTR(newStyle))

        // 2. DWM 扩展帧（启用阴影和边框）
        val margins = MARGINS().apply {
            cxLeftWidth = 0; cxRightWidth = -1
            cyTopHeight = 0; cyBottomHeight = -1
        }
        dwmApi.DwmExtendFrameIntoClientArea(hWnd, margins)

        // 3. 子类化窗口过程
        val callback = object : WndProcCallback {
            override fun callback(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
                return handleMessage(hWnd, msg, wParam, lParam)
            }
        }
        wndProcCallbackRef = callback
        val callbackPointer = CallbackReference.getFunctionPointer(callback as Callback)
        originalWndProc = user32.SetWindowLongPtrW(hWnd, GWL_WNDPROC, callbackPointer)

        // 4. 触发帧更新
        user32.SetWindowPos(
            hWnd, null, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE
        )

        // 5. Win11 圆角
        try {
            val cornerPref = Memory(4).apply { setInt(0, DWMWCP_ROUND) }
            dwmApi.DwmSetWindowAttribute(hWnd, DWMWA_WINDOW_CORNER_PREFERENCE, cornerPref, 4)
        } catch (_: Exception) { }

        // 6. 子类化 SkiaLayer（延迟重试，因为 Compose 首次渲染可能还未完成）
        tryInstallSkiaLayerProcedure()

    }

    // ═══════════════════════════════════════════════════
    // SkiaLayer 子类化（负责 NC 鼠标事件转发，使 Compose 按钮在非客户区正常工作）
    // ═══════════════════════════════════════════════════

    private fun tryInstallSkiaLayerProcedure() {
        val skiaLayer = findComponent(jFrame, SkiaLayer::class.java)
        if (skiaLayer != null) {
            installSkiaLayerProcedure(skiaLayer)
        } else {
            // SkiaLayer 可能还未创建，延迟 200ms 重试
            Timer(200) {
                val layer = findComponent(jFrame, SkiaLayer::class.java)
                if (layer != null) {
                    installSkiaLayerProcedure(layer)
                }
            }.apply { isRepeats = false; start() }
        }
    }

    private fun installSkiaLayerProcedure(skiaLayer: SkiaLayer) {
        val proc = SkiaLayerWindowProcedure(
            skiaLayer = skiaLayer,
            hitTest = { x, y ->
                updateWindowInfo()
                val hitResult = computeHitTest(x, y)
                currentHitResult = hitResult
                hitResult
            }
        )
        proc.install()
        skiaLayerProc = proc
    }

    // ═══════════════════════════════════════════════════
    // HitTest 计算
    // ═══════════════════════════════════════════════════

    // hitTest 计算：调整大小边框 > 按钮/交互区域 > 标题栏
    private fun computeHitTest(x: Float, y: Float): Int {
        val horizontalPadding = frameX
        val verticalPadding = frameY

        // 非最大化时检查调整大小边框
        if (!isMaximizedState) {
            when {
                x <= horizontalPadding && y <= verticalPadding -> return HTTOPLEFT
                x >= windowWidth - horizontalPadding && y <= verticalPadding -> return HTTOPRIGHT
                x <= horizontalPadding && y >= windowHeight - verticalPadding -> return HTBOTTOMLEFT
                x >= windowWidth - horizontalPadding && y >= windowHeight - verticalPadding -> return HTBOTTOMRIGHT
                x <= horizontalPadding -> return HTLEFT
                x >= windowWidth - horizontalPadding -> return HTRIGHT
                y <= verticalPadding -> return HTTOP
                y >= windowHeight - verticalPadding -> return HTBOTTOM
            }
        }

        return performButtonHitTest(x, y)
    }

    // 命中测试优先级：系统按钮 > 交互区域 > 拖拽背板 > 客户区
    private fun performButtonHitTest(x: Float, y: Float): Int {
        return when {
            maximizeButtonArea.contains(x, y) -> HTMAXBUTTON
            minimizeButtonArea.contains(x, y) -> HTMINBUTTON
            closeButtonArea.contains(x, y) -> HTCLOSE
            interactiveAreas.values.any { it.contains(x, y) } -> HTCLIENT
            captionAreas.values.any { it.contains(x, y) } -> HTCAPTION
            else -> HTCLIENT
        }
    }

    private fun Rect.contains(x: Float, y: Float): Boolean {
        return x >= left && x < right && y >= top && y < bottom
    }

    // 屏幕坐标 -> 窗口相对坐标 -> hitTest
    private fun computeHitTestFromScreen(screenX: Int, screenY: Int): Int {
        val hWnd = hwnd ?: return HTCLIENT
        val windowRect = RECT()
        user32.GetWindowRect(hWnd, windowRect)

        val relX = (screenX - windowRect.left).toFloat()
        val relY = (screenY - windowRect.top).toFloat()

        return computeHitTest(relX, relY)
    }

    private fun updateWindowInfo() {
        val hWnd = hwnd ?: return
        try {
            dpi = user32.GetDpiForWindow(hWnd)
            if (dpi <= 0) dpi = 96
            frameX = user32.GetSystemMetricsForDpi(SM_CXFRAME, dpi)
            frameY = user32.GetSystemMetricsForDpi(SM_CYFRAME, dpi)
            edgeX = user32.GetSystemMetricsForDpi(SM_CXEDGE, dpi)
            edgeY = user32.GetSystemMetricsForDpi(SM_CYEDGE, dpi)
            padding = user32.GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi)

            val rect = RECT()
            if (user32.GetWindowRect(hWnd, rect)) {
                windowWidth = rect.right - rect.left
                windowHeight = rect.bottom - rect.top
            }

            isMaximizedState = user32.IsZoomed(hWnd)
        } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════
    // Win32 消息处理
    // ═══════════════════════════════════════════════════

    private fun handleMessage(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
        when (msg) {
            WM_NCCALCSIZE -> {
                if (wParam.toInt() != 0) {
                    updateWindowInfo()
                    // 最大化时，系统会将窗口扩展到超出工作区域（超出部分 = 边框厚度）
                    // 需要将客户区向内收缩，使内容刚好填满工作区域
                    if (isMaximizedState) {
                        val borderX = frameX + padding
                        val borderY = frameY + padding
                        val ptr = Pointer(lParam.toLong())
                        ptr.setInt(0, ptr.getInt(0) + borderX)   // left
                        ptr.setInt(4, ptr.getInt(4) + borderY)   // top
                        ptr.setInt(8, ptr.getInt(8) - borderX)   // right
                        ptr.setInt(12, ptr.getInt(12) - borderY) // bottom
                    }
                }
                return LRESULT(0)
            }

            WM_NCHITTEST -> {
                // 独立计算 hitTest（不依赖 SkiaLayer 缓存）
                updateWindowInfo()
                val lp = lParam.toLong()
                val screenX = (lp.toInt() and 0xFFFF).toShort().toInt()
                val screenY = ((lp.toInt() shr 16) and 0xFFFF).toShort().toInt()
                val result = computeHitTestFromScreen(screenX, screenY)
                currentHitResult = result
                return LRESULT(result.toLong())
            }

            WM_NCACTIVATE -> return LRESULT(1)

            WM_SIZE -> {
                val w = lParam.toInt() and 0xFFFF
                val h = (lParam.toInt() shr 16) and 0xFFFF
                windowWidth = w
                windowHeight = h
                return user32.CallWindowProcW(originalWndProc!!, hWnd, msg, wParam, lParam)
            }

            WM_NCMOUSEMOVE -> {
                skiaLayerProc?.let {
                    user32.PostMessageW(it.contentHandle, msg, wParam, lParam)
                }
                return user32.CallWindowProcW(originalWndProc!!, hWnd, msg, wParam, lParam)
            }

            WM_GETMINMAXINFO -> {
                val monitor = user32.MonitorFromWindow(hWnd, MONITOR_DEFAULTTONEAREST)
                if (monitor != null) {
                    val mi = MONITORINFO()
                    if (user32.GetMonitorInfoW(monitor, mi)) {
                        val work = mi.rcWork
                        val mmi = MINMAXINFO(Pointer(lParam.toLong()))
                        mmi.ptMaxPosition.x = work.left - mi.rcMonitor.left
                        mmi.ptMaxPosition.y = work.top - mi.rcMonitor.top
                        mmi.ptMaxSize.x = work.right - work.left
                        mmi.ptMaxSize.y = work.bottom - work.top
                        mmi.write()
                    }
                }
                return LRESULT(0)
            }
        }

        return user32.CallWindowProcW(originalWndProc!!, hWnd, msg, wParam, lParam)
    }

    // ═══════════════════════════════════════════════════
    // 窗口操作 API
    // ═══════════════════════════════════════════════════

    /** 当前窗口是否最大化 */
    fun isMaximized(): Boolean = hwnd?.let { user32.IsZoomed(it) } ?: false

    /** 最小化窗口 */
    fun minimize() { hwnd?.let { user32.ShowWindow(it, SW_MINIMIZE) } }

    /** 最大化窗口 */
    fun maximize() { hwnd?.let { user32.ShowWindow(it, SW_SHOWMAXIMIZED) } }

    /** 还原窗口 */
    fun restore() { hwnd?.let { user32.ShowWindow(it, SW_RESTORE) } }

    /** 切换最大化/还原状态 */
    fun toggleMaximize() {
        if (isMaximized()) restore() else maximize()
    }
}
