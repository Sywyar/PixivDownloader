package top.sywyar.pixivdownload.gui.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.DesktopMediaToolCapabilityAdapter;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DesktopMediaToolRegistryTest {
    @Test
    @DisplayName("同步通知须等待提交确认，拒绝与异常不留下终态，观察者失败不改变接纳结果")
    void completionWaitsForAdmission() throws Exception {
        var invocations = new ExternalCapabilityInvocationRegistry();
        var registry = new DesktopMediaToolRegistry();
        var adapter = new DesktopMediaToolCapabilityAdapter(registry, invocations);
        var registrar = new PluginCapabilityContributionRegistrar(List.of(), List.of(), List.of(adapter), invocations);
        var source = org.mockito.Mockito.mock(DesktopMediaTool.CompletionSource.class);
        org.mockito.Mockito.when(source.description()).thenReturn(new DesktopMediaTool.Description(
                DesktopUiText.raw("Independent"), "sample", "original", "webp"));
        var delivered = new AtomicInteger();
        org.mockito.Mockito.when(source.start(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    java.util.function.Consumer<DesktopMediaTool.Status> completion = call.getArgument(1);
                    completion.accept(new DesktopMediaTool.Status("running", 2, 0, 0, List.of()));
                    completion.accept(new DesktopMediaTool.Status("completed", 2, 2, 0, List.of()));
                    completion.accept(new DesktopMediaTool.Status("cancelled", 2, 1, 0, List.of()));
                    assertThat(delivered).hasValue(0);
                    return switch ((String) call.getArgument(0)) {
                        case "reject" -> new DesktopMediaTool.Result<>(null, DesktopUiText.raw("Rejected"));
                        case "throw" -> throw new IllegalStateException("provider failed");
                        default -> new DesktopMediaTool.Result<>(new DesktopMediaTool.Status("running", 2, 0, 0, List.of()), null);
                    };
                });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DesktopMediaTool.Source.class, () -> source);
            context.refresh();
            var owner = registrar.allocateOwner("independent", "independent", 3);
            registrar.prepareInto(owner, context);
            var publication = registrar.publish(owner);
            var handle = (DesktopMediaTool.CompletionSource) registry.source(registry.tools().get(0).identity());
            java.util.function.Consumer<DesktopMediaTool.Status> observer = status -> {
                assertThat(status.state()).isEqualTo("completed");
                delivered.incrementAndGet();
                throw new IllegalStateException("observer failed");
            };
            assertThat(handle.start("reject", observer).error()).isNotNull();
            assertThatThrownBy(() -> handle.start("throw", observer)).isInstanceOf(RuntimeException.class);
            assertThat(delivered).hasValue(0);
            assertThat(handle.start("accept", observer).error()).isNull();
            assertThat(delivered).hasValue(1);
            var drain = registrar.withdraw(publication).orElseThrow();
            assertThat(drain.await(java.time.Duration.ofSeconds(1))).isTrue();
            registrar.retireDrained(drain);
            registrar.acknowledgeRetired(drain);
            assertThat(registrar.releaseRetirementProof(drain)).isTrue();
        }
    }

    @Test
    @DisplayName("完成通知通过精确 publication 代理交付，撤回后不能启动新任务")
    void completionCrossesPublicationBoundary() throws Exception {
        var invocations = new ExternalCapabilityInvocationRegistry();
        var registry = new DesktopMediaToolRegistry();
        var adapter = new DesktopMediaToolCapabilityAdapter(registry, invocations);
        var registrar = new PluginCapabilityContributionRegistrar(List.of(), List.of(), List.of(adapter), invocations);
        var source = org.mockito.Mockito.mock(DesktopMediaTool.CompletionSource.class);
        org.mockito.Mockito.when(source.description()).thenReturn(new DesktopMediaTool.Description(
                DesktopUiText.raw("Independent"), "sample", "original", "webp"));
        var completion = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<DesktopMediaTool.Status>>();
        org.mockito.Mockito.when(source.start(org.mockito.ArgumentMatchers.eq("token"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    completion.set(call.getArgument(1));
                    return new DesktopMediaTool.Result<>(new DesktopMediaTool.Status("running", 2, 0, 0, List.of()), null);
                });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DesktopMediaTool.Source.class, () -> source);
            context.refresh();
            var owner = registrar.allocateOwner("independent", "independent", 3);
            registrar.prepareInto(owner, context);
            var publication = registrar.publish(owner);
            var handle = (DesktopMediaTool.CompletionSource) registry.source(registry.tools().get(0).identity());
            var result = new java.util.concurrent.CompletableFuture<DesktopMediaTool.Status>();
            var delivered = new AtomicInteger();
            handle.start("token", status -> { delivered.incrementAndGet(); result.complete(status); }).valueOrThrow();
            var drain = registrar.withdraw(publication).orElseThrow();
            assertThatThrownBy(() -> handle.start("token", result::complete)).isInstanceOf(RuntimeException.class);
            completion.get().accept(new DesktopMediaTool.Status("cancelled", 2, 1, 0, List.of()));
            assertThat(result.get(1, java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("cancelled");
            assertThat(drain.await(java.time.Duration.ofSeconds(1))).isTrue();
            registrar.retireDrained(drain);
            registrar.acknowledgeRetired(drain);
            assertThat(registrar.releaseRetirementProof(drain)).isTrue();
            completion.get().accept(new DesktopMediaTool.Status("completed", 2, 2, 0, List.of()));
            assertThat(delivered).hasValue(1);
        }
    }

    @Test
    @DisplayName("工具随活动 publication 发布，撤回后旧命令不能调用替代来源")
    void commandsAreBoundToExactPublication() throws Exception {
        var invocations = new ExternalCapabilityInvocationRegistry();
        var registry = new DesktopMediaToolRegistry();
        var adapter = new DesktopMediaToolCapabilityAdapter(registry, invocations);
        var registrar = new PluginCapabilityContributionRegistrar(List.of(), List.of(), List.of(adapter), invocations);
        var starts = new AtomicInteger();
        assertThat(registry.tools()).isEmpty();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DesktopMediaTool.Source.class, () -> source(starts));
            context.refresh();
            var owner = registrar.allocateOwner("sample", "sample", 1);
            registrar.prepareInto(owner, context);
            var publication = registrar.publish(owner);
            var identity = registry.tools().get(0).identity();
            var handle = registry.source(identity);
            assertThat(handle.start("invalid").error()).isEqualTo(
                    DesktopUiText.plugin("batch", "media.error.preview-required", "preview-required"));
            handle.start("token");
            assertThat(starts).hasValue(1);
            var drain = registrar.withdraw(publication).orElseThrow();
            assertThatThrownBy(() -> handle.start("token")).isInstanceOf(RuntimeException.class);
            assertThat(drain.await(java.time.Duration.ofSeconds(1))).isTrue();
            registrar.retireDrained(drain);
            assertThat(registry.tools()).isEmpty();
            assertThatThrownBy(() -> registry.source(identity)).isInstanceOf(DesktopMediaTool.OperationException.class);
            registrar.acknowledgeRetired(drain);
            assertThat(registrar.releaseRetirementProof(drain)).isTrue();
            var replacement = registrar.allocateOwner("sample", "sample", 2);
            registrar.prepareInto(replacement, context);
            var next = registrar.publish(replacement);
            adapter.withdraw(publication.owner());
            assertThat(registry.tools()).hasSize(1);
            assertThatThrownBy(() -> handle.start("token")).isInstanceOf(RuntimeException.class);
            assertThat(starts).hasValue(1);
            registry.source(registry.tools().get(0).identity()).start("token");
            assertThat(starts).hasValue(2);
            var nextDrain = registrar.withdraw(next).orElseThrow();
            registrar.retireDrained(nextDrain);
            registrar.acknowledgeRetired(nextDrain);
            assertThat(registrar.releaseRetirementProof(nextDrain)).isTrue();
        }
    }
    private static DesktopMediaTool.Source source(AtomicInteger starts) {
        return new DesktopMediaTool.Source() {
            public DesktopMediaTool.Description description() {
                return new DesktopMediaTool.Description(DesktopUiText.raw("Media"), "sample", "original", "webp");
            }
            public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request request) {
                return new DesktopMediaTool.Result<>(new DesktopMediaTool.Preview("token", List.of(), 0, 0, false), null);
            }
            public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token) {
                if (token.equals("invalid")) return new DesktopMediaTool.Result<>(null,
                        DesktopUiText.plugin("batch", "media.error.preview-required", "preview-required"));
                starts.incrementAndGet();
                return new DesktopMediaTool.Result<>(status(), null);
            }
            public DesktopMediaTool.Status status() { return new DesktopMediaTool.Status("idle", 0, 0, 0, List.of()); }
            public void cancel() {}
            public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() { return new DesktopMediaTool.Result<>(new DesktopMediaTool.Report("ffmpeg", "system", List.of()), null); }
        };
    }
}
