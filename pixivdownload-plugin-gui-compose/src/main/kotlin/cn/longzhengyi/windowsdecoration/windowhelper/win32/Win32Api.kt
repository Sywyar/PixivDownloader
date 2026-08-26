// Vendored from ComposeWindowsDecoration commit f1aa3691fec439cc373053a805cea3fd9ed1d9c1 (Apache-2.0).
package cn.longzhengyi.windowsdecoration.windowhelper.win32

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD.LONG_PTR
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

// ==================== 常量定义 ====================

// WM 消息
const val WM_SIZE = 0x0005
const val WM_GETMINMAXINFO = 0x0024
const val WM_NCCALCSIZE = 0x0083
const val WM_NCHITTEST = 0x0084
const val WM_NCACTIVATE = 0x0086
const val WM_NCMOUSEMOVE = 0x00A0
const val WM_NCLBUTTONDOWN = 0x00A1
const val WM_NCLBUTTONUP = 0x00A2
const val WM_MOUSEMOVE = 0x0200
const val WM_LBUTTONDOWN = 0x0201
const val WM_LBUTTONUP = 0x0202

// NCHITTEST 返回值
const val HTTRANSPARENT = -1
const val HTCLIENT = 1
const val HTCAPTION = 2
const val HTMINBUTTON = 8
const val HTMAXBUTTON = 9
const val HTLEFT = 10
const val HTRIGHT = 11
const val HTTOP = 12
const val HTTOPLEFT = 13
const val HTTOPRIGHT = 14
const val HTBOTTOM = 15
const val HTBOTTOMLEFT = 16
const val HTBOTTOMRIGHT = 17
const val HTCLOSE = 20

// Window Styles
const val GWL_WNDPROC = -4
const val GWL_STYLE = -16

const val WS_CAPTION = 0x00C00000L
const val WS_SYSMENU = 0x00080000L

// SetWindowPos flags
const val SWP_NOSIZE = 0x0001
const val SWP_NOMOVE = 0x0002
const val SWP_NOZORDER = 0x0004
const val SWP_NOACTIVATE = 0x0010
const val SWP_FRAMECHANGED = 0x0020

// DWM 窗口属性
const val DWMWA_WINDOW_CORNER_PREFERENCE = 33

// DWM 圆角偏好
const val DWMWCP_ROUND = 2

// SW_ Show Window 命令
const val SW_MINIMIZE = 6
const val SW_RESTORE = 9
const val SW_SHOWMAXIMIZED = 3

// Monitor flags
const val MONITOR_DEFAULTTONEAREST = 0x00000002

// ==================== 结构体定义 ====================

@Structure.FieldOrder("left", "top", "right", "bottom")
open class RECT : Structure() {
    @JvmField var left: Int = 0
    @JvmField var top: Int = 0
    @JvmField var right: Int = 0
    @JvmField var bottom: Int = 0

    class ByReference : RECT(), Structure.ByReference
}

@Structure.FieldOrder("ptReserved", "ptMaxSize", "ptMaxPosition", "ptMinTrackSize", "ptMaxTrackSize")
open class MINMAXINFO(p: Pointer? = null) : Structure(p) {
    @JvmField var ptReserved: POINT = POINT()
    @JvmField var ptMaxSize: POINT = POINT()
    @JvmField var ptMaxPosition: POINT = POINT()
    @JvmField var ptMinTrackSize: POINT = POINT()
    @JvmField var ptMaxTrackSize: POINT = POINT()

    init {
        if (p != null) read()
    }
}

@Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
open class MONITORINFO : Structure() {
    @JvmField var cbSize: Int = size()
    @JvmField var rcMonitor: RECT = RECT()
    @JvmField var rcWork: RECT = RECT()
    @JvmField var dwFlags: Int = 0
}

@Structure.FieldOrder("cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight")
open class MARGINS : Structure() {
    @JvmField var cxLeftWidth: Int = 0
    @JvmField var cxRightWidth: Int = 0
    @JvmField var cyTopHeight: Int = 0
    @JvmField var cyBottomHeight: Int = 0
}

// ==================== JNA 接口 ====================

interface User32Ex : StdCallLibrary {
    companion object {
        val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun SetWindowLongPtrW(hWnd: HWND, nIndex: Int, dwNewLong: Pointer): Pointer
    fun SetWindowLongPtrW(hWnd: HWND, nIndex: Int, dwNewLong: LONG_PTR): LONG_PTR
    fun GetWindowLongPtrW(hWnd: HWND, nIndex: Int): LONG_PTR
    fun CallWindowProcW(lpPrevWndFunc: Pointer, hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    fun SetWindowPos(hWnd: HWND, hWndInsertAfter: HWND?, x: Int, y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
    fun GetWindowRect(hWnd: HWND, lpRect: RECT): Boolean
    fun MonitorFromWindow(hWnd: HWND, dwFlags: Int): Pointer?
    fun GetMonitorInfoW(hMonitor: Pointer, lpmi: MONITORINFO): Boolean
    fun IsZoomed(hWnd: HWND): Boolean
    fun PostMessageW(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): Boolean
    fun SendMessageW(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    fun ShowWindow(hWnd: HWND, nCmdShow: Int): Boolean
    fun ScreenToClient(hWnd: HWND, lpPoint: POINT): Boolean
    fun GetDpiForWindow(hWnd: HWND): Int
    fun GetSystemMetricsForDpi(nIndex: Int, dpi: Int): Int
}

// System Metrics
const val SM_CXFRAME = 32
const val SM_CYFRAME = 33
const val SM_CXPADDEDBORDER = 92
const val SM_CXEDGE = 45
const val SM_CYEDGE = 46

interface DwmApi : StdCallLibrary {
    companion object {
        val INSTANCE: DwmApi = Native.load("dwmapi", DwmApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun DwmExtendFrameIntoClientArea(hWnd: HWND, pMarInset: MARGINS): HRESULT
    fun DwmSetWindowAttribute(hWnd: HWND, dwAttribute: Int, pvAttribute: Pointer, cbAttribute: Int): HRESULT
}

// ==================== 回调接口 ====================

interface WndProcCallback : StdCallLibrary.StdCallCallback {
    fun callback(hWnd: HWND, msg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
}
