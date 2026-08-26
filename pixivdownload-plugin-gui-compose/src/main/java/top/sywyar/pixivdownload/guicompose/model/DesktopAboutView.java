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
        List<DesktopUiNode> header = new ArrayList<>();
        applicationIcon.ifPresent(icon -> header.add(new DesktopUiNode.Image(
                "about.icon",
                icon,
                key("desktop.ui.about.icon-alt"),
                48,
                48,
                DesktopUiNode.ScaleMode.FIT
        )));
        header.add(alignedRaw(
                "about.name",
                host.applicationName(),
                TextStyle.TITLE,
                DesktopUiNode.TextAlignment.CENTER
        ));
        String version = host.applicationVersion().isBlank() ? host.message("app.version.unknown") : host.applicationVersion();
        header.add(alignedText(
                "about.description",
                "desktop.ui.about.description",
                TextStyle.BODY,
                DesktopUiNode.TextAlignment.CENTER
        ));
        List<DesktopUiNode> links = new ArrayList<>();
        String projectAction = "about.project.open";
        nextActions.put(projectAction, () -> owner.openUri(host.projectUrl()));
        links.add(new DesktopUiNode.Link(
                "about.project",
                projectAction,
                key("desktop.ui.about.project"),
                null,
                true
        ));
        String releasesAction = "about.releases.open";
            nextActions.put(releasesAction, () -> owner.openUri(host.releasesUrl()));
            links.add(raw("about.links.project-separator", "·", TextStyle.CAPTION));
        links.add(new DesktopUiNode.Link(
                    "about.releases",
                    releasesAction,
                    key("desktop.ui.about.releases"),
                    null,
                    true
        ));
        String docsAction = "about.docs.open";
            nextActions.put(docsAction, () -> owner.openUri("https://sywyar.github.io/PixivDownloader/"));
            links.add(raw("about.links.releases-separator", "·", TextStyle.CAPTION));
        links.add(new DesktopUiNode.Link(
                    "about.docs",
                    docsAction,
                    key("desktop.ui.about.documentation"),
                    null,
                    true
        ));
        header.add(new DesktopUiNode.Container(
                "about.links",
                ContainerLayout.ROW,
                1,
                6,
                Alignment.CENTER,
                links
        ));
        header.add(new DesktopUiNode.Container(
                "about.metadata",
                ContainerLayout.FLOW,
                1,
                12,
                Alignment.CENTER,
                List.of(
                        new DesktopUiNode.Text(
                                "about.version",
                                appToken("gui.about.version", version),
                                TextStyle.CAPTION,
                                false,
                                false,
                                DesktopUiNode.TextAlignment.CENTER
                        ),
                        alignedText(
                                "about.license.badge",
                                "gui.about.license.badge",
                                TextStyle.SUCCESS,
                                DesktopUiNode.TextAlignment.CENTER
                        ),
                        new DesktopUiNode.Text(
                                "about.tech",
                                appToken("gui.about.tech", ComposeApplicationInfo.kotlinVersion("--")),
                                TextStyle.CAPTION,
                                false,
                                false,
                                DesktopUiNode.TextAlignment.CENTER
                        )
                )
        ));

        List<DesktopUiNode> summaryContent = new ArrayList<>();
        DesktopUiNode applicationInfo = new DesktopUiNode.Surface(
                "about.header",
                DesktopUiNode.SurfaceStyle.CARD,
                DesktopUiNode.Insets.all(18),
                true,
                new DesktopUiNode.Container(
                        "about.header.content",
                        ContainerLayout.COLUMN,
                        1,
                        6,
                        Alignment.CENTER,
                        header
                )
        );
        {
            List<DesktopUiNode> maintainerContent = new ArrayList<>();
            maintainerContent.add(text(
                    "about.contributors.title",
                    "desktop.ui.about.contributors.title",
                    TextStyle.HEADING
            ));
            if (maintainers.isEmpty()) {
                maintainerContent.add(text(
                        "about.maintainers.load-failed",
                        "desktop.ui.about.maintainers.load-failed",
                        TextStyle.ERROR
                ));
            } else {
                List<DesktopUiNode> maintainerCards = new ArrayList<>();
                for (Maintainer maintainer : maintainers) {
                    String base = "about.maintainer." + maintainer.id();
                    String action = base + ".open";
                    nextActions.put(action, () -> owner.openUri(maintainer.profileUrl()));
                    maintainerCards.add(new DesktopUiNode.Surface(
                            base,
                            DesktopUiNode.SurfaceStyle.CARD,
                            DesktopUiNode.Insets.all(12),
                            false,
                            action,
                            new DesktopUiNode.Container(
                                    base + ".content",
                                    ContainerLayout.COLUMN,
                                    1,
                                    8,
                                    Alignment.CENTER,
                                    List.of(
                                            new DesktopUiNode.Image(
                                                    base + ".avatar",
                                                    maintainer.avatar(),
                                                    appToken(
                                                            "desktop.ui.about.maintainer.avatar-alt",
                                                            maintainer.login()
                                                    ),
                                                    72,
                                                    72,
                                                    DesktopUiNode.ScaleMode.FILL,
                                                    DesktopUiNode.ImageShape.CIRCLE
                                            ),
                                            new DesktopUiNode.Text(
                                                    base + ".name",
                                                    TextToken.raw(maintainer.login()),
                                                    TextStyle.EMPHASIS,
                                                    false,
                                                    false,
                                                    DesktopUiNode.TextAlignment.CENTER
                                            ),
                                            new DesktopUiNode.Text(
                                                    base + ".role",
                                                    key("desktop.ui.about.maintainer.role."
                                                            + maintainer.role()),
                                                    TextStyle.CAPTION,
                                                    false,
                                                    false,
                                                    DesktopUiNode.TextAlignment.CENTER
                                            )
                                    )
                            )
                    ));
                }
                maintainerContent.add(new DesktopUiNode.Container(
                        "about.maintainers",
                        ContainerLayout.FLOW,
                        1,
                        12,
                        Alignment.START,
                        maintainerCards
                ));
            }
            DesktopUiNode contributors = new DesktopUiNode.Surface(
                    "about.contributors",
                    DesktopUiNode.SurfaceStyle.CARD,
                    DesktopUiNode.Insets.all(18),
                    true,
                    column("about.contributors.content", maintainerContent)
            );
            summaryContent.add(new DesktopUiNode.AdaptiveGrid(
                    "about.overview",
                    280,
                    2,
                    16,
                    16,
                    List.of(applicationInfo, contributors)
            ));
        }
        {
            List<DesktopUiNode> updates = new ArrayList<>(statusController.updates.banners(
                    "about.update",
                    nextActions
            ));
            updates.add(button(
                    "about.update.check",
                    "about.update.check",
                    "gui.update.action.check",
                    !owner.busy(),
                    nextActions,
                    statusController.updates::checkUpdates
            ));
            summaryContent.add(group(
                    "about.update",
                    "gui.config.group.update",
                    column("about.update.content", updates)
            ));
        }
        summaryContent.add(group(
                "about.disclaimer",
                "gui.about.disclaimer.title",
                text("about.disclaimer.text", "gui.about.disclaimer.text", TextStyle.BODY)
        ));
        summaryContent.add(alignedText(
                "about.license.title",
                "gui.about.license.title",
                TextStyle.EMPHASIS,
                DesktopUiNode.TextAlignment.CENTER
        ));
        DesktopUiNode summary = column("about.summary", summaryContent);
        return new DesktopUiNode.Dock(
                "about.root",
                8,
                summary,
                scroll(
                        "about.license.scroll",
                        new DesktopUiNode.Text(
                                "about.license.text",
                                TextToken.raw(licenseText),
                                TextStyle.CODE,
                                true,
                                true
                        )
                ),
                null,
                null,
                null
        );
    }

    private static DesktopUiNode.Text alignedText(
            String id,
            String messageKey,
            TextStyle style,
            DesktopUiNode.TextAlignment alignment
    ) {
        return new DesktopUiNode.Text(
                id,
                key(messageKey),
                style,
                true,
                false,
                alignment
        );
    }

    private static DesktopUiNode.Text alignedRaw(
            String id,
            String value,
            TextStyle style,
            DesktopUiNode.TextAlignment alignment
    ) {
        return new DesktopUiNode.Text(
                id,
                TextToken.raw(value == null || value.isBlank() ? "--" : value),
                style,
                true,
                style == TextStyle.CODE,
                alignment
        );
    }
}
