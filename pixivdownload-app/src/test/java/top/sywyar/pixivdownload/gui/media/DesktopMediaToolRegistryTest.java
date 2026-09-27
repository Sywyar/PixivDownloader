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
