package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryImportInteractionTest {
    private static final String ID = "config.market.repository.import";
    private static final String URL = "https://repo.example/repository.json";

    @Test
    @DisplayName("核对后加入草稿，地址变化后必须重新预览并确认")
    void confirmationAddsDraftAndAddressChangesInvalidateConsent() throws Exception {
        var manual = RepositoryConfigEntry.create("manual", "", "https://manual.example/catalog", true,
                "direct-strict", 0, 0, 0, 0);
        var imported = RepositoryConfigEntry.create("sample", "", "https://repo.example/catalog", true,
                "direct-strict", 0, 0, 0, 0);
        var saved = new AtomicReference<>(List.of(manual));
        var calls = new HashMap<String, Function<Object[], Object>>(defaults());
        calls.put("readPluginRepositories", args -> saved.get());
        calls.put("previewPluginRepository", args -> preview());
        calls.put("preparePluginRepository", args -> {
            assertEquals(URL, args[0]);
            assertEquals("ab".repeat(32), args[1]);
            assertEquals(true, args[2]);
            return imported;
        });
        calls.put("writePluginRepositories", args -> {
            saved.set((List<RepositoryConfigEntry>) args[1]);
            return null;
        });
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), calls)) {
            activate(model, "config.market.repository.add");
            var tabs = nodes(model).filter(DesktopUiNode.Tabs.class::isInstance)
                    .map(DesktopUiNode.Tabs.class::cast).findFirst().orElseThrow();
            assertEquals(List.of("descriptor", "manual"), tabs.tabs().stream().map(DesktopUiNode.Tab::id).toList());
            assertEquals("descriptor", tabs.initialSelectedId());
            change(model, ID + ".url", DesktopUiNode.Value.text(URL));
            activate(model, ID + ".preview");
            await(() -> !model.busy() && nodes(model).anyMatch(node -> node.id().equals(ID + ".confirm")));
            assertFalse(button(model, ID + ".accept").enabled());
            change(model, ID + ".confirm", DesktopUiNode.Value.bool(true));
            assertTrue(button(model, ID + ".accept").enabled());
            change(model, ID + ".url", DesktopUiNode.Value.text(URL + "?changed"));
            assertFalse(nodes(model).anyMatch(node -> node.id().equals(ID + ".accept")));
            assertTrue(button(model, ID + ".preview").enabled());
            change(model, ID + ".url", DesktopUiNode.Value.text(URL));
            activate(model, ID + ".preview");
            await(() -> !model.busy() && nodes(model).anyMatch(node -> node.id().equals(ID + ".confirm")));
            change(model, ID + ".confirm", DesktopUiNode.Value.bool(true));
            activate(model, ID + ".accept");
            await(() -> !model.busy() && model.snapshot().document().dialogs().isEmpty());
            assertEquals(List.of(manual), saved.get());
            activate(model, "config.save");
            await(() -> !model.busy() && saved.get().size() == 2);
            assertEquals(List.of(manual, imported), saved.get());
        }
    }

    @Test
    @DisplayName("取消预览后异步结果不能重新打开弹窗或恢复确认")
    void dismissedPreviewCannotReopenDialogOrAddRepository() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var calls = new HashMap<String, Function<Object[], Object>>(defaults());
        calls.put("previewPluginRepository", args -> {
            started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            return preview();
        });
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), calls)) {
            activate(model, "config.market.repository.add");
            change(model, ID + ".url", DesktopUiNode.Value.text(URL));
            activate(model, ID + ".preview");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            activate(model, ID + ".cancel");
            release.countDown();
            await(() -> !model.busy());
            assertTrue(model.snapshot().document().dialogs().isEmpty());
            activate(model, "config.market.repository.add");
            assertFalse(nodes(model).anyMatch(node -> node.id().equals(ID + ".accept")));
            assertFalse(button(model, ID + ".preview").enabled());
        } finally { release.countDown(); }
    }

    static Map<String, Function<Object[], Object>> defaults() {
        return Map.of("coreConfigGroups", args -> List.of(new GuiConfigGroupContribution(
                        GuiConfigGroups.PLUGINS, "plugins", null, 1, true)),
                "coreConfigFields", args -> List.of(new GuiConfigFieldContribution("plugin-catalog.enabled",
                        GuiConfigGroups.PLUGINS, "catalog", GuiConfigFieldType.BOOL, "true", 1)),
                "officialPluginRepositoryKey", args -> null);
    }

    static RepositoryImportPreview preview() {
        return new RepositoryImportPreview(URL, "repo.example", "ab".repeat(32), "sample", "Sample",
                "publisher", "Publisher", null, null, "paged-v2", "https://repo.example/catalog", "repo.example",
                null, null, null, null, List.of("repo.example"), "DIRECT_STRICT", "direct-strict", "NONE",
                List.of(new RepositoryKeyPreview("root", "Ed25519", "ACTIVE", "Publisher", "Root",
                        "sha256:" + "cd".repeat(32), "sha256:" + "cd".repeat(32))),
                "NOT_AVAILABLE", null, List.of(), false, false, false, false, "NOT_REQUIRED", true,
                "import.executable-warning");
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
    }

    private static void change(ComposeDesktopUiModel model, String id, DesktopUiNode.Value value) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE, id, value));
    }

    private static void dispatch(ComposeDesktopUiModel model, DesktopUiNode.Event event) {
        synchronized (model) { model.dispatch(model.snapshot(), event); }
    }

    private static DesktopUiNode.Button button(ComposeDesktopUiModel model, String id) {
        return nodes(model).filter(node -> node.id().equals(id)).map(DesktopUiNode.Button.class::cast).findFirst().orElseThrow();
    }

    private static Stream<DesktopUiNode> nodes(ComposeDesktopUiModel model) {
        return model.snapshot().document().dialogs().stream().flatMap(dialog -> descendants(dialog.content()));
    }

    private static Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return Stream.concat(Stream.of(node), node.childNodes().stream().flatMap(RepositoryImportInteractionTest::descendants));
    }

    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!condition.getAsBoolean()) Thread.sleep(10); });
    }
}
