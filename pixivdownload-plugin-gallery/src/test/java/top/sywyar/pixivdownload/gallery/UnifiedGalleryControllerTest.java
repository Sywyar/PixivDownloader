package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.core.gallery.GalleryProjectionProvider;
import top.sywyar.pixivdownload.core.gallery.GalleryWorkProvider;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendContribution;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendHook;
import top.sywyar.pixivdownload.core.gallery.frontend.GalleryFrontendScope;
import top.sywyar.pixivdownload.core.gallery.model.GalleryDiagnostic;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.media.GalleryMediaKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionDescriptor;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryProjectionPage;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.core.gallery.model.work.GalleryWorkDescriptor;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryProjectionBroker;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryWorkBroker;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;

@DisplayName("统一画廊只读 API")
class UnifiedGalleryControllerTest {

    @Test
    @DisplayName("游客与受邀访客不暴露同 owner 的管理员专属前端贡献")
    void sharedDescriptorsExcludeAdministrativeData() {
        GalleryCapabilityRegistry registry = mock(GalleryCapabilityRegistry.class);
        when(registry.snapshot()).thenReturn(snapshot());
        SetupService setup = mock(SetupService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(setup.hasAdminScope(request)).thenReturn(false);
        UnifiedGalleryController controller = new UnifiedGalleryController(registry,
                mock(GalleryProjectionBroker.class), mock(GalleryWorkBroker.class), setup);

        var response = controller.descriptors(request);

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
    @DisplayName("管理员 descriptors 可见共享与管理员专属前端贡献")
    void administrativeDescriptorsIncludeAllAuthorizedFrontends() {
        GalleryCapabilityRegistry registry = mock(GalleryCapabilityRegistry.class);
        when(registry.snapshot()).thenReturn(snapshot());
        SetupService setup = mock(SetupService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(setup.hasAdminScope(request)).thenReturn(true);
        UnifiedGalleryController controller = new UnifiedGalleryController(registry,
                mock(GalleryProjectionBroker.class), mock(GalleryWorkBroker.class), setup);

        var response = controller.descriptors(request);

        assertThat(response.generation()).isEqualTo(23);
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
    @DisplayName("只读 API 诊断不返回 provider 异常原文")
    void publicDiagnosticsDropProviderFailureMessages() {
        GalleryCapabilityRegistry registry = mock(GalleryCapabilityRegistry.class);
        when(registry.snapshot()).thenReturn(snapshot());
        GalleryProjectionBroker projectionBroker = mock(GalleryProjectionBroker.class);
        when(projectionBroker.page(any(), anySet())).thenReturn(new GalleryProjectionPage(
                List.of(), null, false, List.of(new GalleryDiagnostic(
                        "shared-projection", "shared", GalleryKind.IMAGE,
                        "gallery-provider-page-failed", "SQLException: password=secret"))));
        SetupService setup = mock(SetupService.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UnifiedGalleryController controller = new UnifiedGalleryController(registry,
                projectionBroker, mock(GalleryWorkBroker.class), setup);

        GalleryProjectionPage response = controller.projections(
                GalleryKind.IMAGE, "shared", null, null, null, null, null,
                GallerySortField.DOWNLOADED_AT, GallerySortDirection.DESC, null, 50, request);

        assertThat(response.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("gallery-provider-page-failed");
            assertThat(diagnostic.message()).isNull();
        });
    }

    private static GalleryCapabilityRegistry.Snapshot snapshot() {
        GalleryProjectionDescriptor sharedProjection = descriptor("shared", GalleryDataAccess.SHARED);
        GalleryProjectionDescriptor adminProjection = descriptor("admin", GalleryDataAccess.ADMIN_ONLY);
        GalleryWorkDescriptor sharedWork = new GalleryWorkDescriptor(
                "shared", "work", GalleryDataAccess.SHARED);
        GalleryWorkDescriptor adminWork = new GalleryWorkDescriptor(
                "admin", "work", GalleryDataAccess.ADMIN_ONLY);
        return new GalleryCapabilityRegistry.Snapshot(
                23,
                List.of(projectionProvider(
                        "mixed-owner", List.of(sharedProjection, adminProjection))),
                List.of(workProvider(
                        "mixed-owner", List.of(sharedWork, adminWork))),
                List.of(
                        frontend("mixed-owner", "shared", 10),
                        frontend("mixed-owner", "admin", 20),
                        frontend("mixed-owner", "unproven", 30)),
                List.of(sharedProjection, adminProjection),
                List.of(sharedWork, adminWork),
                List.of(new GalleryDiagnostic(
                        "registry", null, null, "registry-warning", "diagnostic")));
    }

    private static GalleryCapabilityRegistry.RegisteredProjectionProvider projectionProvider(
            String owner, List<GalleryProjectionDescriptor> descriptors) {
        return new GalleryCapabilityRegistry.RegisteredProjectionProvider(
                owner, owner + "-projection", mock(GalleryProjectionProvider.class), descriptors);
    }

    private static GalleryCapabilityRegistry.RegisteredWorkProvider workProvider(
            String owner, List<GalleryWorkDescriptor> descriptors) {
        return new GalleryCapabilityRegistry.RegisteredWorkProvider(
                owner, owner + "-work", mock(GalleryWorkProvider.class), descriptors);
    }

    private static GalleryCapabilityRegistry.RegisteredFrontendContribution frontend(
            String owner, String source, int order) {
        return new GalleryCapabilityRegistry.RegisteredFrontendContribution(owner,
                new GalleryFrontendContribution(
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
                        order));
    }

    private static GalleryProjectionDescriptor descriptor(String source, GalleryDataAccess access) {
        return new GalleryProjectionDescriptor(source, GalleryKind.IMAGE,
                "gallery", "source.test", 1, access, Map.of());
    }
}
