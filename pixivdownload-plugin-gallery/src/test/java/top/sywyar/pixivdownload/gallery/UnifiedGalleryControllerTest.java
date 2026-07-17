package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.facet.GalleryFacetPage;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCountResult;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryRuntimeQuery;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryRuntimeSnapshot;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryWorkResult;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@DisplayName("已过时的画廊只读兼容 API")
@SuppressWarnings("deprecation")
class UnifiedGalleryControllerTest {

    @Test
    @DisplayName("过时范围只覆盖 unified HTTP 控制器，不覆盖长期维护的主画廊能力")
    void deprecationScopeDoesNotIncludeMaintainedGallerySeries() {
        assertThat(UnifiedGalleryController.class.isAnnotationPresent(Deprecated.class)).isTrue();

        assertThat(GalleryController.class.isAnnotationPresent(Deprecated.class)).isFalse();
        assertThat(GalleryService.class.isAnnotationPresent(Deprecated.class)).isFalse();
        assertThat(GalleryBatchService.class.isAnnotationPresent(Deprecated.class)).isFalse();
        assertThat(GalleryFrontendContribution.class.isAnnotationPresent(Deprecated.class)).isFalse();
        assertThat(GalleryFrontendProvider.class.isAnnotationPresent(Deprecated.class)).isFalse();
        assertThat(GalleryRuntimeQuery.class.isAnnotationPresent(Deprecated.class)).isFalse();
    }

    @Test
    @DisplayName("全部兼容端点通过标准响应头声明弃用日期且不承诺移除")
    void compatibilityApiPublishesDeprecationHeader() throws Exception {
        GalleryRuntimeQuery runtimeQuery = mock(GalleryRuntimeQuery.class);
        when(runtimeQuery.snapshot(anySet())).thenReturn(snapshot(false));
        when(runtimeQuery.page(any(), anySet()))
                .thenReturn(new GalleryProjectionPage(List.of(), null, false, List.of()));
        when(runtimeQuery.count(any(), anySet())).thenReturn(new GalleryCountResult(0, List.of()));
        when(runtimeQuery.facets(any(), anySet())).thenReturn(GalleryFacetPage.empty());
        when(runtimeQuery.findWork(any(), anySet()))
                .thenReturn(new GalleryWorkResult(Optional.empty(), List.of()));
        UnifiedGalleryController controller = new UnifiedGalleryController(
                runtimeQuery, ownerIdentityResolver(false));
        MockMvc mockMvc = standaloneSetup(controller).build();

        for (String path : List.of(
                "/api/gallery/unified/descriptors",
                "/api/gallery/unified/projections?kind=IMAGE",
                "/api/gallery/unified/count?kind=IMAGE",
                "/api/gallery/unified/facets?kind=IMAGE")) {
            var response = mockMvc.perform(get(path)).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(response.getHeader("Deprecation")).as(path)
                    .isEqualTo(UnifiedGalleryController.DEPRECATION_HEADER_VALUE);
            assertThat(response.getHeader("Sunset")).as(path).isNull();
        }
        var missingWork = mockMvc.perform(get("/api/gallery/unified/works/source/work/404"))
                .andReturn()
                .getResponse();
        assertThat(missingWork.getStatus()).isEqualTo(404);
        assertThat(missingWork.getHeader("Deprecation"))
                .isEqualTo(UnifiedGalleryController.DEPRECATION_HEADER_VALUE);
        assertThat(missingWork.getHeader("Sunset")).isNull();

        Deprecated annotation = UnifiedGalleryController.class.getAnnotation(Deprecated.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.since()).isEqualTo("1.0.0");
        assertThat(annotation.forRemoval()).isFalse();
    }

    @Test
    @DisplayName("游客与受邀访客只请求共享数据范围")
    void sharedDescriptorsUseSharedAccessOnly() {
        GalleryRuntimeQuery runtimeQuery = mock(GalleryRuntimeQuery.class);
        Set<GalleryDataAccess> sharedAccess = Set.of(GalleryDataAccess.SHARED);
        when(runtimeQuery.snapshot(eq(sharedAccess))).thenReturn(snapshot(false));
        UnifiedGalleryController controller = new UnifiedGalleryController(
                runtimeQuery, ownerIdentityResolver(false));

        var response = controller.descriptors(new MockHttpServletRequest());

        verify(runtimeQuery).snapshot(sharedAccess);
        assertThat(response.generation()).isEqualTo(23);
        assertThat(response.projections()).extracting(GalleryProjectionDescriptor::sourceId)
                .containsExactly("shared");
        assertThat(response.works()).extracting(GalleryWorkDescriptor::sourceId)
                .containsExactly("shared");
        assertThat(response.frontends()).extracting(GalleryFrontendContribution::contributionId)
                .containsExactly("shared.view");
        GalleryFrontendContribution frontend = response.frontends().get(0);
        assertThat(frontend.moduleUrl()).isEqualTo("/gallery-extensions/shared.js");
        assertThat(frontend.scope().sourceIds()).containsExactly("shared");
        assertThat(frontend.scope().sourceWorkNamespaces()).containsExactly("work");
        assertThat(frontend.scope().galleryKinds()).containsExactly(GalleryKind.IMAGE);
        assertThat(frontend.scope().mediaKinds()).containsExactly(GalleryMediaKind.IMAGE);
        assertThat(frontend.hooks()).containsExactly(GalleryFrontendHook.VIEW_ENTRY);
        assertThat(frontend.viewHref())
                .isEqualTo("/pixiv-gallery.html?galleryKind=IMAGE&sourceId=shared");
        assertThat(frontend.displayNamespace()).isEqualTo("gallery");
        assertThat(frontend.displayI18nKey()).isEqualTo("gallery.view.shared");
        assertThat(frontend.iconToken()).isEqualTo("images");
        assertThat(frontend.order()).isEqualTo(10);
        assertThat(response.diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("管理员 descriptors 请求共享与管理员数据并净化注册诊断")
    void administrativeDescriptorsIncludeAllAuthorizedFrontends() {
        GalleryRuntimeQuery runtimeQuery = mock(GalleryRuntimeQuery.class);
        Set<GalleryDataAccess> adminAccess = Set.of(
                GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY);
        when(runtimeQuery.snapshot(eq(adminAccess))).thenReturn(snapshot(true));
        UnifiedGalleryController controller = new UnifiedGalleryController(
                runtimeQuery, ownerIdentityResolver(true));

        var response = controller.descriptors(new MockHttpServletRequest());

        verify(runtimeQuery).snapshot(adminAccess);
        assertThat(response.projections()).extracting(GalleryProjectionDescriptor::sourceId)
                .containsExactly("shared", "admin");
        assertThat(response.works()).extracting(GalleryWorkDescriptor::sourceId)
                .containsExactly("shared", "admin");
        assertThat(response.frontends()).extracting(GalleryFrontendContribution::contributionId)
                .containsExactly("shared.view", "admin.view");
        assertThat(response.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("registry-warning");
            assertThat(diagnostic.message()).isNull();
        });
    }

    @Test
    @DisplayName("非管理员 descriptors 即使门面误返诊断也不泄露全局注册信息")
    void sharedDescriptorsDropUnexpectedRegistryDiagnostics() {
        GalleryRuntimeQuery runtimeQuery = mock(GalleryRuntimeQuery.class);
        GalleryDiagnostic diagnostic = new GalleryDiagnostic(
                "registry", null, null, "registry-warning", "private detail");
        when(runtimeQuery.snapshot(eq(Set.of(GalleryDataAccess.SHARED))))
                .thenReturn(new GalleryRuntimeSnapshot(
                        1, List.of(), List.of(), List.of(), List.of(diagnostic)));
        UnifiedGalleryController controller = new UnifiedGalleryController(
                runtimeQuery, ownerIdentityResolver(false));

        assertThat(controller.descriptors(new MockHttpServletRequest()).diagnostics()).isEmpty();
    }

    @Test
    @DisplayName("只读 API 诊断不返回 provider 异常原文")
    void publicDiagnosticsDropProviderFailureMessages() {
        GalleryRuntimeQuery runtimeQuery = mock(GalleryRuntimeQuery.class);
        when(runtimeQuery.page(any(), anySet())).thenReturn(new GalleryProjectionPage(
                List.of(), null, false, List.of(new GalleryDiagnostic(
                        "shared-projection", "shared", GalleryKind.IMAGE,
                        "gallery-provider-page-failed", "SQLException: password=secret"))));
        UnifiedGalleryController controller = new UnifiedGalleryController(
                runtimeQuery, ownerIdentityResolver(false));

        GalleryProjectionPage response = controller.projections(
                GalleryKind.IMAGE, "shared", null, null, null, null, null,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, null, 50,
                new MockHttpServletRequest());

        assertThat(response.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("gallery-provider-page-failed");
            assertThat(diagnostic.message()).isNull();
        });
    }

    private static GalleryRuntimeSnapshot snapshot(boolean admin) {
        GalleryProjectionDescriptor sharedProjection = descriptor("shared", GalleryDataAccess.SHARED);
        GalleryWorkDescriptor sharedWork = new GalleryWorkDescriptor(
                "shared", "work", GalleryDataAccess.SHARED);
        if (!admin) {
            return new GalleryRuntimeSnapshot(
                    23, List.of(sharedProjection), List.of(sharedWork),
                    List.of(frontend("shared", 10)), List.of());
        }
        GalleryProjectionDescriptor adminProjection = descriptor("admin", GalleryDataAccess.ADMIN_ONLY);
        GalleryWorkDescriptor adminWork = new GalleryWorkDescriptor(
                "admin", "work", GalleryDataAccess.ADMIN_ONLY);
        return new GalleryRuntimeSnapshot(
                23,
                List.of(sharedProjection, adminProjection),
                List.of(sharedWork, adminWork),
                List.of(frontend("shared", 10), frontend("admin", 20)),
                List.of(new GalleryDiagnostic(
                        "registry", null, null, "registry-warning", "diagnostic")));
    }

    private static GalleryFrontendContribution frontend(String source, int order) {
        return new GalleryFrontendContribution(
                source + ".view",
                "/gallery-extensions/" + source + ".js",
                new GalleryFrontendScope(
                        Set.of(source),
                        Set.of("work"),
                        Set.of(GalleryKind.IMAGE),
                        Set.of(GalleryMediaKind.IMAGE)),
                Set.of(GalleryFrontendHook.VIEW_ENTRY),
                "/pixiv-gallery.html?galleryKind=IMAGE&sourceId=" + source,
                "gallery",
                "gallery.view." + source,
                "images",
                order);
    }

    private static GalleryProjectionDescriptor descriptor(String source, GalleryDataAccess access) {
        return new GalleryProjectionDescriptor(source, GalleryKind.IMAGE,
                "gallery", "source.test", 1, access, Map.of());
    }

    private static RequestOwnerIdentityResolver ownerIdentityResolver(boolean admin) {
        RequestOwnerIdentityResolver resolver = mock(RequestOwnerIdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(admin
                ? RequestOwnerIdentity.adminScope()
                : RequestOwnerIdentity.owner("gallery-test-owner"));
        return resolver;
    }
}
