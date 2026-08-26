package top.sywyar.pixivdownload.plugin.api.gui;

/** 桌面界面调用插件自有 GUI 动作时使用的稳定请求头。 */
public final class GuiActionInvocationHeaders {
    /** 携带所声明插件 owner id 的请求头。 */
    public static final String PLUGIN_OWNER = "X-PixivDownload-Plugin-Owner";

    private GuiActionInvocationHeaders() {
    }
}
