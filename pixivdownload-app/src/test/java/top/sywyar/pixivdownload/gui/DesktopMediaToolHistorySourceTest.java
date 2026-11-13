package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopMediaToolHistorySourceTest {
    @Test void httpReceiptAndPersistedCompletionUseTheSameExecutionId(@TempDir Path directory) throws Exception {
        var registry = new top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry();
        var history = new DesktopToolHistory(directory);
        var source = mock(DesktopMediaTool.CompletionSource.class);
        var callback = new AtomicReference<Consumer<DesktopMediaTool.Status>>();
        var running = new DesktopMediaTool.Status("running", 1, 0, 0, List.of());
        when(source.status()).thenReturn(running);
        when(source.start(eq("preview"), any())).thenAnswer(call -> {
            callback.set(call.getArgument(1));
            return new DesktopMediaTool.Result<>(running, null);
        });
        registry.register(new top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityOwner("independent", "independent", 1, 1),
                new DesktopMediaTool.Description(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.key("tool"), "independent", "png", "gif"), source);
        var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new top.sywyar.pixivdownload.gui.media.MediaToolController(registry, history,
                        new ToolApiRequests(messages, mock(top.sywyar.pixivdownload.i18n.WebI18nService.class)))).build();
        var body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/tools/media/tasks")
                .contentType("application/json").content("{\"identity\":{\"pluginId\":\"independent\",\"packageId\":\"independent\",\"generation\":1,\"publication\":1},\"token\":\"preview\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        var id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body).get("executionId").asString();
        callback.get().accept(new DesktopMediaTool.Status("completed", 1, 1, 0, List.of()));
        assertEquals(java.util.UUID.fromString(id), new DesktopToolHistory(directory).entries().getFirst().executionId());
    }
    @Test
    @DisplayName("未知提供者经真实注册与桌面入口留下精确历史，撤回后旧句柄不能提交")
    void independentSourceRunsThroughHost(@TempDir Path directory) throws Exception {
        var registry = new top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry();
        var invocations = new top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry();
        var adapter = new top.sywyar.pixivdownload.plugin.lifecycle.capability.DesktopMediaToolCapabilityAdapter(registry, invocations);
        var registrar = new top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar(
                List.of(), List.of(), List.of(adapter), invocations);
        var source = new IndependentSource();
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
             var paths = mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class);
             var backend = mockStatic(BackendLifecycleManager.class)) {
            paths.when(top.sywyar.pixivdownload.config.RuntimeFiles::guiStateDirectory).thenReturn(directory);
            paths.when(top.sywyar.pixivdownload.config.RuntimeFiles::logDirectory).thenReturn(directory.resolve("logs"));
            backend.when(() -> BackendLifecycleManager.requiredBean(top.sywyar.pixivdownload.gui.media.DesktopMediaToolRegistry.class))
                    .thenReturn(registry);
            context.registerBean(DesktopMediaTool.Source.class, () -> source);
            context.refresh();
            var owner = registrar.allocateOwner("independent", "custom-media", 7);
            registrar.prepareInto(owner, context);
            var publication = registrar.publish(owner);
            var host = new AppDesktopUiHost(0, mock(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigFile.class));
            var identity = host.mediaTools().get(0).identity();
            var handle = host.mediaTool(identity);
            handle.start("token").valueOrThrow();
            var drain = registrar.withdraw(publication).orElseThrow();
            assertThrows(RuntimeException.class, () -> handle.start("token"));
            source.completion.accept(new DesktopMediaTool.Status("cancelled", 2, 1, 0, List.of()));
            source.completion.accept(new DesktopMediaTool.Status("completed", 2, 2, 0, List.of()));
            var restarted = new AppDesktopUiHost(0, mock(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.ConfigFile.class));
            assertEquals(1, restarted.toolHistory().size());
            var record = restarted.toolHistory().get(0);
            assertEquals(identity, record.sourceIdentity());
            assertNotNull(record.executionId());
            assertEquals(DesktopUiToolHost.ToolOutcome.CANCELLED, record.outcome());
            assertEquals(1, source.starts);
            assertTrue(drain.await(java.time.Duration.ofSeconds(1)));
            registrar.retireDrained(drain);
            registrar.acknowledgeRetired(drain);
            assertTrue(registrar.releaseRetirementProof(drain));
        }
    }

    private static final class IndependentSource implements DesktopMediaTool.CompletionSource {
        private Consumer<DesktopMediaTool.Status> completion;
        private int starts;
        public DesktopMediaTool.Description description() {
            return new DesktopMediaTool.Description(top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText.raw("Independent"), "sample", "original", "webp");
        }
        public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token, Consumer<DesktopMediaTool.Status> completion) {
            starts++;
            this.completion = completion;
            return new DesktopMediaTool.Result<>(new DesktopMediaTool.Status("running", 2, 0, 0, List.of()), null);
        }
        public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request request) { throw new AssertionError(); }
        public DesktopMediaTool.Status status() { throw new AssertionError("No polling"); }
        public void cancel() { throw new AssertionError(); }
        public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() { throw new AssertionError(); }
    }

    @Test
    @DisplayName("无界面轮询时仍持久化精确执行终态，重复通知不重复记录")
    void recordsCompletionWithoutPolling(@TempDir Path directory) {
        var source = mock(DesktopMediaTool.CompletionSource.class);
        var callback = new AtomicReference<Consumer<DesktopMediaTool.Status>>();
        when(source.start(eq("token"), any())).thenAnswer(call -> {
            callback.set(call.getArgument(1));
            return new DesktopMediaTool.Result<>(new DesktopMediaTool.Status("running", 4, 0, 0, List.of()), null);
        });
        var history = new DesktopToolHistory(directory);
        var wrapper = new DesktopMediaToolHistorySource(new DesktopMediaTool.Identity("independent", "sample", 9, 3),
                source, history);
        wrapper.start("token").valueOrThrow();
        assertTrue(history.entries().isEmpty());
        var cancelled = new DesktopMediaTool.Status("cancelled", 4, 2, 1, List.of(new DesktopMediaTool.Failure(42, 0)));
        callback.get().accept(cancelled);
        callback.get().accept(cancelled);
        var entries = new DesktopToolHistory(directory).entries();
        assertEquals(1, entries.size());
        assertEquals(DesktopUiToolHost.ToolId.MEDIA_MAINTENANCE, entries.get(0).toolId());
        assertEquals(DesktopUiToolHost.ToolOutcome.CANCELLED, entries.get(0).outcome());
        assertEquals(2, entries.get(0).processedCount());
        assertEquals(1, entries.get(0).changedCount());
        assertEquals(1, entries.get(0).failedCount());
        assertNotNull(entries.get(0).executionId());
        assertEquals(new DesktopMediaTool.Identity("independent", "sample", 9, 3), entries.get(0).sourceIdentity());
        verify(source, never()).status();
    }
}
