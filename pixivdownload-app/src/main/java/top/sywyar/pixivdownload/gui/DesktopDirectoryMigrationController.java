package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.InputKind;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode.TextToken;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static top.sywyar.pixivdownload.gui.DesktopUiNodes.*;

/**
 * 目录前缀迁移与符号目录修复流程。
 */
final class DesktopDirectoryMigrationController {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopDirectoryMigrationController.class);

    private final AppDesktopUiModel owner;
    private final DesktopUiHost host;
    private final Map<String, String> formValues;

    private volatile String appRoot = "";
    private volatile List<PathPrefixRow> prefixes = List.of();

    DesktopDirectoryMigrationController(
            AppDesktopUiModel owner,
            DesktopUiHost host,
            Map<String, String> formValues
    ) {
        this.owner = owner;
        this.host = host;
        this.formValues = formValues;
    }

    void open() {
        owner.runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiGet("path-prefixes", 10_000);
            if (!response.is2xx() || response.body() == null) {
                owner.showDialog(
                        "migrate.unreachable",
                        "gui.dialog.error.title",
                        "gui.migrate-dir.error.unreachable",
                        DesktopUiDocument.DialogStyle.ERROR
                );
                return;
            }
            DesktopUiHost.GuiValue body = response.body();
            List<PathPrefixRow> rows = new ArrayList<>();
            for (DesktopUiHost.GuiValue prefix : body.path("prefixes")) {
                rows.add(new PathPrefixRow(
                        prefix.path("id").asLong(),
                        prefix.path("path").asText(""),
                        prefix.path("downloadRoot").asBoolean(false),
                        prefix.path("symbolic").asBoolean(false)
                ));
            }
            if (rows.isEmpty()) {
                owner.showDialog(
                        "migrate.empty",
                        "gui.dialog.info.title",
                        "gui.migrate-dir.empty",
                        DesktopUiDocument.DialogStyle.INFO
                );
                return;
            }
            formValues.keySet().removeIf(key -> key.startsWith("migrate.prefix."));
            rows.forEach(row -> formValues.put(binding(row), ""));
            appRoot = body.path("appRoot").asText("");
            prefixes = List.copyOf(rows);
            showDialog();
        });
    }

    private void showDialog() {
        owner.showDialog(
                "migrate.directory",
                "gui.migrate-dir.title",
                DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> rows = new ArrayList<>();
                    rows.add(new DesktopUiNode.Surface(
                            "migrate.warning",
                            DesktopUiNode.SurfaceStyle.WARNING,
                            DesktopUiNode.Insets.all(10),
                            true,
                            text(
                                    "migrate.warning.text",
                                    "gui.migrate-dir.warning",
                                    TextStyle.BODY
                            )
                    ));
                    for (PathPrefixRow prefix : prefixes) {
                        String base = "migrate.prefix." + prefix.id();
                        List<DesktopUiNode> card = new ArrayList<>();
                        if (prefix.downloadRoot()) {
                            card.add(text(
                                    base + ".root",
                                    prefix.symbolic() ? "gui.migrate-dir.symbolic-chip" : "gui.migrate-dir.root-chip",
                                    TextStyle.SUCCESS
                            ));
                        }
                        card.add(new DesktopUiNode.Text(
                                base + ".current",
                                appToken("gui.migrate-dir.current-value", prefix.path()),
                                TextStyle.CODE,
                                true,
                                true
                        ));
                        card.add(input(
                                base + ".new",
                                binding(prefix),
                                "gui.migrate-dir.column.new",
                                "gui.migrate-dir.new.placeholder",
                                InputKind.DIRECTORY,
                                form(binding(prefix), ""),
                                !owner.busy()
                        ));
                        rows.add(new DesktopUiNode.Surface(
                                base + ".card",
                                DesktopUiNode.SurfaceStyle.CARD,
                                DesktopUiNode.Insets.all(10),
                                true,
                                column(base + ".content", card)
                        ));
                    }
                    rows.add(row(
                            "migrate.actions",
                            button(
                                    "migrate.apply",
                                    "migrate.apply",
                                    "desktop.ui.action.apply",
                                    !owner.busy(),
                                    nextActions,
                                    this::prepare
                            ),
                            button(
                                    "migrate.cancel",
                                    dismissAction,
                                    "desktop.ui.action.cancel",
                                    !owner.busy(),
                                    nextActions,
                                    dismiss
                            )
                    ));
                    return new DesktopUiNode.Dock(
                            "migrate.layout",
                            12,
                            null,
                            scroll("migrate.scroll", column("migrate.rows", rows)),
                            null,
                            null,
                            null
                    );
                },
                700,
                Math.min(720, 210 + prefixes.size() * 115)
        );
    }

    private void prepare() {
        List<MigrationChange> changes = new ArrayList<>();
        PathPrefixRow rootChange = null;
        for (PathPrefixRow prefix : prefixes) {
            String value = form(binding(prefix), "").trim();
            if (value.isBlank() || sameDirectory(value, prefix.path())) continue;
            changes.add(new MigrationChange(prefix.id(), value));
            if (prefix.downloadRoot()) rootChange = prefix;
        }
        if (changes.isEmpty()) {
            owner.showDialog(
                    "migrate.no-change",
                    "gui.dialog.info.title",
                    "gui.migrate-dir.no-change",
                    DesktopUiDocument.DialogStyle.INFO
            );
            owner.rebuild();
            return;
        }
        confirm(new MigrationPlan(List.copyOf(changes), rootChange));
    }

    private void confirm(MigrationPlan plan) {
        PathPrefixRow root = plan.root();
        if (root == null) {
            showConfirmation(
                    appToken("gui.migrate-dir.confirm", plan.changes().size()),
                    () -> apply(plan.changes(), null, null)
            );
            return;
        }
        String newRoot = plan.changes().stream().filter(change -> change.id() == root.id()).map(
                MigrationChange::path).findFirst().orElse("");
        if (root.symbolic()) {
            String relative = relativeToAppRoot(newRoot, appRoot);
            boolean inside = relative != null;
            TextToken message = appToken(
                    inside ? "gui.migrate-dir.symbolic-sync.inside.message" : "gui.migrate-dir.symbolic-sync.outside.message",
                    root.path(),
                    newRoot,
                    inside ? relative : ""
            );
            showChoice(
                    message,
                    () -> apply(
                            inside ? plan.changes().stream().filter(change -> change.id() != root.id()).toList() : plan.changes(),
                            inside ? relative : newRoot,
                            null
                    ),
                    () -> apply(plan.changes(), null, null)
            );
            return;
        }
        showChoice(
                appToken("gui.migrate-dir.root-sync.message", root.path(), newRoot),
                () -> apply(plan.changes(), newRoot, null),
                () -> apply(plan.changes(), null, root.path())
        );
    }

    private void showConfirmation(TextToken message, Runnable confirm) {
        owner.showDialog(
                "migrate.confirm",
                "gui.migrate-dir.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column(
                        "migrate.confirm.content",
                        new DesktopUiNode.Text(
                                "migrate.confirm.message",
                                message,
                                TextStyle.BODY,
                                true,
                                true
                        ),
                        row(
                                "migrate.confirm.actions",
                                button(
                                        "migrate.confirm.yes",
                                        "migrate.confirm.yes",
                                        "desktop.ui.action.confirm",
                                        true,
                                        nextActions,
                                        confirm
                                ),
                                button(
                                        "migrate.confirm.no",
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
    }

    private void showChoice(TextToken message, Runnable yes, Runnable no) {
        owner.showDialog(
                "migrate.root-choice",
                "gui.migrate-dir.title",
                DesktopUiDocument.DialogStyle.QUESTION,
                (nextActions, dismissAction, dismiss) -> column(
                        "migrate.root-choice.content",
                        new DesktopUiNode.Text(
                                "migrate.root-choice.message",
                                message,
                                TextStyle.BODY,
                                true,
                                true
                        ),
                        row(
                                "migrate.root-choice.actions",
                                button(
                                        "migrate.root-choice.yes",
                                        "migrate.root-choice.yes",
                                        "desktop.ui.action.yes",
                                        true,
                                        nextActions,
                                        yes
                                ),
                                button(
                                        "migrate.root-choice.no",
                                        "migrate.root-choice.no",
                                        "desktop.ui.action.no",
                                        true,
                                        nextActions,
                                        no
                                ),
                                button(
                                        "migrate.root-choice.cancel",
                                        dismissAction,
                                        "desktop.ui.action.cancel",
                                        true,
                                        nextActions,
                                        dismiss
                                )
                        )
                ),
                620,
                0
        );
    }

    private void apply(
            List<MigrationChange> changes,
            String rootSyncPath,
            String registerOldRoot
    ) {
        owner.closeDialog();
        owner.runBusy(() -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    "updates",
                    changes.stream().map(change -> Map.<String, Object>of(
                            "id",
                            change.id(),
                            "path",
                            change.path()
                    )).toList()
            );
            if (registerOldRoot != null && !registerOldRoot.isBlank()) {
                payload.put("registerPaths", List.of(registerOldRoot));
            }
            DesktopUiHost.GuiResponse response = host.guiPostJson(
                    "path-prefixes",
                    payload,
                    15_000
            );
            DesktopUiHost.GuiValue body = response.body();
            if (!response.is2xx() || body == null) {
                owner.showDialog(
                        "migrate.unreachable",
                        "gui.dialog.error.title",
                        "gui.migrate-dir.error.unreachable",
                        DesktopUiDocument.DialogStyle.ERROR
                );
                return;
            }
            if (!body.path("success").asBoolean(false)) {
                StringBuilder details = new StringBuilder();
                for (DesktopUiHost.GuiValue error : body.path("errors")) {
                    details.append("\n• ").append(reason(error.path("reason").asText("")));
                }
                owner.showDialog(
                        "migrate.failed",
                        "gui.dialog.error.title",
                        appToken("gui.migrate-dir.failed", details),
                        DesktopUiDocument.DialogStyle.ERROR
                );
                return;
            }
            int applied = body.path("applied").asInt(0);
            if (rootSyncPath == null) {
                owner.showDialog(
                        "migrate.success",
                        "gui.dialog.info.title",
                        appToken("gui.migrate-dir.success", applied),
                        DesktopUiDocument.DialogStyle.SUCCESS
                );
                return;
            }
            try {
                host.applicationConfig().write(
                        "download.root-folder",
                        host.normalizeRootFolder(rootSyncPath)
                );
                owner.loadConfiguration();
            } catch (Exception failure) {
                LOG.warn("Unable to persist migrated download root", failure);
                owner.showDialog(
                        "migrate.persist-failed",
                        "gui.dialog.error.title",
                        appToken("gui.migrate-dir.root-sync.persist-failed", applied),
                        DesktopUiDocument.DialogStyle.ERROR
                );
                return;
            }
            TextToken success = appToken(
                    applied > 0 ? "gui.migrate-dir.root-sync.success" : "gui.migrate-dir.symbolic-sync.config-only.success",
                    applied > 0 ? applied : rootSyncPath
            );
            owner.showDialog(
                    "migrate.restart",
                    "gui.migrate-dir.title",
                    DesktopUiDocument.DialogStyle.SUCCESS,
                    (nextActions, dismissAction, dismiss) -> column(
                            "migrate.restart.content",
                            new DesktopUiNode.Text(
                                    "migrate.restart.message",
                                    success,
                                    TextStyle.BODY,
                                    true,
                                    true
                            ),
                            row(
                                    "migrate.restart.actions",
                                    button(
                                            "migrate.restart.now",
                                            "migrate.restart.now",
                                            "gui.action.restart-application",
                                            true,
                                            nextActions,
                                            () -> {
                                                owner.closeDialog();
                                                owner.restartApplication();
                                            }
                                    ),
                                    button(
                                            "migrate.restart.later",
                                            dismissAction,
                                            "gui.action.restart-later",
                                            true,
                                            nextActions,
                                            dismiss
                                    )
                            )
                    ),
                    560,
                    0
            );
        });
    }

    private String reason(String code) {
        return host.message("gui.migrate-dir.reason." + switch (nullToEmpty(code)) {
            case "invalid", "not-absolute", "not-exist", "not-directory", "duplicate", "conflict",
                 "unknown-id" -> code;
            default -> "unknown";
        });
    }

    private boolean sameDirectory(String first, String second) {
        return normalizeDirectory(first).equals(normalizeDirectory(second));
    }

    private String normalizeDirectory(String value) {
        return host.stripTrailingPathSeparators(nullToEmpty(value)).replace(
                '\\',
                '/'
        ).toLowerCase(
                Locale.ROOT);
    }

    private static String relativeToAppRoot(String newRoot, String appRoot) {
        if (nullToEmpty(newRoot).isBlank() || nullToEmpty(appRoot).isBlank()) return null;
        try {
            Path app = Path.of(appRoot).toAbsolutePath().normalize();
            Path target = Path.of(newRoot).toAbsolutePath().normalize();
            return target.equals(app) || !target.startsWith(app) ? null : app.relativize(target).toString().replace(
                    '\\',
                    '/'
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String binding(PathPrefixRow row) {
        return "migrate.prefix." + row.id();
    }

    void scheduleSymbolicOrphanCheck() {
        owner.executeAsync(() -> {
            try {
                java.util.concurrent.TimeUnit.SECONDS.sleep(3L);
                for (int attempt = 0; attempt < 24; attempt++) {
                    DesktopUiHost.GuiResponse response = host.guiGet("path-prefixes", 10_000);
                    if (response.is2xx() && response.body() != null) {
                        DesktopUiHost.GuiValue body = response.body();
                        if (body.path("symbolicOrphan").asBoolean(false)) {
                            showSymbolicOrphanDialog(
                                    body.path("symbolicOrphanSuggestedPath").asText(
                                            ""),
                                    null
                            );
                        }
                        return;
                    }
                    java.util.concurrent.TimeUnit.MILLISECONDS.sleep(2_500L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void showSymbolicOrphanDialog(String suggestedPath, TextToken failure) {
        formValues.put("symbolic-orphan.path", nullToEmpty(suggestedPath));
        owner.showDialog(
                "symbolic-orphan",
                "gui.startup.symbolic-orphan.title",
                DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> nodes = new ArrayList<>();
                    if (failure != null) {
                        nodes.add(new DesktopUiNode.Surface(
                                "symbolic-orphan.failure",
                                DesktopUiNode.SurfaceStyle.ERROR,
                                DesktopUiNode.Insets.all(8),
                                true,
                                new DesktopUiNode.Text(
                                        "symbolic-orphan.failure.text",
                                        failure,
                                        TextStyle.ERROR,
                                        true,
                                        true
                                )
                        ));
                    }
                    nodes.add(text(
                            "symbolic-orphan.message",
                            "gui.startup.symbolic-orphan.message",
                            TextStyle.BODY
                    ));
                    nodes.add(input(
                            "symbolic-orphan.path.input",
                            "symbolic-orphan.path",
                            "gui.migrate-dir.column.original",
                            "gui.migrate-dir.new.placeholder",
                            InputKind.DIRECTORY,
                            form("symbolic-orphan.path", suggestedPath),
                            true
                    ));
                    nodes.add(row(
                            "symbolic-orphan.actions",
                            button(
                                    "symbolic-orphan.repair",
                                    "symbolic-orphan.repair",
                                    "desktop.ui.action.confirm",
                                    true,
                                    nextActions,
                                    this::repairSymbolicOrphan
                            ),
                            button(
                                    "symbolic-orphan.cancel",
                                    "symbolic-orphan.cancel",
                                    "desktop.ui.action.cancel",
                                    true,
                                    nextActions,
                                    this::confirmSymbolicOrphanCancel
                            )
                    ));
                    return column("symbolic-orphan.content", nodes);
                },
                false,
                620,
                0
        );
    }

    private void confirmSymbolicOrphanCancel() {
        String path = form("symbolic-orphan.path", "");
        owner.showDialog(
                "symbolic-orphan.cancel",
                "gui.startup.symbolic-orphan.title",
                DesktopUiDocument.DialogStyle.WARNING,
                (nextActions, dismissAction, dismiss) -> column(
                        "symbolic-orphan.cancel.content",
                        text(
                                "symbolic-orphan.cancel.message",
                                "gui.startup.symbolic-orphan.cancel-confirm",
                                TextStyle.BODY
                        ),
                        row(
                                "symbolic-orphan.cancel.actions",
                                button(
                                        "symbolic-orphan.cancel.yes",
                                        "symbolic-orphan.cancel.yes",
                                        "desktop.ui.action.yes",
                                        true,
                                        nextActions,
                                        () -> {
                                            owner.closeDialog();
                                            owner.rebuild();
                                        }
                                ),
                                button(
                                        "symbolic-orphan.cancel.no",
                                        "symbolic-orphan.cancel.no",
                                        "desktop.ui.action.no",
                                        true,
                                        nextActions,
                                        () -> showSymbolicOrphanDialog(path, null)
                                )
                        )
                ),
                false,
                560,
                0
        );
    }

    private void repairSymbolicOrphan() {
        String path = form("symbolic-orphan.path", "").trim();
        if (path.isBlank()) {
            showSymbolicOrphanDialog("", key("gui.migrate-dir.reason.invalid"));
            return;
        }
        owner.closeDialog();
        owner.runBusy(() -> {
            DesktopUiHost.GuiResponse response = host.guiPostJson(
                    "path-prefixes/pin",
                    Map.of("path", path),
                    15_000
            );
            if (response.is2xx() && response.body() != null && response.body().path("success").asBoolean(
                    false)) {
                owner.showDialog(
                        "symbolic-orphan.success",
                        "gui.dialog.info.title",
                        appToken("gui.startup.symbolic-orphan.success", path),
                        DesktopUiDocument.DialogStyle.SUCCESS
                );
                return;
            }
            String failureReason = response.body() == null ? host.message(
                    "gui.migrate-dir.error.unreachable") : reason(response.body().path("errors").path(
                    0).path("reason").asText(""));
            showSymbolicOrphanDialog(
                    path,
                    appToken("gui.startup.symbolic-orphan.failed", failureReason)
            );
        });
    }

    private String form(String key, String fallback) {
        return formValues.getOrDefault(key, fallback);
    }

    private record PathPrefixRow(
            long id,
            String path,
            boolean downloadRoot,
            boolean symbolic
    ) {
    }

    private record MigrationChange(long id, String path) {
    }

    private record MigrationPlan(
            List<MigrationChange> changes,
            PathPrefixRow root
    ) {
    }
}
