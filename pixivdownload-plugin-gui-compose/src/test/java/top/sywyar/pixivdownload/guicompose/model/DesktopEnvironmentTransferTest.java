package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 配置归档交互")
class DesktopEnvironmentTransferTest {
    @Test @DisplayName("导出密码不进入快照和普通表单，调用结束清除副本，目标和选项正确传递")
    void exportsWithoutPublishingPassword() throws Exception {
        var password = new AtomicReference<char[]>();
        var destination = new AtomicReference<Path>();
        var credentials = new AtomicReference<Boolean>();
        var databaseOption = new AtomicReference<Boolean>();
        var transfer = new Fixture() {
            @Override public RestoreState restoreState() { return new RestoreState(Status.SUCCEEDED, ""); }
            @Override public void exportConfiguration(Path path, char[] secret, boolean include, boolean databases, boolean states, boolean resources) {
                assertArrayEquals("archive secret".toCharArray(), secret);
                assertTrue(states);
                assertTrue(resources);
                destination.set(path); credentials.set(include); password.set(secret); databaseOption.set(databases);
            }
        };
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of("environmentTransfer", args -> Optional.of(transfer)))) {
            event(model, "transfer.export", null);
            assertTrue(model.snapshot().document().dialogs().toString().contains("transfer.status.succeeded"));
            event(model, "transfer.archive", "fixture.pdbackup");
            event(model, "transfer.credentials", "true");
            event(model, "transfer.databases", "true");
            event(model, "transfer.states", "true");
            event(model, "transfer.resources", "true");
            event(model, "transfer.password", "archive secret");
            assertFalse(model.snapshot().toString().contains("archive secret"));
            event(model, "transfer.submit", null);
            DesktopSnapshotAwaiter.await(model, snapshot -> snapshot.document().dialogs().stream()
                    .anyMatch(dialog -> dialog.content().toString().contains("transfer.exported")));
            assertEquals(Path.of("fixture.pdbackup"), destination.get());
            assertEquals(true, credentials.get());
            assertEquals(true, databaseOption.get());
            assertArrayEquals(new char["archive secret".length()], password.get());
        }
    }

    @Test @DisplayName("导入预览和字段选择经宿主执行，未保存草稿阻止导入而保持原值")
    void previewsAndImportsSelectionWithoutReplacingDrafts() throws Exception {
        var imported = new AtomicReference<Set<DesktopEnvironmentTransfer.FieldKey>>();
        var path = new DesktopEnvironmentTransfer.FieldKey("host", "download.root-folder");
        var secret = new DesktopEnvironmentTransfer.FieldKey("fixture", "fixture.token");
        var file = new DesktopEnvironmentTransfer.FieldKey("fixture", "resources");
        var transfer = new Fixture() {
            @Override public Preview previewConfiguration(Path archive, char[] password) {
                return new Preview("fixture-preview", "fixture", true, List.of(
                        new Change(path, false, true, "old", "source"), new Change(secret, true, false, "", "")), Set.of(), List.of(new DatabaseStore("host", 42, true)), "",
                        List.of(new FileGroup(file, "RESOURCE", 2, 123, true)), Set.of(), List.of(new DatabasePath(0, "old root")),
                        List.of(new PluginPackage("fixture", "source-version", false, "")),
                        List.of(new RepositoryLocation("fixture", "https://example.invalid/repository.json", "")));
            }
            @Override public void importConfiguration(String token, Set<FieldKey> fields, Map<FieldKey, String> paths, char[] password, boolean databases, Set<FieldKey> files, Map<Long, String> databasePaths) {
                assertEquals("fixture-preview", token);
                assertEquals(Map.of(path, "target"), paths);
                assertTrue(databases);
                assertEquals(Set.of(file), files);
                assertEquals(Map.of(0L, "new media root"), databasePaths);
                imported.set(fields);
            }
        };
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of("environmentTransfer", args -> Optional.of(transfer)))) {
            event(model, "transfer.import", null);
            event(model, "transfer.archive", "fixture.pdbackup");
            event(model, "transfer.submit", null);
            DesktopSnapshotAwaiter.awaitEnabled(model, "transfer.select.0");
            assertTrue(model.snapshot().document().dialogs().toString().contains("transfer.inventory-package"));
            assertTrue(model.snapshot().document().dialogs().toString().contains("https://example.invalid/repository.json"));
            event(model, "transfer.select.1", "false");
            event(model, "transfer.file.0", "true");
            event(model, "transfer.databases", "true");
            event(model, "transfer.database-path.0", "new media root");
            event(model, "transfer.file.0", "true");
            event(model, "transfer.path.0", "target");
            event(model, "transfer.submit", null);
            DesktopSnapshotAwaiter.await(model, snapshot -> snapshot.document().dialogs().stream()
                    .anyMatch(dialog -> dialog.content().toString().contains("transfer.restart-pending")));
            assertEquals(Set.of(path), imported.get());
        }
        var stored = new HashMap<String, String>(Map.of("app.theme", "light"));
        try (var model = DesktopConfigurationControllerTest.model(stored, Map.of("environmentTransfer", args -> Optional.of(transfer)))) {
            synchronized (model) {
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.SELECTION,
                        "interface.theme.input", DesktopUiNode.Value.selection("dark")));
            }
            assertEquals("dark", model.themePreference());
            event(model, "transfer.import", null);
            assertTrue(model.snapshot().document().dialogs().toString().contains("transfer.unsaved"), model.snapshot().document().dialogs().toString());
            assertEquals("dark", model.themePreference());
            assertEquals("light", stored.get("app.theme"));
        }
    }

    private static void event(ComposeDesktopUiModel model, String id, String value) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(value == null ? DesktopUiNode.EventType.ACTIVATE : DesktopUiNode.EventType.CHANGE,
                    id, value == null ? DesktopUiNode.Value.empty()
                            : Set.of("transfer.credentials", "transfer.databases", "transfer.states", "transfer.resources").contains(id)
                            || id.startsWith("transfer.select.") || id.startsWith("transfer.file.")
                            ? new DesktopUiNode.Value(DesktopUiNode.ValueKind.BOOLEAN, List.of(value)) : DesktopUiNode.Value.text(value)));
        }
    }
    private static class Fixture implements DesktopEnvironmentTransfer {
        public Installation installation() { return new Installation(InstallationKind.UNSUPPORTED, "", ""); }
        public void exportConfiguration(Path path, char[] secret, boolean include, boolean databases, boolean states, boolean resources) { throw new AssertionError(); }
        public Preview previewConfiguration(Path archive, char[] password) { throw new AssertionError(); }
        public void importConfiguration(String token, Set<FieldKey> fields, Map<FieldKey, String> paths, char[] password, boolean databases, Set<FieldKey> files, Map<Long, String> databasePaths) { throw new AssertionError(); }
        public RestoreState restoreState() { return new RestoreState(Status.NONE, ""); }
    }
}
