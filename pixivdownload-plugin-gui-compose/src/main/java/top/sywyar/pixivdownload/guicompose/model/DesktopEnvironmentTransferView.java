package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer;

import java.nio.file.Path;
import java.util.*;

import static top.sywyar.pixivdownload.guicompose.model.DesktopUiNodes.*;
import static top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode.*;

/** Compose 设置页的迁移交互；密码只存于当前弹窗，并在关闭或提交后清除。 */
final class DesktopEnvironmentTransferView {
    private static final String ID = "transfer.dialog";
    private final DesktopConfigurationController model;
    private boolean exporting;
    private String archivePath = "";
    private boolean includeCredentials;
    private boolean includeDatabases;
    private boolean includeState;
    private boolean includeResources;
    private final Set<DesktopEnvironmentTransfer.FieldKey> selectedFiles = new LinkedHashSet<>();
    private char[] password = new char[0];
    private long passwordRevision;
    private long generation;
    private Thread operationThread;
    private char[] queuedPassword;
    private String notice = "";
    private String restoreNotice = "";
    private DesktopEnvironmentTransfer.Installation installation;
    private DesktopEnvironmentTransfer.Preview preview;
    private final Set<DesktopEnvironmentTransfer.FieldKey> selected = new LinkedHashSet<>();
    private final Map<DesktopEnvironmentTransfer.FieldKey, String> paths = new LinkedHashMap<>();
    private final Map<Long, String> databasePaths = new LinkedHashMap<>();

    DesktopEnvironmentTransferView(DesktopConfigurationController model) { this.model = model; }

    void refreshRestoreState() {
        restoreNotice = "";
        var capability = model.host.environmentTransfer();
        if (capability.isEmpty()) return;
        try {
            installation = capability.get().installation();
            var state = capability.get().restoreState();
            if (state.status() != DesktopEnvironmentTransfer.Status.NONE)
                restoreNotice = "transfer.status." + state.status().name().toLowerCase(Locale.ROOT);
        } catch (Exception unavailable) { restoreNotice = "transfer.result-unavailable"; }
    }

    List<DesktopUiNode> buttons(Map<String, Runnable> actions) {
        if (model.host.environmentTransfer().isEmpty()) return List.of();
        return List.of(button("transfer.export", "transfer.export", "transfer.export", !model.owner.busy(), actions, () -> open(true)),
                button("transfer.import", "transfer.import", "transfer.import", !model.owner.busy(), actions, () -> open(false)));
    }

    private void open(boolean exporting) {
        clear();
        this.exporting = exporting;
        archivePath = exporting ? "PixivDownloader.pdbackup" : "";
        if (!exporting && model.pendingConfigurationChangeCount() > 0) {
            model.owner.showDialog(ID, "transfer.title", "transfer.unsaved", DesktopUiDocument.DialogStyle.WARNING);
            model.owner.rebuild();
            return;
        }
        model.owner.showDialog(ID, exporting ? "transfer.export" : "transfer.import", DesktopUiDocument.DialogStyle.INFO,
                (actions, dismissAction, dismiss) -> {
                    boolean enabled = !model.owner.busy();
                    List<DesktopUiNode> body = new ArrayList<>();
                    if (!restoreNotice.isEmpty()) body.add(text("transfer.previous", restoreNotice, TextStyle.BODY));
                    if (installation != null && installation.kind() != DesktopEnvironmentTransfer.InstallationKind.UNSUPPORTED) {
                        body.add(text("transfer.installation", "transfer.installation." + installation.kind().name().toLowerCase(Locale.ROOT), TextStyle.BODY));
                        body.add(new Text("transfer.installation-paths", appToken("transfer.installation-paths",
                                installation.directory(), installation.registeredDirectory()), TextStyle.CAPTION, true, true));
                    }
                    body.add(text("transfer.hint", preview == null ? "transfer.saved-only" : "transfer.confirm-hint", TextStyle.BODY));
                    if (preview == null) {
                        body.add(input("transfer.archive", "transfer.archive", "transfer.archive", null,
                                exporting ? InputKind.FILE_SAVE : InputKind.FILE, archivePath, enabled));
                        if (exporting) {
                            body.add(toggle("transfer.credentials", "transfer.credentials", "transfer.credentials", includeCredentials, enabled));
                            body.add(toggle("transfer.databases", "transfer.databases", "transfer.databases", includeDatabases, enabled));
                            body.add(toggle("transfer.states", "transfer.states", "transfer.states", includeState, enabled));
                            body.add(toggle("transfer.resources", "transfer.resources", "transfer.resources", includeResources, enabled));
                            body.add(text("transfer.database-hint", "transfer.database-export-hint", TextStyle.CAPTION));
                        }
                    } else {
                        for (var plugin : preview.plugins()) body.add(new Text("transfer.plugin." + plugin.id(),
                                appToken("transfer.inventory-package", plugin.id(), plugin.sourceVersion(),
                                        plugin.installed() ? plugin.currentVersion() : model.host.message("transfer.plugin-missing")), TextStyle.CAPTION, true, true));
                        if (!preview.repositories().isEmpty()) body.add(text("transfer.repositories-hint", "transfer.repositories-hint", TextStyle.CAPTION));
                        for (var repository : preview.repositories()) body.add(raw("transfer.repository." + repository.id(),
                                repository.id() + ": " + repository.descriptorUrl() + " " + repository.manifestUrl(), TextStyle.CAPTION));
                        if (preview.changes().isEmpty() && preview.databases().isEmpty() && preview.files().isEmpty()) body.add(text("transfer.empty", "transfer.no-changes", TextStyle.BODY));
                        if (!preview.databases().isEmpty()) {
                            body.add(toggle("transfer.databases", "transfer.databases", "transfer.databases", includeDatabases, enabled));
                            body.add(text("transfer.database-hint", "transfer.database-import-hint", TextStyle.WARNING));
                            for (int i = 0; i < preview.databases().size(); i++) {
                                var store = preview.databases().get(i);
                                body.add(new Text("transfer.database." + i, appToken("transfer.database-store", store.owner(), store.rows(),
                                        model.host.message(store.replacesExisting() ? "transfer.database-replace" : "transfer.database-create")), TextStyle.CAPTION, true, true));
                            }
                        }
                        if (!preview.databaseFailure().isEmpty()) body.add(text("transfer.database-error", "transfer.database-unavailable", TextStyle.WARNING));
                        if (!preview.databasePaths().isEmpty()) body.add(text("transfer.database-path-hint", "transfer.database-path-hint", TextStyle.CAPTION));
                        for (var path : preview.databasePaths()) body.add(new TextInput("transfer.database-path." + path.id(),
                                "transfer.database-path." + path.id(), appToken("transfer.database-path", path.id()), null,
                                InputKind.TEXT, databasePaths.getOrDefault(path.id(), path.source()), 32, 1, enabled && includeDatabases));
                        for (int i = 0; i < preview.files().size(); i++) {
                            var file = preview.files().get(i);
                            body.add(new Toggle("transfer.file." + i, "transfer.file." + i,
                                    appToken("transfer.file-group", file.id().owner(), file.id().key(), file.files(), file.bytes()),
                                    file.requiresDatabase() ? key("transfer.files-require-database") : null, ToggleStyle.CHECKBOX,
                                    selectedFiles.contains(file.id()), enabled && (!file.requiresDatabase() || includeDatabases)));
                        }
                        if (!preview.unavailableFiles().isEmpty()) body.add(new Text("transfer.unavailable-files",
                                appToken("transfer.unavailable-files", preview.unavailableFiles().size()), TextStyle.WARNING, true, true));
                        for (int i = 0; i < preview.changes().size(); i++) {
                            var change = preview.changes().get(i);
                            String label = change.field().owner() + ": " + change.field().key();
                            body.add(new Toggle("transfer.select." + i, "transfer.select." + i, TextToken.raw(label), null,
                                    ToggleStyle.CHECKBOX, selected.contains(change.field()), enabled));
                            if (change.path()) body.add(input("transfer.path." + i, "transfer.path." + i, "transfer.path", null,
                                    InputKind.TEXT, paths.getOrDefault(change.field(), change.after()), enabled && selected.contains(change.field())));
                            else body.add(change.sensitive() ? text("transfer.value." + i, "transfer.secret-update", TextStyle.CAPTION)
                                    : raw("transfer.value." + i, change.after(), TextStyle.CAPTION));
                        }
                        if (!preview.unavailable().isEmpty()) body.add(new Text("transfer.unavailable",
                                appToken("transfer.unavailable", preview.unavailable().size()), TextStyle.WARNING, true, true));
                    }
                    body.add(new TextInput("transfer.password", "transfer.password", key("transfer.password"), key("transfer.password-hint"),
                            InputKind.PASSWORD, "", 32, 1, enabled, passwordRevision));
                    if (!notice.isEmpty()) body.add(text("transfer.notice", notice, TextStyle.WARNING));
                    if (!enabled) body.add(text("transfer.working", "transfer.working", TextStyle.BODY));
                    return dialogContent("transfer.content", column("transfer.body", body), endRow("transfer.actions",
                            button("transfer.cancel", dismissAction, "desktop.ui.action.cancel", enabled, actions, dismiss),
                            button("transfer.submit", "transfer.submit", preview != null ? "transfer.apply" : exporting ? "transfer.export" : "transfer.preview",
                                    enabled && (preview == null || !selected.isEmpty() || includeDatabases || !selectedFiles.isEmpty()), actions, this::submit)));
                }, 720, 540);
    }

    boolean accept(String binding, String value) {
        if (!binding.startsWith("transfer.") || !model.owner.isDialogOpen(ID)) return false;
        if (binding.equals("transfer.password")) {
            Arrays.fill(password, '\0');
            password = value.toCharArray();
        } else if (binding.equals("transfer.archive")) archivePath = value;
        else if (binding.equals("transfer.credentials")) includeCredentials = Boolean.parseBoolean(value);
        else if (binding.equals("transfer.databases")) {
            includeDatabases = Boolean.parseBoolean(value);
            if (!includeDatabases) {
                includeResources = false;
                if (preview != null) preview.files().stream().filter(DesktopEnvironmentTransfer.FileGroup::requiresDatabase)
                        .forEach(file -> selectedFiles.remove(file.id()));
            }
        } else if (binding.equals("transfer.states")) includeState = Boolean.parseBoolean(value);
        else if (binding.equals("transfer.resources")) {
            includeResources = Boolean.parseBoolean(value);
            if (includeResources) includeDatabases = true;
        } else if (preview != null && binding.startsWith("transfer.database-path.")) {
            long id = Long.parseLong(binding.substring("transfer.database-path.".length()));
            if (includeDatabases && preview.databasePaths().stream().anyMatch(path -> path.id() == id)) databasePaths.put(id, value);
        } else if (preview != null && binding.startsWith("transfer.file.")) {
            var group = preview.files().get(Integer.parseInt(binding.substring("transfer.file.".length())));
            if (Boolean.parseBoolean(value) && (!group.requiresDatabase() || includeDatabases)) selectedFiles.add(group.id());
            else selectedFiles.remove(group.id());
        }
        else if (preview != null && binding.startsWith("transfer.select.")) {
            var field = preview.changes().get(Integer.parseInt(binding.substring("transfer.select.".length()))).field();
            if (Boolean.parseBoolean(value)) selected.add(field); else selected.remove(field);
        } else if (preview != null && binding.startsWith("transfer.path.")) {
            var field = preview.changes().get(Integer.parseInt(binding.substring("transfer.path.".length()))).field();
            paths.put(field, value);
        } else return false;
        notice = "";
        return true;
    }

    private void submit() {
        if (model.owner.busy()) return;
        var capability = model.host.environmentTransfer().orElseThrow();
        char[] secret = password.clone();
        queuedPassword = secret;
        long submittedGeneration = generation;
        boolean export = exporting;
        String path = archivePath;
        boolean credentials = includeCredentials;
        boolean databases = includeDatabases;
        boolean states = includeState;
        boolean resources = includeResources;
        var files = Set.copyOf(selectedFiles);
        var confirmed = preview;
        Set<DesktopEnvironmentTransfer.FieldKey> fields = Set.copyOf(selected);
        Map<DesktopEnvironmentTransfer.FieldKey, String> mapping = new LinkedHashMap<>(paths);
        mapping.keySet().retainAll(fields);
        Map<Long, String> mappedDatabasePaths = databases ? Map.copyOf(databasePaths) : Map.of();
        model.owner.runBusy(() -> {
            try {
                synchronized (model.owner) {
                    if (generation != submittedGeneration) return;
                    queuedPassword = null;
                    operationThread = Thread.currentThread();
                }
                DesktopEnvironmentTransfer.Preview inspected = null;
                if (confirmed != null) {
                    capability.importConfiguration(confirmed.token(), fields, mapping, secret, databases, files, mappedDatabasePaths);
                } else if (export) {
                    capability.exportConfiguration(Path.of(path), secret, credentials, databases, states, resources);
                } else {
                    inspected = capability.previewConfiguration(Path.of(path), secret);
                }
                synchronized (model.owner) {
                    if (generation != submittedGeneration || !model.owner.isDialogOpen(ID)) return;
                    if (confirmed != null) {
                        clearPassword();
                        notice = "transfer.restart-pending";
                    } else if (export) {
                        clearPassword();
                        model.owner.showDialog(ID, "transfer.title", "transfer.exported", DesktopUiDocument.DialogStyle.SUCCESS);
                    } else {
                        preview = inspected;
                        selected.clear();
                        inspected.changes().forEach(change -> selected.add(change.field()));
                    }
                }
            } catch (Exception failed) {
                synchronized (model.owner) {
                    if (generation == submittedGeneration) notice = "transfer.failed";
                }
            } finally {
                Arrays.fill(secret, '\0');
                synchronized (model.owner) {
                    if (operationThread == Thread.currentThread()) operationThread = null;
                    if (queuedPassword == secret) queuedPassword = null;
                }
            }
        });
    }

    void dismissed(String id) { if (ID.equals(id)) clear(); }
    void clear() {
        generation++;
        if (operationThread != null) operationThread.interrupt();
        if (queuedPassword != null) Arrays.fill(queuedPassword, '\0');
        queuedPassword = null;
        clearPassword();
        preview = null;
        selected.clear();
        paths.clear();
        databasePaths.clear();
        includeCredentials = false;
        includeDatabases = false;
        includeState = false;
        includeResources = false;
        selectedFiles.clear();
        notice = "";
    }
    private void clearPassword() { Arrays.fill(password, '\0'); password = new char[0]; passwordRevision++; }
}
