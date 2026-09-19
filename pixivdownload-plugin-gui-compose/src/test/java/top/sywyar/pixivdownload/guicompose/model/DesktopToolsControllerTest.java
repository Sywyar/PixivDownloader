package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Function;

@DisplayName("Compose 工具表单默认值")
class DesktopToolsControllerTest {
    @Test
    @DisplayName("目录工具在工作区内打开，等待停服时互斥，关闭后恢复服务")
    void opensInlineAndRestoresServiceOnClose() throws Exception {
        List<String> calls = new ArrayList<>();
        List<Runnable> stopped = new ArrayList<>();
        Map<String, Function<Object[], Object>> overrides = new HashMap<>();
        overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
        overrides.put("stopBackend", args -> { calls.add("stop"); stopped.add((Runnable) args[0]); return true; });
        overrides.put("startBackend", args -> { calls.add("start"); ((Runnable) args[0]).run(); return true; });
        overrides.put("recordToolHistory", args -> { calls.add("history"); return null; });
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
            activate(model, "tools.folder-checker.open");
            assertTrue(model.busy());
            activate(model, "tools.folder-checker.open");
            assertEquals(List.of("stop"), calls);
            stopped.get(0).run();
            assertFalse(model.busy());
            assertTrue(model.snapshot().document().dialogs().isEmpty());
            assertTrue(model.snapshot().document().pages().stream().filter(page -> page.id().equals("tools"))
                    .flatMap(page -> descendants(page.content())).anyMatch(node -> node.id().equals("tools.active.close")));
            activate(model, "tools.active.close");
            assertEquals(List.of("stop", "history", "start"), calls);
            assertFalse(model.busy());
            assertEquals("folder", model.snapshot().document().pages().stream()
                    .filter(page -> page.id().equals("tools")).flatMap(page -> descendants(page.content()))
                    .filter(DesktopUiNode.Tabs.class::isInstance).map(DesktopUiNode.Tabs.class::cast)
                    .findFirst().orElseThrow().initialSelectedId());
        }
    }

    @Test
    @DisplayName("迁移失败仍尝试恢复服务，回填失败不停止运行中的服务")
    void restoresOnlyToolsThatPauseTheBackend() throws Exception {
        for (boolean migration : List.of(true, false)) {
            List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
            Map<String, Function<Object[], Object>> overrides = new HashMap<>();
            overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
            overrides.put("stopBackend", args -> { calls.add("stop"); ((Runnable) args[0]).run(); return true; });
            overrides.put("startBackend", args -> { calls.add("start"); ((Runnable) args[0]).run(); return true; });
            overrides.put("recordToolHistory", args -> { calls.add(args[1].toString()); return null; });
            overrides.put(migration ? "countMigrationCandidates" : "countBackfillCandidates",
                    args -> { throw new IllegalStateException("simulated database failure"); });
            try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
                activate(model, migration ? "tools.migration.run" : "tools.backfill.run");
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (model.busy() || model.snapshot().document().dialogs().stream()
                            .noneMatch(dialog -> dialog.id().equals("tools.failed"))) Thread.sleep(10);
                });
                assertEquals(migration ? List.of("stop", "FAILED", "start") : List.of("FAILED"), calls);
                assertTrue(model.snapshot().document().dialogs().stream().anyMatch(dialog -> dialog.id().equals("tools.failed")));
            }
        }
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
        }
    }

    private static java.util.stream.Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(node), node.childNodes().stream().flatMap(DesktopToolsControllerTest::descendants));
    }

    @Test
    @DisplayName("界面显示的路径默认值同时写入动作读取的表单状态")
    void storesVisiblePathDefaultsInFormState() {
        Path database = Path.of("data", "pixiv-download.db");
        Map<String, String> values = new HashMap<>();
        values.put("tools.migration.db", "custom.db");

        DesktopToolsController controller = new DesktopToolsController(
                null,
                host(database),
                "downloads",
                values
        );

        assertEquals(database.toString(), controller.form("tools.folder.db", ""));
        assertEquals("custom.db", controller.form("tools.migration.db", ""));
        assertEquals("downloads", controller.form("tools.migration.root", ""));
    }

    private static DesktopUiHost host(Path database) {
        return (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "resolveDatabasePath" -> database;
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions(
                            database.toString(),
                            "localhost",
                            8080,
                            false,
                            1000,
                            0,
                            false
                    );
                    case "loadImageClassifierSettings" -> new DesktopUiHost.ImageClassifierSettings(
                            "",
                            false,
                            "http://localhost:6999",
                            List.of()
                    );
                    default -> throw new AssertionError("unexpected DesktopUiHost call: " + method.getName());
                }
        );
    }
}
