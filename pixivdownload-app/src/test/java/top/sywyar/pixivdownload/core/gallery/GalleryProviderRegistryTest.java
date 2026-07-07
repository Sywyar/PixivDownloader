package top.sywyar.pixivdownload.core.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryAuthorFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetType;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryTagFacet;
import top.sywyar.pixivdownload.core.gallery.model.GalleryItem;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.GalleryPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryQuery;
import top.sywyar.pixivdownload.core.gallery.model.GallerySourceDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.GalleryWorkRef;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GalleryProviderRegistryTest {

    @Test
    @DisplayName("注册中心可聚合多个画廊数据提供方")
    void registryAggregatesMultipleProviders() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("image-provider", source("image-provider", "image-source", GalleryKind.IMAGE)),
                provider("text-provider", source("text-provider", "text-source", GalleryKind.NOVEL))));

        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("image-source", "text-source");
        assertThat(registry.snapshot().diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("注册和注销外置数据提供方后快照会更新")
    void registerAndUnregisterExternalProviderUpdatesSnapshot() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("core-provider", source("core-provider", "core-source", GalleryKind.IMAGE))));

        registry.register("external-gallery", List.of(
                provider("external-provider", source("external-provider", "external-source", GalleryKind.NOVEL))));

        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("core-source", "external-source");

        registry.unregister("external-gallery");

        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("core-source");
    }

    @Test
    @DisplayName("注销不存在或已注销的归属方时保持幂等")
    void unregisterIsIdempotent() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("core-provider", source("core-provider", "core-source", GalleryKind.IMAGE))));

        registry.unregister("external-gallery");
        registry.register("external-gallery", List.of(
                provider("external-provider", source("external-provider", "external-source", GalleryKind.NOVEL))));
        registry.unregister("external-gallery");
        registry.unregister("external-gallery");

        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("core-source");
    }

    @Test
    @DisplayName("注册失败不会污染旧快照")
    void failedRegisterDoesNotPolluteExistingSnapshot() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("core-provider", source("core-provider", "core-source", GalleryKind.IMAGE))));
        GalleryProviderRegistry.Snapshot before = registry.snapshot();

        assertThatThrownBy(() -> registry.register("external-gallery", List.of(
                provider("external-provider", source("external-provider", "core-source", GalleryKind.IMAGE)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery source/kind");

        assertThat(registry.snapshot()).isSameAs(before);
        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("core-source");
    }

    @Test
    @DisplayName("注册遇到重复数据提供方标识时立即失败")
    void duplicateProviderIdOnRegisterFailsFast() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("core-provider", source("core-provider", "core-source", GalleryKind.IMAGE))));

        assertThatThrownBy(() -> registry.register("external-gallery", List.of(
                provider("core-provider", source("core-provider", "external-source", GalleryKind.NOVEL)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery provider id");
    }

    @Test
    @DisplayName("注册遇到重复来源和类型组合时立即失败")
    void duplicateSourceKindOnRegisterFailsFast() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("core-provider", source("core-provider", "shared-source", GalleryKind.IMAGE))));

        assertThatThrownBy(() -> registry.register("external-gallery", List.of(
                provider("external-provider", source("external-provider", "shared-source", GalleryKind.IMAGE)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery source/kind");
    }

    @Test
    @DisplayName("重复数据提供方标识立即失败")
    void duplicateProviderIdFailsFast() {
        assertThatThrownBy(() -> new GalleryProviderRegistry(List.of(
                provider("same-provider", source("same-provider", "source-a", GalleryKind.IMAGE)),
                provider("same-provider", source("same-provider", "source-b", GalleryKind.NOVEL)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery provider id");
    }

    @Test
    @DisplayName("非法数据提供方标识立即失败")
    void invalidProviderIdFailsFast() {
        assertThatThrownBy(() -> new GalleryProviderRegistry(List.of(
                provider("Pixiv Image", source("Pixiv Image", "source-a", GalleryKind.IMAGE)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid gallery provider id");
    }

    @Test
    @DisplayName("非法来源标识立即失败")
    void invalidSourceIdFailsFast() {
        assertThatThrownBy(() -> new GalleryProviderRegistry(List.of(
                provider("image-provider", source("image-provider", "Pixiv Image", GalleryKind.IMAGE)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid gallery source id");
    }

    @Test
    @DisplayName("空类型集合立即失败")
    void emptyKindsFailFast() {
        assertThatThrownBy(() -> new GalleryProviderRegistry(List.of(
                provider("image-provider", new GallerySourceDescriptor(
                        "image-provider", "image-source", Set.of(), "gallery", "source.image", 10, List.of())))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without kinds");
    }

    @Test
    @DisplayName("重复来源和类型组合冲突立即失败")
    void duplicateSourceKindFailsFast() {
        assertThatThrownBy(() -> new GalleryProviderRegistry(List.of(
                provider("first-provider", source("first-provider", "shared-source", GalleryKind.IMAGE)),
                provider("second-provider", source("second-provider", "shared-source", GalleryKind.IMAGE)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate gallery source/kind");
    }

    @Test
    @DisplayName("同一来源标识可分别服务 IMAGE 与 NOVEL")
    void sameSourceIdCanServeDifferentGalleryKinds() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("pixiv-image", source("pixiv-image", "pixiv", GalleryKind.IMAGE)),
                provider("pixiv-novel", source("pixiv-novel", "pixiv", GalleryKind.NOVEL))));

        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("pixiv", "pixiv");
        assertThat(registry.resolve(GalleryKind.IMAGE, "pixiv"))
                .extracting(GalleryProviderRegistry.RegisteredProvider::providerId)
                .containsExactly("pixiv-image");
        assertThat(registry.resolve(GalleryKind.NOVEL, "pixiv"))
                .extracting(GalleryProviderRegistry.RegisteredProvider::providerId)
                .containsExactly("pixiv-novel");
    }

    @Test
    @DisplayName("来源声明异常被隔离并进入诊断")
    void providerSourcesFailureIsIsolated() {
        GalleryProviderRegistry registry = new GalleryProviderRegistry(List.of(
                provider("healthy-provider", source("healthy-provider", "image-source", GalleryKind.IMAGE)),
                throwingSourcesProvider("broken-provider")));

        assertThat(registry.snapshot().sources())
                .extracting(GallerySourceDescriptor::sourceId)
                .containsExactly("image-source");
        assertThat(registry.snapshot().diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.providerId()).isEqualTo("broken-provider");
                    assertThat(diagnostic.code()).isEqualTo("provider-sources-failed");
                });
    }

    @Test
    @DisplayName("查询中介隔离单个数据提供方查询异常且不调用类型不匹配的数据提供方")
    void queryBrokerIsolatesProviderQueryFailure() {
        CountingProvider broken = provider("broken-provider",
                source("broken-provider", "image-source", GalleryKind.IMAGE));
        broken.failQuery = true;
        CountingProvider unrelated = provider("unrelated-provider",
                source("unrelated-provider", "text-source", GalleryKind.NOVEL));

        GalleryQueryBroker broker = new GalleryQueryBroker(
                new GalleryProviderRegistry(List.of(broken, unrelated)));
        GalleryPage page = broker.query(GalleryQuery.of(GalleryKind.IMAGE, "image-source"));

        assertThat(page.items()).isEmpty();
        assertThat(page.diagnostics())
                .anySatisfy(diagnostic -> {
                    assertThat(diagnostic.providerId()).isEqualTo("broken-provider");
                    assertThat(diagnostic.code()).isEqualTo("gallery-query-failed");
                });
        assertThat(broken.queryCalls).hasValue(1);
        assertThat(unrelated.queryCalls).hasValue(0);
    }

    @Test
    @DisplayName("数据提供方缺席时查询中介返回空页且保留分页语义")
    void queryBrokerReturnsEmptyPageWhenProviderIsAbsent() {
        GalleryQueryBroker broker = new GalleryQueryBroker(new GalleryProviderRegistry(List.of()));
        GalleryPage page = broker.query(new GalleryQuery(GalleryKind.IMAGE, "pixiv", List.of(), Map.of(), 12, 6));

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.offset()).isEqualTo(12);
        assertThat(page.limit()).isEqualTo(6);
        assertThat(page.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("多个数据提供方的全部来源分页返回诊断且不超过限制")
    void allSourcePageAcrossMultipleProvidersRequiresSource() {
        CountingProvider first = provider("first-provider",
                source("first-provider", "source-a", GalleryKind.IMAGE));
        first.page = new GalleryPage(List.of(new GalleryItem(
                new GalleryWorkRef("first-provider", "source-a", GalleryKind.IMAGE, "1"),
                "first", null, null, Map.of())), 1, false, 0, 1, List.of());
        CountingProvider second = provider("second-provider",
                source("second-provider", "source-b", GalleryKind.IMAGE));
        second.page = new GalleryPage(List.of(new GalleryItem(
                new GalleryWorkRef("second-provider", "source-b", GalleryKind.IMAGE, "2"),
                "second", null, null, Map.of())), 1, false, 0, 1, List.of());

        GalleryQueryBroker broker = new GalleryQueryBroker(new GalleryProviderRegistry(List.of(first, second)));
        GalleryPage page = broker.query(new GalleryQuery(GalleryKind.IMAGE, null, List.of(), Map.of(), 0, 1));

        assertThat(page.items()).hasSizeLessThanOrEqualTo(1);
        assertThat(page.items()).isEmpty();
        assertThat(page.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.code())
                        .isEqualTo("gallery-source-required-for-page"));
        assertThat(first.queryCalls).hasValue(0);
        assertThat(second.queryCalls).hasValue(0);
    }

    @Test
    @DisplayName("指定来源标识时只调用匹配的数据提供方")
    void sourceQueryCallsOnlyMatchingProvider() {
        CountingProvider first = provider("first-provider",
                source("first-provider", "source-a", GalleryKind.IMAGE));
        first.page = new GalleryPage(List.of(new GalleryItem(
                new GalleryWorkRef("first-provider", "source-a", GalleryKind.IMAGE, "1"),
                "first", null, null, Map.of())), 1, false, 0, 50, List.of());
        CountingProvider second = provider("second-provider",
                source("second-provider", "source-b", GalleryKind.IMAGE));
        second.page = new GalleryPage(List.of(new GalleryItem(
                new GalleryWorkRef("second-provider", "source-b", GalleryKind.IMAGE, "2"),
                "second", null, null, Map.of())), 1, false, 0, 50, List.of());

        GalleryQueryBroker broker = new GalleryQueryBroker(new GalleryProviderRegistry(List.of(first, second)));
        GalleryPage page = broker.query(GalleryQuery.of(GalleryKind.IMAGE, "source-b"));

        assertThat(page.items())
                .extracting(item -> item.ref().sourceId())
                .containsExactly("source-b");
        assertThat(first.queryCalls).hasValue(0);
        assertThat(second.queryCalls).hasValue(1);
    }

    @Test
    @DisplayName("全部来源下作者和标签筛选项保留来源标识且不按同名合并")
    void facetsKeepSourceIdAcrossAllSources() {
        CountingProvider first = provider("first-provider",
                source("first-provider", "source-a", GalleryKind.IMAGE));
        first.facets = new GalleryFacetPage(List.of(
                new GalleryAuthorFacet("source-a", "100", "same author", 3),
                new GalleryTagFacet("source-a", "200", "same tag", null, 5)), List.of());
        CountingProvider second = provider("second-provider",
                source("second-provider", "source-b", GalleryKind.IMAGE));
        second.facets = new GalleryFacetPage(List.of(
                new GalleryAuthorFacet("source-b", "100", "same author", 7),
                new GalleryTagFacet("source-b", "200", "same tag", null, 11)), List.of());

        GalleryQueryBroker broker = new GalleryQueryBroker(new GalleryProviderRegistry(List.of(first, second)));
        GalleryFacetPage page = broker.facets(GalleryQuery.of(GalleryKind.IMAGE));

        assertThat(page.facets()).hasSize(4);
        assertThat(page.facets())
                .filteredOn(facet -> facet.type() == GalleryFacetType.AUTHOR)
                .extracting(facet -> facet.sourceId())
                .containsExactly("source-a", "source-b");
        assertThat(page.facets())
                .filteredOn(facet -> facet.type() == GalleryFacetType.TAG)
                .extracting(facet -> facet.sourceId())
                .containsExactly("source-a", "source-b");
    }

    private static CountingProvider provider(String providerId, GallerySourceDescriptor source) {
        return new CountingProvider(providerId, List.of(source));
    }

    private static CountingProvider throwingSourcesProvider(String providerId) {
        CountingProvider provider = new CountingProvider(providerId, List.of());
        provider.failSources = true;
        return provider;
    }

    private static GallerySourceDescriptor source(String providerId, String sourceId, GalleryKind kind) {
        return new GallerySourceDescriptor(
                providerId, sourceId, Set.of(kind), "gallery", "source." + sourceId, 10, List.of());
    }

    private static final class CountingProvider implements GalleryDataProvider {
        private final String providerId;
        private final List<GallerySourceDescriptor> sources;
        private final AtomicInteger queryCalls = new AtomicInteger();
        private boolean failSources;
        private boolean failQuery;
        private GalleryPage page = GalleryPage.empty(0, 50, List.of());
        private GalleryFacetPage facets = GalleryFacetPage.empty();

        private CountingProvider(String providerId, List<GallerySourceDescriptor> sources) {
            this.providerId = providerId;
            this.sources = sources;
        }

        @Override
        public String providerId() {
            return providerId;
        }

        @Override
        public List<GallerySourceDescriptor> sources() {
            if (failSources) {
                throw new IllegalStateException("source boom");
            }
            return sources;
        }

        @Override
        public GalleryPage query(GalleryQuery query) {
            queryCalls.incrementAndGet();
            if (failQuery) {
                throw new IllegalStateException("query boom");
            }
            return page;
        }

        @Override
        public GalleryFacetPage facets(GalleryQuery query) {
            return facets;
        }
    }
}
