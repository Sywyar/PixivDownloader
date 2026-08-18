package top.sywyar.pixivdownload.plugin.api.gui;

/** Stable request headers used when a desktop UI invokes a plugin-owned GUI action. */
public final class GuiActionInvocationHeaders {
    /** Header carrying the claimed plugin owner id. */
    public static final String PLUGIN_OWNER = "X-PixivDownload-Plugin-Owner";

    private GuiActionInvocationHeaders() {
    }
}