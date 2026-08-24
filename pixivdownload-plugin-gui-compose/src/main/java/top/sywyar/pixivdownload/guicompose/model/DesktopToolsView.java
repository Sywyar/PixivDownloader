package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.Alignment;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.ContainerLayout;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.SelectionMode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 工具中心的文档节点与对话框渲染。
 */
final class DesktopToolsView {
    private final DesktopToolsController model;

    DesktopToolsView(DesktopToolsController model) {
        this.model = model;
    }

    DesktopUiDocument.Dialog dialog(
            DesktopToolsController.ToolDialog value,
            Map<String, Runnable> nextActions
    ) {
        String base = value == DesktopToolsController.ToolDialog.IMAGE_CLASSIFIER ? "classifier.dialog" : "folder.dialog";
        String dismissAction = base + ".dismiss";
        Runnable dismiss = () -> {
            model.toolDialog = null;
            if (value == DesktopToolsController.ToolDialog.FOLDER_CHECKER)
                model.closeFolderCheckerDialog();
            else model.closeImageClassifierDialog();
        };
        nextActions.put(dismissAction, dismiss);
        DesktopUiNode content = value == DesktopToolsController.ToolDialog.IMAGE_CLASSIFIER ? classifierDialogContent(
                nextActions) : folderDialogContent(nextActions);
        String title = value == DesktopToolsController.ToolDialog.IMAGE_CLASSIFIER ? "gui.tools.card.image-classifier.title" : "gui.tools.card.folder-checker.title";
        return new DesktopUiDocument.Dialog(
                base,
                key(title),
                DesktopUiDocument.DialogStyle.INFO,
                new DesktopUiNode.Dock(
                        base + ".layout",
                        12,
                        null,
                        scroll(base + ".scroll", content),
                        row(
                                base + ".actions",
                                button(
                                        base + ".close",
                                        dismissAction,
                                        "desktop.ui.action.close",
                                        !model.owner.busy(),
                                        nextActions,
                                        dismiss
                                )
                        ),
                        null,
                        null
                ),
                dismissAction,
                !model.owner.busy(),
                value == DesktopToolsController.ToolDialog.IMAGE_CLASSIFIER ? 1180 : 860,
                value == DesktopToolsController.ToolDialog.IMAGE_CLASSIFIER ? 760 : 640
        );
    }

    private DesktopUiNode classifierDialogContent(Map<String, Runnable> nextActions) {
        DesktopUiNode top = row(
                "classifier.top",
                input(
                        "classifier.default-folder.input",
                        "classifier.default-folder",
                        "gui.image-classifier.label.folder-path",
                        null,
                        InputKind.DIRECTORY,
                        model.form("classifier.default-folder", ""),
                        !model.owner.busy()
                ),
                button(
                        "classifier.open",
                        "classifier.open",
                        "gui.image-classifier.button.open",
                        !model.owner.busy(),
                        nextActions,
                        model::scanClassifierFolders
                ),
                button(
                        "classifier.settings",
                        "classifier.settings",
                        "gui.image-classifier.button.settings",
                        !model.owner.busy(),
                        nextActions,
                        model::showClassifierSettings
                ),
                raw(
                        "classifier.server",
                        model.classifierServer.available() ? model.host.message(
                                "gui.image-classifier.server.ok") : model.host.message(
                                "gui.image-classifier.server.connect-failed"),
                        model.classifierServer.available() ? TextStyle.SUCCESS : TextStyle.WARNING
                )
        );
        DesktopUiNode center = new DesktopUiNode.Split(
                "classifier.center",
                DesktopUiNode.Axis.HORIZONTAL,
                0.78d,
                classifierPreview(nextActions),
                classifierCategories(nextActions)
        );
        return new DesktopUiNode.Dock(
                "classifier.dialog.content",
                10,
                top,
                center,
                classifierStatus(),
                null,
                null
        );
    }

    private DesktopUiNode classifierPreview(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> thumbnails = new ArrayList<>();
        int start = model.classifierGroupIndex * 10;
        int end = Math.min(start + 10, model.classifierImages.size());
        for (int index = start; index < end; index++) {
            int imageIndex = index;
            Path image = model.classifierImages.get(index);
            String base = "classifier.image." + index;
            List<DesktopUiNode> content = new ArrayList<>();
            model.classifierSupport.materializeImage(image).ifPresent(data -> content.add(new DesktopUiNode.Image(
                    base + ".preview",
                    data,
                    TextToken.raw(image.getFileName().toString()),
                    160,
                    150,
                    DesktopUiNode.ScaleMode.FIT
            )));
            content.add(raw(
                    base + ".name",
                    image.getFileName().toString(),
                    TextStyle.CAPTION
            ));
            content.add(rawButton(
                    base + ".open",
                    base + ".open",
                    image.getFileName().toString(),
                    !model.owner.busy(),
                    nextActions,
                    () -> model.showClassifierImage(imageIndex)
            ));
            thumbnails.add(new DesktopUiNode.Surface(
                    base,
                    DesktopUiNode.SurfaceStyle.CARD,
                    DesktopUiNode.Insets.all(6),
                    true,
                    column(base + ".content", content)
            ));
        }
        if (thumbnails.isEmpty()) {
            thumbnails.add(text(
                    "classifier.images.empty",
                    "gui.image-classifier.thumbnail.empty",
                    TextStyle.CAPTION
            ));
        }
        int groups = model.classifierImages.isEmpty() ? 0 : (model.classifierImages.size() + 9) / 10;
        return column(
                "classifier.preview",
                new DesktopUiNode.Container(
                        "classifier.thumbnails",
                        ContainerLayout.GRID,
                        5,
                        8,
                        Alignment.STRETCH,
                        thumbnails
                ),
                row(
                        "classifier.group.navigation",
                        button(
                                "classifier.group.previous",
                                "classifier.group.previous",
                                "gui.image-classifier.button.prev-group",
                                !model.owner.busy() && model.classifierGroupIndex > 0,
                                nextActions,
                                () -> model.moveClassifierGroup(-1)
                        ),
                        raw(
                                "classifier.group.position",
                                groups == 0 ? "--" : (model.classifierGroupIndex + 1) + " / " + groups,
                                TextStyle.CAPTION
                        ),
                        button(
                                "classifier.group.next",
                                "classifier.group.next",
                                "gui.image-classifier.button.next-group",
                                !model.owner.busy() && model.classifierGroupIndex + 1 < groups,
                                nextActions,
                                () -> model.moveClassifierGroup(1)
                        )
                )
        );
    }

    private DesktopUiNode classifierCategories(Map<String, Runnable> nextActions) {
        List<DesktopUiHost.ImageClassifierTarget> targets = model.classifierSupport.parseTargets(
                model.form("classifier.targets", ""));
        List<DesktopUiNode> categories = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            int targetIndex = index;
            DesktopUiHost.ImageClassifierTarget target = targets.get(index);
            String label = index + "  " + (target.remark().isBlank() ? target.folder() : target.remark());
            categories.add(new DesktopUiNode.Surface(
                    "classifier.category." + index,
                    DesktopUiNode.SurfaceStyle.MUTED,
                    DesktopUiNode.Insets.all(6),
                    true,
                    column(
                            "classifier.category." + index + ".content",
                            rawButton(
                                    "classifier.category." + index + ".select",
                                    "classifier.category." + index + ".select",
                                    label,
                                    !model.owner.busy(),
                                    nextActions,
                                    () -> {
                                        model.formValues.put(
                                                "classifier.target",
                                                "target." + targetIndex
                                        );
                                        model.owner.rebuild();
                                    }
                            ),
                            raw(
                                    "classifier.category." + index + ".path",
                                    target.folder(),
                                    TextStyle.CODE
                            )
                    )
            ));
        }
        if (categories.isEmpty()) categories.add(text(
                "classifier.categories.empty",
                "gui.image-classifier.validation.target-folders-not-configured",
                TextStyle.WARNING
        ));
        String source = model.form("classifier.source", "");
        String target = model.form("classifier.target", "");
        List<DesktopUiNode> actions = new ArrayList<>();
        actions.add(button(
                "classifier.classify",
                "classifier.classify",
                "gui.image-classifier.button.classify-folder",
                !model.owner.busy() && !source.isBlank() && !target.isBlank() && !model.classifierImages.isEmpty(),
                nextActions,
                model::classifyFolder
        ));
        if (model.boolForm("classifier.show-skip", true)) {
            actions.add(button(
                    "classifier.skip",
                    "classifier.skip",
                    "gui.image-classifier.button.skip-folder",
                    !model.owner.busy() && !source.isBlank(),
                    nextActions,
                    () -> model.moveClassifierFolder(1)
            ));
        }
        actions.add(button(
                "classifier.previous",
                "classifier.previous",
                "gui.image-classifier.button.prev-folder",
                !model.owner.busy() && model.classifierFolderIndex() > 0,
                nextActions,
                () -> model.moveClassifierFolder(-1)
        ));
        actions.add(button(
                "classifier.refresh",
                "classifier.refresh",
                "gui.image-classifier.button.refresh-thumbnails",
                !model.owner.busy() && !source.isBlank(),
                nextActions,
                model::refreshClassifierFolder
        ));
        return column(
                "classifier.sidebar",
                group(
                        "classifier.categories",
                        "gui.image-classifier.section.categories",
                        scroll(
                                "classifier.categories.scroll",
                                column("classifier.categories.rows", categories)
                        )
                ),
                group(
                        "classifier.actions",
                        "gui.image-classifier.section.classify",
                        column("classifier.actions.content", actions)
                )
        );
    }

    private DesktopUiNode classifierStatus() {
        String source = model.form("classifier.source", "");
        Path folder = model.classifierSupport.path(source);
        if (folder == null) return status(
                "classifier.notice",
                model.classifierNotice.isBlank() ? model.host.message(
                        "gui.image-classifier.status.select-parent-folder") : model.classifierNotice
        );
        int groups = model.classifierImages.isEmpty() ? 0 : (model.classifierImages.size() + 9) / 10;
        String value = model.host.message(
                "gui.image-classifier.status.current-folder",
                folder.getFileName(),
                model.classifierImages.size(),
                groups == 0 ? 0 : model.classifierGroupIndex + 1,
                groups
        );
        if (model.classifierArtwork != null) {
            value += "   " + model.host.message(model.classifierArtwork.xRestrict() == null ? "gui.image-classifier.status.tag.unknown" : model.classifierArtwork.xRestrict() == 2 ? "gui.image-classifier.status.tag.r18g" : model.classifierArtwork.xRestrict() == 1 ? "gui.image-classifier.status.tag.r18" : "gui.image-classifier.status.tag.sfw");
        }
        return status(
                "classifier.notice",
                model.classifierNotice.isBlank() ? value : model.classifierNotice
        );
    }

    private DesktopUiNode folderDialogContent(Map<String, Runnable> nextActions) {
        DesktopUiHost.FolderArtwork selected = model.selectedFolderRow == null ? null : model.folderArtworks.get(
                model.selectedFolderRow);
        return column(
                "folder.dialog.content",
                text(
                        "tools.folder.help",
                        "desktop.ui.tools.folder.description",
                        TextStyle.CAPTION
                ),
                input(
                        "tools.folder.db",
                        "tools.folder.db",
                        "gui.tools.form.database-path",
                        null,
                        InputKind.FILE,
                        model.form(
                                "tools.folder.db",
                                model.host.resolveDatabasePath(model.rootFolder).toString()
                        ),
                        !model.owner.busy()
                ),
                button(
                        "tools.folder.check",
                        "tools.folder.check",
                        "gui.folder-checker.button.check-folders",
                        !model.owner.busy(),
                        nextActions,
                        model::checkFolders
                ),
                status(
                        "tools.folder.notice",
                        model.folderNotice.isBlank() ? model.host.message(
                                "gui.tools.folder-checker.status.preparing") : model.folderNotice
                ),
                model.folderTable(),
                row(
                        "tools.folder.selected",
                        raw(
                                "tools.folder.selected-id",
                                model.host.message(
                                        "gui.folder-checker.label.selected-id",
                                        selected == null ? model.host.message("gui.value.none") : selected.artworkId()
                                ),
                                TextStyle.BODY
                        ),
                        button(
                                "tools.folder.copy",
                                "tools.folder.copy",
                                "gui.folder-checker.button.copy-id",
                                !model.owner.busy() && selected != null,
                                nextActions,
                                model::copySelectedFolderId
                        )
                ),
                input(
                        "tools.folder.new-path",
                        "tools.folder.new-path",
                        "gui.folder-checker.label.new-path",
                        null,
                        InputKind.DIRECTORY,
                        model.form("tools.folder.new-path", ""),
                        !model.owner.busy()
                ),
                button(
                        "tools.folder.update",
                        "tools.folder.update",
                        "gui.folder-checker.button.update-db",
                        !model.owner.busy() && selected != null,
                        nextActions,
                        model::requestFolderUpdate
                )
        );
    }

    DesktopUiNode controlCenterPage(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> cards = toolCards(nextActions);
        DesktopUiNode quick = new DesktopUiNode.AdaptiveGrid(
                "tools.quick.row",
                360,
                2,
                16,
                16,
                List.of(
                        new DesktopUiNode.AdaptiveGrid(
                                "tools.quick.grid",
                                260,
                                2,
                                12,
                                12,
                                List.of(cards.get(2), cards.get(3))
                        ),
                        cards.get(0)
                )
        );
        DesktopUiNode maintenance = new DesktopUiNode.AdaptiveGrid(
                "tools.maintenance.row",
                360,
                2,
                16,
                16,
                List.of(
                        new DesktopUiNode.AdaptiveGrid(
                                "tools.maintenance.grid",
                                320,
                                2,
                                12,
                                12,
                                List.of(cards.get(4), cards.get(5))
                        ),
                        cards.get(1)
                )
        );
        return scroll(
                "tools.scroll",
                column(
                        "tools.layout",
                        text(
                                "tools.quick.title",
                                "desktop.ui.tools.quick.title",
                                TextStyle.HEADING
                        ),
                        quick,
                        text(
                                "tools.maintenance.title",
                                "desktop.ui.tools.maintenance.title",
                                TextStyle.HEADING
                        ),
                        maintenance
                )
        );
    }

    private List<DesktopUiNode> toolCards(Map<String, Runnable> nextActions) {
        List<DesktopUiNode> cards = new ArrayList<>();
        cards.add(group(
                "tools.overview",
                "gui.tools.card.overview.title",
                column(
                        "tools.overview.content",
                        raw(
                                "tools.backend",
                                model.host.message(
                                        "gui.tools.backend-status",
                                        model.owner.backendMessage()
                                ),
                                TextStyle.BODY
                        ),
                        raw(
                                "tools.exclusive",
                                model.host.message(
                                        "gui.tools.exclusive-tool",
                                        model.exclusiveToolName.isBlank() ? model.host.message(
                                                "gui.value.none") : model.exclusiveToolName
                                ),
                                TextStyle.BODY
                        ),
                        text(
                                "tools.overview.hint",
                                "gui.tools.card.overview.hint",
                                TextStyle.CAPTION
                        )
                )
        ));
        cards.add(toolHistoryCard());
        cards.add(group(
                "tools.image-classifier",
                "gui.tools.card.image-classifier.title",
                column(
                        "tools.image-classifier.summary",
                        text(
                                "tools.image-classifier.description",
                                "gui.tools.card.image-classifier.description",
                                TextStyle.CAPTION
                        ),
                        row(
                                "tools.image-classifier.actions",
                                button(
                                        "tools.image-classifier.open",
                                        "tools.image-classifier.open",
                                        "gui.tools.action.open-image-classifier",
                                        !model.owner.busy(),
                                        nextActions,
                                        () -> model.openToolDialog(DesktopToolsController.ToolDialog.IMAGE_CLASSIFIER)
                                )
                        )
                )
        ));
        cards.add(group(
                "tools.folder-checker",
                "gui.tools.card.folder-checker.title",
                column(
                        "tools.folder-checker.summary",
                        text(
                                "tools.folder-checker.description",
                                "gui.tools.card.folder-checker.description",
                                TextStyle.CAPTION
                        ),
                        row(
                                "tools.folder-checker.actions",
                                button(
                                        "tools.folder-checker.open",
                                        "tools.folder-checker.open",
                                        "gui.tools.action.open-folder-checker",
                                        !model.owner.busy(),
                                        nextActions,
                                        model::openFolderCheckerDialog
                                )
                        )
                )
        ));
        cards.add(group(
                "tools.backfill",
                "gui.tools.card.backfill.title",
                column(
                        "tools.backfill.content",
                        new DesktopUiNode.Form(
                                "tools.backfill.form",
                                DesktopUiNode.FormStyle.COMPACT,
                                null,
                                List.of(
                                        new DesktopUiNode.FormRow(
                                                "tools.backfill.db.row",
                                                key("gui.tools.form.database-path"),
                                                null,
                                                input(
                                                        "tools.backfill.db",
                                                        "tools.backfill.db",
                                                        "gui.tools.form.database-path",
                                                        null,
                                                        InputKind.FILE,
                                                        model.form(
                                                                "tools.backfill.db",
                                                                model.host.resolveDatabasePath(model.rootFolder).toString()
                                                        ),
                                                        !model.owner.busy()
                                                ),
                                                null
                                        ),
                                        new DesktopUiNode.FormRow(
                                                "tools.backfill.proxy.row",
                                                key("gui.tools.form.proxy"),
                                                null,
                                                new DesktopUiNode.Dock(
                                                        "tools.backfill.proxy.controls",
                                                        8,
                                                        null,
                                                        input(
                                                                "tools.backfill.proxy-host",
                                                                "tools.backfill.proxy-host",
                                                                "gui.tools.form.proxy-host",
                                                                null,
                                                                InputKind.TEXT,
                                                                model.form(
                                                                        "tools.backfill.proxy-host",
                                                                        model.host.defaultProxyHost()
                                                                ),
                                                                !model.owner.busy() && model.boolForm(
                                                                        "tools.backfill.proxy",
                                                                        true
                                                                )
                                                        ),
                                                        null,
                                                        row(
                                                                "tools.backfill.proxy.start",
                                                                toggle(
                                                                        "tools.backfill.proxy",
                                                                        "tools.backfill.proxy",
                                                                        "gui.tools.form.use-proxy",
                                                                        model.boolForm(
                                                                                "tools.backfill.proxy",
                                                                                true
                                                                        ),
                                                                        !model.owner.busy()
                                                                ),
                                                                text(
                                                                        "tools.backfill.proxy-host.label",
                                                                        "gui.tools.form.proxy-host",
                                                                        TextStyle.BODY
                                                                )
                                                        ),
                                                        row(
                                                                "tools.backfill.proxy.end",
                                                                text(
                                                                        "tools.backfill.proxy-port.label",
                                                                        "gui.tools.form.proxy-port",
                                                                        TextStyle.BODY
                                                                ),
                                                                number(
                                                                        "tools.backfill.proxy-port",
                                                                        "tools.backfill.proxy-port",
                                                                        "gui.tools.form.proxy-port",
                                                                        null,
                                                                        model.intForm(
                                                                                "tools.backfill.proxy-port",
                                                                                model.host.defaultProxyPort()
                                                                        ),
                                                                        1,
                                                                        65_535,
                                                                        !model.owner.busy() && model.boolForm(
                                                                                "tools.backfill.proxy",
                                                                                true
                                                                        )
                                                                )
                                                        )
                                                ),
                                                null
                                        ),
                                        new DesktopUiNode.FormRow(
                                                "tools.backfill.delay.row",
                                                key("gui.tools.form.delay-ms"),
                                                null,
                                                number(
                                                        "tools.backfill.delay",
                                                        "tools.backfill.delay",
                                                        "gui.tools.form.delay-ms",
                                                        null,
                                                        model.intForm("tools.backfill.delay", 1000),
                                                        0,
                                                        Integer.MAX_VALUE,
                                                        !model.owner.busy()
                                                ),
                                                new DesktopUiNode.Text(
                                                        "tools.backfill.limit-hint",
                                                        key("gui.tools.form.limit-hint"),
                                                        TextStyle.CAPTION,
                                                        false,
                                                        false
                                                )
                                        ),
                                        new DesktopUiNode.FormRow(
                                                "tools.backfill.limit.row",
                                                key("gui.tools.form.limit"),
                                                null,
                                                number(
                                                        "tools.backfill.limit",
                                                        "tools.backfill.limit",
                                                        "gui.tools.form.limit",
                                                        null,
                                                        model.intForm("tools.backfill.limit", 0),
                                                        0,
                                                        Integer.MAX_VALUE,
                                                        !model.owner.busy()
                                                ),
                                                toggle(
                                                        "tools.backfill.dry",
                                                        "tools.backfill.dry",
                                                        "gui.tools.form.dry-run",
                                                        model.boolForm("tools.backfill.dry", false),
                                                        !model.owner.busy()
                                                )
                                        )
                                )
                        ),
                        status(
                                "tools.backfill.notice",
                                model.backfillNotice.isBlank() ? model.host.message(
                                        "gui.tools.backfill.status.idle") : model.backfillNotice
                        ),
                        row(
                                "tools.backfill.actions",
                                button(
                                        "tools.backfill.run",
                                        "tools.backfill.run",
                                        "gui.tools.action.start-backfill",
                                        !model.owner.busy(),
                                        nextActions,
                                        model::runBackfill
                                ),
                                button(
                                        "tools.backfill.log",
                                        "tools.backfill.log",
                                        "gui.tools.action.open-log-page",
                                        !model.owner.busy() && Files.isRegularFile(Path.of(
                                                "log",
                                                "html",
                                                "artworks-backfill-latest.html"
                                        )),
                                        nextActions,
                                        () -> model.openToolLog("artworks-backfill")
                                )
                        )
                )
        ));
        cards.add(group(
                "tools.migration",
                "gui.tools.card.migration.title",
                column(
                        "tools.migration.content",
                        new DesktopUiNode.Form(
                                "tools.migration.form",
                                DesktopUiNode.FormStyle.COMPACT,
                                null,
                                List.of(
                                        new DesktopUiNode.FormRow(
                                                "tools.migration.db.row",
                                                key("gui.tools.form.database-path"),
                                                null,
                                                input(
                                                        "tools.migration.db",
                                                        "tools.migration.db",
                                                        "gui.tools.form.database-path",
                                                        null,
                                                        InputKind.FILE,
                                                        model.form(
                                                                "tools.migration.db",
                                                                model.host.resolveDatabasePath(model.rootFolder).toString()
                                                        ),
                                                        !model.owner.busy()
                                                ),
                                                null
                                        ),
                                        new DesktopUiNode.FormRow(
                                                "tools.migration.root.row",
                                                key("gui.tools.form.root-folder"),
                                                null,
                                                input(
                                                        "tools.migration.root",
                                                        "tools.migration.root",
                                                        "gui.tools.form.root-folder",
                                                        null,
                                                        InputKind.DIRECTORY,
                                                        model.form(
                                                                "tools.migration.root",
                                                                model.rootFolder
                                                        ),
                                                        !model.owner.busy()
                                                ),
                                                null
                                        )
                                )
                        ),
                        text(
                                "tools.migration.description",
                                "gui.tools.card.migration.description",
                                TextStyle.CAPTION
                        ),
                        status(
                                "tools.migration.notice",
                                model.migrationNotice.isBlank() ? model.host.message(
                                        "gui.tools.migration.status.idle") : model.migrationNotice
                        ),
                        row(
                                "tools.migration.actions",
                                button(
                                        "tools.migration.run",
                                        "tools.migration.run",
                                        "gui.tools.action.start-migration",
                                        !model.owner.busy(),
                                        nextActions,
                                        model::runMigration
                                ),
                                button(
                                        "tools.migration.log",
                                        "tools.migration.log",
                                        "gui.tools.action.open-migration-log-page",
                                        !model.owner.busy() && Files.isRegularFile(Path.of(
                                                "log",
                                                "html",
                                                "json-to-sqlite-migration-latest.html"
                                        )),
                                        nextActions,
                                        () -> model.openToolLog("json-to-sqlite-migration")
                                )
                        )
                )
        ));
        return List.copyOf(cards);
    }

    private DesktopUiNode toolHistoryCard() {
        List<DesktopUiToolHost.ToolHistoryEntry> entries = model.host.toolHistory();
        if (entries.isEmpty()) {
            return group(
                    "tools.history",
                    "gui.tools.history.title",
                    text("tools.history.empty", "gui.tools.history.empty", TextStyle.CAPTION)
            );
        }
        List<DesktopUiNode.TableRow> rows = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            DesktopUiToolHost.ToolHistoryEntry entry = entries.get(index);
            rows.add(new DesktopUiNode.TableRow(
                    "history." + entry.finishedAtEpochMs() + "." + index,
                    List.of(
                            model.host.message("gui.tools.history.tool." + machineKey(entry.toolId().name())),
                            model.host.message("gui.tools.history.outcome." + machineKey(entry.outcome().name())),
                            formatTimestamp(Instant.ofEpochMilli(entry.startedAtEpochMs())),
                            formatTimestamp(Instant.ofEpochMilli(entry.finishedAtEpochMs())),
                            countCell(entry.processedCount()),
                            countCell(entry.changedCount()),
                            countCell(entry.failedCount()),
                            entry.logFileName() == null ? "—" : entry.logFileName()
                    )
            ));
        }
        return group(
                "tools.history",
                "gui.tools.history.title",
                new DesktopUiNode.Table(
                        "tools.history.table",
                        "tools.history.selection",
                        List.of(
                                new DesktopUiNode.TableColumn(
                                        "tool",
                                        key("gui.tools.history.column.tool"),
                                        160
                                ),
                                new DesktopUiNode.TableColumn(
                                        "outcome",
                                        key("gui.tools.history.column.outcome"),
                                        90
                                ),
                                new DesktopUiNode.TableColumn(
                                        "started",
                                        key("gui.tools.history.column.started"),
                                        150
                                ),
                                new DesktopUiNode.TableColumn(
                                        "finished",
                                        key("gui.tools.history.column.finished"),
                                        150
                                ),
                                new DesktopUiNode.TableColumn(
                                        "processed",
                                        key("gui.tools.history.column.processed"),
                                        75
                                ),
                                new DesktopUiNode.TableColumn(
                                        "changed",
                                        key("gui.tools.history.column.changed"),
                                        75
                                ),
                                new DesktopUiNode.TableColumn(
                                        "failed",
                                        key("gui.tools.history.column.failed"),
                                        75
                                ),
                                new DesktopUiNode.TableColumn(
                                        "log",
                                        key("gui.tools.history.column.log"),
                                        210
                                )
                        ),
                        rows,
                        SelectionMode.SINGLE,
                        List.of(),
                        false
                )
        );
    }

    private static String machineKey(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String countCell(Integer value) {
        return value == null ? "—" : Integer.toString(value);
    }

}
