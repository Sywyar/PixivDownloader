package top.sywyar.pixivdownload.guicompose.model;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.AboutFact;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

/** 关于页只收集明确列出的平台信息，不读取用户目录、环境变量或凭据。 */
final class ComposeApplicationInfo {
    private ComposeApplicationInfo() {}

    static List<AboutFact> platformFacts(DesktopUiHost host) {
        return List.of(
                fact("version", value(host.applicationVersion())),
                fact("channel", channel(host.applicationBuildChannel())),
                fact("mode", label(host.developmentMode() ? "development" : "normal")),
                fact("os", property("os.name")),
                fact("os-version", property("os.version")),
                fact("architecture", property("os.arch")),
                fact("java", property("java.runtime.version")),
                fact("java-vendor", property("java.vendor")),
                fact("vm", property("java.vm.name")),
                fact("interface", TextToken.raw("Compose Multiplatform")),
                fact("kotlin", TextToken.raw(kotlin.KotlinVersion.CURRENT.toString())),
                fact("launch", label(host.launchedFromExecutable() ? "executable" : "jvm"))
        );
    }

    private static AboutFact fact(String id, TextToken value) {
        return new AboutFact(id, label("platform." + id), value);
    }

    private static TextToken channel(DesktopUiHost.BuildChannel channel) {
        if (channel == null) return label("unknown");
        return label(switch (channel) {
            case LOCAL -> "local";
            case RELEASE -> "release";
            case NIGHTLY -> "nightly";
            case UNKNOWN -> "unknown";
        });
    }

    private static TextToken property(String name) {
        try { return value(System.getProperty(name)); }
        catch (SecurityException denied) { return label("unknown"); }
    }

    private static TextToken value(String value) {
        return value == null || value.isBlank() ? label("unknown") : TextToken.raw(value);
    }

    private static TextToken label(String key) {
        return new TextToken("gui-compose", "gui.compose.about." + key, "", List.of());
    }

}
