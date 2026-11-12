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
    @Test @DisplayName("真实工具页按 ID 预览、执行与取消，临时输入不回写配置")
    void toolsWorkflow() throws Exception {
        var request = new AtomicReference<Request>();
        var phase = new AtomicReference<>(Phase.READY);
        var id = UUID.randomUUID();
        var starts = new AtomicInteger();
        var scanFailure = new AtomicReference<VariableException>();
        LocalArtworkImports service = new LocalArtworkImports() {
            public List<Preset> presets() { return List.of(new Preset("Pictures", "D:/{artwork_id}/", true)); }
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
