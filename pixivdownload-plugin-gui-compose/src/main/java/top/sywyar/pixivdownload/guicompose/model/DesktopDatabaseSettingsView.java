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
    private volatile TextToken confirmationFailure;
    private volatile boolean resultFailed;
    private volatile String resultCode = "";

    DesktopDatabaseSettingsView(DesktopConfigurationController model) { this.model = model; }

    void edited() { result = null; resultFailed = false; resultCode = ""; }

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
            catch (Exception failure) { state = null; migration = null; }
        });
    }

    List<DesktopUiNode> nodes(Map<String, Runnable> actions) {
        var capability = model.host.databaseSettings();
        if (capability.isEmpty()) return List.of();
        var settings = capability.get();
        List<DesktopUiNode> nodes = new ArrayList<>();
        var current = state;
        if (current != null) {
            if (current.activeTarget().isEmpty() || current.activeTarget().equals("DATABASE_UNAVAILABLE"))
                nodes.add(new DesktopUiNode.Text("database.targets", activeTarget(), TextStyle.WARNING, true, true));
            if (current.nextTarget().isEmpty()) nodes.add(text("database.invalid", "gui.config.database.invalid", TextStyle.ERROR));
            if (!current.overriddenKeys().isEmpty()) {
                nodes.add(new DesktopUiNode.Text("database.overridden",
                        message("gui.config.database.overridden", String.join(", ", current.overriddenKeys())),
                        TextStyle.BODY, true, true));
            }
            if (current.restartRequired()) nodes.add(text("database.restart", "gui.config.notice.saved-process", TextStyle.BODY));
        }
        TextToken notice = result;
        if (notice != null) nodes.add(new DesktopUiNode.Text("database.result", notice,
                resultFailed ? TextStyle.ERROR : TextStyle.BODY, true, true));
        var currentMigration = migration;
        if (currentMigration != null && !currentMigration.status().isEmpty()) {
            if (!currentMigration.status().equals("SUCCEEDED")) {
                nodes.add(text("database.migration-status", statusKey("status", currentMigration.status()), TextStyle.BODY));
            }
            if (!currentMigration.failure().isEmpty()) nodes.add(new DesktopUiNode.Text("database.migration-failure",
                    key(DesktopDatabaseSettings.failureMessageKey(currentMigration.failure())), TextStyle.ERROR, true, true));
            if (currentMigration.stores().stream().anyMatch(store -> store.phase().equals("UNKNOWN"))) {
                nodes.add(text("database.migration-uncertain", statusKey("phase", "UNKNOWN"), TextStyle.ERROR));
            }
        }
        nodes.add(row("database.actions",
                button("database.check", "database.check", "gui.config.database.check", !model.owner.busy(), actions,
                        () -> check(settings)),
                button("database.inspect", "database.inspect", "gui.config.database.inspect", !model.owner.busy(), actions,
                        () -> model.owner.runBusy(() -> {
                            refresh();
                            showDetails();
                        }))));
        if (currentMigration != null && (!currentMigration.status().equals("SUCCEEDED") || !currentMigration.failure().isEmpty()))
            nodes.addAll(storeNodes("database.previous", currentMigration.stores(), false));
        return nodes;
    }

    private void showDetails() {
        model.owner.showDialog(
                "database.inspection",
                "gui.config.database.inspection-title",
                top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument.DialogStyle.INFO,
                (actions, dismissAction, dismiss) -> {
                    List<DesktopUiNode> content = new ArrayList<>();
                    var current = state;
                    if (current == null) content.add(text("database.details-unavailable", "gui.config.database.invalid", TextStyle.ERROR));
                    else {
                        content.add(column(
                                "database.connection",
                                new DesktopUiNode.Text(
                                        "database.connection-label",
                                        new TextToken("gui-compose", "gui.compose.database.current", "", List.of()),
                                        TextStyle.EMPHASIS,
                                        false,
                                        true
                                ),
                                new DesktopUiNode.Text("database.active", activeTarget(), TextStyle.CAPTION, true, true)
                        ));
                        if (!current.activeTarget().equals(current.nextTarget())) content.add(new DesktopUiNode.Text(
                                "database.next", message("gui.config.database.next", current.nextTarget().isEmpty()
                                    ? model.host.message("gui.config.database.invalid") : current.nextTarget()), TextStyle.CAPTION, true, true));
                    }
                    if (!resultCode.isEmpty()) content.add(new DesktopUiNode.Text("database.check-error.code",
                            message("gui.config.database.diagnostic-code", resultCode), TextStyle.BODY, true, true));
                    content.add(new DesktopUiNode.Separator("database.history-divider", DesktopUiNode.Axis.HORIZONTAL));
                    content.add(text("database.history", "gui.config.database.history", TextStyle.CAPTION));
                    var recent = migration;
                    if (recent != null && !recent.status().isEmpty()) {
                        content.add(text(
                                "database.details-status",
                                statusKey("status", recent.status()),
                                switch (recent.status()) {
                                    case "SUCCEEDED" -> TextStyle.SUCCESS;
                                    case "FAILED" -> TextStyle.ERROR;
                                    default -> TextStyle.BODY;
                                }
                        ));
                        if (!recent.failure().isEmpty()) {
                            content.add(text("database.details-failure", DesktopDatabaseSettings.failureMessageKey(recent.failure()), TextStyle.ERROR));
                            content.add(new DesktopUiNode.Text("database.failure-code", message("gui.config.database.diagnostic-code", recent.failure()),
                                    TextStyle.BODY, true, true));
                        }
                        List<DesktopUiNode> stores = new ArrayList<>();
                        for (var store : recent.stores()) {
                            String id = "database.details.store." + store.owner();
                            if (!stores.isEmpty()) stores.add(new DesktopUiNode.Separator(id + ".divider", DesktopUiNode.Axis.HORIZONTAL));
                            stores.add(new DesktopUiNode.Group(
                                    id,
                                    storeTitle(store.owner()),
                                    column(id + ".content", storeDetails(id, store)),
                                    true,
                                    text(
                                            id + ".phase",
                                            statusKey("phase", store.phase()),
                                            store.phase().equals("UNKNOWN") || store.phase().equals("BLOCKED")
                                                    ? TextStyle.ERROR : TextStyle.CAPTION
                                    )
                            ));
                        }
                        content.add(new DesktopUiNode.Container(
                                "database.details-stores",
                                DesktopUiNode.ContainerLayout.COLUMN,
                                1,
                                0,
                                DesktopUiNode.Alignment.STRETCH,
                                stores
                        ));
                    } else content.add(new DesktopUiNode.Text(
                            "database.history-empty",
                            new TextToken("gui-compose", "gui.compose.database.history-empty", "", List.of()),
                            TextStyle.BODY,
                            false,
                            true
                    ));
                    return new DesktopUiNode.Dock(
                            "database.inspection-layout",
                            12,
                            null,
                            scroll("database.inspection-scroll", column("database.inspection-content", content)),
                            endRow("database.inspection-actions",
                                    button("database.refresh", "database.refresh", "gui.config.database.refresh", !model.owner.busy(), actions,
                                            () -> model.owner.runBusy(() -> { edited(); refresh(); })),
                                    button("database.inspection-close", dismissAction, "desktop.ui.action.close", true, actions, dismiss)),
                            null,
                            null
                    );
                },
                680,
                520
        );
    }

    void prepareMigration(java.util.function.BooleanSupplier unchanged,
                          top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.IoOperation saveOthers) throws java.io.IOException {
        var settings = model.host.databaseSettings().orElseThrow();
        var inspected = settings.previewMigration(draft());
        if (!unchanged.getAsBoolean()) { result = key("gui.config.database.stale"); return; }
        confirmationFailure = null;
        resultCode = "";
        resultFailed = !inspected.ready();
        result = inspected.ready() ? null
                : key(DesktopDatabaseSettings.failureMessageKey(inspected.failure()));
        model.owner.showDialog("database.migration", "gui.config.database.migration.title",
                top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument.DialogStyle.WARNING,
                (actions, dismissAction, dismiss) -> {
                    var content = new ArrayList<DesktopUiNode>();
                    content.add(new DesktopUiNode.Text("database.migration-summary",
                            message("gui.config.database.migration.summary", Integer.toString(inspected.stores().size())),
                            TextStyle.HEADING, true, true));
                    content.add(new DesktopUiNode.Text("database.migration-notice", inspected.ready()
                            ? key("gui.config.database.migration.confirm-notice")
                            : key(DesktopDatabaseSettings.failureMessageKey(inspected.failure())),
                            inspected.ready() ? TextStyle.BODY : TextStyle.ERROR, true, true));
                    inspected.stores().stream().filter(store -> store.owner().equals("main")).findFirst().ifPresent(store -> {
                        content.add(new DesktopUiNode.Text("database.migration-from",
                                message("gui.config.database.migration.source", store.source()), TextStyle.BODY, true, true));
                        content.add(new DesktopUiNode.Text("database.migration-to",
                                message("gui.config.database.migration.target", store.target()), TextStyle.BODY, true, true));
                    });
                    if (inspected.stores().stream().anyMatch(store -> store.phase().equals("REPLACE_TARGET"))) {
                        content.add(text("database.replacement-notice", "gui.config.database.migration.replacement-notice", TextStyle.BODY));
                    }
                    if (confirmationFailure != null) content.add(new DesktopUiNode.Text(
                            "database.confirmation-failure", confirmationFailure, TextStyle.ERROR, true, true));
                    content.addAll(storeNodes("database.confirm", inspected.stores(), true));
                    if (!inspected.failure().isEmpty()) content.add(diagnosticDetails("database.plan-error", inspected.failure()));
                    actions.put("database.migration-confirm", () -> model.owner.runBusy(() -> {
                        try {
                            if (!unchanged.getAsBoolean()) {
                                confirmationFailure = result = key("gui.config.database.stale");
                                resultFailed = true;
                                return;
                            }
                            settings.startMigration(inspected.token(), saveOthers);
                            result = key("gui.config.database.migration.status.pending");
                            resultFailed = false;
                            model.owner.closeDialog();
                        } catch (Exception failure) {
                            confirmationFailure = result = key("gui.config.database.migration.failed");
                            resultFailed = true;
                        }
                    }));
                    boolean connectionOnly = !inspected.stores().isEmpty() && inspected.stores().stream().allMatch(store ->
                            store.phase().equals("SAME_TARGET") || store.phase().equals("CONNECTION_REPAIR"));
                    var controls = endRow("database.migration-actions",
                            button("database.migration-cancel", "database.migration-cancel", "desktop.ui.action.cancel",
                                    !model.owner.busy(), actions, () -> { model.owner.closeDialog(); model.owner.rebuild(); }),
                            new DesktopUiNode.Button(
                                    "database.migration-confirm",
                                    "database.migration-confirm",
                                    key(connectionOnly ? "gui.config.database.migration.confirm-connection"
                                            : "gui.config.database.migration.confirm"),
                                    null,
                                    DesktopUiNode.ButtonStyle.PRIMARY,
                                    inspected.ready() && !model.owner.busy()
                            ));
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

    private List<DesktopUiNode> storeNodes(
            String prefix,
            List<DesktopDatabaseSettings.MigrationStore> stores,
            boolean preview
    ) {
        List<DesktopUiNode> nodes = new ArrayList<>();
        for (var store : stores) {
            String id = prefix + ".store." + store.owner();
            nodes.add(new DesktopUiNode.Text(id, storeTitle(store.owner()),
                    TextStyle.HEADING, true, true));
            nodes.add(text(id + ".phase", statusKey("phase", preview && store.phase().equals("COMMITTED")
                    ? "REUSE" : store.phase()), store.phase().equals("BLOCKED") || store.phase().equals("UNKNOWN")
                    ? TextStyle.ERROR : TextStyle.BODY));
            nodes.add(new DesktopUiNode.Group(id + ".details", key("gui.config.database.migration.details"),
                    column(id + ".detail-content", storeDetails(id, store)), true));
        }
        return nodes;
    }

    private List<DesktopUiNode> storeDetails(String id, DesktopDatabaseSettings.MigrationStore store) {
        List<DesktopUiNode> details = new ArrayList<>();
        details.add(new DesktopUiNode.Text(id + ".source", message("gui.config.database.migration.source", store.source()),
                TextStyle.CAPTION, true, true));
        details.add(new DesktopUiNode.Text(id + ".target", message("gui.config.database.migration.target", store.target()),
                TextStyle.CAPTION, true, true));
        if (!store.rebuildObjects().isEmpty()) details.add(new DesktopUiNode.Text(id + ".rebuild",
                message("gui.config.database.migration.rebuild", String.join(", ", store.rebuildObjects())), TextStyle.CAPTION, true, true));
        return details;
    }

    private TextToken storeTitle(String owner) {
        if (owner.equals("main")) return key("gui.config.database.migration.main");
        String pluginId = owner.startsWith("private:") ? owner.substring("private:".length()) : owner;
        return model.owner.currentSources().stream().filter(plugin -> plugin.id().equals(pluginId))
                .map(plugin -> plugin.displayName())
                .map(name -> new TextToken(name.namespace(), name.key(), name.fallback(), name.arguments()))
                .findFirst().orElseGet(() -> message("gui.config.database.migration.plugin", pluginId));
    }

    private DesktopUiNode diagnosticDetails(String id, String code) {
        return new DesktopUiNode.Group(id, key("gui.config.database.migration.details"),
                new DesktopUiNode.Text(id + ".code", message("gui.config.database.diagnostic-code", code),
                        TextStyle.BODY, true, true), true);
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
        resultFailed = false;
        resultCode = "";
        model.owner.runBusy(() -> {
            try {
                var checked = settings.check(submitted);
                resultFailed = checked.outcome() == DesktopDatabaseSettings.Outcome.FAILED;
                resultCode = resultFailed && submitted.equals(draft()) ? checked.sqlState() : "";
                result = submitted.equals(draft())
                        ? resultFailed ? key(DesktopDatabaseSettings.failureMessageKey(checked.sqlState()))
                            : message("gui.config.database.result." + checked.outcome().name().toLowerCase(java.util.Locale.ROOT),
                                    checked.target(), checked.sqlState())
                        : key("gui.config.database.stale");
            } catch (Exception failure) { resultFailed = true; result = key("gui.config.database.invalid"); }
        });
    }

    private static TextToken message(String key, String... arguments) {
        return new TextToken(null, key, key, List.of(arguments));
    }
}
