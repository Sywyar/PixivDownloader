package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;

import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** Compose 关于页的应用资料、受控链接和更新动作。 */
final class DesktopAboutView {
    private final ComposeDesktopUiModel owner;
    private final DesktopUiHost host;
    private final DesktopUpdateController updates;
    private final DesktopApplicationResources.Snapshot resources = DesktopApplicationResources.load();

    DesktopAboutView(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            DesktopStatusController status
    ) {
        this.owner = owner;
        this.host = host;
        this.updates = status.updates;
    }

    DesktopUiNode page(Map<String, Runnable> actions) {
        String version = host.applicationVersion().isBlank() ? host.message("app.version.unknown") : host.applicationVersion();
        Image icon = resources.applicationIcon().map(data -> new Image(
                "about.icon",
                data,
                key("desktop.ui.about.icon-alt"),
                80,
                80,
                ScaleMode.FIT
        )).orElse(null);
        List<AboutMaintainer> people = resources.maintainers().stream().map(person -> {
            String id = "about.maintainer." + person.id();
            actions.put(id + ".open", () -> owner.openUri(person.profileUrl()));
            return new AboutMaintainer(
                    new Image(
                            id + ".avatar",
                            person.avatar(),
                            appToken("desktop.ui.about.maintainer.avatar-alt", person.login()),
                            38,
                            38,
                            ScaleMode.FILL,
                            ImageShape.CIRCLE
                    ),
                    new Link(id + ".name", id + ".open", TextToken.raw(person.login()), null, !owner.busy()),
                    key("desktop.ui.about.maintainer.role." + person.role()));
        }).toList();
        return new AboutOverview(
                "about.overview",
                icon,
                host.applicationName(),
                version,
                button(
                        "about.update.check",
                        "about.update.check",
                        "gui.update.action.check",
                        !owner.busy(),
                        actions,
                        updates::checkUpdatesInline
                ),
                updates.aboutState(),
                updates.banners("about.update", actions),
                List.of(
                        link("about.project", "desktop.ui.about.project", host.projectUrl(), actions),
                        link("about.docs", "desktop.ui.about.documentation", "https://sywyar.github.io/PixivDownloader/", actions),
                        link("about.releases", "desktop.ui.about.releases", host.releasesUrl(), actions)
                ),
                people,
                key("gui.about.disclaimer.text"),
                resources.licenseText(),
                ComposeApplicationInfo.platformFacts(host)
        );
    }

    private Link link(String id, String label, String uri, Map<String, Runnable> actions) {
        actions.put(id + ".open", () -> owner.openUri(uri));
        return new Link(id, id + ".open", key(label), null, !owner.busy());
    }
}
