package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import static top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class DesktopFileImportControllerTest {
    static final String PREFIX = "import.fixture.1";
    static final class Fixture {
        final DesktopFileImportTool tool = new DesktopFileImportTool(new Identity("fixture", "fixture", 1, 1),
                new Description(DesktopUiText.raw("History import"), DesktopUiText.raw("Select history and original files")));
        final AtomicReference<List<DesktopFileImportTool>> tools = new AtomicReference<>(List.of(tool));
        final AtomicReference<Request> request = new AtomicReference<>();
        final AtomicReference<String> receivedSecret = new AtomicReference<>();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicReference<Completion> completion = new AtomicReference<>();
        final AtomicReference<Status> state = new AtomicReference<>(new Status(Phase.IDLE, 0, 0, 0, 0, 0, List.of()));
        final Source source = new Source() {
            public Description description() { return tool.description(); }
            public Result<Preview> inspect(Request value) {
                request.set(value); state.set(new Status(Phase.READY, 1, 0, 0, 0, 0, List.of()));
                return Result.success(new Preview("token", 2, 1, 1, 0, 0, List.of(new Item("artwork:42", 2, Disposition.CANDIDATE))));
            }
            public Result<Status> start(String token, String secret) {
                assertEquals("token", token); receivedSecret.set(secret); starts.incrementAndGet();
                state.set(new Status(Phase.RUNNING, 1, 0, 0, 0, 0, List.of())); return Result.success(state.get());
            }
            public Status status() { return state.get(); }
            public Optional<Completion> pendingCompletion() { return Optional.ofNullable(completion.get()); }
            public void acknowledgeCompletion(String id) { completion.updateAndGet(c -> c != null && c.id().equals(id) ? null : c); }
            public void cancel() { state.set(new Status(Phase.CANCELLED, 1, 0, 0, 0, 0, List.of())); }
        };
    }
    @Test
    @DisplayName("浏览器导入完成后全局提示一次，仅确认时启动回填，纯本地来源隐藏凭据")
    void completionConfirmation() throws Exception {
        completionRequestsBackfillOnlyAfterConfirmation(false);
        completionRequestsBackfillOnlyAfterConfirmation(true);
    }
    void completionRequestsBackfillOnlyAfterConfirmation(boolean accept) throws Exception {
        var completion = new AtomicReference<>(new Completion("completed-run", 3));
        var starts = new AtomicInteger();
        var executionId = UUID.randomUUID();
        var queries = new AtomicInteger();
        var tool = new DesktopFileImportTool(new Identity("local", "local", 1, 1),
                new Description(DesktopUiText.raw("Local history"), DesktopUiText.raw("Local files"), false));
        Source source = new Source() {
            public Description description() { return tool.description(); }
            public Result<Preview> inspect(Request value) { throw new AssertionError("No GUI import"); }
            public Result<Status> start(String token, String credential) { throw new AssertionError("No GUI import"); }
            public Status status() {
                if (queries.getAndIncrement() == 0) throw new IllegalStateException("temporarily unavailable");
                return new Status(Phase.IDLE, 0, 0, 0, 0, 0, List.of());
            }
            public void cancel() { throw new AssertionError("Must not cancel"); }
            public Optional<Completion> pendingCompletion() { return Optional.ofNullable(completion.get()); }
            public void acknowledgeCompletion(String id) {
                assertEquals("completed-run", id); completion.set(null);
            }
        };
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "fileImportTools", args -> List.of(tool), "fileImportTool", args -> source,
                "startBackfill", args -> { starts.incrementAndGet(); return executionId; }))) {
            await(() -> model.snapshot().document().dialogs().stream().anyMatch(d -> d.id().equals("import-completion")));
            assertEquals(0, starts.get());
            await(() -> completion.get() == null);
            assertFalse(nodes(model).anyMatch(n -> n.id().equals("import.local.1.credential")));
            synchronized (model) {
                var dialog = model.snapshot().document().dialogs().get(0);
                String action = accept ? "import-completion.start" : dialog.id();
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                        action, DesktopUiNode.Value.empty()));
            }
            if (accept) {
                await(() -> executionId.equals(model.snapshot().requestedBackfill()));
                assertFalse(model.consumeBackfillNavigation(UUID.randomUUID()));
                assertTrue(model.consumeBackfillNavigation(executionId));
                assertFalse(model.consumeBackfillNavigation(executionId));
            }
            model.rebuild();
            assertNull(model.snapshot().requestedBackfill());
            assertTrue(model.snapshot().document().dialogs().stream().noneMatch(d -> d.id().equals("import-completion")));
            assertEquals(accept ? 1 : 0, starts.get());
        }
    }
    @Test
    @DisplayName("导入后回填启动失败时保留错误提示，不请求跳转")
    void failedBackfillDoesNotNavigate() throws Exception {
        var fixture = new Fixture();
        fixture.completion.set(new Completion("done", 3));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "fileImportTools", args -> fixture.tools.get(), "fileImportTool", args -> fixture.source,
                "startBackfill", args -> { throw new IllegalStateException("fixture start rejected"); }))) {
            await(() -> model.isDialogOpen("import-completion"));
            synchronized (model) {
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE,
                        "import-completion.start", DesktopUiNode.Value.empty()));
            }
            await(() -> model.isDialogOpen("backfill.start-failed"));
            assertNull(model.snapshot().requestedBackfill());
        }
    }
    @Test @DisplayName("原生导入先预览再启动，修改输入使旧预览失效，凭据不进入视图且提交后清除")
    void nativeWorkflow() throws Exception {
        var fixture = new Fixture();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "fileImportTools", args -> fixture.tools.get(), "fileImportTool", args -> fixture.source))) {
            edit(model, ".file", "/records.json"); edit(model, ".root", "/original");
            edit(model, ".credential", "secret-value");
            assertFalse(model.snapshot().toString().contains("secret-value"));
            activate(model, ".inspect");
            await(() -> button(model, ".start").enabled());
            assertEquals(0, fixture.starts.get());
            edit(model, ".root", "/changed");
            assertFalse(button(model, ".start").enabled());
            activate(model, ".inspect");
            await(() -> button(model, ".start").enabled());
            activate(model, ".start");
            await(() -> fixture.starts.get() == 1 && button(model, ".cancel").enabled());
            assertEquals("secret-value", fixture.receivedSecret.get());
            activate(model, ".cancel");
            await(() -> button(model, ".inspect").enabled());
            activate(model, ".inspect");
            await(() -> button(model, ".start").enabled());
            activate(model, ".start");
            await(() -> fixture.starts.get() == 2);
            assertEquals("", fixture.receivedSecret.get());
            fixture.tools.set(List.of()); model.rebuild();
            assertFalse(nodes(model).anyMatch(n -> n.id().startsWith(PREFIX)));
        }
    }
    @Test @DisplayName("非法路径由界面反馈失败，返回工具清除未提交凭据")
    void invalidPathAndLeave() throws Exception {
        var fixture = new Fixture();
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "fileImportTools", args -> fixture.tools.get(), "fileImportTool", args -> fixture.source))) {
            edit(model, ".file", "x".repeat(5000)); edit(model, ".root", "/source");
            activate(model, ".inspect");
            await(() -> nodes(model).anyMatch(n -> n.id().equals(PREFIX + ".notice")));
            assertNull(fixture.request.get());
            edit(model, ".file", "/records.json"); edit(model, ".credential", "secret");
            activate(model, ".leave"); activate(model, ".inspect");
            await(() -> button(model, ".start").enabled());
            activate(model, ".start"); await(() -> fixture.starts.get() == 1);
            assertEquals("", fixture.receivedSecret.get());
        }
    }
    static Stream<DesktopUiNode> nodes(ComposeDesktopUiModel model) { return DesktopMediaToolsControllerTest.nodes(model); }
    static DesktopUiNode.Button button(ComposeDesktopUiModel model, String suffix) {
        return (DesktopUiNode.Button) nodes(model).filter(n -> n.id().equals(PREFIX + suffix)).findFirst().orElseThrow();
    }
    static void edit(ComposeDesktopUiModel model, String suffix, String value) {
        synchronized (model) { model.dispatch(model.snapshot(), new DesktopUiNode.Event(
                DesktopUiNode.EventType.CHANGE, PREFIX + suffix, DesktopUiNode.Value.text(value))); }
    }
    static void activate(ComposeDesktopUiModel model, String suffix) {
        await(() -> {
            synchronized (model) {
                if (!button(model, suffix).enabled()) return false;
                model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, PREFIX + suffix, DesktopUiNode.Value.empty()));
                return true;
            }
        });
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> { while (!condition.getAsBoolean()) Thread.sleep(10); });
    }
}
