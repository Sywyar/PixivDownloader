package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.gui.AppDesktopUiModel.RendererContract;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.common.AppVersion;
import top.sywyar.pixivdownload.gui.DesktopApplicationResources.Maintainer;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiExperienceProfile;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.ContainerLayout;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;

/**
 * 应用元数据、维护者、更新入口与许可证页面。
 */
final class DesktopAboutView {
    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final DesktopStatusController statusController;
    private final RendererContract rendererContract;
    private final Optional<DesktopUiNode.ImageData> applicationIcon;
    private final List<Maintainer> maintainers;
    private final String licenseText;

    DesktopAboutView(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            DesktopStatusController statusController,
            RendererContract rendererContract
    ) {
        this.owner = owner;
        this.host = host;
        this.statusController = statusController;
        this.rendererContract = rendererContract;
        DesktopApplicationResources.Snapshot resources = DesktopApplicationResources.load();
        this.applicationIcon = resources.applicationIcon();
        this.maintainers = resources.maintainers();
        this.licenseText = resources.licenseText();
    }

    DesktopUiNode page(Map<String, Runnable> nextActions) {
        boolean controlCenter = rendererContract.experienceProfile() == DesktopUiExperienceProfile.CONTROL_CENTER;
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
                controlCenter ? "desktop.ui.about.description" : "gui.about.description",
                TextStyle.BODY,
                DesktopUiNode.TextAlignment.CENTER
        ));
        List<DesktopUiNode> links = new ArrayList<>();
        String projectAction = "about.project.open";
        nextActions.put(projectAction, () -> owner.openUri(host.projectUrl()));
        links.add(new DesktopUiNode.Link(
                "about.project",
                projectAction,
                controlCenter ? key("desktop.ui.about.project") : TextToken.raw(host.projectUrl()),
                null,
                true
        ));
        if (controlCenter) {
            String releasesAction = "about.releases.open";
            nextActions.put(releasesAction, () -> owner.openUri(AppInfo.RELEASES_URL));
            links.add(raw("about.links.project-separator", "·", TextStyle.CAPTION));
            links.add(new DesktopUiNode.Link(
                    "about.releases",
                    releasesAction,
                    key("desktop.ui.about.releases"),
                    null,
                    true
            ));
            String docsAction = "about.docs.open";
            nextActions.put(docsAction, () -> owner.openUri(AppInfo.DOCS_URL));
            links.add(raw("about.links.releases-separator", "·", TextStyle.CAPTION));
            links.add(new DesktopUiNode.Link(
                    "about.docs",
                    docsAction,
                    key("desktop.ui.about.documentation"),
                    null,
                    true
            ));
        }
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
                                appToken("gui.about.tech", AppVersion.getKotlinVersionOrDefault("--")),
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
                controlCenter ? DesktopUiNode.SurfaceStyle.CARD : DesktopUiNode.SurfaceStyle.PLAIN,
                controlCenter ? DesktopUiNode.Insets.all(18) : new DesktopUiNode.Insets(
                        0,
                        0,
                        8,
                        0
                ),
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
        if (controlCenter) {
            List<DesktopUiNode> maintainerContent = new ArrayList<>();
            maintainerContent.add(text(
                    "about.contributors.title",
                    "desktop.ui.about.contributors.title",
                    TextStyle.HEADING
            ));
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
        } else {
            summaryContent.add(applicationInfo);
        }
        if (controlCenter) {
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
