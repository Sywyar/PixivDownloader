package releasee2e.fixture;

import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;

/** 正常加载、仅在真正启动桌面时失败的第三方测试包。 */
public final class FailingGuiPlugin extends Plugin implements PixivPluginProvider {
    @Override public PixivFeaturePlugin featurePlugin() { return new Feature(); }

    public static final class Feature implements PixivFeaturePlugin, DesktopUiProvider {
        @Override public String id() { return "release-e2e-broken-gui"; }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.description"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
        @Override public DesktopUiSession launch(DesktopUiContext context) {
            throw new ExceptionInInitializerError("RELEASE_E2E_GUI_INITIALIZER_FAILURE");
        }
    }
}
