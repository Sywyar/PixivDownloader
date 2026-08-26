package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.SelectionMode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/**
 * 工具中心的页面、状态、动作与独占后台生命周期。
 */
final class DesktopToolsController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopToolsController.class);

    final ComposeDesktopUiModel owner;
    final DesktopUiHost host;
    final String rootFolder;
    final Map<String, String> formValues;
    final DesktopImageClassifierSupport classifierSupport;
    private final DesktopToolsView view;

    volatile String classifierNotice = "";
    volatile String folderNotice = "";
    volatile String backfillNotice = "";
    volatile String migrationNotice = "";
    volatile List<DesktopUiNode.TableRow> folderRows = List.of();
    volatile Map<String, DesktopUiHost.FolderArtwork> folderArtworks = Map.of();
    volatile List<Path> classifierFolders = List.of();
    volatile List<Path> classifierImages = List.of();
    volatile DesktopUiHost.ImageClassifierServer classifierServer = new DesktopUiHost.ImageClassifierServer(
            false,
            ""
    );
    volatile DesktopUiHost.ImageClassifierArtwork classifierArtwork;
    volatile int classifierGroupIndex;
    volatile String selectedFolderRow;
    volatile ToolDialog toolDialog;
    private volatile boolean folderCheckerRestartBackend;
    private volatile long classifierToolStartedAt;
    volatile String exclusiveToolName = "";
    private volatile long exclusiveToolStartedAt;

    DesktopToolsController(
            ComposeDesktopUiModel owner,
            DesktopUiHost host,
            String rootFolder,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.rootFolder = rootFolder;
        this.formValues = formValues;
        this.classifierSupport = new DesktopImageClassifierSupport(host, rootFolder);
        this.view = new DesktopToolsView(this);
        loadDefaults();
    }

    void selectFolder(String value) {
        selectedFolderRow = value.isBlank() ? null : value;
    }

    Optional<DesktopUiDocument.Dialog> dialog(Map<String, Runnable> nextActions) {
        return toolDialog == null ? Optional.empty() : Optional.of(view.dialog(
                toolDialog,
                nextActions
        ));
    }

    DesktopUiNode controlCenterPage(Map<String, Runnable> nextActions) {
        return view.controlCenterPage(nextActions);
    }

    String exclusiveToolName() {
        return exclusiveToolName;
    }

    long exclusiveToolStartedAt() {
        return exclusiveToolStartedAt;
    }

    void openToolDialog(ToolDialog value) {
        if (owner.busy()) return;
        toolDialog = value;
        if (value == ToolDialog.IMAGE_CLASSIFIER)
            classifierToolStartedAt = System.currentTimeMillis();
        owner.rebuild();
        if (value == ToolDialog.IMAGE_CLASSIFIER && !form(
                "classifier.default-folder",
                ""
        ).isBlank()) scanClassifierFolders();
    }

    void openFolderCheckerDialog() {
        if (owner.busy() || toolDialog != null) return;
        if (owner.backendSnapshot().state() != DesktopUiHost.BackendState.RUNNING && owner.backendSnapshot().state() != DesktopUiHost.BackendState.STOPPED) {
            owner.showDialog(
                    "folder.backend-busy",
                    "gui.dialog.please-wait.title",
                    "gui.message.backend-busy",
                    DesktopUiDocument.DialogStyle.WARNING
            );
            owner.rebuild();
            return;
        }
        folderCheckerRestartBackend = owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING;
        exclusiveToolName = host.message("gui.tools.card.folder-checker.title");
        exclusiveToolStartedAt = System.currentTimeMillis();
        if (!folderCheckerRestartBackend) {
            toolDialog = ToolDialog.FOLDER_CHECKER;
            owner.rebuild();
            return;
        }
        owner.setBusy(true);
        owner.rebuild();
        if (!host.stopBackend(() -> {
            owner.setBusy(false);
            toolDialog = ToolDialog.FOLDER_CHECKER;
            folderNotice = host.message("gui.tools.folder-checker.status.opened");
            owner.rebuild();
        })) {
            owner.setBusy(false);
            exclusiveToolName = "";
            exclusiveToolStartedAt = 0L;
            folderCheckerRestartBackend = false;
            owner.showDialog(
                    "folder.backend-busy",
                    "gui.dialog.please-wait.title",
                    "gui.message.backend-busy",
                    DesktopUiDocument.DialogStyle.WARNING
            );
            owner.rebuild();
        }
    }

    void closeFolderCheckerDialog() {
        long startedAt = exclusiveToolStartedAt;
        boolean restart = folderCheckerRestartBackend;
        folderCheckerRestartBackend = false;
        exclusiveToolName = "";
        exclusiveToolStartedAt = 0L;
        if (startedAt > 0L) {
            host.recordToolHistory(
                    DesktopUiToolHost.ToolId.FOLDER_CHECKER,
                    DesktopUiToolHost.ToolOutcome.CLOSED,
                    startedAt,
                    null,
                    null,
                    null,
                    null
            );
        }
        if (!restart) {
            owner.rebuild();
            return;
        }
        owner.setBusy(true);
        folderNotice = host.message("gui.tools.folder-checker.status.restoring");
        owner.rebuild();
        if (!host.startBackend(() -> {
            owner.setBusy(false);
            folderNotice = host.message("gui.tools.folder-checker.status.completed");
            owner.rebuild();
        })) {
            owner.setBusy(false);
            folderNotice = host.message("gui.tools.folder-checker.status.closed");
            owner.rebuild();
        }
    }

    void closeImageClassifierDialog() {
        long startedAt = classifierToolStartedAt;
        classifierToolStartedAt = 0L;
        if (startedAt > 0L) {
            host.recordToolHistory(
                    DesktopUiToolHost.ToolId.IMAGE_CLASSIFIER,
                    DesktopUiToolHost.ToolOutcome.CLOSED,
                    startedAt,
                    null,
                    null,
                    null,
                    null
            );
        }
        owner.rebuild();
    }

    void openToolLog(String name) {
        owner.runBusy(() -> {
            try (DesktopUiHost.ToolLogSession log = host.openToolLog(name)) {
                log.openLatestInBrowser();
            } catch (Exception failure) {
                LOG.warn("Unable to open desktop tool log {}", name, failure);
                owner.showDialog(
                        "tools.log.error",
                        "gui.dialog.error.title",
                        "desktop.ui.tools.log-open-failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            }
        });
    }

    void runBackfill() {
        String db = form("tools.backfill.db", "").trim();
        if (db.isBlank()) {
            backfillNotice = host.message("gui.tools.validation.database-path.required");
            owner.rebuild();
            return;
        }
        DesktopUiHost.BackfillOptions options = new DesktopUiHost.BackfillOptions(
                db,
                form("tools.backfill.proxy-host", host.defaultProxyHost()),
                intForm("tools.backfill.proxy-port", host.defaultProxyPort()),
                boolForm("tools.backfill.proxy", true),
                intForm("tools.backfill.delay", 1000),
                intForm("tools.backfill.limit", 0),
                boolForm("tools.backfill.dry", false)
        );
        runExclusiveTool(
                host.message("gui.tools.card.backfill.title"),
                () -> {
                    int count = host.countBackfillCandidates(options);
                    backfillNotice = count == 0 ? host.message(
                            "gui.tools.backfill.status.none-found") : host.message(
                                    "gui.tools.backfill.status.pending-found",
                                    count
                            );
                    owner.rebuild();
                    try (DesktopUiHost.ToolLogSession log = host.openToolLog("artworks-backfill")) {
                        log.openLatestInBrowser();
                        DesktopUiHost.BackfillSummary summary = host.runBackfill(options);
                        String result = host.message(summary.rateLimited() ? "gui.tools.backfill.result.rate-limited" : "gui.tools.backfill.result.completed");
                        backfillNotice = result;
                        owner.showDialog(
                                "tools.backfill.completed",
                                "gui.tools.dialog.backfill.completed.title",
                                appToken(
                                        "gui.tools.dialog.backfill.completed.message",
                                        result,
                                        summary.processed(),
                                        summary.totalCandidates()
                                ),
                                DesktopUiDocument.DialogStyle.SUCCESS
                        );
                        return new ToolCompletion(
                                summary.processed(),
                                null,
                                null,
                                log.sessionPath()
                        );
                    }
                },
                DesktopUiToolHost.ToolId.ARTWORKS_BACKFILL,
                false
        );
    }

    void runMigration() {
        String db = form("tools.migration.db", "").trim();
        String root = form("tools.migration.root", "").trim();
        if (db.isBlank() || root.isBlank()) {
            migrationNotice = host.message(db.isBlank() ? "gui.tools.validation.database-path.required" : "gui.tools.validation.root-folder.required");
            owner.rebuild();
            return;
        }
        DesktopUiHost.MigrationOptions options = new DesktopUiHost.MigrationOptions(
                db,
                root
        );
        runExclusiveTool(
                host.message("gui.tools.card.migration.title"),
                () -> {
                    int count = host.countMigrationCandidates(options);
                    migrationNotice = count == 0 ? host.message(
                            "gui.tools.migration.status.none-found") : host.message(
                                    "gui.tools.migration.status.pending-found",
                                    count
                            );
                    owner.rebuild();
                    try (DesktopUiHost.ToolLogSession log = host.openToolLog(
                            "json-to-sqlite-migration")) {
                        log.openLatestInBrowser();
                        DesktopUiHost.MigrationSummary summary = host.runMigration(
                                options,
                                ignored -> {
                                }
                        );
                        if (summary.historyFileMissing()) {
                            migrationNotice = host.message(
                                    "gui.tools.migration.status.history-missing");
                        } else {
                            String result = host.message("gui.tools.migration.result.completed");
                            migrationNotice = result;
                            owner.showDialog(
                                    "tools.migration.completed",
                                    "gui.tools.dialog.migration.completed.title",
                                    appToken(
                                            "gui.tools.dialog.migration.completed.message",
                                            result,
                                            summary.migrated(),
                                            summary.skipped(),
                                            summary.totalCandidates()
                                    ),
                                    DesktopUiDocument.DialogStyle.SUCCESS
                            );
                        }
                        return new ToolCompletion(
                                summary.totalCandidates(),
                                summary.migrated(),
                                null,
                                log.sessionPath()
                        );
                    }
                },
                DesktopUiToolHost.ToolId.JSON_TO_SQLITE_MIGRATION,
                true
        );
    }

    void checkFolders() {
        String db = form("tools.folder.db", "").trim();
        if (db.isBlank()) {
            folderNotice = host.message("gui.tools.validation.database-path.required");
            owner.rebuild();
            return;
        }
        runFolderAction(() -> {
            DesktopUiHost.FolderCheckResult result = host.checkArtworkFolders(Path.of(db));
            List<DesktopUiNode.TableRow> rows = new ArrayList<>();
            Map<String, DesktopUiHost.FolderArtwork> artworks = new LinkedHashMap<>();
            for (DesktopUiHost.FolderArtwork artwork : result.inaccessible()) {
                String id = "artwork." + artwork.artworkId();
                artworks.put(id, artwork);
                rows.add(new DesktopUiNode.TableRow(
                        id,
                        List.of(
                                Long.toString(artwork.artworkId()),
                                nullToEmpty(artwork.title()),
                                host.message(artwork.moved() ? "gui.folder-checker.path-type.moved" : "gui.folder-checker.path-type.original"),
                                artwork.path() == null ? host.message("gui.folder-checker.value.null-path") : artwork.path(),
                                host.message("gui.folder-checker.status.not-found")
                        )
                ));
            }
            folderRows = List.copyOf(rows);
            folderArtworks = Map.copyOf(artworks);
            selectedFolderRow = null;
            formValues.remove("tools.folder.new-path");
            folderNotice = host.message(
                    rows.isEmpty() ? "gui.folder-checker.status.all-accessible" : "gui.folder-checker.status.inaccessible-count",
                    rows.isEmpty() ? new Object[]{result.total()} : new Object[]{rows.size(), result.total()}
            );
        });
    }

    DesktopUiNode folderTable() {
        return new DesktopUiNode.Table(
                "tools.folder.table",
                "folder.selected",
                List.of(
                        new DesktopUiNode.TableColumn(
                                "id",
                                key("gui.folder-checker.column.artwork-id"),
                                90
                        ),
                        new DesktopUiNode.TableColumn(
                                "title",
                                key("gui.folder-checker.column.title"),
                                180
                        ),
                        new DesktopUiNode.TableColumn(
                                "type",
                                key("gui.folder-checker.column.path-type"),
                                100
                        ),
                        new DesktopUiNode.TableColumn(
                                "path",
                                key("gui.folder-checker.column.path"),
                                340
                        ),
                        new DesktopUiNode.TableColumn(
                                "status",
                                key("gui.folder-checker.column.status"),
                                90
                        )
                ),
                folderRows,
                SelectionMode.SINGLE,
                selectedFolderRow == null ? List.of() : List.of(selectedFolderRow),
                !owner.busy()
        );
    }

    void requestFolderUpdate() {
        DesktopUiHost.FolderArtwork artwork = folderArtworks.get(selectedFolderRow);
        String newPath = form("tools.folder.new-path", "").trim();
        if (artwork == null) {
            folderNotice = host.message("gui.folder-checker.error.row-required");
            owner.rebuild();
            return;
        }
        if (newPath.isBlank()) {
            folderNotice = host.message("gui.folder-checker.error.new-path.required");
            owner.rebuild();
            return;
        }
        boolean directory;
        try {
            directory = host.isImageClassifierDirectory(Path.of(newPath));
        } catch (RuntimeException invalid) {
            directory = false;
        }
        if (!directory) {
            owner.showDialog(
                    "folder.path-not-found",
                    "gui.folder-checker.dialog.path-not-found.title",
                    DesktopUiDocument.DialogStyle.QUESTION,
                    (nextActions, dismissAction, dismiss) -> column(
                            "folder.path-not-found.content",
                            new DesktopUiNode.Text(
                                    "folder.path-not-found.message",
                                    appToken(
                                            "gui.folder-checker.dialog.path-not-found.message",
                                            newPath
                                    ),
                                    TextStyle.BODY,
                                    true,
                                    true
                            ),
                            row(
                                    "folder.path-not-found.actions",
                                    button(
                                            "folder.path-not-found.yes",
                                            "folder.path-not-found.yes",
                                            "desktop.ui.action.yes",
                                            true,
                                            nextActions,
                                            () -> applyFolderUpdate(artwork, newPath)
                                    ),
                                    button(
                                            "folder.path-not-found.no",
                                            dismissAction,
                                            "desktop.ui.action.cancel",
                                            true,
                                            nextActions,
                                            dismiss
                                    )
                            )
                    ),
                    560,
                    0
            );
            return;
        }
        applyFolderUpdate(artwork, newPath);
    }

    private void applyFolderUpdate(
            DesktopUiHost.FolderArtwork artwork,
            String newPath
    ) {
        owner.closeDialog();
        runFolderAction(() -> {
            host.updateArtworkFolder(
                    Path.of(form("tools.folder.db", "")),
                    artwork.artworkId(),
                    artwork.moved(),
                    newPath
            );
            folderNotice = host.message(
                    "gui.folder-checker.dialog.update-success.message",
                    artwork.artworkId(),
                    host.message(artwork.moved() ? "gui.folder-checker.column-name.move-folder" : "gui.folder-checker.column-name.folder"),
                    newPath
            );
            checkFoldersDirect();
        });
    }

    void copySelectedFolderId() {
        DesktopUiHost.FolderArtwork artwork = folderArtworks.get(selectedFolderRow);
        if (artwork == null) return;
        try {
            host.copyText(Long.toString(artwork.artworkId()));
            owner.showDialog(
                    "folder.copied",
                    "gui.folder-checker.dialog.copied.title",
                    appToken("gui.folder-checker.dialog.copied.message", artwork.artworkId()),
                    DesktopUiDocument.DialogStyle.SUCCESS
            );
            owner.rebuild();
        } catch (Exception failure) {
            LOG.warn("Unable to copy artwork id", failure);
            folderNotice = host.message("desktop.ui.action.failed");
            owner.rebuild();
        }
    }

    private void runFolderAction(ThrowingRunnable action) {
        if (toolDialog != ToolDialog.FOLDER_CHECKER) {
            runExclusiveTool(
                    host.message("gui.tools.card.folder-checker.title"),
                    () -> {
                        action.run();
                        return ToolCompletion.EMPTY;
                    },
                    DesktopUiToolHost.ToolId.FOLDER_CHECKER,
                    true
            );
            return;
        }
        if (owner.busy()) return;
        owner.setBusy(true);
        owner.rebuild();
        owner.executeAsync(() -> {
            try {
                action.run();
            } catch (Exception failure) {
                LOG.error("Folder checker action failed", failure);
                folderNotice = host.message("desktop.ui.action.failed");
            } finally {
                owner.setBusy(false);
                owner.rebuild();
            }
        });
    }

    private void checkFoldersDirect() throws Exception {
        DesktopUiHost.FolderCheckResult result = host.checkArtworkFolders(Path.of(form(
                "tools.folder.db",
                ""
        )));
        folderRows = result.inaccessible().stream().map(artwork -> new DesktopUiNode.TableRow(
                "artwork." + artwork.artworkId(),
                List.of(
                        Long.toString(artwork.artworkId()),
                        nullToEmpty(artwork.title()),
                        host.message(artwork.moved() ? "gui.folder-checker.path-type.moved" : "gui.folder-checker.path-type.original"),
                        artwork.path() == null ? host.message("gui.folder-checker.value.null-path") : artwork.path(),
                        host.message("gui.folder-checker.status.not-found")
                )
        )).toList();
        folderArtworks = result.inaccessible().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                artwork -> "artwork." + artwork.artworkId(),
                Function.identity()
        ));
        selectedFolderRow = null;
    }

    void showClassifierSettings() {
        owner.showDialog(
                "classifier.settings",
                "gui.image-classifier.button.settings",
                DesktopUiDocument.DialogStyle.INFO,
                (nextActions, dismissAction, dismiss) -> column(
                        "classifier.settings.content",
                        input(
                                "classifier.settings.folder",
                                "classifier.default-folder",
                                "gui.image-classifier.label.default-folder",
                                null,
                                InputKind.DIRECTORY,
                                form("classifier.default-folder", ""),
                                !owner.busy()
                        ),
                        input(
                                "classifier.settings.server",
                                "classifier.server-url",
                                "gui.image-classifier.label.server-url",
                                null,
                                InputKind.TEXT,
                                form("classifier.server-url", "http://localhost:6999"),
                                !owner.busy()
                        ),
                        toggle(
                                "classifier.settings.skip",
                                "classifier.show-skip",
                                "gui.image-classifier.label.show-skip-button",
                                boolForm("classifier.show-skip", true),
                                !owner.busy()
                        ),
                        input(
                                "classifier.settings.targets",
                                "classifier.targets",
                                "desktop.ui.classifier.targets",
                                null,
                                InputKind.MULTILINE,
                                form("classifier.targets", ""),
                                !owner.busy()
                        ),
                        row(
                                "classifier.settings.actions",
                                button(
                                        "classifier.settings.save",
                                        "classifier.settings.save",
                                        "gui.button.save",
                                        !owner.busy(),
                                        nextActions,
                                        () -> {
                                            owner.closeDialog();
                                            saveClassifierSettings();
                                        }
                                ),
                                button(
                                        "classifier.settings.cancel",
                                        dismissAction,
                                        "desktop.ui.action.cancel",
                                        !owner.busy(),
                                        nextActions,
                                        dismiss
                                )
                        )
                ),
                660,
                560
        );
    }

    private void openClassifierImage(Path image) {
        owner.runBusy(() -> {
            try {
                host.openLocalPath(image);
            } catch (Exception failure) {
                LOG.warn("Unable to open classifier image {}", image, failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    void showClassifierImage(int index) {
        if (index < 0 || index >= classifierImages.size()) return;
        Path image = classifierImages.get(index);
        owner.showDialog(
                "classifier.viewer",
                "gui.image-classifier.dialog.image-viewer.title",
                DesktopUiDocument.DialogStyle.INFO,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> content = new ArrayList<>();
                    classifierSupport.materializeImage(image).ifPresentOrElse(
                            data -> content.add(new DesktopUiNode.Image(
                                    "classifier.viewer.image",
                                    data,
                                    TextToken.raw(image.getFileName().toString()),
                                    980,
                                    700,
                                    DesktopUiNode.ScaleMode.FIT
                            )),
                            () -> content.add(text(
                                    "classifier.viewer.failed",
                                    "gui.image-classifier.thumbnail.viewer-load-failed-generic",
                                    TextStyle.ERROR
                            ))
                    );
                    content.add(raw(
                            "classifier.viewer.page",
                            host.message(
                                    "gui.image-classifier.dialog.image-viewer.page-label",
                                    index + 1,
                                    classifierImages.size()
                            ),
                            TextStyle.CAPTION
                    ));
                    content.add(raw(
                            "classifier.viewer.name",
                            image.getFileName().toString(),
                            TextStyle.CODE
                    ));
                    content.add(row(
                            "classifier.viewer.actions",
                            button(
                                    "classifier.viewer.previous",
                                    "classifier.viewer.previous",
                                    "gui.image-classifier.button.prev-image",
                                    index > 0,
                                    nextActions,
                                    () -> showClassifierImage(index - 1)
                            ),
                            button(
                                    "classifier.viewer.next",
                                    "classifier.viewer.next",
                                    "gui.image-classifier.button.next-image",
                                    index + 1 < classifierImages.size(),
                                    nextActions,
                                    () -> showClassifierImage(index + 1)
                            ),
                            button(
                                    "classifier.viewer.external",
                                    "classifier.viewer.external",
                                    "desktop.ui.action.open",
                                    true,
                                    nextActions,
                                    () -> openClassifierImage(image)
                            ),
                            button(
                                    "classifier.viewer.close",
                                    dismissAction,
                                    "desktop.ui.action.close",
                                    true,
                                    nextActions,
                                    dismiss
                            )
                    ));
                    return column("classifier.viewer.content", content);
                },
                1100,
                860
        );
    }

    int classifierFolderIndex() {
        Path selected = classifierSupport.path(form("classifier.source", ""));
        return selected == null ? -1 : classifierFolders.indexOf(selected);
    }

    void moveClassifierGroup(int offset) {
        int groups = classifierImages.isEmpty() ? 0 : (classifierImages.size() + 9) / 10;
        classifierGroupIndex = Math.max(
                0,
                Math.min(Math.max(0, groups - 1), classifierGroupIndex + offset)
        );
        owner.rebuild();
    }

    void moveClassifierFolder(int offset) {
        int current = classifierFolderIndex();
        int next = current + offset;
        if (current < 0 || next < 0) return;
        if (offset > 0) {
            try {
                host.deleteImageClassifierFolderIfEmpty(classifierFolders.get(current));
            } catch (Exception ignored) {
            }
        }
        if (next >= classifierFolders.size()) {
            formValues.remove("classifier.source");
            classifierImages = List.of();
            classifierArtwork = null;
            classifierNotice = host.message(
                    "gui.image-classifier.dialog.all-folders-complete.message");
            owner.showDialog(
                    "classifier.complete",
                    "gui.image-classifier.dialog.all-folders-complete.title",
                    "gui.image-classifier.dialog.all-folders-complete.message",
                    DesktopUiDocument.DialogStyle.SUCCESS
            );
            owner.rebuild();
            return;
        }
        formValues.put(
                "classifier.source",
                classifierSupport.id(classifierFolders.get(next))
        );
        refreshClassifierFolder();
    }

    void refreshClassifierFolder() {
        if (owner.busy() || classifierSupport.path(form(
                "classifier.source",
                ""
        )) == null) return;
        owner.runBusy(() -> {
            try {
                refreshClassifierSelection();
            } catch (Exception failure) {
                LOG.warn("Unable to refresh image classifier folder", failure);
                classifierNotice = safeMessage(failure);
            }
        });
    }

    private void refreshClassifierSelection() throws Exception {
        Path selected = classifierSupport.path(form("classifier.source", ""));
        if (selected == null) return;
        classifierImages = List.copyOf(host.listImageClassifierImages(selected));
        classifierSupport.clearCache();
        classifierGroupIndex = 0;
        classifierServer = host.checkImageClassifierServer(form(
                "classifier.server-url",
                "http://localhost:6999"
        ));
        classifierArtwork = host.resolveImageClassifierArtwork(
                selected,
                classifierServer
        ).orElse(
                null);
        classifierNotice = "";
    }

    private void saveClassifierSettings() {
        owner.runBusy(() -> {
            try {
                host.saveImageClassifierSettings(
                        rootFolder,
                        new DesktopUiHost.ImageClassifierSettings(
                                form("classifier.default-folder", ""),
                                boolForm("classifier.show-skip", false),
                                form("classifier.server-url", "http://localhost:6999"),
                                classifierSupport.parseTargets(form("classifier.targets", ""))
                        )
                );
                classifierNotice = host.message("gui.image-classifier.dialog.settings-saved.message");
                classifierServer = host.checkImageClassifierServer(form(
                        "classifier.server-url",
                        "http://localhost:6999"
                ));
            } catch (Exception failure) {
                LOG.warn("Unable to save image classifier settings", failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    void scanClassifierFolders() {
        String parent = form("classifier.default-folder", "").trim();
        if (parent.isBlank()) {
            classifierNotice = host.message("gui.image-classifier.validation.folder-path.required");
            owner.rebuild();
            return;
        }
        owner.runBusy(() -> {
            try {
                Path directory = Path.of(parent);
                if (!host.isImageClassifierDirectory(directory)) {
                    classifierNotice = host.message(
                            "gui.image-classifier.validation.folder-path.invalid");
                    return;
                }
                List<Path> folders = host.listImageClassifierFolders(directory);
                if (folders.isEmpty()) {
                    classifierNotice = host.message("gui.image-classifier.validation.no-subfolders");
                    return;
                }
                Map<String, Path> paths = new LinkedHashMap<>();
                for (int index = 0; index < folders.size(); index++)
                    paths.put("folder." + index, folders.get(index));
                classifierFolders = List.copyOf(folders);
                classifierSupport.setPaths(paths);
                formValues.put("classifier.source", "folder.0");
                refreshClassifierSelection();
            } catch (Exception failure) {
                LOG.warn("Unable to scan image classifier folders", failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    void classifyFolder() {
        Path source = classifierSupport.path(form("classifier.source", ""));
        int targetIndex = parseInt(
                form("classifier.target", "").replace("target.", ""),
                -1
        );
        List<DesktopUiHost.ImageClassifierTarget> targets = classifierSupport.parseTargets(form(
                "classifier.targets",
                ""
        ));
        if (source == null || targetIndex < 0 || targetIndex >= targets.size()) return;
        owner.runBusy(() -> {
            try {
                DesktopUiHost.ImageClassifierServer server = host.checkImageClassifierServer(form(
                        "classifier.server-url",
                        "http://localhost:6999"
                ));
                if (!server.available()) {
                    classifierNotice = host.message("gui.image-classifier.server.connect-failed");
                    return;
                }
                DesktopUiHost.ImageClassifierArtwork artwork = host.resolveImageClassifierArtwork(
                        source,
                        server
                ).orElseThrow(() -> new IllegalStateException("artwork metadata unavailable"));
                List<Path> images = List.copyOf(classifierImages);
                if (images.isEmpty()) {
                    classifierNotice = host.message(
                            "gui.image-classifier.dialog.no-images-to-classify.message");
                    return;
                }
                Path destination = Path.of(targets.get(targetIndex).folder());
                host.classifyImageFolder(
                        source,
                        images,
                        artwork.artworkId(),
                        destination,
                        server,
                        (detail, remaining) -> false
                );
                host.deleteImageClassifierFolderIfEmpty(source);
                classifierFolders = classifierFolders.stream().filter(path -> !path.equals(source)).toList();
                Map<String, Path> paths = new LinkedHashMap<>();
                for (int index = 0; index < classifierFolders.size(); index++) {
                    paths.put("folder." + index, classifierFolders.get(index));
                }
                classifierSupport.setPaths(paths);
                classifierSupport.clearCache();
                classifierArtwork = null;
                classifierImages = List.of();
                if (classifierFolders.isEmpty()) {
                    formValues.remove("classifier.source");
                    classifierNotice = host.message(
                            "gui.image-classifier.dialog.all-folders-complete.message");
                    owner.showDialog(
                            "classifier.complete",
                            "gui.image-classifier.dialog.all-folders-complete.title",
                            "gui.image-classifier.dialog.all-folders-complete.message",
                            DesktopUiDocument.DialogStyle.SUCCESS
                    );
                } else {
                    formValues.put("classifier.source", "folder.0");
                    refreshClassifierSelection();
                    classifierNotice = host.message(
                            "desktop.ui.classifier.completed",
                            artwork.artworkId()
                    );
                }
            } catch (Exception failure) {
                LOG.warn("Unable to classify image folder {}", source, failure);
                classifierNotice = host.message("desktop.ui.action.failed");
            }
        });
    }

    private void runExclusiveTool(
            String toolName,
            ToolOperation operation,
            DesktopUiToolHost.ToolId toolId,
            boolean stopBackend
    ) {
        if (owner.busy()) return;
        DesktopUiHost.BackendState backendState = owner.backendSnapshot().state();
        if (stopBackend
                ? backendState != DesktopUiHost.BackendState.RUNNING
                        && backendState != DesktopUiHost.BackendState.STOPPED
                : backendState != DesktopUiHost.BackendState.RUNNING) {
            owner.showDialog(
                    "tools.backend-busy",
                    "gui.dialog.error.title",
                    "gui.message.backend-busy",
                    DesktopUiDocument.DialogStyle.WARNING
            );
            owner.rebuild();
            return;
        }
        exclusiveToolName = toolName;
        long startedAt = System.currentTimeMillis();
        exclusiveToolStartedAt = startedAt;
        owner.setBusy(true);
        owner.rebuild();
        owner.executeAsync(() -> {
            boolean restart = stopBackend
                    && owner.backendSnapshot().state() == DesktopUiHost.BackendState.RUNNING;
            try {
                if (restart) {
                    java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(
                            1);
                    if (!host.stopBackend(stopped::countDown) || !stopped.await(
                            Duration.ofSeconds(
                                    30).toMillis(),
                            java.util.concurrent.TimeUnit.MILLISECONDS
                    )) {
                        throw new IllegalStateException(host.message("gui.message.backend-busy"));
                    }
                }
                ToolCompletion completion = operation.run();
                if (completion == null) completion = ToolCompletion.EMPTY;
                host.recordToolHistory(
                        toolId,
                        DesktopUiToolHost.ToolOutcome.SUCCEEDED,
                        startedAt,
                        completion.processedCount(),
                        completion.changedCount(),
                        completion.failedCount(),
                        completion.logPath()
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                host.recordToolHistory(
                        toolId,
                        DesktopUiToolHost.ToolOutcome.CANCELLED,
                        startedAt,
                        null,
                        null,
                        null,
                        null
                );
                LOG.warn("Desktop tool was interrupted", interrupted);
                owner.showDialog(
                        "tools.interrupted",
                        "gui.dialog.error.title",
                        "desktop.ui.tools.operation-failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            } catch (Exception failure) {
                host.recordToolHistory(
                        toolId,
                        DesktopUiToolHost.ToolOutcome.FAILED,
                        startedAt,
                        null,
                        null,
                        null,
                        null
                );
                LOG.error("Desktop tool failed", failure);
                owner.showDialog(
                        "tools.failed",
                        "gui.dialog.error.title",
                        "desktop.ui.tools.operation-failed",
                        DesktopUiDocument.DialogStyle.ERROR
                );
            } finally {
                if (restart) host.startBackend(() -> {
                });
                exclusiveToolName = "";
                exclusiveToolStartedAt = 0L;
                owner.setBusy(false);
                owner.rebuild();
            }
        });
    }

    private void loadDefaults() {
        String databasePath = host.resolveDatabasePath(rootFolder).toString();
        formValues.putIfAbsent("tools.folder.db", databasePath);
        formValues.putIfAbsent("tools.migration.db", databasePath);
        formValues.putIfAbsent("tools.migration.root", rootFolder);
        try {
            DesktopUiHost.BackfillOptions defaults = host.defaultBackfillOptions();
            formValues.putIfAbsent("tools.backfill.db", defaults.dbPath());
            formValues.putIfAbsent("tools.backfill.proxy-host", defaults.proxyHost());
            formValues.putIfAbsent(
                    "tools.backfill.proxy-port",
                    Integer.toString(defaults.proxyPort())
            );
            formValues.putIfAbsent(
                    "tools.backfill.proxy",
                    Boolean.toString(defaults.useProxy())
            );
            formValues.putIfAbsent(
                    "tools.backfill.delay",
                    Long.toString(defaults.delayMs())
            );
            formValues.putIfAbsent(
                    "tools.backfill.limit",
                    Integer.toString(defaults.limit())
            );
            formValues.putIfAbsent(
                    "tools.backfill.dry",
                    Boolean.toString(defaults.dryRun())
            );
        } catch (RuntimeException ignored) {
            // 保持页面默认值可用。
        }
        DesktopUiHost.ImageClassifierSettings settings = classifierSupport.loadSettings();
        formValues.putIfAbsent("classifier.default-folder", settings.defaultFolder());
        formValues.putIfAbsent("classifier.server-url", settings.serverUrl());
        formValues.putIfAbsent(
                "classifier.show-skip",
                Boolean.toString(settings.showSkipButton())
        );
        formValues.putIfAbsent(
                "classifier.targets",
                classifierSupport.encodeTargets(settings.targets())
        );
    }

    List<Path> classifierFolders() {
        return classifierFolders;
    }

    String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }

    boolean boolForm(String key, boolean fallback) {
        return Boolean.parseBoolean(form(key, Boolean.toString(fallback)));
    }

    int intForm(String key, int fallback) {
        return parseInt(form(key, null), fallback);
    }

    enum ToolDialog {
        IMAGE_CLASSIFIER, FOLDER_CHECKER
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ToolOperation {
        ToolCompletion run() throws Exception;
    }

    private record ToolCompletion(
            Integer processedCount,
            Integer changedCount,
            Integer failedCount,
            Path logPath
    ) {
        private static final ToolCompletion EMPTY = new ToolCompletion(
                null,
                null,
                null,
                null
        );
    }
}
