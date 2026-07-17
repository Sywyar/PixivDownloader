package top.sywyar.pixivdownload.core.gallery.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.identity.GalleryWorkKey;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("主画廊只读运行时适配器")
class GalleryRuntimeQueryAdapterTest {

    @Test
    @DisplayName("快照按数据权限裁剪描述符和前端并只向管理员暴露注册诊断")
    void filtersSnapshotByDataAccessAndFrontendScopeProof() {
        GalleryCapabilityRegistry registry = mock(GalleryCapabilityRegistry.class);
        GalleryProjectionProvider sharedProjectionProvider = mock(GalleryProjectionProvider.class);
        GalleryProjectionProvider adminProjectionProvider = mock(GalleryProjectionProvider.class);
        GalleryWorkProvider sharedWorkProvider = mock(GalleryWorkProvider.class);
        GalleryWorkProvider adminWorkProvider = mock(GalleryWorkProvider.class);

        GalleryProjectionDescriptor sharedProjection = projection("shared-source", GalleryDataAccess.SHARED);
        GalleryProjectionDescriptor adminProjection = projection("admin-source", GalleryDataAccess.ADMIN_ONLY);
        GalleryWorkDescriptor sharedWork = work("shared-source", GalleryDataAccess.SHARED);
        GalleryWorkDescriptor adminWork = work("admin-source", GalleryDataAccess.ADMIN_ONLY);
        GalleryFrontendContribution sharedFrontend = frontend("shared-card", "shared-source");
        GalleryFrontendContribution adminFrontend = frontend("admin-card", "admin-source");
        GalleryFrontendContribution spoofedFrontend = frontend("spoofed-card", "shared-source");
        GalleryFrontendContribution unprovedFrontend = frontend("orphan-card", "orphan-source");
        GalleryDiagnostic diagnostic = new GalleryDiagnostic(
                "admin-projection", "admin-source", GalleryKind.IMAGE, "registry-warning", "private detail");

        when(registry.snapshot()).thenReturn(new GalleryCapabilityRegistry.Snapshot(
                7,
                List.of(
                        new GalleryCapabilityRegistry.RegisteredProjectionProvider(
                                "shared-owner", "shared-projection", sharedProjectionProvider,
                                List.of(sharedProjection)),
                        new GalleryCapabilityRegistry.RegisteredProjectionProvider(
                                "admin-owner", "admin-projection", adminProjectionProvider,
                                List.of(adminProjection))),
                List.of(
                        new GalleryCapabilityRegistry.RegisteredWorkProvider(
                                "shared-owner", "shared-work", sharedWorkProvider, List.of(sharedWork)),
                        new GalleryCapabilityRegistry.RegisteredWorkProvider(
                                "admin-owner", "admin-work", adminWorkProvider, List.of(adminWork))),
                List.of(
                        new GalleryCapabilityRegistry.RegisteredFrontendContribution(
                                "shared-owner", sharedFrontend),
                        new GalleryCapabilityRegistry.RegisteredFrontendContribution(
                                "admin-owner", adminFrontend),
                        new GalleryCapabilityRegistry.RegisteredFrontendContribution(
                                "spoofed-owner", spoofedFrontend),
                        new GalleryCapabilityRegistry.RegisteredFrontendContribution(
                                "orphan-owner", unprovedFrontend)),
                List.of(sharedProjection, adminProjection),
                List.of(sharedWork, adminWork),
                List.of(diagnostic)));
        GalleryRuntimeQueryAdapter adapter = new GalleryRuntimeQueryAdapter(
                registry, mock(GalleryProjectionBroker.class), mock(GalleryWorkBroker.class));

        GalleryRuntimeSnapshot shared = adapter.snapshot(Set.of(GalleryDataAccess.SHARED));
        assertThat(shared.generation()).isEqualTo(7);
        assertThat(shared.projections()).containsExactly(sharedProjection);
        assertThat(shared.works()).containsExactly(sharedWork);
        assertThat(shared.frontends()).containsExactly(sharedFrontend);
        assertThat(shared.diagnostics()).isEmpty();

        GalleryRuntimeSnapshot admin = adapter.snapshot(
                Set.of(GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY));
        assertThat(admin.projections()).containsExactly(sharedProjection, adminProjection);
        assertThat(admin.works()).containsExactly(sharedWork, adminWork);
        assertThat(admin.frontends()).containsExactly(sharedFrontend, adminFrontend);
        assertThat(admin.diagnostics()).containsExactly(diagnostic);
    }

    @Test
    @DisplayName("分页计数分面和作品查询原样委托现有 broker 并保留诊断")
    void delegatesReadOperationsWithoutChangingResults() {
        GalleryCapabilityRegistry registry = mock(GalleryCapabilityRegistry.class);
        GalleryProjectionBroker projectionBroker = mock(GalleryProjectionBroker.class);
        GalleryWorkBroker workBroker = mock(GalleryWorkBroker.class);
        GalleryRuntimeQueryAdapter adapter = new GalleryRuntimeQueryAdapter(registry, projectionBroker, workBroker);
        Set<GalleryDataAccess> access = Set.of(GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY);
        GalleryProjectionQuery query = new GalleryProjectionQuery(
                GalleryKind.IMAGE, null, List.of(), GallerySortField.DOWNLOADED_AT,
                GallerySortDirection.DESC, null, 50);
        GalleryWorkKey key = new GalleryWorkKey("source-a", "work", "1");
        GalleryDiagnostic diagnostic = new GalleryDiagnostic(
                "provider-a", "source-a", GalleryKind.IMAGE, "provider-warning", "detail");
        GalleryProjectionPage page = new GalleryProjectionPage(List.of(), null, false, List.of(diagnostic));
        GalleryCountResult count = new GalleryCountResult(3, List.of(diagnostic));
        GalleryFacetPage facets = GalleryFacetPage.empty(List.of(diagnostic));
        GalleryWorkResult work = new GalleryWorkResult(Optional.empty(), List.of(diagnostic));
        when(projectionBroker.page(eq(query), eq(access))).thenReturn(page);
        when(projectionBroker.count(eq(query), eq(access))).thenReturn(count);
        when(projectionBroker.facets(eq(query), eq(access))).thenReturn(facets);
        when(workBroker.find(eq(key), eq(access))).thenReturn(work);

        assertThat(adapter.page(query, access)).isSameAs(page);
        assertThat(adapter.count(query, access)).isSameAs(count);
        assertThat(adapter.facets(query, access)).isSameAs(facets);
        assertThat(adapter.findWork(key, access)).isSameAs(work);
        verify(projectionBroker).page(query, access);
        verify(projectionBroker).count(query, access);
        verify(projectionBroker).facets(query, access);
        verify(workBroker).find(key, access);
    }

    private static GalleryProjectionDescriptor projection(String sourceId, GalleryDataAccess dataAccess) {
        return new GalleryProjectionDescriptor(
                sourceId, GalleryKind.IMAGE, null, null, 0, dataAccess, Map.of());
    }

    private static GalleryWorkDescriptor work(String sourceId, GalleryDataAccess dataAccess) {
        return new GalleryWorkDescriptor(sourceId, "work", dataAccess);
    }

    private static GalleryFrontendContribution frontend(String contributionId, String sourceId) {
        return new GalleryFrontendContribution(
                contributionId,
                "/gallery/" + contributionId + ".js",
                new GalleryFrontendScope(
                        Set.of(sourceId), Set.of("work"), Set.of(GalleryKind.IMAGE), Set.of()),
                Set.of(GalleryFrontendHook.CARD_EXTENSION),
                null, null, null, null, 0);
    }
}
