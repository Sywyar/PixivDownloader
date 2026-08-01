package top.sywyar.pixivdownload.common.web;

/**
 * Host-internal headers used when the desktop GUI invokes a plugin-contributed configuration action.
 *
 * <p>The owner value is not an authentication credential. {@code AuthFilter} first applies the normal local-request
 * and GUI-token checks, then uses this claim to verify the target against the current route registry.
 */
public final class GuiActionInvocationHeaders {

    public static final String PLUGIN_OWNER = "X-Pixiv-Gui-Action-Owner";

    private GuiActionInvocationHeaders() {
    }
}
