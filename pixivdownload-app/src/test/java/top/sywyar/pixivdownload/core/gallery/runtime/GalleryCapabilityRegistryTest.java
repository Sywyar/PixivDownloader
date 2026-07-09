package top.sywyar.pixivdownload.core.gallery.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.GalleryCapabilityContributionAdapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("统一画廊 capability 注册中心")
class GalleryCapabilityRegistryTest {

    @Test
    @DisplayName("同一 Bean 的投影与详情端口按 owner 原子注册和注销")
    void registersAndUnregistersBothPortsAtomically() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        GalleryCapabilityContributionAdapter adapter = new GalleryCapabilityContributionAdapter(registry);
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("provider", TestProvider.class,
                    () -> new TestProvider("mixed", "source-a", GalleryKind.IMAGE, "work"));
            child.refresh();

            adapter.register("owner-a", child);

            GalleryCapabilityRegistry.Snapshot registered = registry.snapshot();
            assertThat(registered.projectionProviders()).hasSize(1);
            assertThat(registered.workProviders()).hasSize(1);
            assertThat(registry.resolveProjections(GalleryKind.IMAGE, "source-a")).hasSize(1);
            assertThat(registry.resolveWork("source-a", "work")).isPresent();

            adapter.unregister("owner-a");
            GalleryCapabilityRegistry.Snapshot removed = registry.snapshot();
            assertThat(removed.projectionProviders()).isEmpty();
            assertThat(removed.workProviders()).isEmpty();
        }
    }

    @Test
    @DisplayName("冲突注册失败时投影与详情快照均保持原 generation")
    void conflictDoesNotPartiallyPublishEitherPort() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        TestProvider original = new TestProvider("original", "source-a", GalleryKind.IMAGE, "work");
        registry.register("owner-a", List.of(original), List.of(original));
        GalleryCapabilityRegistry.Snapshot before = registry.snapshot();
        TestProvider conflicting = new TestProvider("conflicting", "source-a", GalleryKind.IMAGE, "other-work");

        assertThatThrownBy(() -> registry.register(
                "owner-b", List.of(conflicting), List.of(conflicting)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery projection route");

        assertThat(registry.snapshot()).isSameAs(before);
        assertThat(registry.resolveWork("source-a", "other-work")).isEmpty();
        assertThat(registry.resolveWork("source-a", "work")).isPresent();
    }

    @Test
    @DisplayName("描述符读取异常被隔离为诊断而不破坏其它 provider")
    void descriptorFailureIsIsolated() {
        GalleryProjectionProvider broken = new GalleryProjectionProvider() {
            @Override
            public String providerId() {
                return "broken";
            }

            @Override
            public List<GalleryProjectionDescriptor> projections() {
                throw new IllegalStateException("boom");
            }

            @Override
            public GalleryProjectionPage page(GalleryProjectionQuery query) {
                return GalleryProjectionPage.empty();
            }

            @Override
            public long count(GalleryProjectionQuery query) {
                return 0;
            }
        };
        TestProvider healthy = new TestProvider("healthy", "source-b", GalleryKind.NOVEL, "novel");

        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(
                List.of(broken, healthy), List.of(healthy));

        assertThat(registry.resolveProjections(GalleryKind.NOVEL, "source-b")).hasSize(1);
        assertThat(registry.snapshot().diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .contains("projection-descriptors-failed");
    }

    private static final class TestProvider implements GalleryProjectionProvider, GalleryWorkProvider {
        private final String providerId;
        private final String sourceId;
        private final GalleryKind kind;
        private final String namespace;

        private TestProvider(String providerId, String sourceId, GalleryKind kind, String namespace) {
            this.providerId = providerId;
            this.sourceId = sourceId;
            this.kind = kind;
            this.namespace = namespace;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public List<GalleryProjectionDescriptor> projections() {
            return List.of(new GalleryProjectionDescriptor(
                    sourceId, kind, "gallery", "source.test", 0, Map.of()));
        }

        @Override
        public GalleryProjectionPage page(GalleryProjectionQuery query) {
            return GalleryProjectionPage.empty();
        }

        @Override
        public long count(GalleryProjectionQuery query) {
            return 0;
        }

        @Override
        public List<GalleryWorkDescriptor> works() {
            return List.of(new GalleryWorkDescriptor(sourceId, namespace));
        }

        @Override
        public Optional<GalleryWork> find(GalleryWorkKey key) {
            return Optional.empty();
        }
    }
}
