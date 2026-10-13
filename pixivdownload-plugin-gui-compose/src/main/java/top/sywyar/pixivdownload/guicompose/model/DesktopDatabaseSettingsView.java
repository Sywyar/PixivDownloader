package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextStyle;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.TextToken;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;

/** Compose 设置页的数据库检查反馈；字段和保存仍使用统一配置模型。 */
final class DesktopDatabaseSettingsView {
    private final DesktopConfigurationController model;
    private volatile DesktopDatabaseSettings.State state;
    private volatile TextToken result;
    private volatile DesktopDatabaseSettings.MigrationState migration;
    private volatile DesktopDatabaseSettings.MigrationPlan plan;

    DesktopDatabaseSettingsView(DesktopConfigurationController model) { this.model = model; }

    void edited() { result = null; plan = null; }

    TextToken activeTarget() {
        var current = state;
        if (current == null) return new TextToken("gui-compose", "gui.compose.about.unknown", "", List.of());
        return switch (current.activeTarget()) {
            case "" -> TextToken.key("gui.config.database.stopped");
            case "DATABASE_UNAVAILABLE" -> TextToken.key("gui.config.database.unavailable");
            default -> TextToken.raw(current.activeTarget());
        };
    }

    void refresh() {
        model.host.databaseSettings().ifPresent(settings -> {
            try { state = settings.state(); migration = settings.migrationState(); }
            catch (Exception failure) { state = null; }
        });
    }

    List<DesktopUiNode> nodes(Map<String, Runnable> actions) {
        var capability = model.host.databaseSettings();
        if (capability.isEmpty()) return List.of();
        var settings = capability.get();
        List<DesktopUiNode> nodes = new ArrayList<>();
        nodes.add(text("database.notice", "gui.config.database.notice", TextStyle.BODY));
        var current = state;
        if (current != null) {
            nodes.add(new DesktopUiNode.Text("database.targets", message("gui.config.database.targets",
                    switch (current.activeTarget()) {
                        case "" -> model.host.message("gui.config.database.stopped");
                        case "DATABASE_UNAVAILABLE" -> model.host.message("gui.config.database.unavailable");
                        default -> current.activeTarget();
                    },
                    current.nextTarget().isEmpty() ? model.host.message("gui.config.database.invalid") : current.nextTarget()),
                    TextStyle.BODY, true, true));
            if (!current.overriddenKeys().isEmpty()) {
                nodes.add(new DesktopUiNode.Text("database.overridden",
                        message("gui.config.database.overridden", String.join(", ", current.overriddenKeys())),
                        TextStyle.BODY, true, true));
            }
            if (current.restartRequired()) nodes.add(text("database.restart", "gui.config.notice.saved-process", TextStyle.BODY));
        }
        TextToken notice = result;
        if (notice != null) nodes.add(new DesktopUiNode.Text("database.result", notice, TextStyle.BODY, true, true));
        var currentMigration = migration;
        if (currentMigration != null && !currentMigration.status().isEmpty()) {
            nodes.add(text("database.migration-status", statusKey("status", currentMigration.status()), TextStyle.BODY));
            nodes.addAll(storeNodes("database.previous", currentMigration.stores()));
            if (!currentMigration.failure().isEmpty()) nodes.add(new DesktopUiNode.Text("database.migration-failure",
                    message("gui.config.database.migration.failure", currentMigration.failure()), TextStyle.ERROR, true, true));
        }
        if (plan != null) nodes.addAll(storeNodes("database.preview", plan.stores()));
        nodes.add(row("database.actions",
                button("database.check", "database.check", "gui.config.database.check", !model.owner.busy(), actions,
                        () -> check(settings)),
                button("database.refresh", "database.refresh", "gui.config.database.refresh", !model.owner.busy(), actions,
                        () -> model.owner.runBusy(this::refresh)),
                button("database.guide", "database.guide", "gui.config.database.guide", !model.owner.busy(), actions,
                        () -> model.owner.runBusy(() -> {
                            try { model.host.openExternalUri(settings.migrationGuide()); }
                            catch (Exception failure) { result = key("desktop.ui.action.failed"); }
                        }))));
        return nodes;
    }

    void prepareMigration(java.util.function.BooleanSupplier unchanged,
                          top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.IoOperation saveOthers) throws java.io.IOException {
        var settings = model.host.databaseSettings().orElseThrow();
        var inspected = settings.previewMigration(draft());
        if (!unchanged.getAsBoolean()) { result = key("gui.config.database.stale"); return; }
        plan = inspected;
        result = inspected.ready() ? key("gui.config.database.migration.ready")
                : message("gui.config.database.migration.failure", inspected.failure());
        model.owner.showDialog("database.migration", "gui.config.database.migrate",
                top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument.DialogStyle.WARNING,
                (actions, dismissAction, dismiss) -> {
                    var content = new ArrayList<DesktopUiNode>(storeNodes("database.confirm", inspected.stores()));
                    content.add(new DesktopUiNode.Text("database.migration-notice", inspected.ready()
                            ? key("gui.config.database.migration.confirm-notice")
                            : message("gui.config.database.migration.failure", inspected.failure()), TextStyle.BODY, true, true));
                    var controls = endRow("database.migration-actions",
                            button("database.migration-confirm", "database.migration-confirm", "gui.config.database.migration.confirm",
                                    inspected.ready() && !model.owner.busy(), actions, () -> model.owner.runBusy(() -> {
                                        try {
                                            if (!unchanged.getAsBoolean()) { result = key("gui.config.database.stale"); return; }
                                            settings.startMigration(inspected.token(), saveOthers);
                                            result = key("gui.config.database.migration.status.pending");
                                            model.owner.closeDialog();
                                        } catch (Exception failure) { result = key("gui.config.database.migration.failed"); }
                                    })),
                            button("database.migration-cancel", "database.migration-cancel", "desktop.ui.action.cancel",
                                    !model.owner.busy(), actions, () -> { model.owner.closeDialog(); model.owner.rebuild(); }));
                    return new DesktopUiNode.Dock(
                            "database.migration-layout",
                            12,
                            null,
                            scroll(
                                    "database.migration-scroll",
                                    column("database.migration-content", content)
                            ),
                            controls,
                            null,
                            null
                    );
                }, 720, 520);
    }

    private List<DesktopUiNode> storeNodes(String prefix, List<DesktopDatabaseSettings.MigrationStore> stores) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        for (var store : stores) {
            nodes.add(new DesktopUiNode.Text(prefix + ".store." + store.owner(),
                    message("gui.config.database.migration.store", store.owner(), store.source(), store.target(),
                            model.host.message(statusKey("phase", store.phase()))), TextStyle.BODY, true, true));
            if (!store.rebuildObjects().isEmpty()) nodes.add(new DesktopUiNode.Text(prefix + ".rebuild." + store.owner(),
                    message("gui.config.database.migration.rebuild", String.join(", ", store.rebuildObjects())), TextStyle.BODY, true, true));
        }
        return nodes;
    }

    private static String statusKey(String kind, String value) {
        return "gui.config.database.migration." + kind + "." + value.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private Map<String, String> draft() {
        Map<String, String> draft = new LinkedHashMap<>();
        for (var field : model.configFields) {
            if (field.owner() != null) continue;
            String value = model.values.getOrDefault(field.key(), field.spec().defaultValue());
            if (!field.spec().sensitive() || !value.isEmpty() || model.clearedCoreCredentials.contains(field.key())) {
                draft.put(field.spec().key(), value);
            }
        }
        return draft;
    }

    private void check(DesktopDatabaseSettings settings) {
        Map<String, String> submitted = draft();
        result = key("gui.config.database.checking");
        model.owner.runBusy(() -> {
            try {
                var checked = settings.check(submitted);
                result = submitted.equals(draft())
                        ? message("gui.config.database.result." + checked.outcome().name().toLowerCase(java.util.Locale.ROOT),
                                checked.target(), checked.sqlState())
                        : key("gui.config.database.stale");
            } catch (Exception failure) { result = key("gui.config.database.invalid"); }
        });
    }

    private static TextToken message(String key, String... arguments) {
        return new TextToken(null, key, key, List.of(arguments));
    }
}
