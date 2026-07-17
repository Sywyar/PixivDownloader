package top.sywyar.pixivdownload.core.gallery.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryAuthorFacet;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryProjectionKey;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjection;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryAiStatus;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryContentRating;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("主画廊内部跨来源游标合并")
class GalleryProjectionBrokerTest {

    @Test
    @DisplayName("多来源按稳定全序分页且后续游标无重复无遗漏")
    void mergesSourcesWithOpaqueProviderCursors() {
        GalleryProjectionProvider first = provider("first", "source-a", List.of(
                projection("source-a", "a3", 30), projection("source-a", "a1", 10)));
        GalleryProjectionProvider second = provider("second", "source-b", List.of(
                projection("source-b", "b4", 40), projection("source-b", "b2", 20)));
        GalleryProjectionBroker broker = new GalleryProjectionBroker(
                new GalleryCapabilityRegistry(List.of(first, second), List.of()));

        GalleryProjectionPage page1 = broker.page(query(null, 3));
        GalleryProjectionPage page2 = broker.page(query(page1.nextCursor(), 3));

        assertThat(ids(page1)).containsExactly("b4", "a3", "b2");
        assertThat(page1.hasMore()).isTrue();
        assertThat(ids(page2)).containsExactly("a1");
        assertThat(page2.hasMore()).isFalse();
        assertThat(java.util.stream.Stream.concat(ids(page1).stream(), ids(page2).stream()).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("游标绑定画廊类型与排序口径且不解析 provider 私有游标")
    void cursorIsVersionedAndBoundToOrdering() {
        GalleryProjectionProvider provider = provider("first", "source-a", List.of(
                projection("source-a", "a2", 20), projection("source-a", "a1", 10)));
        GalleryProjectionBroker broker = new GalleryProjectionBroker(
                new GalleryCapabilityRegistry(List.of(provider), List.of()));
        GalleryProjectionPage page = broker.page(query(null, 1));

        GalleryProjectionQuery wrongOrdering = new GalleryProjectionQuery(
                GalleryKind.IMAGE, null, List.of(), GallerySortField.CREATED_AT,
                GallerySortDirection.DESC, page.nextCursor(), 1);
        assertThatThrownBy(() -> broker.page(wrongOrdering))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match query ordering");
    }

    @Test
    @DisplayName("count 与 facet 对各来源使用同一 typed query 并保留来源作用域")
    void countAndFacetsUseSamePredicateAndSourceScope() {
        GalleryProjectionProvider first = provider("first", "source-a", List.of(projection("source-a", "a1", 10)));
        GalleryProjectionProvider second = provider("second", "source-b", List.of(projection("source-b", "b1", 20)));
        GalleryProjectionBroker broker = new GalleryProjectionBroker(
                new GalleryCapabilityRegistry(List.of(first, second), List.of()));
        GalleryProjectionQuery query = query(null, 10);

        assertThat(broker.count(query).count()).isEqualTo(2);
        assertThat(broker.facets(query).facets())
                .extracting(facet -> facet.sourceId())
                .containsExactly("source-a", "source-b");
    }

    @Test
    @DisplayName("默认排除管理数据且显式管理查询可以纳入")
    void filtersAdministrativeDatasetsWithoutSourceSpecificKnowledge() {
        GalleryProjectionProvider shared = provider("shared", "source-a",
                List.of(projection("source-a", "shared", 10)), GalleryDataAccess.SHARED);
        GalleryProjectionProvider admin = provider("admin", "source-b",
                List.of(projection("source-b", "admin", 20)), GalleryDataAccess.ADMIN_ONLY);
        GalleryProjectionBroker broker = new GalleryProjectionBroker(
                new GalleryCapabilityRegistry(List.of(shared, admin), List.of()));

        assertThat(ids(broker.page(query(null, 10)))).containsExactly("shared");
        assertThat(ids(broker.page(query(null, 10),
                Set.of(GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY))))
                .containsExactly("admin", "shared");
    }

    private static GalleryProjectionProvider provider(String id, String sourceId,
                                                       List<GalleryProjection> projections) {
        return provider(id, sourceId, projections, GalleryDataAccess.SHARED);
    }

    private static GalleryProjectionProvider provider(String id, String sourceId,
                                                       List<GalleryProjection> projections,
                                                       GalleryDataAccess dataAccess) {
        return new GalleryProjectionProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public List<GalleryProjectionDescriptor> projections() {
                return List.of(new GalleryProjectionDescriptor(
                        sourceId, GalleryKind.IMAGE, "gallery", "source.test", 0,
                        dataAccess, Map.of()));
            }

            @Override
            public GalleryProjectionPage page(GalleryProjectionQuery query) {
                int offset = query.cursor() == null ? 0
                        : Integer.parseInt(query.cursor().substring("opaque-".length()));
                if (offset >= projections.size()) {
                    return GalleryProjectionPage.empty();
                }
                int next = offset + 1;
                boolean hasMore = next < projections.size();
                return new GalleryProjectionPage(List.of(projections.get(offset)),
                        hasMore ? "opaque-" + next : null, hasMore, List.of());
            }

            @Override
            public long count(GalleryProjectionQuery query) {
                return projections.size();
            }

            @Override
            public GalleryFacetPage facets(GalleryProjectionQuery query) {
                return new GalleryFacetPage(List.of(
                        new GalleryAuthorFacet(sourceId, "author", "Author", projections.size())), List.of());
            }
        };
    }

    private static GalleryProjection projection(String sourceId, String id, long epochSecond) {
        return new GalleryProjection(
                new GalleryProjectionKey(new GalleryWorkKey(sourceId, "work", id), GalleryKind.IMAGE),
                id, null, null, null, List.of(), null, Instant.ofEpochSecond(epochSecond), null,
                Set.of(GalleryMediaKind.IMAGE), GalleryContentRating.SFW, GalleryAiStatus.UNKNOWN,
                null, Map.of());
    }

    private static GalleryProjectionQuery query(String cursor, int limit) {
        return new GalleryProjectionQuery(GalleryKind.IMAGE, null, List.of(),
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, cursor, limit);
    }

    private static List<String> ids(GalleryProjectionPage page) {
        return page.projections().stream().map(projection -> projection.key().workKey().sourceWorkId()).toList();
    }
}
