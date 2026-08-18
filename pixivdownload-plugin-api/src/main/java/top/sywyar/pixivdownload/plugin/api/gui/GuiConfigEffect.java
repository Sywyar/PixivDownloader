package top.sywyar.pixivdownload.plugin.api.gui;

/** 配置字段保存后的生效方式，由 GUI 渲染器据此提示并执行相应动作。 */
public enum GuiConfigEffect {
    /** 保存后可由运行中的后端立即应用。 */
    HOT_RELOAD,
    /** 保存后需要重建后端服务，但桌面 UI 进程可继续运行。 */
    BACKEND_RESTART,
    /** 保存后需要完整重启应用进程。 */
    PROCESS_RESTART
}
