package top.sywyar.pixivdownload.core.gallery.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWork;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.GalleryCapabilityContributionAdapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

@DisplayName("统一画廊 capability 注册中心")
class GalleryCapabilityRegistryTest {

    @Test
    @DisplayName("状态发布遇到普通或致命错误时 owner 与画廊快照保持同一旧版本")
    void statePublicationFailureKeepsOwnerAndSnapshotAtomic() {
        AtomicReference<Throwable> nextFailure = new AtomicReference<>();
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(
                List.of(), List.of(), () -> throwPending(nextFailure));
        GalleryFrontendContribution first = frontend(
                "first.card", 10, Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any());
        GalleryFrontendContribution second = frontend(
                "second.card", 20, Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any());
        registry.registerPrepared("owner-a", 1L, List.of(), List.of(), List.of(first));
        GalleryCapabilityRegistry.Snapshot beforePublish = registry.snapshot();

        for (Throwable expected : List.of(
                new IllegalStateException("ordinary-publish"),
                new OutOfMemoryError("fatal-publish"),
                new ThreadDeath())) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.registerPrepared(
                    "owner-b", 2L, List.of(), List.of(), List.of(second)))).isSameAs(expected);
            assertThat(registry.snapshot()).isSameAs(beforePublish);
        }

        registry.registerPrepared("owner-b", 2L, List.of(), List.of(), List.of(second));
        GalleryCapabilityRegistry.Snapshot beforeWithdraw = registry.snapshot();
        for (Throwable expected : List.of(
                new IllegalStateException("ordinary-withdraw"),
                new OutOfMemoryError("fatal-withdraw"),
                new ThreadDeath())) {
            nextFailure.set(expected);
            assertThat(catchThrowable(() -> registry.unregisterPrepared("owner-b", 2L))).isSameAs(expected);
            assertThat(registry.snapshot()).isSameAs(beforeWithdraw);
        }
        registry.unregisterPrepared("owner-b", 2L);
        assertThat(registry.snapshot().frontendContributions())
                .extracting(item -> item.contribution().contributionId())
                .containsExactly("first.card");
    }

    @Test
    @DisplayName("同一子上下文的投影、详情与前端贡献按 owner 原子注册和注销")
    void registersAndUnregistersAllCapabilitiesAtomically() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        GalleryCapabilityContributionAdapter adapter = new GalleryCapabilityContributionAdapter(registry);
        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("provider", TestProvider.class,
                    () -> new TestProvider("mixed", "source-a", GalleryKind.IMAGE, "work"));
            child.registerBean("frontend", GalleryFrontendProvider.class,
                    () -> () -> List.of(frontend("source-a.view", 10,
                            Set.of(GalleryFrontendHook.VIEW_ENTRY),
                            new GalleryFrontendScope(Set.of("source-a"), Set.of("work"),
                                    Set.of(GalleryKind.IMAGE), Set.of(GalleryMediaKind.IMAGE)))));
            child.refresh();

            assertThat(registry.snapshot().generation()).isZero();
            adapter.register("owner-a", child);

            GalleryCapabilityRegistry.Snapshot registered = registry.snapshot();
            assertThat(registered.generation()).isEqualTo(1);
            assertThat(registered.projectionProviders()).hasSize(1);
            assertThat(registered.workProviders()).hasSize(1);
            assertThat(registered.frontendContributions()).hasSize(1);
            assertThat(registry.resolveProjections(GalleryKind.IMAGE, "source-a")).hasSize(1);
            assertThat(registry.resolveWork("source-a", "work")).isPresent();
            assertThat(registry.resolveFrontends(
                    "source-a", "work", GalleryKind.IMAGE, GalleryMediaKind.IMAGE)).hasSize(1);

            adapter.unregister("owner-a");
            GalleryCapabilityRegistry.Snapshot removed = registry.snapshot();
            assertThat(removed.generation()).isEqualTo(2);
            assertThat(removed.projectionProviders()).isEmpty();
            assertThat(removed.workProviders()).isEmpty();
            assertThat(removed.frontendContributions()).isEmpty();
        }
    }

    @Test
    @DisplayName("冲突注册失败时投影与详情快照均保持原 generation")
    void conflictDoesNotPartiallyPublishEitherPort() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        TestProvider original = new TestProvider("original", "source-a", GalleryKind.IMAGE, "work");
        registry.register("owner-a", List.of(original), List.of(original),
                List.of(frontend("original.card", 10,
                        Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any())));
        GalleryCapabilityRegistry.Snapshot before = registry.snapshot();
        TestProvider conflicting = new TestProvider("conflicting", "source-a", GalleryKind.IMAGE, "other-work");

        assertThatThrownBy(() -> registry.register(
                "owner-b", List.of(conflicting), List.of(conflicting),
                List.of(frontend("conflicting.card", 20,
                        Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery projection route");

        assertThat(registry.snapshot()).isSameAs(before);
        assertThat(registry.resolveWork("source-a", "other-work")).isEmpty();
        assertThat(registry.resolveWork("source-a", "work")).isPresent();
    }

    @Test
    @DisplayName("描述符读取异常回滚整个 owner 并保留其它 owner 的原快照")
    void descriptorFailureRollsBackOwner() {
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

        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        registry.register("healthy-owner", List.of(healthy), List.of(healthy),
                List.of(frontend("healthy.card", 10,
                        Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any())));
        GalleryCapabilityRegistry.Snapshot before = registry.snapshot();

        assertThatThrownBy(() -> registry.register("broken-owner", List.of(broken), List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projection descriptors");
        assertThat(registry.snapshot()).isSameAs(before);
        assertThat(registry.resolveProjections(GalleryKind.NOVEL, "source-b")).hasSize(1);
        assertThat(registry.snapshot().frontendContributions()).hasSize(1);
    }

    @Test
    @DisplayName("前端 getter 异常发生在发布前并保留原 generation")
    void frontendGetterFailureKeepsOldSnapshot() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        registry.register("healthy-owner", List.of(), List.of(),
                List.of(frontend("healthy.card", 10,
                        Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any())));
        GalleryCapabilityRegistry.Snapshot before = registry.snapshot();
        GalleryCapabilityContributionAdapter adapter = new GalleryCapabilityContributionAdapter(registry);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext()) {
            child.registerBean("brokenFrontend", GalleryFrontendProvider.class,
                    () -> () -> { throw new IllegalStateException("boom"); });
            child.refresh();

            assertThatThrownBy(() -> adapter.register("broken-owner", child))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("frontend contributions");
        }

        assertThat(registry.snapshot()).isSameAs(before);
    }

    @Test
    @DisplayName("前端贡献按 order 与 id 稳定排序且快照不可变")
    void frontendContributionsAreSortedAndImmutable() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());

        registry.register("owner-a", List.of(), List.of(), List.of(
                frontend("view.z", 10, Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any()),
                frontend("view.b", 20, Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any()),
                frontend("view.a", 20, Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any())));

        assertThat(registry.snapshot().frontendContributions())
                .extracting(item -> item.contribution().contributionId())
                .containsExactly("view.z", "view.a", "view.b");
        assertThatThrownBy(() -> registry.snapshot().frontendContributions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("同一匹配范围只能有一个媒体 renderer 且冲突不发布")
    void overlappingMediaRendererConflictDoesNotPublish() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        GalleryFrontendScope sourceScope = new GalleryFrontendScope(
                Set.of("source-a"), Set.of(), Set.of(GalleryKind.IMAGE), Set.of(GalleryMediaKind.IMAGE));
        registry.register("owner-a", List.of(), List.of(), List.of(
                frontend("renderer.a", 10, Set.of(GalleryFrontendHook.MEDIA_RENDERER), sourceScope)));
        GalleryCapabilityRegistry.Snapshot before = registry.snapshot();

        assertThatThrownBy(() -> registry.register("owner-b", List.of(), List.of(), List.of(
                frontend("renderer.b", 20, Set.of(GalleryFrontendHook.MEDIA_RENDERER),
                        new GalleryFrontendScope(Set.of("source-a"), Set.of("work"),
                                Set.of(GalleryKind.IMAGE), Set.of(GalleryMediaKind.IMAGE))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("media renderer match");

        assertThat(registry.snapshot()).isSameAs(before);
    }

    @Test
    @DisplayName("前端 contribution id 全局重复时拒绝新 owner 且保留原快照")
    void duplicateFrontendIdDoesNotPublish() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        registry.register("owner-a", List.of(), List.of(), List.of(
                frontend("shared.card", 10,
                        Set.of(GalleryFrontendHook.CARD_EXTENSION), GalleryFrontendScope.any())));
        GalleryCapabilityRegistry.Snapshot before = registry.snapshot();

        assertThatThrownBy(() -> registry.register("owner-b", List.of(), List.of(), List.of(
                frontend("shared.card", 20,
                        Set.of(GalleryFrontendHook.DETAIL_ACTION), GalleryFrontendScope.any()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery frontend contribution id");

        assertThat(registry.snapshot()).isSameAs(before);
    }

    @Test
    @DisplayName("moduleUrl 与 viewHref 只接受无空白无遍历的同源绝对路径")
    void frontendPathsMustBeSafeSameOriginPaths() {
        GalleryCapabilityRegistry registry = new GalleryCapabilityRegistry(List.of(), List.of());
        List<String> invalidModuleUrls = List.of(
                "https://example.test/module.js", "//example.test/module.js", "javascript:alert(1)",
                "/\\example.test/module.js", "/gallery/../module.js", "/gallery/a module.js");

        for (String moduleUrl : invalidModuleUrls) {
            GalleryFrontendContribution invalid = new GalleryFrontendContribution(
                    "invalid.path", moduleUrl, GalleryFrontendScope.any(),
                    Set.of(GalleryFrontendHook.CARD_EXTENSION), null, null, null, null, 10);
            assertThatThrownBy(() -> registry.register("owner-a", List.of(), List.of(), List.of(invalid)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("same-origin absolute path");
            assertThat(registry.snapshot().generation()).isZero();
        }

        GalleryFrontendContribution invalidView = new GalleryFrontendContribution(
                "invalid.view", "/gallery/module.js", GalleryFrontendScope.any(),
                Set.of(GalleryFrontendHook.VIEW_ENTRY), "https://example.test/gallery",
                "gallery", "view.all", "images", 10);
        assertThatThrownBy(() -> registry.register("owner-a", List.of(), List.of(), List.of(invalidView)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("viewHref");
    }

    private static GalleryFrontendContribution frontend(
            String id, int order, Set<GalleryFrontendHook> hooks, GalleryFrontendScope scope) {
        boolean view = hooks.contains(GalleryFrontendHook.VIEW_ENTRY);
        return new GalleryFrontendContribution(
                id, "/gallery/module.js", scope, hooks,
                view ? "/gallery.html?view=" + id : null,
                view ? "gallery" : null,
                view ? "view." + id : null,
                view ? "images" : null,
                order);
    }

    private static void throwPending(AtomicReference<Throwable> pending) {
        Throwable failure = pending.getAndSet(null);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
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
