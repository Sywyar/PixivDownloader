package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.*;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopLocalImportControllerTest {
    @Test @DisplayName("相同输入再次扫描按新任务读取样本，扫描失败与取消不复用旧预览")
    void rescansRefreshSamplesAndDiscardFailedPreviews() throws Exception {
        var id = new AtomicReference<UUID>();
        var phase = new AtomicReference<>(Phase.READY);
        var scans = new AtomicInteger();
        var fail = new AtomicBoolean();
        var itemRequests = new java.util.concurrent.CopyOnWriteArrayList<UUID>();
        var started = new AtomicReference<UUID>();
        LocalArtworkImports service = new LocalArtworkImports() {
            public List<Preset> presets() { return List.of(new Preset("D:/{artwork_id}/")); }
            public Snapshot scan(Request input) {
                if (fail.get()) throw new IllegalStateException("IMPORT_IO_FAILED");
                id.set(UUID.randomUUID());
                phase.set(scans.incrementAndGet() == 2 ? Phase.SCANNING : Phase.READY);
                return status(id.get());
            }
            public Snapshot status(UUID token) {
                assertEquals(id.get(), token);
                return new Snapshot(token, phase.get(), 1, 1, 0, 0, 0, 0, false, List.of());
            }
            public List<Item> items(UUID token, int offset, int limit) {
                assertEquals(id.get(), token);
                itemRequests.add(token);
                return List.of(new Item(41 + scans.get(), List.of(new File("D:/sample.png", null, Map.of())), Disposition.NEW, ""));
            }
            public Snapshot start(UUID token) { started.set(token); phase.set(Phase.IMPORTING); return status(token); }
            public Snapshot cancel(UUID token) { phase.set(Phase.CANCELLED); return status(token); }
        };
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(),
                Map.of("localArtworkImports", args -> Optional.of(service)))) {
            activate(model, "scan");
            await(() -> sampleArtwork(model).equals("42"));
            UUID first = id.get();
            activate(model, "scan");
            await(() -> button(model, "cancel").enabled());
            assertEquals("", sampleArtwork(model));
            UUID second = id.get();
            assertNotEquals(first, second);
            phase.set(Phase.READY);
            await(() -> sampleArtwork(model).equals("43"));
            assertTrue(itemRequests.contains(second));
            activate(model, "start");
            await(() -> second.equals(started.get()));
            activate(model, "cancel");
            await(() -> button(model, "scan").enabled());
            activate(model, "scan");
            await(() -> sampleArtwork(model).equals("44"));
            fail.set(true);
            activate(model, "scan");
            await(() -> button(model, "scan").enabled());
            assertEquals("", sampleArtwork(model));
            assertFalse(button(model, "start").enabled());
        }
    }

    private static String sampleArtwork(ComposeDesktopUiModel model) {
        return DesktopMediaToolsControllerTest.nodes(model).filter(node -> node.id().equals("local-import.sample.0"))
                .map(DesktopUiNode.Text.class::cast).map(node -> node.text().arguments().getFirst()).findFirst().orElse("");
    }

    @Test @DisplayName("工具页预设独立保存，失败保留编辑，取消不写入且保留其他设置草稿")
    @SuppressWarnings("unchecked")
    void savedPresetsWorkflow() throws Exception {
        var stored = new HashMap<>(Map.of("local-import.presets", "original", "fixture.value", "saved"));
        var fail = new AtomicBoolean(true);
        var available = new AtomicBoolean(true);
        var presets = new AtomicReference<>(List.of(new Preset("D:/{artwork_id}/")));
        var draft = new AtomicReference<>(presets.get());
        var field = new top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution(
                "fixture.value", "fixture", "Fixture", "", top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType.STRING,
                "", 0, false, top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect.BACKEND_RESTART);
        var service = java.lang.reflect.Proxy.newProxyInstance(LocalArtworkImports.class.getClassLoader(),
                new Class<?>[]{LocalArtworkImports.class}, (proxy, method, args) -> {
                    if (method.getName().equals("presets")) return presets.get();
                    throw new AssertionError(method.getName());
                });
        try (var model = DesktopConfigurationControllerTest.model(stored, Map.of(
                "coreConfigFields", args -> List.of(field),
                "coreConfigGroups", args -> List.of(new top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroupContribution("fixture", "Fixture", 0)),
                "localArtworkImports", args -> available.get() ? Optional.of(service) : Optional.empty(),
                "parseLocalImportPresets", args -> presets.get(),
                "formatLocalImportPresets", args -> { draft.set((List<Preset>) args[0]); return "updated"; },
                "saveLocalImportPresets", args -> {
                    if (fail.get()) throw new java.io.UncheckedIOException(new java.io.IOException("fixture write failure"));
                    stored.put("local-import.presets", (String) args[0]); presets.set(draft.get()); return null;
                }
        ))) {
            dispatch(model, DesktopUiNode.EventType.CHANGE, "config.app.fixture.value.input", "unsaved");
            activate(model, "presets");
            await(() -> model.isDialogOpen("import-presets") && !model.busy());
            dispatch(model, DesktopUiNode.EventType.ACTIVATE, "import-presets.add", "");
            dispatch(model, DesktopUiNode.EventType.CHANGE, "import-presets.field.1.template", "E:/{artwork_id}/");
            dispatch(model, DesktopUiNode.EventType.ACTIVATE, "import-presets.apply", "");
            await(() -> model.snapshot().document().dialogs().stream()
                    .flatMap(dialog -> descendants(dialog.content()))
                    .anyMatch(node -> node.id().equals("import-presets.error")));
            assertEquals("original", stored.get("local-import.presets"));
            assertEquals("E:/{artwork_id}/", draft.get().get(1).template());
            fail.set(false);
            dispatch(model, DesktopUiNode.EventType.ACTIVATE, "import-presets.apply", "");
            await(() -> !model.isDialogOpen("import-presets"));
            assertEquals("updated", stored.get("local-import.presets"));
            assertEquals("saved", stored.get("fixture.value"));
            assertTrue(model.snapshot().document().pages().stream().flatMap(page -> descendants(page.content())).filter(DesktopUiNode.TextInput.class::isInstance)
                    .map(DesktopUiNode.TextInput.class::cast).anyMatch(input -> input.value().equals("unsaved")));
            activate(model, "presets");
            await(() -> model.isDialogOpen("import-presets") && !model.busy());
            dispatch(model, DesktopUiNode.EventType.ACTIVATE, "import-presets.add", "");
            dispatch(model, DesktopUiNode.EventType.ACTIVATE, "import-presets.dismiss", "");
            assertEquals(2, presets.get().size());
            assertEquals("updated", stored.get("local-import.presets"));
            available.set(false);
            model.rebuild();
            activate(model, "presets");
            await(() -> model.isDialogOpen("import-presets") && !model.busy());
            assertTrue(DesktopMediaToolsControllerTest.nodes(model).noneMatch(node -> node.id().equals("local-import.scan")));
            dispatch(model, DesktopUiNode.EventType.ACTIVATE, "import-presets.dismiss", "");
        }
    }
    private static void dispatch(ComposeDesktopUiModel model, DesktopUiNode.EventType type, String id, String value) {
        synchronized (model) { model.dispatch(model.snapshot(), new DesktopUiNode.Event(type, id,
                type == DesktopUiNode.EventType.ACTIVATE ? DesktopUiNode.Value.empty() : DesktopUiNode.Value.text(value))); }
    }
    private static java.util.stream.Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(node),
                node.childNodes().stream().flatMap(DesktopLocalImportControllerTest::descendants));
    }
    @Test @DisplayName("真实工具页按 ID 预览、执行与取消，临时输入不回写配置")
    void toolsWorkflow() throws Exception {
        var request = new AtomicReference<Request>();
        var phase = new AtomicReference<>(Phase.READY);
        var id = UUID.randomUUID();
        var starts = new AtomicInteger();
        var scanFailure = new AtomicReference<VariableException>();
        LocalArtworkImports service = new LocalArtworkImports() {
            public List<Preset> presets() { return List.of(new Preset("D:/{artwork_id}/")); }
            public Snapshot scan(Request input) {
                if (scanFailure.get() != null) throw scanFailure.get();
                request.set(input); phase.set(Phase.READY); return status(id);
            }
            public Snapshot status(UUID token) { return new Snapshot(id, phase.get(), 1, 1, 0, 0, 0, 0, false, List.of()); }
            public List<Item> items(UUID token, int offset, int limit) { return List.of(new Item(42, List.of(new File("D:/42/pic.png", null, Map.of())), Disposition.NEW, "")); }
            public Snapshot start(UUID token) { assertEquals(id, token); starts.incrementAndGet(); phase.set(Phase.IMPORTING); return status(id); }
            public Snapshot cancel(UUID token) { phase.set(Phase.CANCELLED); return status(id); }
        };
        var stored = new HashMap<String, String>();
        try (var model = DesktopConfigurationControllerTest.model(stored, Map.of("localArtworkImports", args -> Optional.of(service)))) {
            edit(model, "ids", "42, 43\n44");
            activate(model, "scan");
            await(() -> request.get() != null && button(model, "start").enabled());
            assertEquals(List.of(42L, 43L, 44L), request.get().artworkIds());
            assertTrue(request.get().templates().isEmpty());
            activate(model, "start"); await(() -> starts.get() == 1 && button(model, "cancel").enabled());
            activate(model, "cancel"); await(() -> button(model, "scan").enabled());
            assertTrue(stored.isEmpty());
            edit(model, "ids", "invalid"); activate(model, "scan");
            assertEquals(1, starts.get());
            assertTrue(DesktopMediaToolsControllerTest.nodes(model).anyMatch(n -> n.id().equals("local-import.notice")));
            for (String variable : List.of("foo", "ai")) {
                scanFailure.set(new VariableException(variable, variable.equals("ai")));
                edit(model, "ids", "42");
                edit(model, "templates", "D:/{artwork_id}_{" + variable + "}.png");
                activate(model, "scan");
                await(() -> button(model, "scan").enabled() && DesktopMediaToolsControllerTest.nodes(model)
                        .anyMatch(n -> n.id().equals("local-import.notice")));
                var notice = (DesktopUiNode.Text) DesktopMediaToolsControllerTest.nodes(model)
                        .filter(n -> n.id().equals("local-import.notice")).findFirst().orElseThrow();
                assertEquals("local-import.error." + scanFailure.get().getMessage(), notice.text().key());
                assertEquals(List.of("{" + variable + "}"), notice.text().arguments());
                assertFalse(button(model, "start").enabled());
                assertTrue(stored.isEmpty());
            }
        }
    }
    private static DesktopUiNode.Button button(ComposeDesktopUiModel model, String suffix) {
        return (DesktopUiNode.Button) DesktopMediaToolsControllerTest.nodes(model)
                .filter(n -> n.id().equals("local-import." + suffix)).findFirst().orElseThrow();
    }
    private static void edit(ComposeDesktopUiModel model, String suffix, String value) {
        synchronized (model) { model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                "local-import." + suffix, DesktopUiNode.Value.text(value))); }
    }
    private static void activate(ComposeDesktopUiModel model, String suffix) {
        await(() -> { synchronized (model) {
            if (!button(model, suffix).enabled()) return false;
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                    "local-import." + suffix, DesktopUiNode.Value.empty())); return true;
        }});
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!condition.getAsBoolean()) Thread.sleep(10); });
    }
}
