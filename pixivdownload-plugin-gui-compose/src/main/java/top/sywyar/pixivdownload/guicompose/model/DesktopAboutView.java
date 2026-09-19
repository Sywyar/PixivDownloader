package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import top.sywyar.pixivdownload.guicompose.model.DesktopApplicationResources.Maintainer;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ContainerLayout;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * Compose 应用元数据、维护者、更新入口与许可证页面。
 */
final class DesktopAboutView {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final DesktopStatusController statusController;
    private final Optional<DesktopUiNode.ImageData> applicationIcon;
    private final List<Maintainer> maintainers;
    private final String licenseText;

    DesktopAboutView(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            DesktopStatusController statusController
    ) {
        this.owner = owner;
        this.host = host;
        this.statusController = statusController;
        DesktopApplicationResources.Snapshot resources = DesktopApplicationResources.load();
        this.applicationIcon = resources.applicationIcon();
        this.maintainers = resources.maintainers();
        this.licenseText = resources.licenseText();
    }

    DesktopUiNode page(Map<String, Runnable> nextActions) {
        String version = host.applicationVersion().isBlank() ? host.message("app.version.unknown") : host.applicationVersion();
        List<DesktopUiNode> identity = new ArrayList<>();
        applicationIcon.ifPresent(icon -> identity.add(new DesktopUiNode.Image("about.icon", icon,
                key("desktop.ui.about.icon-alt"), 40, 40, DesktopUiNode.ScaleMode.FIT)));
        identity.add(raw("about.name", host.applicationName(), TextStyle.TITLE));
        List<DesktopUiNode> content = new ArrayList<>();
        content.add(new DesktopUiNode.Container("about.identity", ContainerLayout.FLOW, 1, 12, Alignment.START, identity));
        content.add(text("about.description", "desktop.ui.about.description", TextStyle.BODY));
        content.add(new DesktopUiNode.Container("about.version.actions", ContainerLayout.FLOW, 1, 12, Alignment.START, List.of(
                new DesktopUiNode.Text("about.version", appToken("gui.about.version", version), TextStyle.BODY, true, false),
                button("about.update.check", "about.update.check", "gui.update.action.check", !owner.busy(), nextActions,
                        statusController.updates::checkUpdates))));
        content.addAll(statusController.updates.banners("about.update", nextActions));
        List<DesktopUiNode> links = new ArrayList<>();
        links.add(link("about.project", "desktop.ui.about.project", host.projectUrl(), nextActions));
        links.add(link("about.docs", "desktop.ui.about.documentation", "https://sywyar.github.io/PixivDownloader/", nextActions));
        links.add(link("about.releases", "desktop.ui.about.releases", host.releasesUrl(), nextActions));
        content.add(new DesktopUiNode.Container("about.links", ContainerLayout.FLOW, 1, 16, Alignment.START, links));
        List<DesktopUiNode> credits = new ArrayList<>();
        if (maintainers.isEmpty()) credits.add(text("about.maintainers.load-failed", "desktop.ui.about.maintainers.load-failed", TextStyle.ERROR));
        for (Maintainer maintainer : maintainers) {
            String base = "about.maintainer." + maintainer.id();
            nextActions.put(base + ".open", () -> owner.openUri(maintainer.profileUrl()));
            credits.add(new DesktopUiNode.Container(base, ContainerLayout.ROW, 1, 8, Alignment.CENTER, List.of(
                    new DesktopUiNode.Image(base + ".avatar", maintainer.avatar(),
                            appToken("desktop.ui.about.maintainer.avatar-alt", maintainer.login()), 28, 28,
                            DesktopUiNode.ScaleMode.FILL, DesktopUiNode.ImageShape.CIRCLE),
                    new DesktopUiNode.Link(base + ".name", base + ".open", TextToken.raw(maintainer.login()), null, true),
                    text(base + ".role", "desktop.ui.about.maintainer.role." + maintainer.role(), TextStyle.CAPTION))));
        }
        content.add(group("about.contributors", "desktop.ui.about.contributors.title",
                new DesktopUiNode.Container("about.maintainers", ContainerLayout.FLOW, 1, 20, Alignment.START, credits)));
        content.add(new DesktopUiNode.Group("about.disclaimer", key("gui.about.disclaimer.title"),
                text("about.disclaimer.text", "gui.about.disclaimer.text", TextStyle.BODY), true));
        content.add(new DesktopUiNode.Group("about.license", key("gui.about.license.title"),
                new DesktopUiNode.Text("about.license.text", TextToken.raw(licenseText), TextStyle.CODE, true, true), true));
        content.add(new DesktopUiNode.Group("about.technical", new TextToken("gui-compose", "gui.compose.about.technical", "", List.of()),
                column("about.technical.content", text("about.license.badge", "gui.about.license.badge", TextStyle.CAPTION), new DesktopUiNode.Text("about.tech", appToken("gui.about.tech", ComposeApplicationInfo.kotlinVersion("--")),
                        TextStyle.CAPTION, true, false)), true));
        return scroll("about.scroll", column("about.content", content));
    }

    private DesktopUiNode link(String id, String label, String uri, Map<String, Runnable> nextActions) {
        nextActions.put(id + ".open", () -> owner.openUri(uri));
        return new DesktopUiNode.Link(id, id + ".open", key(label), null, true);
    }
}
